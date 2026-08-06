"""LangGraph 节点实现 — Chat Core 编排层。

包含 9 个节点：
- input_guardrail:      输入安全护栏（规则级，Sprint 1）
- intent_router:        混合式意图分类（规则预判 + LLM 精判）
- agent_invoker:        调用 Domain Agent（MCP Client + 安全 Headers）
- utility_tool_executor:调用 Utility Tool
- chat_responder:       LLM 直接回复（闲聊）
- output_guardrail:     输出安全护栏（脱敏 + 系统错误过滤）
- response_aggregator:  汇总 Agent/Utility 结果生成最终回复
- human_approval:       Human-in-the-loop 审批节点
- fallback_handler:     降级处理

设计原则：
- 每个节点是纯函数，只依赖 AgentState，无副作用（HTTP 调用除外）
- 节点返回<strong>最小状态增量</strong>（而非整个 state），避免 stream_mode="updates"
  携带全量 state 导致事件重复（例如 response_aggregator 在已有 AIMessage 时返回 {}）
- LLM 统一从 ..llm 工厂创建（DeepSeek，.env 配置，streaming=True）
- chat_responder 为异步节点（await llm.ainvoke）：streaming=True 时 token 通过回调
  被 stream_mode="messages" 捕获 → 前端打字机效果
- Sprint 1：Agent 调用时若 state 未携带 Delegation Token，
  用 AGENT_SHARED_SECRET 本地签发 HS256 Token（与 Mock Agent 联调）；
  Sprint 3+ 改为从 Token Service 获取 RS256 Token
"""

from __future__ import annotations

import json
import re
from typing import Any, Optional

from langchain_core.messages import AIMessage, HumanMessage, SystemMessage

from ..llm import chat_llm, intent_llm, summary_llm
from .state import AgentInvocation, AgentState

# ---------------------------------------------------------------------------
# 意图分类能力注册表（与 agent/schemas/*.json 对齐）
# ---------------------------------------------------------------------------

AGENT_CAPABILITIES: dict[str, str] = {
    "mail-agent": "邮件搜索、阅读、管理（归档/删除/标记）",
    "facility-agent": "研讨室、自习室、体育场馆查询与预订",
    "lost-found-agent": "失物报失、查找、认领",
    "skill-agent": "校园技能搜索、发布、联系",
}

UTILITY_CAPABILITIES: dict[str, str] = {
    "calculator": "数学计算，如加减乘除、开方、三角函数",
    "get_current_time": "查询当前日期时间、星期几",
    "unit_converter": "单位换算（长度/重量/温度/货币）",
    "text_translator": "文本翻译（中英日韩）",
    "web_search": "联网搜索获取实时信息",
}

_INTENT_SYSTEM_PROMPT = """你是校园助手 Chat Core 的意图路由器。分析用户消息，返回严格 JSON：

{{
  "intent_type": "domain_agent" | "utility" | "chat",
  "targets": ["目标名1", "目标名2"],
  "reasoning": "一句话说明"
}}

规则：
1. intent_type="domain_agent"：用户需要操作邮件、预约设施、失物招领、技能市场
2. intent_type="utility"：用户需要计算、查时间、单位换算、翻译、联网搜索
3. intent_type="chat"：闲聊、问候、一般知识问答
4. 一句话同时涉及多类时，intent_type 取主意图，targets 列出所有命中的目标
5. 无法确定时返回 intent_type="chat", targets=[]

Domain Agent 能力：
{agent_capabilities}

Utility Tool 能力：
{utility_capabilities}
"""

# 规则级关键词预判（LLM 不可用时的 fallback + 混合路由前置过滤）
_INTENT_KEYWORDS: dict[str, dict[str, list[str]]] = {
    "domain_agent": {
        "mail-agent": ["邮件", "邮箱", "收发", "收件箱", "发件", "附件", "归档", "删除邮件"],
        "facility-agent": ["研讨室", "自习室", "体育馆", "场地", "预订", "预约", "会议室", "教室"],
        "lost-found-agent": ["丢失", "遗失", "失物", "招领", "捡到", "丢了", "认领"],
        "skill-agent": ["技能", "教学", "辅导", "找人教", "发布技能", "吉他", "编程"],
    },
    "utility": {
        "calculator": ["计算", "等于", "加", "减", "乘", "除", "开方", "平方"],
        "get_current_time": ["现在几点", "时间", "今天", "星期几", "日期"],
        "unit_converter": ["换算", "多少公里", "多少英里", "多少斤", "转换单位"],
        "text_translator": ["翻译", "翻译成", "英文怎么说", "日语怎么说"],
        "web_search": ["搜索", "查一下", "搜一下", "新闻", "最新"],
    },
}

