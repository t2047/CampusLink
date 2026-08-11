"""中英文规则对话、字段补充、写操作确认与工具编排。"""

import re
from collections.abc import Callable
from datetime import UTC, date, datetime, timedelta
from typing import Any, Literal

from .confirmation import ConfirmationError, ConfirmationStore
from .events import AgentEvent
from .matching import rank_candidates
from .models import (
    ActionTaken,
    ConfirmationRequired,
    InvokeRequest,
    InvokeResponse,
    VerifiedRequest,
)
from .tools import (
    BackendApiError,
    CampusApiClient,
    ClaimItemInput,
    GetItemDetailInput,
    ReportFoundInput,
    ReportLostInput,
    SearchFoundItemsInput,
    SearchLostItemsInput,
)

Intent = Literal[
    "report_lost",
    "report_found",
    "search_found_items",
    "get_item_detail",
    "claim_item",
]
Emit = Callable[[AgentEvent], None]

CATEGORIES: dict[str, str] = {
    "electronics": "ELECTRONICS",
    "electronic": "ELECTRONICS",
    "headphone": "ELECTRONICS",
    "earbud": "ELECTRONICS",
    "电子": "ELECTRONICS",
    "耳机": "ELECTRONICS",
    "手机": "ELECTRONICS",
    "电脑": "ELECTRONICS",
    "id card": "ID_CARD",
    "student card": "ID_CARD",
    "证件": "ID_CARD",
    "学生卡": "ID_CARD",
    "wallet": "WALLET_PURSE",
    "purse": "WALLET_PURSE",
    "钱包": "WALLET_PURSE",
    "key": "KEYS",
    "钥匙": "KEYS",
    "bag": "BAG",
    "backpack": "BAG",
    "包": "BAG",
    "clothing": "CLOTHING",
    "clothes": "CLOTHING",
    "衣服": "CLOTHING",
    "book": "BOOKS_STATIONERY",
    "stationery": "BOOKS_STATIONERY",
    "书": "BOOKS_STATIONERY",
    "文具": "BOOKS_STATIONERY",
    "umbrella": "UMBRELLA",
    "雨伞": "UMBRELLA",
    "伞": "UMBRELLA",
    "other": "OTHER",
    "其他": "OTHER",
}

COLOURS = {
    "black": "Black",
    "white": "White",
    "blue": "Blue",
    "red": "Red",
    "green": "Green",
    "yellow": "Yellow",
    "grey": "Grey",
    "gray": "Grey",
    "purple": "Purple",
    "pink": "Pink",
    "orange": "Orange",
    "brown": "Brown",
    "黑色": "黑色",
    "白色": "白色",
    "蓝色": "蓝色",
    "红色": "红色",
    "绿色": "绿色",
    "黄色": "黄色",
    "灰色": "灰色",
    "紫色": "紫色",
    "粉色": "粉色",
    "橙色": "橙色",
    "棕色": "棕色",
}

ALLOWED_CONTEXT_FIELDS = {
    "intent",
    "item_name",
    "category",
    "description",
    "colour",
    "location",
    "event_date",
    "time_description",
    "keyword",
    "date_from",
    "date_to",
    "report_id",
    "system_facts",
    "recent_messages",
    "proof_description",
}


