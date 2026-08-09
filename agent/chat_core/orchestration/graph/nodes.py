"""LangGraph 节点实现 — Chat Core 编排层。

包含 9 个节点：
- input_guardrail:      输入安全护栏（规则级，Sprint 1）
- intent_router:        LLM 语义意图分类（DeepSeek）
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
- chat_responder 为异步节点（await llm.ainvoke；模型 streaming=True 时 token 经
  回调被 stream_mode="messages" 捕获 → 前端打字机；不用 astream 手动聚合，
  避免其末尾完整 chunk 被当作 token 重发）
- Agent 调用的 Delegation Token 由 AgentClient 内部获取：优先从 Token Service 兑换
  RS256（内嵌于 Chat Backend）；Token Service 不可用时 fail-closed 拒绝调用
"""

from __future__ import annotations

import json
import logging
import re
from typing import Any, Optional

from langchain_core.messages import AIMessage, HumanMessage, SystemMessage

from ..llm import chat_llm, intent_llm, summary_llm
from .state import AgentInvocation, AgentState

logger = logging.getLogger(__name__)

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
2. intent_type="utility"：用户需要计算、查时间、单位换算、联网搜索
3. intent_type="chat"：闲聊、问候、一般知识问答
4. 一句话同时涉及多类时，intent_type 取主意图，targets 列出所有命中的目标
5. 无法确定时返回 intent_type="chat", targets=[]
6. 根据用户语言使用对应语言回答
7. 用户明确拒绝使用工具（如"不要用工具""别调用工具""不用工具计算""不需要搜索"
   等）时，intent_type="chat"——直接回答或说明，即使消息里含计算/搜索等
   工具关键词；"工具"指所有 utility 工具与 domain agent

Domain Agent 能力：
{agent_capabilities}