_INJECTION_PATTERNS: list[str] = [
    r"ignore\s+(all\s+)?previous\s+instructions",
    r"system\s*prompt",
    r"<\|im_start\|>",
    r"\[INST\].*\[/INST\]",
    r"你(的)?(系统|开发)?指令",
]

_PII_PATTERNS: list[tuple[str, str]] = [
    (r"\d{17}[\dXx]", "[身份证号已隐藏]"),
    (r"1[3-9]\d{9}", "[手机号已隐藏]"),
    (r"\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b", "[邮箱已隐藏]"),
]

_SYSTEM_ERROR_PATTERNS: list[str] = [
    r"Traceback\s*\(most recent call last\)",
    r"(java|org|com)\.\w+.*Exception",
    r"Caused by:",
]


# ---------------------------------------------------------------------------
# 1. 输入护栏
# ---------------------------------------------------------------------------

def input_guardrail(state: AgentState) -> AgentState:
    """输入安全护栏：Prompt injection 检测 + 敏感信息提示。"""
    last_msg = state["messages"][-1].content if state.get("messages") else ""
    if not isinstance(last_msg, str):
        return {}

    for pattern in _INJECTION_PATTERNS:
        if re.search(pattern, last_msg, re.IGNORECASE):
            return {
                "error": "detected_prompt_injection",
                "intent_type": "chat",
                "agent_plan": [],
                "utility_plan": [],
            }

    return {}


# ---------------------------------------------------------------------------
# 2. 意图路由器（混合：规则预判 → LLM 精判）
# ---------------------------------------------------------------------------

def _rule_based_intent(message: str) -> tuple[str, list[str]] | None:
    """关键词规则预判。命中则直接返回，未命中返回 None 交给 LLM。"""
    message_lower = message.lower()
    for intent, targets in _INTENT_KEYWORDS.items():
        hits = [t for t, kws in targets.items() if any(k in message_lower for k in kws)]
        if hits:
            return intent, hits
    return None


def intent_router(state: AgentState) -> AgentState:
    """混合式意图分类：先规则预判，规则不确定时调用 LLM（DeepSeek）。"""
    user_msg = state["messages"][-1].content if state.get("messages") else ""

    # Prompt injection 已由 input_guardrail 拦截
    if state.get("error"):
        return {
            "intent_type": "chat",
            "targets": [],
            "agent_plan": [],
            "utility_plan": [],
            "current_agent_index": 0,
        }

    rule_result = _rule_based_intent(user_msg)
    if rule_result is not None:
        intent_type, targets = rule_result
    else:
        llm = intent_llm()
        prompt = _INTENT_SYSTEM_PROMPT.format(
            agent_capabilities=json.dumps(AGENT_CAPABILITIES, ensure_ascii=False, indent=2),
            utility_capabilities=json.dumps(UTILITY_CAPABILITIES, ensure_ascii=False, indent=2),
        )
        try:
            response = llm.invoke(
                [SystemMessage(content=prompt), HumanMessage(content=user_msg)]
            )
            parsed = json.loads(response.content)
            intent_type = parsed.get("intent_type", "chat")
            targets = parsed.get("targets", [])
        except Exception:
            intent_type, targets = "chat", []

    if intent_type == "domain_agent":
        agent_plan, utility_plan = targets, []
    elif intent_type == "utility":
        agent_plan, utility_plan = [], targets
    else:
        agent_plan, utility_plan = [], []

    return {
        "intent_type": intent_type,
        "targets": targets,
        "agent_plan": agent_plan,
        "utility_plan": utility_plan,
        "current_agent_index": 0,
    }


# ---------------------------------------------------------------------------
# 3. Agent 调用器
# ---------------------------------------------------------------------------

async def agent_invoker(state: AgentState, client: Any = None) -> AgentState:
    """调用 Domain Agent 的 POST /agent/invoke（异步节点）。

    携带完整安全 Headers（Delegation Token + HMAC 签名 + Nonce）。
    失败时记录到 failed_agents 并返回降级响应。
    """
    client = client or _default_client()
    agent_plan = state.get("agent_plan", [])
    index = state.get("current_agent_index", 0)

    if index >= len(agent_plan):
        return {}

    agent_name = agent_plan[index]
    user_msg = state["messages"][-1].content
    user_id = state.get("user_id", "")

    # Delegation Token：优先用 state 中已携带的（Sprint 3+），否则本地签发（Sprint 1）
    tokens = state.get("delegation_tokens") or {}
    delegation_token = tokens.get(agent_name) or client.issue_local_delegation_token(
        user_id=user_id,
        role=state.get("user_role", "STUDENT"),
        target_agent=agent_name,
    )

    result = await client.invoke_agent(
        agent_name=agent_name,
        message=user_msg,
        user_id=user_id,
        user_role=state.get("user_role", ""),
        delegation_token=delegation_token,
        conversation_context=_build_conversation_context(state),
        trace_id=state.get("trace_id"),
    )

    invocation: AgentInvocation = {
        "agent_name": agent_name,
        "input_message": user_msg,
        "output_response": result.get("response", ""),
        "output_status": result.get("status", "failed"),
        "confirmation_required": result.get("confirmation_required"),
        "shared_context": result.get("shared_context", {}),
        "actions_taken": result.get("actions_taken", []),
        "error": result.get("error"),
    }

    update: dict[str, Any] = {
        "agent_invocations": [invocation],
        "current_agent_index": index + 1,
    }

    if result.get("status") == "needs_confirmation":
        update["requires_approval"] = True
        update["approval_context"] = result.get("confirmation_required") or {}
        update["approval_agent"] = agent_name
    elif result.get("status") == "failed" or result.get("error"):
        update["failed_agents"] = [agent_name]

    return update