class RuleEngine:
    def __init__(
        self,
        api_client: CampusApiClient,
        confirmations: ConfirmationStore,
        minimum_score: float,
    ) -> None:
        self._api = api_client
        self._confirmations = confirmations
        self._minimum_score = minimum_score

    async def handle(
        self,
        payload: InvokeRequest,
        verified: VerifiedRequest,
        request_id: str,
        emit: Emit,
        interpreted_intent: Intent | None = None,
        interpreted_fields: dict[str, Any] | None = None,
    ) -> InvokeResponse:
        language = detect_language(payload.message)
        if payload.confirmed or payload.confirmation_id:
            return await self._handle_confirmation(payload, verified, request_id, language, emit)

        context = safe_context(payload.conversation_context.shared_data)
        previous_intent = context.get("intent")
        intent = (
            detect_explicit_intent(payload.message)
            or (previous_intent if previous_intent in ALLOWED_INTENTS else None)
            or interpreted_intent
            or "search_found_items"
        )
        context["intent"] = intent
        context.update(extract_fields(payload.message, intent))
        if interpreted_fields:
            context.update(
                {
                    key: value
                    for key, value in interpreted_fields.items()
                    if key in ALLOWED_CONTEXT_FIELDS and key != "intent"
                }
            )

        if intent == "report_lost":
            return self._prepare_report(context, verified, request_id, language, emit)
        if intent == "report_found":
            return self._prepare_found(context, verified, request_id, language, emit)
        if intent == "claim_item":
            return self._prepare_claim(context, verified, request_id, language, emit)
        if intent == "get_item_detail":
            return await self._detail(context, verified, request_id, language, emit)
        return await self._search(context, verified, request_id, language, emit)

    def _prepare_report(
        self,
        context: dict[str, Any],
        verified: VerifiedRequest,
        request_id: str,
        language: str,
        emit: Emit,
    ) -> InvokeResponse:
        required = ["item_name", "category", "description", "location", "event_date"]
        missing = [field for field in required if not context.get(field)]
        if missing:
            message = missing_message(missing, language)
            emit(AgentEvent("needs_more_info", {"missing_fields": missing, "message": message}))
            return response_with_token(
                message,
                "needs_more_info",
                request_id,
                emit,
                shared_context=context,
            )

        report = ReportLostInput.model_validate(context)
        confirmation_id, pending = self._confirmations.create(
            verified.user_id, "report_lost", report.model_dump(mode="json")
        )
        summary = report_summary(report, language)
        confirmation = ConfirmationRequired(
            confirmation_id=confirmation_id,
            action="report_lost",
            summary=summary,
            expires_at=datetime.fromtimestamp(pending.expires_at, UTC).isoformat(),
        )
        message = (
            f"请确认报失信息：{summary}"
            if language == "zh"
            else f"Please confirm this lost-item report: {summary}"
        )
        emit(AgentEvent("confirmation_required", confirmation.model_dump(mode="json")))
        return response_with_token(
            message,
            "needs_confirmation",
            request_id,
            emit,
            shared_context=context,
            confirmation_required=confirmation,
        )

    def _prepare_found(
        self,
        context: dict[str, Any],
        verified: VerifiedRequest,
        request_id: str,
        language: str,
        emit: Emit,
    ) -> InvokeResponse:
        """登记捡到物品（report_found）：先确认（写操作），确认后创建 FOUND 报告。"""
        required = ["item_name", "category", "description", "location", "event_date"]
        missing = [field for field in required if not context.get(field)]
        if missing:
            message = missing_message(missing, language)
            emit(AgentEvent("needs_more_info", {"missing_fields": missing, "message": message}))
            return response_with_token(
                message,
                "needs_more_info",
                request_id,
                emit,
                shared_context=context,
            )

        report = ReportFoundInput.model_validate(context)
        confirmation_id, pending = self._confirmations.create(
            verified.user_id, "report_found", report.model_dump(mode="json")
        )
        summary = report_summary(report, language)
        confirmation = ConfirmationRequired(
            confirmation_id=confirmation_id,
            action="report_found",
            summary=summary,
            expires_at=datetime.fromtimestamp(pending.expires_at, UTC).isoformat(),
        )
        message = (
            f"请确认捡到物品信息：{summary}"
            if language == "zh"
            else f"Please confirm this found-item report: {summary}"
        )
        emit(AgentEvent("confirmation_required", confirmation.model_dump(mode="json")))
        return response_with_token(
            message,
            "needs_confirmation",
            request_id,
            emit,
            shared_context=context,
            confirmation_required=confirmation,
        )

    def _prepare_claim(
        self,
        context: dict[str, Any],
        verified: VerifiedRequest,
        request_id: str,
        language: str,
        emit: Emit,
    ) -> InvokeResponse:
        missing: list[str] = []
        if not context.get("report_id"):
            missing.append("report_id")
        if len(str(context.get("proof_description", "")).strip()) < 10:
            missing.append("proof_description")
        if missing:
            message = missing_message(missing, language)
            emit(AgentEvent("needs_more_info", {"missing_fields": missing, "message": message}))
            return response_with_token(
                message,
                "needs_more_info",
                request_id,
                emit,
                shared_context=context,
            )

        claim = ClaimItemInput.model_validate(context)
        confirmation_id, pending = self._confirmations.create(
            verified.user_id, "claim_item", claim.model_dump(mode="json")
        )
        summary = (
            f"认领记录 #{claim.report_id}，证明：{claim.proof_description}"
            if language == "zh"
            else f"Claim report #{claim.report_id}; proof: {claim.proof_description}"
        )
        confirmation = ConfirmationRequired(
            confirmation_id=confirmation_id,
            action="claim_item",
            summary=summary,
            expires_at=datetime.fromtimestamp(pending.expires_at, UTC).isoformat(),
        )
        message = f"请确认：{summary}" if language == "zh" else f"Please confirm: {summary}"
        emit(AgentEvent("confirmation_required", confirmation.model_dump(mode="json")))
        return response_with_token(
            message,
            "needs_confirmation",
            request_id,
            emit,
            shared_context=context,
            confirmation_required=confirmation,
        )

    async def _handle_confirmation(
        self,
        payload: InvokeRequest,
        verified: VerifiedRequest,
        request_id: str,
        language: str,
        emit: Emit,
    ) -> InvokeResponse:
        if not payload.confirmed or not payload.confirmation_id:
            message = (
                "确认参数不完整" if language == "zh" else "Confirmation parameters are incomplete"
            )
            return response_with_token(message, "failed", request_id, emit)
        try:
            pending = self._confirmations.consume(payload.confirmation_id, verified.user_id)
        except ConfirmationError as exc:
            message = (
                str(exc)
                if language == "zh"
                else "Confirmation is invalid, expired, or unauthorized"
            )
            emit(AgentEvent("agent_error", {"code": "INVALID_CONFIRMATION", "message": message}))
            return response_with_token(message, "failed", request_id, emit)

        try:
            if pending.action == "claim_item":
                claim = ClaimItemInput.model_validate(pending.payload)
                emit(tool_event("claim_item", "started"))
                result = await self._api.claim_item(verified.user_id, verified.user_role, claim)
                emit(tool_event("claim_item", "completed"))
                message = (
                    "认领申请已提交，请等待拾获记录发布者处理。"
                    if language == "zh"
                    else "Your claim was submitted for review by the found-item reporter."
                )
                return response_with_token(
                    message,
                    "completed",
                    request_id,
                    emit,
                    actions_taken=[
                        ActionTaken(
                            action="claim_item",
                            result_summary=f"claim_id={result.get('id')}",
                            status="success",
                        )
                    ],
                )

            if pending.action == "report_found":
                found_report = ReportFoundInput.model_validate(pending.payload)
                emit(tool_event("report_found", "started"))
                created = await self._api.report_found(
                    verified.user_id, verified.user_role, found_report
                )
                emit(tool_event("report_found", "completed"))
                query = found_report.model_dump(mode="json")
                matches, search_action = await self._search_candidates(
                    query,
                    verified,
                    language,
                    emit,
                    target_report_type="LOST",
                )
                message = found_created_message(created, matches, language)
                return response_with_token(
                    message,
                    "match_found" if matches else "completed",
                    request_id,
                    emit,
                    match_results=matches,
                    actions_taken=[
                        ActionTaken(
                            action="report_found",
                            result_summary=f"report_id={created.get('id')}",
                            status="success",
                        ),
                        search_action,
                    ],
                )

            report = ReportLostInput.model_validate(pending.payload)
            emit(tool_event("report_lost", "started"))
            created = await self._api.report_lost(verified.user_id, verified.user_role, report)
            emit(tool_event("report_lost", "completed"))
            query = report.model_dump(mode="json")
            matches, search_action = await self._search_candidates(query, verified, language, emit)
            message = report_created_message(created, matches, language)
            return response_with_token(
                message,
                "match_found" if matches else "completed",
                request_id,
                emit,
                match_results=matches,
                actions_taken=[
                    ActionTaken(
                        action="report_lost",
                        result_summary=f"report_id={created.get('id')}",
                        status="success",
                    ),
                    search_action,
                ],
            )
        except BackendApiError as exc:
            return backend_error_response(exc, request_id, language, emit)

    async def _search(
        self,
        context: dict[str, Any],
        verified: VerifiedRequest,
        request_id: str,
        language: str,
        emit: Emit,
    ) -> InvokeResponse:
        if not any(
            context.get(field)
            for field in (
                "keyword",
                "item_name",
                "description",
                "category",
                "colour",
                "location",
                "event_date",
                "date_from",
                "date_to",
            )
        ):
            message = (
                "请提供物品名称、类别、颜色、地点或日期中的至少一项。"
                if language == "zh"
                else "Please provide at least an item name, category, colour, location, or date."
            )
            emit(
                AgentEvent(
                    "needs_more_info", {"missing_fields": ["search_criteria"], "message": message}
                )
            )
            return response_with_token(
                message, "needs_more_info", request_id, emit, shared_context=context
            )
        try:
            matches, action = await self._search_candidates(context, verified, language, emit)
        except BackendApiError as exc:
            return backend_error_response(exc, request_id, language, emit)
        if matches:
            message = match_results_message(matches, language)
            status = "match_found"
        else:
            message = (
                "暂时没有达到最低匹配分数的候选物品。"
                if language == "zh"
                else "No candidate currently meets the minimum matching score."
            )
            status = "no_match"
        return response_with_token(
            message,
            status,
            request_id,
            emit,
            match_results=matches,
            shared_context=context,
            actions_taken=[action],
        )

    async def _search_candidates(
        self,
        query: dict[str, Any],
        verified: VerifiedRequest,
        language: str,
        emit: Emit,
        target_report_type: Literal["LOST", "FOUND"] = "FOUND",
    ) -> tuple[list[Any], ActionTaken]:
        event_date = parse_date(query.get("event_date"))
        date_from = parse_date(query.get("date_from"))
        date_to = parse_date(query.get("date_to"))
        if event_date:
            date_from = date_from or event_date - timedelta(days=30)
            date_to = date_to or event_date + timedelta(days=30)
        search_values = {
            "category": query.get("category"),
            "date_from": date_from,
            "date_to": date_to,
            "page": 0,
            "size": 100,
        }
        if target_report_type == "LOST":
            action_name = "search_lost_items"
            search = SearchLostItemsInput(**search_values)
            emit(tool_event(action_name, "started"))
            result = await self._api.search_lost_items(
                verified.user_id,
                verified.user_role,
                search,
            )
            emit(tool_event(action_name, "completed"))
        else:
            action_name = "search_found_items"
            found_search = SearchFoundItemsInput(**search_values)
            emit(tool_event(action_name, "started"))
            result = await self._api.search_found_items(
                verified.user_id,
                verified.user_role,
                found_search,
            )
            emit(tool_event(action_name, "completed"))
        content = result.get("content", [])
        candidates = content if isinstance(content, list) else []
        matches = rank_candidates(query, candidates, self._minimum_score, language)
        action = ActionTaken(
            action=action_name,
            params_summary=f"{target_report_type} + OPEN, size=100",
            result_summary=f"candidates={len(candidates)}, matches={len(matches)}",
            status="success",
        )
        return matches, action

    async def _detail(
        self,
        context: dict[str, Any],
        verified: VerifiedRequest,
        request_id: str,
        language: str,
        emit: Emit,
    ) -> InvokeResponse:
        report_id = context.get("report_id")
        if not report_id:
            message = "请提供记录 ID。" if language == "zh" else "Please provide the report ID."
            emit(
                AgentEvent("needs_more_info", {"missing_fields": ["report_id"], "message": message})
            )
            return response_with_token(
                message, "needs_more_info", request_id, emit, shared_context=context
            )
        try:
            emit(tool_event("get_item_detail", "started"))
            detail = await self._api.get_item_detail(
                verified.user_id,
                verified.user_role,
                GetItemDetailInput(report_id=int(report_id)),
            )
            emit(tool_event("get_item_detail", "completed"))
        except BackendApiError as exc:
            return backend_error_response(exc, request_id, language, emit)
        message = detail_message(detail, language)
        return response_with_token(
            message,
            "completed",
            request_id,
            emit,
            shared_context=context,
            actions_taken=[
                ActionTaken(
                    action="get_item_detail",
                    result_summary=f"report_id={report_id}",
                    status="success",
                )
            ],
        )