Utility Tool 能力：
{utility_capabilities}
"""

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
# 2. 意图路由（LLM 语义分类）
# ---------------------------------------------------------------------------

def intent_router(state: AgentState) -> AgentState:
    """意图分类：完全由 LLM 语义判定（DeepSeek，temperature=0）。

    不再使用关键词规则预判：规则无法理解"不要用计算器"这类否定语境，
    且维护成本高。LLM 失败/超时/返回非 JSON 时安全降级为 chat（不乱调工具）。
    """
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
        # LLM 失败/超时/返回非 JSON → 安全降级为闲聊，不误调 Agent/Utility
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

    # HITL 确认重调：用户确认后回到本节点，对同一 Agent 携带 confirmed + confirmation_id
    # （首轮 needs_confirmation 时 index 不前进，确认后由 human_approval 设 pending_confirmation）
    pending = state.get("pending_confirmation") or {}
    is_confirmation_call = pending.get("agent_name") == agent_name
    confirmation_id = pending.get("confirmation_id") if is_confirmation_call else None

    # Delegation Token 由 client 内部获取：RS256（Token Service），不可用时 fail-closed
    result = await client.invoke_agent(
        agent_name=agent_name,
        message=user_msg,
        user_id=user_id,
        user_role=state.get("user_role", ""),
        conversation_context=_build_conversation_context(state),
        trace_id=state.get("trace_id"),
        confirmed=is_confirmation_call,
        confirmation_id=confirmation_id,
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
        # 追加而非覆盖：agent_plan 含多 Agent 时保留全部调用记录（聚合器依赖）
        "agent_invocations": list(state.get("agent_invocations") or []) + [invocation],
        # 首轮 needs_confirmation 保持 index（确认后由 human_approval 设 pending 回到本
        # Agent 重调）；其余情况（成功/失败/确认重调完成）一律前进，避免死循环。
        # 注意：确认重调（is_confirmation_call）仍返回 needs_confirmation 时也必须前进
        # （见下方 needs_confirmation 分支的活锁防御）
        "current_agent_index": (
            index if result.get("status") == "needs_confirmation" and not is_confirmation_call
            else index + 1
        ),
        # 消费确认标记（确认重调只执行一次）
        "pending_confirmation": None,
    }

    if result.get("status") == "needs_confirmation":
        if is_confirmation_call:
            # 确认重调仍返回 needs_confirmation = Agent 端 confirmed/confirmation_id
            # 契约未生效：标记失败并前进（防"确认-确认"活锁），记录日志便于排查
            logger.error(
                "confirmed re-invoke still needs_confirmation: agent=%s confirmation_id=%s",
                agent_name, confirmation_id,
            )
            failed = list(state.get("failed_agents") or [])
            if agent_name not in failed:
                failed.append(agent_name)
            update["failed_agents"] = failed
        else:
            update["requires_approval"] = True
            update["approval_context"] = result.get("confirmation_required") or {}
            update["approval_agent"] = agent_name
    elif result.get("status") == "failed" or result.get("error"):
        # 追加而非覆盖：多 Agent 连续失败时保留全部失败名单
        failed = list(state.get("failed_agents") or [])
        if agent_name not in failed:
            failed.append(agent_name)
        update["failed_agents"] = failed
        # 失败上下文：转主 Agent（LLM）兜底时使用
        update["service_failures"] = list(state.get("service_failures") or []) + [
            f"「{agent_name}」服务暂时不可用"
        ]

    return update


def _default_client():
    """延迟创建默认 AgentClient（进程级单例）。"""
    from ..mcp.client import AgentClient

    if not hasattr(_default_client, "_client"):
        _default_client._client = AgentClient.from_yaml()
    return _default_client._client


def _build_conversation_context(state: AgentState) -> dict[str, Any]:
    """构造传给 Agent 的跨 Agent 上下文（前置 Agent 的 shared_context 聚合）。

    含 session_id：L&F 等 Agent 用它做 per_session 限流与多轮字段累积
    （ConversationContext.session_id），否则每轮回退 request_id 导致限流失效。
    """
    shared: dict[str, Any] = {}
    for inv in state.get("agent_invocations", []):
        shared.update(inv.get("shared_context") or {})
    return {"session_id": state.get("session_id") or "", "shared_data": shared}


# ---------------------------------------------------------------------------
# 4. Utility Tool 执行器
# ---------------------------------------------------------------------------

async def utility_tool_executor(state: AgentState, client: Any = None) -> AgentState:
    """调用 Utility MCP Server 的 POST /tools/call（异步节点）。"""
    client = client or _default_client()
    utility_plan = state.get("utility_plan", [])

    results: dict[str, Any] = {}
    failures: list[str] = []
    for tool_name in utility_plan:
        params = _extract_utility_params(tool_name, state)
        # Delegation Token 由 client 内部获取（RS256；兑换失败即拒绝，见 mcp/client.py）
        result = await client.invoke_utility(
            tool_name=tool_name,
            params=params,
            user_id=state.get("user_id", ""),
            user_role=state.get("user_role", "STUDENT"),
        )
        results[tool_name] = result
        if not isinstance(result, dict) or result.get("status") == "failed":
            failures.append(f"工具 {tool_name} 暂时不可用")

    update: dict[str, Any] = {"utility_results": results}
    if failures:
        # 失败上下文：转主 Agent（LLM）兜底时使用
        update["service_failures"] = list(state.get("service_failures") or []) + failures
    return update if (results or failures) else {}


def _extract_utility_params(tool_name: str, state: AgentState) -> dict[str, Any]:
    """从用户消息中提取 Utility Tool 参数（规则级，Sprint 1）。"""
    msg = state["messages"][-1].content if state.get("messages") else ""
    if tool_name == "get_current_time":
        return {"timezone": "Asia/Shanghai", "format": "datetime"}
    if tool_name == "calculator":
        match = re.search(r"[\d+\-*/().\s^]+", msg)
        return {"expression": match.group(0).strip() if match else "0"}
    # text_translator 已移除（2026-08-08）：翻译由 chat_responder 的 LLM 直答
    return {}


# ---------------------------------------------------------------------------
# 5. 闲聊回复（异步：await llm.ainvoke，streaming=True → messages 模式捕获 token）
# ---------------------------------------------------------------------------

async def chat_responder(state: AgentState) -> AgentState:
    """闲聊/知识问答：LLM 回复（DeepSeek，异步 ainvoke）。

    使用异步 ``ainvoke``（模型 streaming=True 时内部走流式，token 经 LangChain 回调
    被 ``graph.astream(stream_mode="messages")`` 捕获 → 前端打字机）。

    若 messages 模式未捕获（边界情况），main.py 的 updates 模式兜底会把完整
    回复作为单条 token 发出，保证回复不为空。
    """
    # 主 Agent 兜底：工具/子 Agent 调用失败时，由 LLM 生成友好的失败说明
    failures = list(state.get("service_failures") or [])
    if failures:
        user_msg = state["messages"][-1].content if state.get("messages") else ""
        llm = chat_llm()
        try:
            response = await llm.ainvoke([
                SystemMessage(content=(
                    "你是校园助手。部分服务当前不可用时，请用自然、友好的语气向用户说明情况，"
                    "给出替代建议或请其稍后重试。不要提及内部技术细节。"
                    "重要：你只知道自己列出的不可用服务名称，不具备任何额外业务信息——"
                    "绝对不要编造需要用户提供的具体字段（如物品名称、地点、日期等），"
                    "也不要让用户以为系统还在正常工作；如实告知服务暂时无法处理即可。"
                )),
                HumanMessage(content=f"用户请求：{user_msg}\n不可用的服务：{'; '.join(failures)}"),
            ])
            content = getattr(response, "content", "") or ""
            return {"messages": [
                AIMessage(content=content.strip() or "抱歉，部分服务暂时不可用，请稍后重试。")
            ]}
        except Exception:
            return {"messages": [
                AIMessage(content="抱歉，部分服务暂时不可用，请稍后重试。")
            ]}

    if state.get("error"):
        return {"messages": [AIMessage(content="抱歉，我没有理解你的请求，请换一种说法。")]}

    llm = chat_llm()
    try:
        response = await llm.ainvoke(state.get("messages", [HumanMessage(content="你好")]))
        content = getattr(response, "content", "") or ""
        return {"messages": [AIMessage(content=content.strip() or "抱歉，我现在暂时无法回复，请稍后重试。")]}
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
        if inv.get("output_status") == "needs_confirmation":
            # 确认提示由 HITL 确认框呈现，不进最终回复；确认后的操作结果由重调记录输出
            continue
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
    if "timezone" in result and result.get("value"):
        return f"现在是 {result['value']}（{result.get('timezone', 'Asia/Shanghai')}）"
    if result.get("error") or result.get("status") == "failed":
        # 失败项只显示友好文案，不暴露英文技术详情（详情在日志 / error 字段）
        return f"（{tool_name} 暂时不可用，请稍后重试）"
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

    if state.get("agent_invocations"):
        # 只 merge 最后一条（当前等待审批的 Agent），保留前面已完成 Agent 的记录
        invocations = list(state["agent_invocations"])
        if isinstance(decision, dict) and decision.get("approved"):
            invocations[-1] = {**invocations[-1], "output_status": "confirmed"}
            # 确认重调标记：agent_invoker 下次调用该 Agent 时携带 confirmed + confirmation_id
            update["pending_confirmation"] = {
                "agent_name": approval_agent,
                "confirmation_id": (state.get("approval_context") or {}).get("confirmation_id"),
            }
        else:
            invocations[-1] = {
                **invocations[-1],
                "output_response": "操作已取消。",
                "output_status": "cancelled",
            }
            # 用户取消：跳过该 Agent（不再重调），index 前进到下一个
            update["current_agent_index"] = (state.get("current_agent_index") or 0) + 1
        update["agent_invocations"] = invocations

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