def _default_client():
    """延迟创建默认 AgentClient（进程级单例）。"""
    from ..mcp.client import AgentClient

    if not hasattr(_default_client, "_client"):
        _default_client._client = AgentClient.from_yaml()
    return _default_client._client


def _build_conversation_context(state: AgentState) -> dict[str, Any]:
    """构造传给 Agent 的跨 Agent 上下文（前置 Agent 的 shared_context 聚合）。"""
    shared: dict[str, Any] = {}
    for inv in state.get("agent_invocations", []):
        shared.update(inv.get("shared_context") or {})
    return {"shared_data": shared}


# ---------------------------------------------------------------------------
# 4. Utility Tool 执行器
# ---------------------------------------------------------------------------

async def utility_tool_executor(state: AgentState, client: Any = None) -> AgentState:
    """调用 Utility MCP Server 的 POST /tools/call（异步节点）。"""
    client = client or _default_client()
    utility_plan = state.get("utility_plan", [])

    results: dict[str, Any] = {}
    for tool_name in utility_plan:
        params = _extract_utility_params(tool_name, state)
        token = (state.get("delegation_tokens") or {}).get("utility-tools") \
            or client.issue_local_delegation_token(
                user_id=state.get("user_id", ""),
                role=state.get("user_role", "STUDENT"),
                target_agent="utility-tools",
            )
        result = await client.invoke_utility(
            tool_name=tool_name,
            params=params,
            delegation_token=token,
        )
        results[tool_name] = result

    return {"utility_results": results} if results else {}


def _extract_utility_params(tool_name: str, state: AgentState) -> dict[str, Any]:
    """从用户消息中提取 Utility Tool 参数（规则级，Sprint 1）。"""
    msg = state["messages"][-1].content if state.get("messages") else ""
    if tool_name == "get_current_time":
        return {"timezone": "Asia/Shanghai", "format": "datetime"}
    if tool_name == "calculator":
        match = re.search(r"[\d+\-*/().\s^]+", msg)
        return {"expression": match.group(0).strip() if match else "0"}
    if tool_name == "text_translator":
        target = "zh"
        m = re.search(r"翻译成(英文|英语|中文|日语|韩语)", msg)
        lang_map = {"英文": "en", "英语": "en", "中文": "zh", "日语": "ja", "韩语": "ko"}
        if m:
            target = lang_map.get(m.group(1), "zh")
        text = re.sub(r"^(请|帮我把|帮我|把)?(这段|这个)?(文本)?(翻译成\w+[，,]?|翻译[，,]?)", "", msg).strip()
        return {"text": text, "target_lang": target, "source_lang": "auto"}
    return {}


# ---------------------------------------------------------------------------
# 5. 闲聊回复（异步：await llm.ainvoke，streaming=True → messages 模式捕获 token）
# ---------------------------------------------------------------------------

async def chat_responder(state: AgentState) -> AgentState:
    """闲聊/知识问答：LLM 直接回复（DeepSeek，异步 ainvoke）。

    使用异步 ainvoke 且模型 streaming=True：token 通过 LangChain 回调被
    ``graph.astream(stream_mode="messages")`` 捕获，前端实现打字机效果。
    若 messages 模式未捕获（同步上下文等边界情况），main.py 的 updates
    模式兜底会把完整回复作为单条 token 发出，保证回复不为空。
    """
    if state.get("error"):
        return {"messages": [AIMessage(content="抱歉，我没有理解你的请求，请换一种说法。")]}

    llm = chat_llm()
    try:
        response = await llm.ainvoke(state.get("messages", [HumanMessage(content="你好")]))
        return {"messages": [AIMessage(content=response.content)]}
    except Exception:
        return {"messages": [AIMessage(content="抱歉，我现在暂时无法回复，请稍后重试。")]}