def detect_language(message: str) -> str:
    return "zh" if re.search(r"[\u4e00-\u9fff]", message) else "en"


def detect_intent(message: str, previous: Any = None) -> Intent:
    explicit = detect_explicit_intent(message)
    if explicit:
        return explicit
    return previous if previous in ALLOWED_INTENTS else "search_found_items"


def detect_explicit_intent(message: str) -> Intent | None:
    """识别用户本轮明确表达的意图，并避免将有歧义的“找到”直接判为搜索。"""
    lowered = message.lower()
    if re.search(r"\bclaim\b|\bownership\b", lowered) or "认领" in message:
        return "claim_item"
    if re.search(r"\bdetail(?:s)?\b|\bview\b", lowered) or any(
        keyword in message for keyword in ("详情", "查看记录")
    ):
        return "get_item_detail"

    # “找到”既可能表示失主搜索，也可能表示拾获者发现物品。只有同时表达
    # 创建、登记或发布时，规则层才明确将其识别为拾获登记；其余模糊情况交给 LLM。
    explicit_search = any(
        keyword in message for keyword in ("搜索", "帮我找", "查找", "匹配", "有没有人捡到")
    )
    found_publication = (
        not explicit_search
        and any(keyword in message for keyword in ("创建", "登记", "发布", "上报", "记录"))
        and any(keyword in message for keyword in ("找到", "捡到", "捡了", "拾到"))
    )
    if found_publication:
        return "report_found"

    if re.search(r"\bsearch\b|\bfind\b|\bfound item", lowered) or any(
        keyword in message for keyword in ("搜索", "帮我找", "查找", "匹配", "有没有人捡到")
    ):
        return "search_found_items"
    if re.search(r"\bi lost\b|\breport(?:ed)? lost\b|\blost my\b", lowered) or any(
        keyword in message for keyword in ("我丢了", "丢了", "丢失", "遗失", "报失")
    ):
        return "report_lost"
    # “捡到/拾到”含义明确；单独的“找到”交给 LLM 结合上下文判断。
    if re.search(r"\bpick(?:ed)? up\b|\bfound\b", lowered) or any(
        keyword in message for keyword in ("捡到", "捡了", "拾到")
    ):
        return "report_found"
    return None


