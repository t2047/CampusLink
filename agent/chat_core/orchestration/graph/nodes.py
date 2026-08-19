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
from datetime import UTC
from typing import Any

from langchain_core.messages import AIMessage, HumanMessage, SystemMessage

from ..llm import chat_llm, intent_llm, summary_llm
from .state import (
    AgentInvocation,
    AgentState,
    current_turn_invocations,
    invocation_can_update_context,
)

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# 意图分类能力注册表（与 agent/schemas/*.json 对齐）
# ---------------------------------------------------------------------------

AGENT_CAPABILITIES: dict[str, str] = {
    "mail-agent": "邮件搜索、阅读、管理（归档/删除/标记）",
    "facility-agent": (
        "校园设施与空间：搜索研讨室、自习室、实验室、教室和体育场馆，查看空间详情与"
        "可用时间，创建、查询、列出或取消本人预约，以及提交、查询或列出设施报修请求"
    ),
    "lost-found-agent": "失物报失、登记拾获、查找、认领",
}

UTILITY_CAPABILITIES: dict[str, str] = {
    "calculator": "数学计算，如加减乘除、开方、三角函数",
    "get_current_time": "查询当前日期时间、星期几",
    "unit_converter": "单位换算（长度/重量/温度/货币）",
    "web_search": "联网搜索获取实时信息",
    "search_policy": "查询学校政策、规章制度、考试条例、评估规则、学生行为守则等官方文档内容",
}

_INTENT_SYSTEM_PROMPT = """你是校园助手 Chat Core 的意图路由器。分析用户消息，返回严格 JSON：

{{
  "intent_type": "domain_agent" | "utility" | "chat",
  "targets": ["目标名1", "目标名2"],
  "reasoning": "一句话说明"
}}

规则：
1. intent_type="domain_agent"：用户需要操作邮件、预约设施、失物招领、技能市场。
   典型语义（含问句形式）：查询/登记/查找失物下落、报失、捡到东西、认领、
   预约或查询教室/场馆/研讨室、查邮件、技能搜索与交易等——即使表述为
   "我的东西在哪""帮我看看"等口语问句，只要涉及上述业务域，即为 domain_agent
2. intent_type="utility"：用户需要计算、查时间、单位换算、联网搜索、
   或查询学校政策/规章制度。联网搜索包括查询任何实时/最新信息（新闻、天气、
   汇率、股价、赛事、百科事实等），典型语义："搜索/搜一下/查一下/查询/有什么新闻/
   今天天气/最新消息/帮我找找资料"——即使表述为问句（"最近有什么新闻？"）也归
   utility，targets=["web_search"]；不得把含"搜索/查询"意图的消息归为 chat
   （chat 只用于纯闲聊/知识问答且用户未要求联网）。政策/规章制度查询（如
   "考试可以带计算器吗""学生守则对抄袭怎么规定""查一下评估规则"）归
   utility，targets=["search_policy"]——不得在 chat 中凭常识编造校规条款
3. intent_type="chat"：闲聊、问候、一般知识问答（与校园业务无关）
4. 一句话同时涉及多类时，intent_type 取主意图，targets 列出所有命中的目标
5. 无法确定时：若消息明显涉及校园业务域（失物/设施/邮件/技能），返回
   intent_type="domain_agent"（宁可让子 Agent 追问细节，也不要在 chat 里编造
   业务信息）；确属无关闲聊才返回 intent_type="chat", targets=[]
6. 根据用户语言使用对应语言回答
7. 用户明确拒绝使用工具（如"不要用工具""别调用工具""不用工具计算""不需要搜索"
   等）时，intent_type="chat"——直接回答或说明，即使消息里含计算/搜索等
   工具关键词；"工具"指所有 utility 工具与 domain agent
8. intent_type="chat" 时你只做一般性闲聊/知识回答，**绝对禁止**
   编造校园业务事实（如失物存放地点/电话/地址、登记信息、预约状态等）。
   用户消息一旦涉及具体校园业务（物品、预约、邮件、技能），一律路由
   agent 或如实说明无法处理，不得虚构

Domain Agent 能力：
{agent_capabilities}

Utility Tool 能力：
{utility_capabilities}

示例：
- "我丢了一串钥匙，帮我登记" → {{"intent_type":"domain_agent","targets":["lost-found-agent"]}}
- "我捡到一张学生卡" → {{"intent_type":"domain_agent","targets":["lost-found-agent"]}}（捡到/找到物品属于失物招领域，与设施无关）
- "明天下午有没有空的研讨室" → {{"intent_type":"domain_agent","targets":["facility-agent"]}}
- "帮我查一下昨天收到的邮件" → {{"intent_type":"domain_agent","targets":["mail-agent"]}}
- "把 15 美元换算成人民币" → {{"intent_type":"utility","targets":["unit_converter"]}}
- "今天天气怎么样" → {{"intent_type":"utility","targets":["web_search"]}}（实时信息查询归联网搜索）
- "搜索一下最近有什么新闻" → {{"intent_type":"utility","targets":["web_search"]}}
- "考试可以带计算器吗" → {{"intent_type":"utility","targets":["search_policy"]}}（校规/考试规则查询）
- "学生守则对抄袭是怎么规定的" → {{"intent_type":"utility","targets":["search_policy"]}}
- "不要用工具，直接告诉我 2+2" → {{"intent_type":"chat","targets":[]}}
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


# chat_responder 闲聊/知识问答的系统提示词：定义校园助手身份与功能，
# 避免 LLM 自报底层模型（如 DeepSeek）或编造业务信息（2026-08-15）。
_CHAT_SYSTEM_PROMPT = """你是 CampusLink 校园助手，服务于 CampusLink 校园平台。