# ---------------------------------------------------------------------------
# 6. 输出护栏
# ---------------------------------------------------------------------------

def output_guardrail(state: AgentState) -> AgentState:
    """输出护栏：PII 脱敏 + 系统错误过滤（对所有已生成回复生效）。"""
    messages = state.get("messages", [])
    if not messages or not isinstance(messages[-1], AIMessage):
        return {}

    content = messages[-1].content or ""

    for pattern in _SYSTEM_ERROR_PATTERNS:
        if re.search(pattern, content):
            content = "抱歉，处理过程中遇到了技术问题，请稍后重试。"
            break

    for pattern, replacement in _PII_PATTERNS:
        content = re.sub(pattern, replacement, content)

    # 只在内容发生变化时返回更新
    if content != messages[-1].content:
        return {"messages": [AIMessage(content=content)]}
    return {}


# ---------------------------------------------------------------------------
# 7. 回复聚合器
# ---------------------------------------------------------------------------

def response_aggregator(state: AgentState) -> AgentState:
    """汇总所有 Agent / Utility 结果生成最终自然语言回复。

    返回最小状态增量：已有最终 AIMessage 时返回 {}（避免 chat 路径重复输出）；
    否则返回 {"messages": [AIMessage]} 由 reducer 追加。
    """
    if state.get("messages") and isinstance(state["messages"][-1], AIMessage):
        return {}  # 已有最终回复（如 chat_responder 生成），不重复

    agent_invocations = state.get("agent_invocations", [])
    utility_results = state.get("utility_results", {})
    parts: list[str] = []

    for inv in agent_invocations:
        parts.append(inv.get("output_response", ""))

    for tool_name, result in utility_results.items():
        parts.append(_format_utility_result(tool_name, result))

    if not parts:
        final = "抱歉，暂时无法处理你的请求。"
    elif len(parts) == 1:
        final = parts[0]
    else:
        llm = summary_llm()
        try:
            summary = llm.invoke(
                [SystemMessage(content="将以下多个结果整合成一段连贯、自然的回复："),
                 HumanMessage(content="\n".join(parts))]
            )
            final = summary.content
        except Exception:
            final = "\n".join(parts)

    return {"messages": [AIMessage(content=final)]}


def _format_utility_result(tool_name: str, result: Any) -> str:
    """Utility 工具结果直接格式化（避免多余 LLM 调用）。"""
    if not isinstance(result, dict):
        return f"（{tool_name} 返回异常：{result}）"
    if "result" in result and isinstance(result["result"], (int, float)):
        return f"计算结果：{result.get('expression', '')} = {result['result']}"
    if result.get("datetime"):
        return f"现在是 {result['datetime']}（{result.get('timezone', 'Asia/Shanghai')}）"
    if result.get("translated_text"):
        return f"翻译结果：{result['translated_text']}"
    if result.get("error") or result.get("status") == "failed":
        return f"（{tool_name} 调用失败：{result.get('error', 'unknown')}）"
    return json.dumps(result, ensure_ascii=False)


# ---------------------------------------------------------------------------
# 8. Human-in-the-loop 审批
# ---------------------------------------------------------------------------

def human_approval(state: AgentState) -> AgentState:
    """审批节点：依赖 LangGraph interrupt 暂停等待用户确认。

    编排层通过 Command(resume=...) 恢复执行。
    """
    from langgraph.types import interrupt

    approval_agent = state.get("approval_agent", "")
    decision = interrupt({
        "type": "confirm_action",
        "agent": approval_agent,
        "details": state.get("approval_context", {}),
        "message": "请确认此操作",
    })

    update: dict[str, Any] = {
        "requires_approval": False,
        "approval_context": None,
        "approval_agent": None,
    }

    if isinstance(decision, dict) and decision.get("approved"):
        if state.get("agent_invocations"):
            update["agent_invocations"] = [
                {**state["agent_invocations"][-1], "output_status": "confirmed"}
            ]
    else:
        if state.get("agent_invocations"):
            update["agent_invocations"] = [
                {**state["agent_invocations"][-1], "output_response": "操作已取消。", "output_status": "cancelled"}
            ]

    return update


# ---------------------------------------------------------------------------
# 9. 降级处理
# ---------------------------------------------------------------------------

def fallback_handler(state: AgentState) -> AgentState:
    """降级：Agent 不可用 / 超时 / 安全拦截时返回友好文案。"""
    failed = state.get("failed_agents", [])
    if failed:
        names = "、".join(failed)
        return {"messages": [AIMessage(content=f"「{names}」暂时不可用，请稍后重试。")]}
    return {"messages": [AIMessage(content="抱歉，服务暂时不可用，请稍后重试。")]}