ALLOWED_INTENTS = {
    "report_lost",
    "report_found",
    "search_found_items",
    "get_item_detail",
    "claim_item",
}


def safe_context(shared_data: dict[str, Any]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in shared_data.items():
        if key not in ALLOWED_CONTEXT_FIELDS:
            continue
        if key == "system_facts" and isinstance(value, dict):
            # 编排层注入的系统事实包：只放行字符串值（today/now/timezone/user_language）
            result[key] = {k: v for k, v in value.items() if isinstance(v, str)}
        elif key == "recent_messages" and isinstance(value, list):
            # 编排层注入的最近对话历史：只保留 role/content 字符串对
            cleaned = []
            for item in value:
                if (
                    isinstance(item, dict)
                    and isinstance(item.get("role"), str)
                    and isinstance(item.get("content"), str)
                ):
                    cleaned.append({"role": item["role"], "content": item["content"]})
            result[key] = cleaned
        elif isinstance(value, (str, int, float, bool)):
            result[key] = value
    return result


def extract_fields(message: str, intent: Intent) -> dict[str, Any]:
    fields: dict[str, Any] = {}
    labelled = {
        "item_name": [
            r"(?:物品(?:名称)?|名称)[:：]\s*([^,，;；\n]+)",
            r"item(?:\s+name)?\s*[:=]\s*([^,;\n]+)",
        ],
        "description": [r"(?:描述|特征)[:：]\s*([^;；\n]+)", r"description\s*[:=]\s*([^;\n]+)"],
        "location": [r"(?:地点|位置)[:：]\s*([^,，;；\n]+)", r"location\s*[:=]\s*([^,;\n]+)"],
        "colour": [r"(?:颜色)[:：]\s*([^,，;；\n]+)", r"colou?r\s*[:=]\s*([^,;\n]+)"],
        "time_description": [r"(?:时间描述)[:：]\s*([^,，;；\n]+)", r"time\s*[:=]\s*([^,;\n]+)"],
        "proof_description": [
            r"(?:认领证明|证明)[:：]\s*(.+)",
            r"(?:proof|because)\s*[:=]?\s*(.+)",
        ],
        "keyword": [r"(?:关键词)[:：]\s*([^,，;；\n]+)", r"keyword\s*[:=]\s*([^,;\n]+)"],
    }
    for field, patterns in labelled.items():
        for pattern in patterns:
            match = re.search(pattern, message, re.IGNORECASE)
            if match:
                fields[field] = match.group(1).strip()
                break

    category_match = re.search(r"(?:类别)[:：]\s*([\w_\u4e00-\u9fff ]+)", message, re.IGNORECASE)
    english_category = re.search(r"category\s*[:=]\s*([\w_ ]+)", message, re.IGNORECASE)
    category_value = category_match or english_category
    if category_value:
        fields["category"] = map_category(category_value.group(1))
    else:
        mapped = map_category(message)
        if mapped:
            fields["category"] = mapped

    if "colour" not in fields:
        for keyword, value in COLOURS.items():
            if keyword in message.lower():
                fields["colour"] = value
                break

    iso_dates = re.findall(r"(?<!\d)\d{4}-\d{2}-\d{2}(?!\d)", message)
    if iso_dates:
        fields["event_date"] = iso_dates[0]
        if len(iso_dates) > 1:
            fields["date_from"], fields["date_to"] = iso_dates[:2]
    elif "前天" in message or "day before yesterday" in message.lower():
        fields["event_date"] = (date.today() - timedelta(days=2)).isoformat()
    elif "昨天" in message or "yesterday" in message.lower():
        fields["event_date"] = (date.today() - timedelta(days=1)).isoformat()
    elif "今天" in message or "today" in message.lower():
        fields["event_date"] = date.today().isoformat()

    report_id = re.search(
        r"(?:#|ID\s*[:=]?\s*|记录\s*|(?:item|report)\s+#?)(\d+)",
        message,
        re.IGNORECASE,
    )
    if report_id:
        fields["report_id"] = int(report_id.group(1))

    if intent == "report_lost" and "item_name" not in fields:
        item = re.search(
            r"(?:我丢了|丢了|丢失了|遗失了)\s*(?:一(?:个|把|只|本|张|副))?\s*"
            r"([^,，。;；\n]{2,30})",
            message,
        )
        english_item = re.search(r"(?:i lost|lost my)\s+([^,;\n]{2,40})", message, re.IGNORECASE)
        matched_item = item or english_item
        if matched_item:
            fields["item_name"] = matched_item.group(1).strip()
        if "location" not in fields:
            location = re.search(
                r"于([^,，。;；\n]{2,40}?)(?:丢了|丢失|遗失)",
                message,
            ) or re.search(
                r"在([^,，。;；\n]{2,40}?)(?:丢了|丢失|遗失)",
                message,
            )
            if location:
                fields["location"] = location.group(1).strip()
        if "description" not in fields and len(message.strip()) >= 10:
            fields["description"] = message.strip()
    if intent == "report_found" and "item_name" not in fields:
        item = re.search(
            r"(?:我)?(?:找到|捡到|捡了|拾到)\s*(?:了)?\s*(?:一(?:个|把|只|本|张|副))?\s*"
            r"([^,，。;；\n]{2,30})",
            message,
        )
        english_item = re.search(
            r"(?:i\s+found|(?:i\s+)?picked\s+up)\s+(?:an?\s+|the\s+)?"
            r"([^,;\n]{2,40}?)(?=\s+(?:at|in|on)\s|[,;\n]|$)",
            message,
            re.IGNORECASE,
        )
        matched_item = item or english_item
        if matched_item:
            fields["item_name"] = matched_item.group(1).strip()
        if "location" not in fields:
            location = re.search(
                r"于([^,，。;；\n]{2,40}?)(?:找到|捡到|捡了|拾到)",
                message,
            ) or re.search(
                r"在([^,，。;；\n]{2,40}?)(?:找到|捡到|捡了|拾到)",
                message,
            )
            english_location = re.search(
                r"(?:at|in)\s+([^,;\n]{2,40}?)(?=\s+on\s+\d{4}-\d{2}-\d{2}|[,;\n]|$)",
                message,
                re.IGNORECASE,
            )
            matched_location = location or english_location
            if matched_location:
                fields["location"] = matched_location.group(1).strip()
        if "description" not in fields and len(message.strip()) >= 10:
            fields["description"] = message.strip()
    if intent == "search_found_items" and "keyword" not in fields:
        search = re.search(r"(?:帮我找|查找|搜索)\s*([^,，;；\n]{2,40})", message)
        english_search = re.search(r"(?:find|search for)\s+([^,;\n]{2,40})", message, re.IGNORECASE)
        matched_search = search or english_search
        if matched_search:
            fields["keyword"] = matched_search.group(1).strip()
    return {key: value for key, value in fields.items() if value not in (None, "")}


def map_category(value: str) -> str | None:
    normalized = value.lower().strip()
    for enum_value in {
        "ELECTRONICS",
        "ID_CARD",
        "WALLET_PURSE",
        "KEYS",
        "BAG",
        "CLOTHING",
        "BOOKS_STATIONERY",
        "UMBRELLA",
        "OTHER",
    }:
        if enum_value.lower() in normalized:
            return enum_value
    for keyword, category in CATEGORIES.items():
        if keyword in normalized:
            return category
    return None


def parse_date(value: Any) -> date | None:
    if not value:
        return None
    try:
        return date.fromisoformat(str(value))
    except ValueError:
        return None


def missing_message(fields: list[str], language: str) -> str:
    labels = {
        "zh": {
            "item_name": "物品名称",
            "category": "类别",
            "description": "详细描述",
            "location": "地点",
            "event_date": "日期（YYYY-MM-DD）",
            "report_id": "记录 ID",
            "proof_description": "不少于 10 个字符的认领证明",
        },
        "en": {
            "item_name": "item name",
            "category": "category",
            "description": "detailed description",
            "location": "location",
            "event_date": "date (YYYY-MM-DD)",
            "report_id": "report ID",
            "proof_description": "ownership proof of at least 10 characters",
        },
    }
    active = labels["zh" if language == "zh" else "en"]
    names = ", ".join(active[field] for field in fields)
    return f"还需要以下信息：{names}。" if language == "zh" else f"Please provide: {names}."


def report_summary(report: ReportLostInput | ReportFoundInput, language: str) -> str:
    if language == "zh":
        return (
            f"{report.item_name}，类别 {report.category}，地点 {report.location}，"
            f"日期 {report.event_date.isoformat()}，描述：{report.description}"
        )
    return (
        f"{report.item_name}; category {report.category}; location {report.location}; "
        f"date {report.event_date.isoformat()}; description: {report.description}"
    )


def report_created_message(created: dict[str, Any], matches: list[Any], language: str) -> str:
    report_id = created.get("id")
    if language == "zh":
        if matches:
            return f"报失记录 #{report_id} 已创建。\n{match_results_message(matches, language)}"
        return f"报失记录 #{report_id} 已创建，暂时未找到高匹配候选。"
    if matches:
        return f"Lost report #{report_id} was created.\n{match_results_message(matches, language)}"
    return f"Lost report #{report_id} was created with no strong match yet."


def found_created_message(created: dict[str, Any], matches: list[Any], language: str) -> str:
    """拾获记录创建成功后，附带可能对应的开放报失记录。"""
    report_id = created.get("id")
    if language == "zh":
        if matches:
            return f"捡到物品记录 #{report_id} 已登记。\n{match_results_message(matches, language)}"
        return f"捡到物品记录 #{report_id} 已登记，暂时未找到高匹配的报失记录。"
    if matches:
        details = match_results_message(matches, language)
        return f"Found report #{report_id} was registered.\n{details}"
    return f"Found report #{report_id} was registered with no strong lost-report match yet."


def match_results_message(matches: list[Any], language: str) -> str:
    """生成所有客户端都能直接展示的候选摘要，结构化结果仍单独返回。"""
    heading = (
        f"找到 {len(matches)} 个匹配度较高的候选物品："
        if language == "zh"
        else f"Found {len(matches)} strong candidate item(s):"
    )
    lines = [heading]
    for index, match in enumerate(matches, start=1):
        score = round(float(match.match_score) * 100)
        colour = match.colour or ("未填写" if language == "zh" else "not provided")
        reasons = (
            "；".join(match.match_reason) if language == "zh" else "; ".join(match.match_reason)
        )
        if language == "zh":
            lines.append(
                f"{index}. #{match.item_id} [{match.report_type}] {match.item_name}；"
                f"类别 {match.category}；颜色 {colour}；地点 {match.location}；"
                f"日期 {match.event_date}；匹配度 {score}%\n"
                f"   描述：{match.description}\n   匹配原因：{reasons}"
            )
        else:
            lines.append(
                f"{index}. #{match.item_id} [{match.report_type}] {match.item_name}; "
                f"category {match.category}; colour {colour}; location {match.location}; "
                f"date {match.event_date}; score {score}%\n"
                f"   Description: {match.description}\n   Reasons: {reasons}"
            )
    return "\n".join(lines)


def detail_message(detail: dict[str, Any], language: str) -> str:
    if language == "zh":
        return (
            f"记录 #{detail.get('id')}：{detail.get('itemName')}，类别 {detail.get('category')}，"
            f"地点 {detail.get('location')}，日期 {detail.get('eventDate')}，"
            f"状态 {detail.get('status')}。{detail.get('description', '')}"
        )
    return (
        f"Report #{detail.get('id')}: {detail.get('itemName')}; category {detail.get('category')}; "
        f"location {detail.get('location')}; date {detail.get('eventDate')}; "
        f"status {detail.get('status')}. {detail.get('description', '')}"
    )


def tool_event(action: str, status: str) -> AgentEvent:
    return AgentEvent("tool_execution", {"action": action, "status": status})


def response_with_token(
    message: str,
    status: Any,
    request_id: str,
    emit: Emit,
    **kwargs: Any,
) -> InvokeResponse:
    emit(AgentEvent("token", {"text": message}))
    return InvokeResponse(response=message, status=status, request_id=request_id, **kwargs)


def backend_error_response(
    error: BackendApiError,
    request_id: str,
    language: str,
    emit: Emit,
) -> InvokeResponse:
    chinese_messages = {
        "CLAIM_ALREADY_EXISTS": "你已经提交过该物品的待处理或已批准认领申请，不能重复认领。",
        "CANNOT_CLAIM_OWN_REPORT": "不能认领自己发布的拾获记录。",
        "ONLY_FOUND_REPORTS_CAN_BE_CLAIMED": "只有拾获记录可以提交认领。",
        "REPORT_NOT_OPEN": "该记录当前不是开放状态，不能继续认领。",
        "LOST_FOUND_REPORT_NOT_FOUND": "没有找到指定的失物招领记录。",
        "CLAIM_NOT_FOUND": "没有找到指定的认领申请。",
    }
    message = (
        chinese_messages.get(error.code, str(error))
        if language == "zh"
        else f"Campus API error ({error.code}): {error}"
    )
    emit(AgentEvent("agent_error", {"code": error.code, "message": message}))
    return response_with_token(message, "failed", request_id, emit)