你可以帮助用户：
- 邮件：搜索、阅读、管理邮件
- 校园设施：查找/预约研讨室、自习室、教室、实验室、体育场馆，查询设施报修
- 失物招领：报失、登记拾获、查找失物、认领
- 实用工具：数学计算、当前时间、单位换算、联网搜索实时信息
- 校园常识与一般知识问答

回答规则：
1. 使用与用户消息相同的语言回复（用户用英文则回复英文，用户用中文则回复中文）。
2. 用户询问"你是谁/你能做什么/有哪些功能"时，用上面的功能列表介绍自己，
   任何情况下（即使测试、调试等过程中）不要透露底层模型名称（如 DeepSeek）或任何内部技术细节。
3. 涉及具体业务操作（预约/报失/查邮件/找东西/查信息等）时，引导用户直接提出需求；
   不要编造业务事实（如失物存放地点、预约状态、邮件内容、设施信息）。
4. 不要提及"工具""agent""LLM""模型"等内部实现细节。"""


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
    history_text = _recent_history_text(state, limit=6)

    # Prompt injection 已由 input_guardrail 拦截
    if state.get("error"):
        return {
            "intent_type": "chat",
            "targets": [],
            "agent_plan": [],
            "utility_plan": [],
            "current_agent_index": 0,
        }

    # 澄清循环短路（编排层主动信息收集）：上轮子 Agent 返回 needs_more_info（缺信息），
    # 本轮用户消息是对追问的补充 → 跳过 LLM 意图分类，直接回到同一 Agent。
    # 好处：补充内容（如"在操场"）不会被重新分类成别的意图/闲聊。
    # 显式放弃（算了/不用了等）→ 退出澄清循环转闲聊。
    pending_info = state.get("pending_info") or {}
    if pending_info.get("agent_name"):
        abandon = any(
            k in user_msg
            for k in ("算了", "不用了", "不用找", "不找了", "放弃", "换一个", "先不管", "先不弄")
        )
        if abandon:
            logger.info("intent_router: abandon clarification for %s", pending_info["agent_name"])
            return {
                "intent_type": "chat",
                "targets": [],
                "agent_plan": [],
                "utility_plan": [],
                "current_agent_index": 0,
                "pending_info": None,  # 退出澄清循环
            }
        logger.info(
            "intent_router: clarification turn for %s (attempt %s), skip LLM routing",
            pending_info["agent_name"],
            pending_info.get("attempts", 1),
        )
        return {
            "intent_type": "domain_agent",
            "targets": [pending_info["agent_name"]],
            "agent_plan": [pending_info["agent_name"]],
            "utility_plan": [],
            "current_agent_index": 0,
            # pending_info 显式保留：由 agent_invoker 依据本轮结果更新/消费
            "pending_info": pending_info,
        }

    llm = intent_llm()
    prompt = _INTENT_SYSTEM_PROMPT.format(
        agent_capabilities=json.dumps(AGENT_CAPABILITIES, ensure_ascii=False, indent=2),
        utility_capabilities=json.dumps(UTILITY_CAPABILITIES, ensure_ascii=False, indent=2),
    )
    parsed: dict[str, Any] = {}
    intent_type = "chat"
    targets: list[str] = []
    try:
        response = llm.invoke(
            [
                SystemMessage(content=prompt),
                HumanMessage(
                    content=(
                        f"对话历史：\n{history_text}\n\n当前消息：{user_msg}"
                        if history_text
                        else f"当前消息：{user_msg}"
                    )
                ),
            ]
        )
        parsed = json.loads(response.content)
        intent_type = str(parsed.get("intent_type", "chat"))
        targets = [t for t in (parsed.get("targets") or []) if isinstance(t, str)]
    except Exception:
        # LLM 失败/超时/返回非 JSON → 安全降级为闲聊，不误调 Agent/Utility
        intent_type, targets = "chat", []

    # targets 白名单：只保留已知 Agent/Utility（防 LLM 幻觉出未知目标名）
    valid_agents = set(AGENT_CAPABILITIES.keys())
    valid_utils = set(UTILITY_CAPABILITIES.keys())
    if intent_type == "domain_agent":
        # targets 白名单：只保留已知 Agent（防 LLM 幻觉出未知目标名）
        agent_plan: list[str] = [t for t in targets if t in valid_agents]
        # 混合支持（2026-08-15）：targets 可同时含 Agent 与 Utility——
        # 先执行工具（route_by_intent 优先 utility），再经 after_utility 转 agent
        utility_plan: list[str] = [t for t in targets if t in valid_utils]
    elif intent_type == "utility":
        agent_plan = [t for t in targets if t in valid_agents]
        utility_plan = [t for t in targets if t in valid_utils]
    else:
        agent_plan, utility_plan = [], []

    # 失物语义兜底：LLM 可能把"捡到学生卡"误判为设施/闲聊。消息含明确失物词且
    # 无否定意图时，强制纳入 lost-found-agent（避免主 LLM 无数据编造失物信息）
    if "lost-found-agent" not in agent_plan:
        has_lost_keyword = any(
            k in user_msg for k in ("捡到", "捡了", "丢了", "丢失", "遗失", "报失", "失物", "招领", "失主")
        )
        refuses_tools = any(
            k in user_msg.lower() for k in ("不要用工具", "别调用", "不用工具", "不需要搜索", "不找")
        )
        if has_lost_keyword and not refuses_tools:
            agent_plan.append("lost-found-agent")
            intent_type = "domain_agent"
            logger.info("intent_router: lost-found keyword fallback applied")

    logger.info(
        "intent_router: msg=%.80s intent_type=%s targets=%s reasoning=%s",
        user_msg,
        intent_type,
        targets,
        parsed.get("reasoning", ""),
    )

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
        "turn_id": state.get("turn_id") or "",
        "agent_name": agent_name,
        "input_message": user_msg,
        "output_response": result.get("response", ""),
        "output_status": result.get("status", "failed"),
        "confirmation_required": result.get("confirmation_required"),
        "shared_context": result.get("shared_context", {}),
        "actions_taken": result.get("actions_taken", []),
        "match_results": result.get("match_results", []),
        "request_id": result.get("request_id"),
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
            index if result.get("status") == "needs_confirmation" and not is_confirmation_call else index + 1
        ),
        # 消费确认标记（确认重调只执行一次）
        "pending_confirmation": None,
        # 默认结束澄清循环；needs_more_info 分支会覆盖为新一轮澄清状态
        "pending_info": None,
    }

    if result.get("status") == "needs_more_info":
        # 澄清循环（编排层主动信息收集）：记录缺失信息与尝试次数，
        # 下一轮由 intent_router 短路回本 Agent 继续收集
        attempts = int((state.get("pending_info") or {}).get("attempts", 0)) + 1
        if attempts >= 3:
            # 3 轮未补齐 → 终止澄清，输出明确提示（不再静默循环）
            logger.info("clarification terminated: agent=%s attempts=%s", agent_name, attempts)
            # 替换 update 中已追加的 needs_more_info 记录为终止文案（避免残留两条）。
            # 语言跟随：按用户语言输出，且不指定具体模块（agent 各不相同，
            # 引导去对应模块手动完成即可）；同语言时后续重述逻辑会跳过，避免中英混排。
            update["agent_invocations"][-1] = {
                **invocation,
                "output_response": (
                    "信息仍不完整，暂时无法处理。请尝试重新描述，"
                    "或可以直接使用对应模块手动完成。"
                    if _looks_chinese(user_msg)
                    else (
                        "The information is still incomplete and cannot be processed "
                        "at the moment. Please try rephrasing, or use the corresponding "
                        "module directly."
                    )
                ),
                "output_status": "failed",
            }
        else:
            update["pending_info"] = {
                "agent_name": agent_name,
                "missing_fields": result.get("missing_fields", []),
                "attempts": attempts,
            }

    if result.get("status") == "needs_confirmation":
        if is_confirmation_call:
            # 确认重调仍返回 needs_confirmation = Agent 端 confirmed/confirmation_id
            # 契约未生效：标记失败并前进（防"确认-确认"活锁），记录日志便于排查
            logger.error(
                "confirmed re-invoke still needs_confirmation: agent=%s confirmation_id=%s",
                agent_name,
                confirmation_id,
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
    supplied_context = state.get("conversation_context") or {}
    supplied_shared = supplied_context.get("shared_data", {})
    if isinstance(supplied_shared, dict):
        shared.update(supplied_shared)
    for inv in state.get("agent_invocations", []):
        if not invocation_can_update_context(inv):
            continue
        invocation_shared = inv.get("shared_context") or {}
        if isinstance(invocation_shared, dict):
            shared.update(invocation_shared)

    # 统一注入系统事实包：权威日期/时间/时区/用户语言（Asia/Singapore, UTC+8）。
    # agent 解析相对时间（"刚刚/今天/明天"）时使用注入值，而不是各自猜测/重复计算。
    from datetime import datetime, timedelta
    from datetime import timezone as dt_timezone

    now = datetime.now(UTC).astimezone(dt_timezone(timedelta(hours=8)))
    user_msgs = [m.content for m in state.get("messages", []) if isinstance(m, HumanMessage)]
    shared["system_facts"] = {
        "today": now.strftime("%Y-%m-%d"),
        "now": now.isoformat(timespec="seconds"),
        "timezone": "Asia/Singapore",
        "user_language": "zh" if _looks_chinese("".join(user_msgs)) else "en",
    }
    # 最近对话历史（结构化为 role/content 列表）：agent 解析补充性消息（如"刚刚"）
    # 时结合上文理解，而不是只看当前一句
    recent: list[dict[str, str]] = []
    for m in state.get("messages", [])[-6:]:
        if isinstance(m, HumanMessage):
            recent.append({"role": "user", "content": m.content})
        elif isinstance(m, AIMessage):
            recent.append({"role": "assistant", "content": m.content})
    shared["recent_messages"] = recent
    return {"session_id": state.get("session_id") or "", "shared_data": shared}


# ---------------------------------------------------------------------------
# 4. Utility Tool 执行器
# ---------------------------------------------------------------------------


async def utility_tool_executor(state: AgentState, client: Any = None) -> AgentState:
    """调用 Utility MCP Server 的 POST /tools/call（异步节点）。

    联网搜索确认门（HITL）：web_search 会向外部搜索服务发送查询词，属外部网络
    操作——执行前须人工确认（2026-08-15）。首次进入且计划含 web_search 时返回
    requires_approval → human_approval 中断；用户确认/取消后恢复回到本节点，
    按 pending_utility_confirm 消费（确认则执行，取消则跳过）。
    """
    client = client or _default_client()
    utility_plan = state.get("utility_plan", [])
    pending = state.get("pending_utility_confirm") or {}

    # 确认门：web_search 在计划中且尚未确认/取消 → 先征求人工确认
    if "web_search" in utility_plan and pending.get("tool") != "web_search":
        # 语言跟随：确认文案按用户消息语言输出（2026-08-17 修复：此前固定中文）
        user_msg = state["messages"][-1].content if state.get("messages") else ""
        user_zh = _looks_chinese(user_msg)
        query = _extract_utility_params("web_search", state).get("query", "")
        if query:
            message = (
                f"即将联网搜索「{query}」，搜索会向外部服务发送该查询词，是否继续？"
                if user_zh
                else (
                    f'About to search the web for "{query}". This will send the query '
                    "to an external search service. Continue?"
                )
            )
        else:
            message = (
                "即将进行联网搜索，是否继续？"
                if user_zh
                else "About to perform a web search. Continue?"
            )
        return {
            "requires_approval": True,
            "approval_agent": "utility:web_search",
            "approval_context": {
                "type": "confirm_external_call",
                "tool": "web_search",
                "message": message,
                "summary": "联网搜索" if user_zh else "Web search",
            },
        }

    # 用户取消联网 → 从计划中剔除，不执行
    if pending.get("cancelled"):
        utility_plan = [tool for tool in utility_plan if tool != "web_search"]

    results: dict[str, Any] = {}
    failures: list[str] = []
    executed_search_query: str | None = None
    for tool_name in utility_plan:
        params = _extract_utility_params(tool_name, state)
        if tool_name == "unit_converter":
            # 主路径：LLM 解析（任意货币/回指）；失败时回退规则提取主流货币（2026-08-19）
            llm_params = await _llm_extract_unit_converter(state)
            params = llm_params or _rule_extract_unit_converter(state["messages"][-1].content if state.get("messages") else "")
        # Delegation Token 由 client 内部获取（RS256；兑换失败即拒绝，见 mcp/client.py）
        result = await client.invoke_utility(
            tool_name=tool_name,
            params=params,
            user_id=state.get("user_id", ""),
            user_role=state.get("user_role", "STUDENT"),
        )
        results[tool_name] = result
        if tool_name in ("web_search", "search_policy") and params.get("query"):
            # 记录实际使用的查询词，供下一轮回指请求（"再查一下"）复用
            executed_search_query = params["query"]
        if not isinstance(result, dict) or result.get("status") == "failed":
            failures.append(f"工具 {tool_name} 暂时不可用")

    update: dict[str, Any] = {
        "utility_results": results,
        # 消费联网确认标记（确认/取消只生效一次）
        "pending_utility_confirm": None,
    }
    if executed_search_query is not None:
        update["last_search_query"] = executed_search_query
    if failures:
        # 失败上下文：转主 Agent（LLM）兜底时使用
        update["service_failures"] = list(state.get("service_failures") or []) + failures
    elif results:
        # 工具全部成功 → 交给 LLM 按用户语言重述（避免用户英文提问却输出中文结果）；
        # 重述失败回退格式化拼接（_format_utility_result），保证永远有回复
        user_msg = state["messages"][-1].content if state.get("messages") else ""
        rephrased = await _rephrase_utility_results(user_msg, results)
        if rephrased:
            update["utility_response"] = rephrased
    return update if (results or failures) else {}


def _looks_chinese(text: str) -> bool:
    """启发式：文本是否含中文字符（用于判断是否需要语言重述）。"""
    return any("\u4e00" <= ch <= "\u9fff" for ch in text[:200])


def _recent_history_text(state: AgentState, limit: int = 6) -> str:
    """取最近几轮对话（用户/助手）的纯文本，供意图分类与重述参考。"""
    lines: list[str] = []
    for m in state.get("messages", [])[-limit:]:
        if isinstance(m, HumanMessage):
            lines.append(f"用户：{m.content}")
        elif isinstance(m, AIMessage):
            lines.append(f"助手：{m.content}")
    return "\n".join(lines)


async def _rephrase_in_user_language(user_msg: str, text: str) -> str | None:
    """把一段结果文本交给 LLM，用与用户消息相同的语言重述为自然回复。

    返回重述文本；LLM 调用失败返回 None（调用方回退原文，保证永远有回复）。
    """
    if not text.strip():
        return None
    try:
        llm = chat_llm()
        response = await llm.ainvoke(
            [
                SystemMessage(
                    content=(
                        "你是校园助手。以下是一段系统生成的结果文本（可能是子 Agent 的"
                        "原始回复，语言可能与用户不一致）。请用与用户消息相同的语言，把内容"
                        "组织成一段自然、简洁的回复。"
                        "严格只做语言转换和润色：保留原文本全部事实（编号、数量、时间、"
                        "地点等），不得添加、删减或推断原文本没有的信息，不得评论功能是否"
                        "可用、是否成功或‘暂时无法’等，不得替用户下任何结论，不得提及内部细节。"
                        "如果结果文本本身是一个问题/澄清/请求补充信息（如 asking for a space"
                        "ID, asking to clarify time），必须保持提问或请求形式——绝不能把它"
                        "改写成已完成、已成功的陈述（例如绝不能输出‘已为您预订’除非原文明确"
                        "说明预订已完成）。"
                    )
                ),
                HumanMessage(content=f"用户消息：{user_msg}\n结果文本：{text}"),
            ]
        )
        content = getattr(response, "content", "") or ""
        return content.strip() or None
    except Exception:
        return None


async def _rephrase_utility_results(user_msg: str, results: dict[str, Any]) -> str | None:
    """把工具原始结果交给 LLM，用与用户消息相同的语言重述为自然回复。

    返回重述文本；LLM 调用失败返回 None（调用方回退格式化拼接）。
    """
    llm = chat_llm()
    try:
        response = await llm.ainvoke(
            [
                SystemMessage(
                    content=(
                        "你是校园助手。以下是工具调用返回的原始结果（JSON/文本）。"
                        "请用与用户消息相同的语言，把结果组织成一段自然、简洁的回复。"
                        "结果可能包含英文或其他语言的条目（新闻、搜索结果等），请翻译并"
                        "整理成用户语言呈现，不要因来源语言而省略内容。"
                        "若结果含具体条目（新闻标题/时间/来源、搜索结果标题与摘要），"
                        "请直接呈现这些条目内容（如把多条新闻整理成简明要闻/摘要），"
                        "绝不要只罗列来源网站名或让用户自行去查看。"
                        "不要编造结果中没有的信息，不要提及工具名等内部细节。"
                    )
                ),
                HumanMessage(
                    content=(
                        f"用户消息：{user_msg}\n"
                        f"工具结果：{json.dumps(results, ensure_ascii=False, default=str)}"
                    )
                ),
            ]
        )
        content = getattr(response, "content", "") or ""
        return content.strip() or None
    except Exception:
        return None


# 单位换算支持的单位词（货币中文别名 + 常用 ISO 码 + 长度/重量/温度）。
# 顺序按消息中出现位置取前两个：第一个为 from_unit，第二个为 to_unit。
# 主流货币规则兜底（LLM 解析失败时回退；主路径仍为 LLM，2026-08-19）
_UNIT_CONVERTER_TERMS = [
    "人民币", "美元", "欧元", "英镑", "日元", "港币", "新币", "新加坡元",
    "澳元", "加元", "韩元",
    "CNY", "USD", "EUR", "GBP", "JPY", "HKD", "SGD", "AUD", "CAD", "KRW",
    # 长度 / 重量 / 温度
    "米", "公里", "英里", "英尺", "千克", "公斤", "斤", "磅", "摄氏度", "华氏度",
]


def _rule_extract_unit_converter(msg: str) -> dict[str, Any]:
    """规则提取（兜底）：取第一个数字为 value；单位词按消息中首次出现顺序取前两个。"""
    value_m = re.search(r"(\d+(?:\.\d+)?)", msg)
    hits = sorted(
        ((msg.find(t), t) for t in _UNIT_CONVERTER_TERMS if t in msg),
        key=lambda p: p[0],
    )
    if not value_m or len(hits) < 2:
        return {}
    return {
        "value": float(value_m.group(1)),
        "from_unit": hits[0][1],
        "to_unit": hits[1][1],
    }


# 回指请求模式：消息本身不含新的查询内容，指代上一次搜索
# （"再查一下/再搜一次/继续/还有呢"等）。命中时复用 state.last_search_query。
_ANAPHORA_REQ = re.compile(
    r"^(?:再(?:查|搜|找|看看)(?:一下|一次|一)?|接着(?:查|搜)|继续(?:查|搜|找|看看|一下)?|"
    r"查查|搜搜|再查查|还有呢|然后呢|"
    r"search\s+again|look\s+(?:it\s+)?up\s+again|check\s+again)$",
    re.IGNORECASE,
)


async def _llm_extract_unit_converter(state: AgentState) -> dict[str, Any] | None:
    """unit_converter 参数解析（全 LLM，2026-08-19）。

    从最近对话解析 value/from_unit/to_unit：支持任意货币（含词表外的
    越南盾等）与回指（"是多少美元"——从历史继承金额与基准币，只更新
    目标币）。from/to 优先输出 ISO 4217 大写代码（长度/重量/温度输出
    中文单位词）。解析失败返回 None（调用方维持原失败路径）。
    """
    history = _recent_history_text(state, limit=6)
    llm = chat_llm()
    try:
        response = await llm.ainvoke(
            [
                SystemMessage(
                    content=(
                        "你是单位换算参数解析器。根据对话历史输出严格 JSON，简化思考过程以加快速度，但保证精确："
                        '{"value": 数字, "from_unit": "X", "to_unit": "Y"}。'
                        "方向语义：from_unit = 金额所在的货币/单位，to_unit = 要换算成的目标货币/单位"
                        "（如'100美元是多少人民币' → from=USD, to=CNY；切勿颠倒）。"
                        "规则：货币一律用 ISO 4217 大写代码（如 CNY/USD/SGD/VND/THB/MYR/RUB/CHF）；"
                        "长度/重量/温度用中文单位词（米/公里/千克/摄氏度等）。"
                        "若消息是回指（如'是多少美元'），从历史继承金额与基准币，只更新目标币。"
                        "货币名有歧义时选仍在流通的货币（如'法郎'→CHF 瑞士法郎，法国法郎已废止）。"
                        '无法解析时输出 {"error": "无法解析"}。只输出 JSON，不要任何其他文字。'
                    )
                ),
                HumanMessage(content=f"对话历史：\n{history}"),
            ]
        )
        text = (getattr(response, "content", "") or "").strip()
        start, end = text.find("{"), text.rfind("}")
        if start < 0 or end < 0:
            return None
        data = json.loads(text[start : end + 1])
        if not isinstance(data, dict) or "error" in data:
            return None
        value = float(data["value"])
        from_unit = str(data.get("from_unit", "")).strip().upper()
        to_unit = str(data.get("to_unit", "")).strip().upper()
        if not from_unit or not to_unit:
            return None
        return {"value": value, "from_unit": from_unit, "to_unit": to_unit}
    except Exception as exc:
        logger.warning("unit_converter LLM parse failed: err=%s", exc)
        return None


def _extract_utility_params(tool_name: str, state: AgentState) -> dict[str, Any]:
    """从用户消息中提取 Utility Tool 参数（规则级，Sprint 1）。

    web_search / search_policy：去除搜索引导词后作为检索词；消息为回指请求
    （无实质查询内容）时复用上一次实际执行的查询词（state.last_search_query），
    修复"再查一下"被当作字面查询词搜索的问题（2026-08-18）。
    """
    msg = state["messages"][-1].content if state.get("messages") else ""
    if tool_name == "get_current_time":
        # Asia/Singapore：项目部署地（与 system_facts 一致，2026-08-15 统一）
        return {"timezone": "Asia/Singapore", "format": "datetime"}
    if tool_name == "calculator":
        match = re.search(r"[\d+\-*/().\s^]+", msg)
        return {"expression": match.group(0).strip() if match else "0"}
    if tool_name in ("web_search", "search_policy"):
        # 规则级提取 query：去除搜索引导词后作为检索词（"搜索/查一下/search for" 等）
        query = re.sub(
            r"^(?:搜索|搜一下|搜下|查一下|查查|帮我查(?:一下|一)?|帮我搜(?:一下|一)?|查询|"
            r"search(?:\s+for)?\s*[:：]?\s*|find\s*[:：]?\s*)",
            "",
            msg,
            flags=re.IGNORECASE,
        ).strip()
        if not query or _ANAPHORA_REQ.match(msg.strip()):
            # 回指：复用上一次实际执行的查询词；无历史时回退整句（原行为）
            prev = state.get("last_search_query")
            if prev:
                return {"query": prev, "reused_last": True}
        return {"query": query or msg.strip()}


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
        # 语言跟随：英文用户必须得到英文兜底（2026-08-15 修复：失败路径此前固定中文）
        user_zh = _looks_chinese(user_msg)
        fallback_text = (
            "抱歉，部分服务暂时不可用，请稍后重试。"
            if user_zh
            else "Sorry, some services are temporarily unavailable. Please try again later."
        )
        llm = chat_llm()
        try:
            response = await llm.ainvoke(
                [
                    SystemMessage(
                        content=(
                            "你是校园助手。部分服务当前不可用时，请用自然、友好的语气向用户说明情况，"
                            "给出替代建议或请其稍后重试。不要提及内部技术细节。"
                            "请使用与用户消息相同的语言回复（用户用英文则回复英文，用户用中文则回复中文）。"
                            "重要：你只知道自己列出的不可用服务名称，不具备任何额外业务信息——"
                            "绝对不要编造需要用户提供的具体字段（如物品名称、地点、日期等），"
                            "也不要让用户以为系统还在正常工作；如实告知服务暂时无法处理即可。"
                        )
                    ),
                    HumanMessage(content=f"用户请求：{user_msg}\n不可用的服务：{'; '.join(failures)}"),
                ]
            )
            content = getattr(response, "content", "") or ""
            return {"messages": [AIMessage(content=content.strip() or fallback_text)]}
        except Exception:
            return {"messages": [AIMessage(content=fallback_text)]}

    if state.get("error"):
        user_msg = state["messages"][-1].content if state.get("messages") else ""
        text = (
            "抱歉，我没有理解你的请求，请换一种说法。"
            if _looks_chinese(user_msg)
            else "Sorry, I did not understand your request. Please rephrase it."
        )
        return {"messages": [AIMessage(content=text)]}

    last_msg = state["messages"][-1].content if state.get("messages") else ""
    user_msg = last_msg if isinstance(last_msg, str) else ""
    fallback_text = (
        "抱歉，我现在暂时无法回复，请稍后重试。"
        if _looks_chinese(user_msg)
        else "Sorry, I can't reply right now. Please try again later."
    )
    llm = chat_llm()
    try:
        history = state.get("messages", []) or [HumanMessage(content="你好")]
        # 注入身份/功能系统提示词：避免自报 DeepSeek，回答介绍 CampusLink 功能
        response = await llm.ainvoke([SystemMessage(content=_CHAT_SYSTEM_PROMPT), *history])
        content = getattr(response, "content", "") or ""
        return {"messages": [AIMessage(content=content.strip() or fallback_text)]}
    except Exception:
        return {"messages": [AIMessage(content=fallback_text)]}


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


async def response_aggregator(state: AgentState) -> AgentState:
    """汇总所有 Agent / Utility 结果生成最终自然语言回复。

    返回最小状态增量：已有最终 AIMessage 时返回 {}（避免 chat 路径重复输出）；
    否则返回 {"messages": [AIMessage]} 由 reducer 追加。
    """
    if state.get("messages") and isinstance(state["messages"][-1], AIMessage):
        return {}  # 已有最终回复（如 chat_responder 生成），不重复

    agent_invocations = current_turn_invocations(state)
    utility_results = state.get("utility_results", {})
    parts: list[str] = []

    # 澄清循环：若本轮已有进展（completed/failed/确认结果），历史 needs_more_info 的
    # 追问文本不再输出（避免"追问 + 最终结果"拼接残留）；仅当没有任何进展
    # （首轮追问、用户还在补充中）时才输出追问文本
    has_progress = any(
        inv.get("output_status") not in ("needs_confirmation", "needs_more_info") for inv in agent_invocations
    )

    for inv in agent_invocations:
        if inv.get("output_status") == "needs_confirmation":
            # 确认提示由 HITL 确认框呈现，不进最终回复；确认后的操作结果由重调记录输出
            continue
        if inv.get("output_status") == "needs_more_info" and has_progress:
            # 澄清历史追问已被最终结果取代
            continue
        parts.append(inv.get("output_response", ""))

    # Utility 结果：优先用 LLM 重述文本（语言跟随用户），否则回退逐项格式化
    utility_response = state.get("utility_response")
    if utility_response:
        parts.append(utility_response)
    else:
        for tool_name, result in utility_results.items():
            parts.append(_format_utility_result(tool_name, result))

    if not parts:
        # 语言跟随：无任何可聚合内容时的兜底文案（英文用户默认英文）
        user_msgs = [m.content for m in state.get("messages", []) if isinstance(m, HumanMessage)]
        final = (
            "抱歉，暂时无法处理你的请求。"
            if _looks_chinese("".join(user_msgs))
            else "Sorry, I couldn't process your request right now."
        )
    elif len(parts) == 1:
        final = parts[0]
    else:
        # 语言跟随：把用户原始消息一并传给 LLM，确保聚合输出语言与用户一致
        summary_user_msgs = [m.content for m in state.get("messages", []) if isinstance(m, HumanMessage)]
        summary_user_msg = summary_user_msgs[-1] if summary_user_msgs else ""
        llm = summary_llm()
        try:
            summary = llm.invoke(
                [
                    SystemMessage(
                        content=(
                            "Combine the following results into one coherent, natural reply. "
                            "Use the same language as the user's request."
                        )
                    ),
                    HumanMessage(
                        content=(
                            f"User request: {summary_user_msg}\n\nResults:\n" + "\n".join(parts)
                            if summary_user_msg
                            else "\n".join(parts)
                        )
                    ),
                ]
            )
            final = summary.content
        except Exception:
            final = "\n".join(parts)

    # 语言跟随：domain Agent 的原始回复（如 L&F 的中文文案）按用户消息语言重述，
    # 避免英文提问却返回中文；用户消息与结果语言一致时跳过（省一次 LLM 往返），
    # 重述失败回退原文（LLM 不可用时保证有回复）
    if final:
        # 用户语言用整个会话历史判断（而不是最后一条消息）：中文会话中用户可能
        # 输入纯英文房间名/短语（如 "Room 101"），只看末条会误判为英文用户
        user_msgs = [m.content for m in state.get("messages", []) if isinstance(m, HumanMessage)]
        user_msg = user_msgs[-1] if user_msgs else ""
        user_zh = _looks_chinese("".join(user_msgs))
        final_zh = _looks_chinese(final)
        if user_zh != final_zh:
            rephrased = await _rephrase_in_user_language(user_msg, final)
            if rephrased:
                final = rephrased

    # 本节点位于 output_guardrail 之后，重述/聚合新生成的文本未经过脱敏，
    # 此处补一轮 PII 脱敏（邮箱/手机号/身份证）
    for pattern, replacement in _PII_PATTERNS:
        final = re.sub(pattern, replacement, final)

    return {"messages": [AIMessage(content=final)]}


def _format_utility_result(tool_name: str, result: Any) -> str:
    """Utility 工具结果直接格式化（避免多余 LLM 调用）。"""
    if not isinstance(result, dict):
        return f"（{tool_name} 返回异常：{result}）"
    if "result" in result and isinstance(result["result"], (int, float)):
        return f"计算结果：{result.get('expression', '')} = {result['result']}"
    if "timezone" in result and result.get("value"):
        return f"现在是 {result['value']}（{result.get('timezone', 'Asia/Singapore')}）"
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
    approval_context = state.get("approval_context", {}) or {}
    # 语言跟随：兜底文案按用户语言输出（2026-08-17 检查确认门时发现：
    # 无 message/summary 时的兜底此前固定中文）
    user_msg = state["messages"][-1].content if state.get("messages") else ""
    user_zh = _looks_chinese(user_msg)
    decision = interrupt(
        {
            "type": "confirm_action",
            "agent": approval_agent,
            "details": approval_context,
            # 确认信息优先用 Agent 的真实摘要（L&F confirmation_required.summary），
            # 前端确认框展示的就是用户要确认的具体内容
            "message": (
                approval_context.get("message")
                or approval_context.get("summary")
                or ("请确认此操作" if user_zh else "Please confirm this action")
            ),
        }
    )

    update: dict[str, Any] = {
        "requires_approval": False,
        "approval_context": None,
        "approval_agent": None,
    }

    approved = isinstance(decision, dict) and decision.get("approved")
    if approval_agent.startswith("utility:"):
        # 工具执行确认（如 web_search 联网）：记录确认结果，
        # 由 utility_tool_executor 消费（确认则执行，取消则跳过）
        update["pending_utility_confirm"] = {
            "tool": approval_agent.split(":", 1)[1],
            "cancelled": not approved,
        }
        return update

    if state.get("agent_invocations"):
        # 只 merge 最后一条（当前等待审批的 Agent），保留前面已完成 Agent 的记录
        invocations = list(state["agent_invocations"])
        if approved:
            invocations[-1] = {**invocations[-1], "output_status": "confirmed"}
            # 确认重调标记：agent_invoker 下次调用该 Agent 时携带 confirmed + confirmation_id
            update["pending_confirmation"] = {
                "agent_name": approval_agent,
                "confirmation_id": (state.get("approval_context") or {}).get("confirmation_id"),
            }
        else:
            invocations[-1] = {
                **invocations[-1],
                "output_response": (
                    "操作已取消。" if user_zh else "The operation has been cancelled."
                ),
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
    """降级：Agent 不可用 / 超时 / 安全拦截时返回友好文案（语言跟随用户）。"""
    user_msgs = [m.content for m in state.get("messages", []) if isinstance(m, HumanMessage)]
    user_zh = _looks_chinese("".join(user_msgs))
    failed = state.get("failed_agents", [])
    if failed:
        names = "、".join(failed) if user_zh else ", ".join(failed)
        text = (
            f"「{names}」暂时不可用，请稍后重试。"
            if user_zh
            else f"「{names}」is temporarily unavailable. Please try again later."
        )
        return {"messages": [AIMessage(content=text)]}
    text = (
        "抱歉，服务暂时不可用，请稍后重试。"
        if user_zh
        else "Sorry, the service is temporarily unavailable. Please try again later."
    )
    return {"messages": [AIMessage(content=text)]}
