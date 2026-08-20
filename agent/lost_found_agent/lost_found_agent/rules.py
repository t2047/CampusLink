"""失物招领 Agent 的规则引擎核心：意图识别、字段抽取补全、写操作确认与工具编排。

本模块是"纯规则"对话与检索的主干（rules 模式 / LLM 不可用时的降级路径），不调用
大模型，一切依赖正则与确定性逻辑。职责划分：

1. 意图识别
   detect_language / detect_explicit_intent / detect_intent 把用户消息映射为五种意图
   （report_lost 报失 / report_found 拾获登记 / search_found_items 找物 /
   get_item_detail 查看详情 / claim_item 认领），并刻意处理"找到/捡到"等歧义词：
   只有同时表达"创建/登记/发布"时才判为拾获登记，其余模糊情况返回 None 交给 LLM。

2. 字段抽取与补全
   extract_fields / extract_colour / map_category / parse_date 从消息正则抽取物品名、
   类别、颜色、地点、日期、认领证明、关键词等结构化字段；相对日期词（今天/昨天/
   明天）基于编排层注入的权威 today 解析，避免服务器本地时区差一天。

3. 写操作确认
   RuleEngine._prepare_report/_prepare_found/_prepare_claim 对三个写操作先生成
   ConfirmationRequired 让用户确认，确认后 _handle_confirmation 再真正落库，防止
   误报/恶意刷单；确认单由 ConfirmationStore 托管（与用户绑定、短期有效、一次性）。

4. 候选检索与打分
   search_candidates / RuleEngine._search* 通过 CampusApiClient 拉取候选，再交给
   matching.rank_candidates 打分；与 Browse 以图搜物（/agent/search）共用同一链路，
   保证两端打分逐字节一致。

协作关系：
- 被 main.py import（RuleEngine、detect_language、map_category、search_candidates）：
  main.invoke 在 LLM 不可用或 rules 模式下调用 RuleEngine.handle 兜底；/agent/classify
  用 map_category，/agent/search 用 search_candidates。
- 被 llm.py、nlu_eval.py import（ALLOWED_CONTEXT_FIELDS、ALLOWED_INTENTS、safe_context、
  detect_explicit_intent、extract_fields），保证规则与 LLM 的字段口径一致。
- 依赖 confirmation.py（ConfirmationStore/ConfirmationError）、events.py（AgentEvent）、
  matching.py（颜色 canonical 表与 rank_candidates）、tools.py（CampusApiClient 及
  各输入模型）、pretrained.py（PretrainedEmbeddingClient，Embedding 增强查询）。
"""

# 标准库：
# - logging：模块级日志器，记录校验降级、清除不可信字段等告警；
# - re：意图识别、字段抽取、日期解析的正则引擎；
# - Callable：定义事件回调类型 Emit；
# - UTC/date/datetime/timedelta：解析相对日期词、把确认单过期时间转 ISO8601；
# - Any/Literal：类型标注（Literal 使 Intent 取值可被类型检查器穷举）。
import logging
import re
from collections.abc import Callable
from datetime import UTC, date, datetime, timedelta
from typing import Any, Literal

# pydantic：BaseModel 用于 drop_invalid_fields 对报告模型做试校验；
# ValidationError 在 RuleEngine.handle 中作为"字段校验失败 → 降级追问"的兜底异常。
from pydantic import BaseModel, ValidationError

# 本包内部模块：
# - confirmation：写操作确认单的存储与校验。ConfirmationStore 托管待确认载荷，
#   ConfirmationError 表示确认单无效/已用/过期/非本人；
# - events：SSE 事件模型（AgentEvent），用于向面板流式推送 token、工具执行、确认等进度；
# - matching：颜色 canonical 表与候选打分。COLOUR_GROUPS / COLOUR_FORM_ASCII_PATTERN /
#   contains_colour_form 供 extract_colour 抽取颜色；rank_candidates 对候选打分并排序。
from .confirmation import ConfirmationError, ConfirmationStore
from .events import AgentEvent
from .matching import (
    COLOUR_FORM_ASCII_PATTERN,
    COLOUR_GROUPS,
    contains_colour_form,
    rank_candidates,
)
# - models：对外契约模型。InvokeRequest（入口请求）/ InvokeResponse（统一响应，含
#   status、match_results、confirmation_required、shared_context、actions_taken）、
#   VerifiedRequest（鉴权后携带的用户身份/角色）、ConfirmationRequired（写操作确认单）、
#   ActionTaken（已执行工具的动作记录）；
# - pretrained：独立 Embedding 服务客户端，enrich_query 给搜索查询注入语义/跨模态向量；
# - tools：真实后端工具。CampusApiClient 调用 Spring Boot 内部 API，
#   BackendApiError 为脱敏后的后端错误；ReportLostInput / ReportFoundInput /
#   ClaimItemInput / GetItemDetailInput / SearchFoundItemsInput / SearchLostItemsInput
#   为各工具的入参模型。
from .models import (
    ActionTaken,
    ConfirmationRequired,
    InvokeRequest,
    InvokeResponse,
    VerifiedRequest,
)
from .pretrained import PretrainedEmbeddingClient
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

# 模块级日志器：LLM 值校验失败降级、drop_invalid_fields 清除不可信字段等场景都走它告警。
logger = logging.getLogger(__name__)

# 规则引擎支持的意图全集（五种）：报失、拾获登记、搜索拾获物品、查看详情、认领。
# Literal 让类型检查器能穷举这几种取值，避免拼写错误。
Intent = Literal[
    "report_lost",
    "report_found",
    "search_found_items",
    "get_item_detail",
    "claim_item",
]
# 事件回调类型：把 AgentEvent（如 token 文本、工具执行进度、需要确认）推给上层
# （main.py 中实际是写入 EventStore，供 /agent/stream 以 SSE 推给面板）。
Emit = Callable[[AgentEvent], None]

# 关键词 → 后端物品类别枚举（ItemCategory）的映射表，供 map_category 使用：
# 中英文关键词都映射到同一个 canonical 类别（ELECTRONICS/ID_CARD/...）。
# 命中按"字典插入顺序"遍历，先命中先得，所以长词/组合词（如 "id card"、"学生卡"）
# 需排在同前缀短词之前；车辆等无专属类别的词显式映射到 OTHER，避免分类为 None。
CATEGORIES: dict[str, str] = {
    # 电子/数码产品 → ELECTRONICS
    "electronics": "ELECTRONICS",
    "electronic": "ELECTRONICS",
    "headphone": "ELECTRONICS",
    "earbud": "ELECTRONICS",
    "电子": "ELECTRONICS",
    "耳机": "ELECTRONICS",
    "手机": "ELECTRONICS",
    "电脑": "ELECTRONICS",
    "遥控": "ELECTRONICS",
    # 证件类（身份证/学生卡）→ ID_CARD
    "id card": "ID_CARD",
    "student card": "ID_CARD",
    "证件": "ID_CARD",
    "学生卡": "ID_CARD",
    # 钱包/钱夹 → WALLET_PURSE
    "wallet": "WALLET_PURSE",
    "purse": "WALLET_PURSE",
    "钱包": "WALLET_PURSE",
    # 钥匙 → KEYS
    "key": "KEYS",
    "钥匙": "KEYS",
    # 包/背包 → BAG
    "bag": "BAG",
    "backpack": "BAG",
    "包": "BAG",
    # 衣物 → CLOTHING
    "clothing": "CLOTHING",
    "clothes": "CLOTHING",
    "衣服": "CLOTHING",
    # 书籍/文具 → BOOKS_STATIONERY
    "book": "BOOKS_STATIONERY",
    "stationery": "BOOKS_STATIONERY",
    "书": "BOOKS_STATIONERY",
    "文具": "BOOKS_STATIONERY",
    # 雨伞 → UMBRELLA
    "umbrella": "UMBRELLA",
    "雨伞": "UMBRELLA",
    "伞": "UMBRELLA",
    # 车辆无专属类别，落入 OTHER；遥控 需在 汽车 之前命中，
    # 使 遥控汽车 归为 ELECTRONICS 而非 OTHER。
    "汽车": "OTHER",
    "车辆": "OTHER",
    "other": "OTHER",
    "其他": "OTHER",
}

# 多轮上下文（shared_data）允许保留的字段白名单。
# safe_context 只放行这些 key；handle 把 LLM 的 interpreted_fields 并进 context 时也只
# 接受这些 key，防止前端注入的脏字段、LLM 幻觉字段等任意数据污染上下文。
ALLOWED_CONTEXT_FIELDS = {
    # —— 意图与报告/搜索共用的物品字段 ——
    "intent",
    "item_name",
    "category",
    "description",
    "colour",
    "location",
    "event_date",
    "time_description",
    # —— 搜索专用字段 ——
    "keyword",
    "date_from",
    "date_to",
    # —— 详情 / 认领用字段 ——
    "report_id",
    # —— 编排层注入的系统事实与最近对话历史 ——
    "system_facts",
    "recent_messages",
    # —— 认领证明（写操作确认） ——
    "proof_description",
    # —— 面板图片与视觉检索字段（单图/多图指纹 + Embedding + 暂存 objectKey） ——
    "visual_fingerprint",
    "visual_fingerprints",
    "visual_embeddings",
    "images",
}

# 搜索关键词里的无语义停顿词：仅发图占位语（"帮我找这个"）会抽取出指示代词/量词
# "这个"。它没有检索信息，若作为查询端 text 分量，会以近零相似度把纯视觉匹配的
# 加权平均分拉低到最低阈值（默认 0.35）以下，导致"完全一样的图片也匹配不到"。
# 因此这些词被抽出来时不进入 context（见 extract_fields 与 handle 的过滤逻辑）。
KEYWORD_STOPWORDS = {
    # 中文指示代词 / 量词（含占位语常见组合）
    "这个",
    "那个",
    "这些",
    "那些",
    "这样",
    "那样",
    "这个物品",
    "那个物品",
    "这个东西",
    "那个东西",
    "一下",
    "一点",
    "一个",
    "一种",
    "一只",
    "一把",
    "一本",
    "一张",
    # 英文指示代词（"find this"/"search for that"）
    "this",
    "that",
    "these",
    "those",
    "it",
}


def is_stopword_keyword(value: Any) -> bool:
    """value 是否为无语义停顿词（空值/非字符串返回 False）。

    供两处调用：extract_fields 决定是否把抽出的 keyword 写入 context；
    handle 过滤 LLM 解释的 interpreted_fields 中 keyword 字段时同样调用，
    保证规则与 LLM 两条路径对占位词的处置一致。
    """
    # 统一 strip + lower 后查表，避免 " 这个 " / "This" 等变体漏判。
    return isinstance(value, str) and value.strip().lower() in KEYWORD_STOPWORDS


class RuleEngine:
    """失物招领对话的规则引擎（无 LLM 的主干）。

    生命周期：在 main.create_app 中随应用一起创建、随请求多次调用，常驻进程内。
    依赖注入的四项能力：
    - _api（CampusApiClient）：真正的后端工具，用于建报告/搜索/详情/认领；
    - _confirmations（ConfirmationStore）：写操作确认单的存储与一次性消费；
    - _minimum_score（float）：rank_candidates 的最低匹配阈值，低于则视为无候选；
    - _embedding_client（PretrainedEmbeddingClient | None）：可选，给搜索查询注入
      语义/跨模态向量（失败时原样返回，不影响可用性）。

    对外唯一入口是 handle()：它先合并规则抽取 + LLM 解释（interpreted_*）+ 多轮上下文，
    再按意图分派到 _prepare_report / _prepare_found / _prepare_claim / _detail / _search；
    三个写操作统一走"先生成确认单、确认后再执行"的流程，读操作（搜索/详情）直连后端。
    """

    def __init__(
        self,
        api_client: CampusApiClient,  # 后端内部 API 客户端（必填）
        confirmations: ConfirmationStore,  # 写操作确认单存储（必填）
        minimum_score: float,  # 候选最低匹配分数阈值，来自配置 lost_found_match_min_score
        embedding_client: PretrainedEmbeddingClient | None = None,  # 可选：Embedding 增强查询
    ) -> None:
        # 全部以私有属性保存，供后续方法复用；构造函数不做任何网络/IO 副作用。
        self._api = api_client
        self._confirmations = confirmations
        self._minimum_score = minimum_score
        self._embedding_client = embedding_client

    async def handle(
        self,
        payload: InvokeRequest,
        verified: VerifiedRequest,
        request_id: str,
        emit: Emit,
        interpreted_intent: Intent | None = None,
        interpreted_fields: dict[str, Any] | None = None,
    ) -> InvokeResponse:
        """处理一轮对话：识别意图 → 抽取/合并字段 → 按意图分派。

        入参：
        - payload：本轮请求（消息、多轮上下文、确认标志/确认单 id、面板图片）；
        - verified：鉴权后的用户身份（user_id/user_role 等，写操作与后端调用都要用它）；
        - request_id：本次请求唯一 id，贯穿日志与事件流；
        - emit：事件回调（SSE 推送 token/工具进度/确认单等）；
        - interpreted_intent / interpreted_fields：可选，LLM 解释器的意图与字段结果，
          rules 模式或 LLM 不可用时为 None，规则引擎自行兜底。

        返回：InvokeResponse（含状态、匹配结果、确认单、actions_taken 等）。
        可能抛出：ValidationError——已在方法内捕获并降级为 needs_more_info 追问，
        不会继续冒泡到 main.invoke 变成"内部错误"。
        """
        # 按消息是否含中文字符（匹配 [\\u4e00-\\u9fff]，见 detect_language）
        # 决定后续所有回复文案的语言。
        language = detect_language(payload.message)
        if payload.confirmed or payload.confirmation_id:
            # 用户已确认（或本轮携带确认单 id）：直接进入确认执行分支。
            # 此时不再重新识别意图/抽取字段，保证确认一次性、确定性地执行。
            return await self._handle_confirmation(payload, verified, request_id, language, emit)

        # 从多轮 shared_data 按白名单过滤出安全上下文，避免前端/LLM 的脏字段污染。
        context = safe_context(payload.conversation_context.shared_data)
        try:
            # —— 意图决策：优先级 = 本轮显式意图 > 上一轮意图（多轮追问中沿用）
            #   > LLM 解释 > 默认"搜索拾获物品"。 ——
            previous_intent = context.get("intent")
            intent = (
                detect_explicit_intent(payload.message)
                or (previous_intent if previous_intent in ALLOWED_INTENTS else None)
                or interpreted_intent
                or "search_found_items"
            )
            context["intent"] = intent  # 写回 context，供后续轮次继续沿用该意图
            # 相对日期词（今天/昨天/明天/后天等）基于编排层注入的权威 today
            # （system_facts.today, Asia/Singapore）解析，而非服务器本地时区
            system_facts = context.get("system_facts") or {}
            trusted_today = system_facts.get("today") if isinstance(system_facts, dict) else None
            # 规则正则抽取结构化字段（物品名/类别/颜色/地点/日期等）。
            context.update(extract_fields(payload.message, intent, trusted_today))
            if interpreted_fields:
                # 合并 LLM 抽取的字段：只接受白名单内的 key；intent 以规则层为准；
                # 搜索关键词若是占位停顿词则丢弃，避免以近零相似度拖低视觉匹配分。
                context.update(
                    {
                        key: value
                        for key, value in interpreted_fields.items()
                        if key in ALLOWED_CONTEXT_FIELDS
                        and key != "intent"
                        and not (key == "keyword" and is_stopword_keyword(value))
                    }
                )
            # 多轮共享的面板图片：本轮携带则覆盖，否则沿用上一轮 context（shared_data）
            if payload.images:
                # objectKey：确认创建时经内部 API 关联为报告图片。
                context["images"] = [
                    image.object_key for image in payload.images if image.object_key
                ]
                # 视觉指纹：多图存列表（visual_fingerprints），并单列首图（visual_fingerprint）
                # 兼容单图写法，供 matching 的视觉分量打分。
                fingerprints = [
                    image.visual_fingerprint for image in payload.images if image.visual_fingerprint
                ]
                if fingerprints:
                    context["visual_fingerprints"] = fingerprints
                    context["visual_fingerprint"] = fingerprints[0]
                # 预训练 Embedding（base64 向量）列表：用于 pretrained_multimodal 匹配模式。
                pretrained = [
                    image.visual_embedding for image in payload.images if image.visual_embedding
                ]
                if pretrained:
                    context["visual_embeddings"] = pretrained

            # —— 按意图分派：三个写操作先生成确认单（同步返回 needs_confirmation），
            #    两个读操作异步拉取后端结果。 ——
            if intent == "report_lost":
                return self._prepare_report(context, verified, request_id, language, emit)
            if intent == "report_found":
                return self._prepare_found(context, verified, request_id, language, emit)
            if intent == "claim_item":
                return self._prepare_claim(context, verified, request_id, language, emit)
            if intent == "get_item_detail":
                return await self._detail(context, verified, request_id, language, emit)
            return await self._search(context, verified, request_id, language, emit)
        except ValidationError as exc:
            # 兜底：任何路径的字段校验失败（如 LLM 幻觉的未来日期）都降级为询问，
            # 不再冒泡成"内部错误"（2026-08-11 修复；report 路径另有 drop_invalid_fields
            # 的精准降级，这里的统一捕获作为最后防线）。
            logger.warning(
                "L&F validation degraded to needs_more_info: request_id=%s err=%.200s",
                request_id,
                exc,
            )
            # 固定文案提示用户补全/修正信息；不携带 actions_taken 与 match_results。
            message = (
                "请补充完整、正确的信息：日期需为 YYYY-MM-DD 且不能是未来日期，"
                "描述不少于 10 个字符。"
                if language == "zh"
                else "Please provide complete, valid information: date must be YYYY-MM-DD "
                "and not in the future; description at least 10 characters."
            )
            return response_with_token(
                message,
                "needs_more_info",
                request_id,
                emit,
                shared_context=context,
            )

    def _prepare_report(
        self,
        context: dict[str, Any],
        verified: VerifiedRequest,
        request_id: str,
        language: str,
        emit: Emit,
    ) -> InvokeResponse:
        """报失（report_lost）准备阶段：校验必填字段，生成确认单让用户二次确认。

        写操作不直接落库：先把 ReportLostInput 载荷存入 ConfirmationStore 并返回
        ConfirmationRequired，用户确认后由 _handle_confirmation 真正创建报告。

        返回：字段缺失 → needs_more_info（追问缺哪些字段）；字段齐全 → needs_confirmation。
        抛异常：无（drop_invalid_fields 已先清除不可信字段，防止 ValidationError 外泄）。
        """
        # 报失报告必填字段：物品名、类别、描述、地点、事件日期。
        required = ["item_name", "category", "description", "location", "event_date"]
        # 值不可信（如 LLM 幻觉的未来日期）先清除，再统一走缺失字段追问，
        # 避免 ValidationError 冒泡成"内部错误"（2026-08-11 修复）
        drop_invalid_fields(ReportLostInput, context)
        # 找出缺失字段；缺任一必填字段则追问，并把 shared_context 回传前端以延续
        # 多轮对话（已填字段在下一轮仍可用，只补缺的部分）。
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

        # 字段齐全：校验通过后构造 ReportLostInput，并把 JSON 形态载荷暂存到确认单
        # 存储（payload 存 JSON 便于确认阶段原样重建模型）。
        report = ReportLostInput.model_validate(context)
        confirmation_id, pending = self._confirmations.create(
            verified.user_id, "report_lost", report.model_dump(mode="json")
        )
        summary = report_summary(report, language)
        # 组装确认单：id + 动作 + 摘要 + 过期时间（epoch 秒 → ISO8601 字符串）。
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
        # 推送 confirmation_required 事件（前端据此弹确认按钮），并在响应里携带确认单。
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
        """登记捡到物品（report_found）：先确认（写操作），确认后创建 FOUND 报告。

        与 _prepare_report 逻辑镜像对称（字段/必填项/确认单流程一致），
        只是动作类型为 report_found、载荷模型为 ReportFoundInput。
        返回：字段缺失 → needs_more_info；齐全 → needs_confirmation。
        """
        # 拾获报告必填字段：与报失一致（物品名、类别、描述、地点、事件日期）。
        required = ["item_name", "category", "description", "location", "event_date"]
        # 值不可信（如 LLM 幻觉的未来日期）先清除，再统一走缺失字段追问，
        # 避免 ValidationError 冒泡成"内部错误"（2026-08-11 修复）
        drop_invalid_fields(ReportFoundInput, context)
        # 缺失字段 → 追问，并把上下文回传以延续多轮。
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

        # 字段齐全：构造 ReportFoundInput 并存确认单（确认后由 _handle_confirmation 落库）。
        report = ReportFoundInput.model_validate(context)
        confirmation_id, pending = self._confirmations.create(
            verified.user_id, "report_found", report.model_dump(mode="json")
        )
        summary = report_summary(report, language)
        # 组装确认单（过期时间 epoch 秒 → ISO8601）。
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
        # 推送确认单事件并在响应里携带 confirmation_required。
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
        """认领（claim_item）准备阶段：校验报告 ID 与认领证明，生成确认单。

        与前两个写操作不同，认领不需要物品描述等报告字段，只需：
        - report_id：要认领的拾获记录 ID；
        - proof_description：认领证明，至少 10 个字符（证明你对物品的所有权）。

        返回：缺任一 → needs_more_info；齐全 → needs_confirmation。
        """
        missing: list[str] = []
        # report_id 缺失 → 追问。
        if not context.get("report_id"):
            missing.append("report_id")
        # 认领证明少于 10 个字符 → 追问（后端 ClaimItemInput 同样要求 min_length=10）。
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

        # 构造认领载荷并暂存确认单；确认后由 _handle_confirmation 提交给后端。
        claim = ClaimItemInput.model_validate(context)
        confirmation_id, pending = self._confirmations.create(
            verified.user_id, "claim_item", claim.model_dump(mode="json")
        )
        # 认领摘要：记录号 + 认领证明原文。
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
        """处理用户的写操作确认：消费确认单 → 调后端执行对应写操作 → 返回结果。

        三种写操作在此汇合：claim_item 直接提交认领；report_found / report_lost 创建
        报告后用已创建记录作为 query 反查另一侧候选（拾获创建后反查报失，报失创建后
        反查拾获），把"创建 + 智能匹配"一次返回给用户。

        返回：成功 → completed 或 match_found；确认单无效/过期/非本人 → failed。
        抛异常：BackendApiError（已转成 backend_error_response 的用户友好文案）。
        """
        # 参数完整性校验：confirmed 与 confirmation_id 必须同时为真，否则直接失败。
        if not payload.confirmed or not payload.confirmation_id:
            message = (
                "确认参数不完整" if language == "zh" else "Confirmation parameters are incomplete"
            )
            return response_with_token(message, "failed", request_id, emit)
        try:
            # 消费确认单（一次性）：取出待执行的载荷；重复使用/过期/非本人会抛错。
            pending = self._confirmations.consume(payload.confirmation_id, verified.user_id)
        except ConfirmationError as exc:
            # 确认单无效 → 明确失败，并向面板推送 agent_error 事件。
            message = (
                str(exc)
                if language == "zh"
                else "Confirmation is invalid, expired, or unauthorized"
            )
            emit(AgentEvent("agent_error", {"code": "INVALID_CONFIRMATION", "message": message}))
            return response_with_token(message, "failed", request_id, emit)

        try:
            # —— 分支一：确认认领 ——
            if pending.action == "claim_item":
                # 从确认单载荷重建认领入参。
                claim = ClaimItemInput.model_validate(pending.payload)
                # 推送工具执行"开始"事件，随后调用后端提交认领申请。
                emit(tool_event("claim_item", "started"))
                result = await self._api.claim_item(verified.user_id, verified.user_role, claim)
                emit(tool_event("claim_item", "completed"))
                message = (
                    "认领申请已提交，请等待拾获记录发布者处理。"
                    if language == "zh"
                    else "Your claim was submitted for review by the found-item reporter."
                )
                # 认领是提交申请，不自动创建报告，因此只登记 actions_taken，无匹配结果。
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

            # —— 分支二：确认拾获登记 ——
            if pending.action == "report_found":
                found_report = ReportFoundInput.model_validate(pending.payload)
                emit(tool_event("report_found", "started"))
                # 调后端创建 FOUND 报告（imageKeys 会经内部 API 关联面板图片）。
                created = await self._api.report_found(
                    verified.user_id, verified.user_role, found_report
                )
                emit(tool_event("report_found", "completed"))
                # 创建成功后，用报告内容反查开放报失记录（target_report_type="LOST"），
                # 给拾获者展示可能的失主候选。
                query = found_report.model_dump(mode="json")
                matches, search_action = await self._search_candidates(
                    query,
                    verified,
                    language,
                    emit,
                    target_report_type="LOST",
                )
                message = found_created_message(created, matches, language)
                # 有匹配 → match_found 并附 match_results；无匹配 → completed。
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

            # —— 分支三：确认报失登记（默认动作） ——
            report = ReportLostInput.model_validate(pending.payload)
            emit(tool_event("report_lost", "started"))
            # 调后端创建 LOST 报告。
            created = await self._api.report_lost(verified.user_id, verified.user_role, report)
            emit(tool_event("report_lost", "completed"))
            # 用报告内容反查开放拾获记录（默认 target_report_type="FOUND"），
            # 给失主展示可能的匹配物品。
            query = report.model_dump(mode="json")
            matches, search_action = await self._search_candidates(query, verified, language, emit)
            message = report_created_message(created, matches, language)
            # 有匹配 → match_found 并附 match_results；无匹配 → completed。
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
            # 后端调用失败（超时/不可用/业务拒绝）→ 统一转成用户友好的失败文案。
            return backend_error_response(exc, request_id, language, emit)

    async def _search(
        self,
        context: dict[str, Any],
        verified: VerifiedRequest,
        request_id: str,
        language: str,
        emit: Emit,
    ) -> InvokeResponse:
        """搜索拾获物品（search_found_items）：先校验是否有检索条件，再检索打分。

        搜索是纯读操作，不走确认流程。检索条件可以是文本字段（关键词/名称/描述/
        类别/颜色/地点/日期），也可以是纯视觉（仅发图：指纹或 Embedding）。
        返回：有候选 → match_found；无候选 → no_match；缺条件 → needs_more_info。
        """
        # 检查 context 中是否存在任一检索条件；全部为空则追问用户提供。
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
                # 仅发图（无文字或"帮我找这个"）也可按图检索
                "visual_fingerprint",
                "visual_fingerprints",
                "visual_embeddings",
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
            # 走统一的候选检索 + 打分链路（与 Browse 以图搜物一致）。
            matches, action = await self._search_candidates(context, verified, language, emit)
        except BackendApiError as exc:
            return backend_error_response(exc, request_id, language, emit)
        # 有命中 → 生成候选摘要并标记 match_found；无命中 → no_match。
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
        """RuleEngine 内对候选检索的薄封装：把自身依赖注入到模块级 search_candidates。

        target_report_type：默认 FOUND（找拾获记录）；报失创建后反查时为 LOST。
        返回：(matches, action)——matches 为已打分排序的候选列表，action 为本次
        搜索的动作记录（供 actions_taken 上报）。
        """
        return await search_candidates(
            self._api,
            query,
            verified,
            self._minimum_score,  # 最低匹配阈值来自配置
            language,
            emit,
            target_report_type,
            self._embedding_client,
        )

    async def _detail(
        self,
        context: dict[str, Any],
        verified: VerifiedRequest,
        request_id: str,
        language: str,
        emit: Emit,
    ) -> InvokeResponse:
        """查看物品详情（get_item_detail）：按 report_id 拉取单条记录。

        纯读操作，不走确认流程。report_id 取自 context（由 extract_fields 从
        "#123" / "ID:123" / "记录 123" 等格式解析，也可由 LLM 抽取）。
        返回：成功 → completed；缺 report_id → needs_more_info；后端失败 → failed。
        """
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
            # 推送工具执行事件，随后调后端查询详情。
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
        # 详情只有文本摘要返回，不携带 match_results。
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


async def search_candidates(
    api_client: CampusApiClient,
    query: dict[str, Any],
    verified: VerifiedRequest,
    minimum_score: float,
    language: str,
    emit: Emit,
    target_report_type: Literal["LOST", "FOUND"] = "FOUND",
    embedding_client: PretrainedEmbeddingClient | None = None,
) -> tuple[list[Any], ActionTaken]:
    """候选检索 + 打分。Browse 以图搜物与 chat 双向匹配共用同一套链路，
    保证两端打分逐字节一致（原 RuleEngine._search_candidates 抽取）。

    流程：Embedding 增强查询 → 解析日期并做 ±30 天窗口兜底 → 调后端拉候选 →
    rank_candidates 打分排序 → 组装 ActionTaken 动作记录。
    仅当查询含 event_date 时才做 ±30 天窗口兜底；显式 date_from/date_to 原样透传。
    入参：api_client（后端客户端）、query（结构化查询字段）、verified（用户身份）、
    minimum_score（最低匹配阈值）、language（理由文案语言）、emit（事件回调）、
    target_report_type（候选方向：LOST 报失 / FOUND 拾获）、embedding_client（可选）。
    返回：(matches, action)——matches 为匹配候选列表（最高分在前，最多 5 个），
    action 为本次搜索的 ActionTaken 动作记录。
    抛异常：BackendApiError（后端拉候选失败，由调用方转成用户友好文案）。
    """
    # 若配置了 Embedding 服务，先给查询注入语义/跨模态向量（失败时原样返回，
    # 不会中断检索），供 matching 的 pretrained 各分量打分。
    if embedding_client is not None:
        query = await embedding_client.enrich_query(query)
    # 解析三个日期字段（非法/空值返回 None）。
    event_date = parse_date(query.get("event_date"))
    date_from = parse_date(query.get("date_from"))
    date_to = parse_date(query.get("date_to"))
    # 只有 event_date 时扩展为 ±30 天窗口（丢失/拾获时间模糊，给宽一点范围）；
    # 显式 date_from/date_to 则优先保留用户/调用方的精确区间。
    if event_date:
        date_from = date_from or event_date - timedelta(days=30)
        date_to = date_to or event_date + timedelta(days=30)
    # 后端候选接口的过滤参数：类别 + 日期区间 + 分页。关键词/描述/颜色不参与
    # 后端硬过滤（避免漏召回），统一交给 rank_candidates 打分。
    search_values = {
        "category": query.get("category"),
        "date_from": date_from,
        "date_to": date_to,
        "page": 0,
        "size": 100,
    }
    if target_report_type == "LOST":
        # 反查报失：搜索开放报失记录（如拾获创建后给拾获者找失主）。
        action_name = "search_lost_items"
        search = SearchLostItemsInput(**search_values)
        emit(tool_event(action_name, "started"))
        result = await api_client.search_lost_items(
            verified.user_id,
            verified.user_role,
            search,
        )
        emit(tool_event(action_name, "completed"))
    else:
        # 默认反查拾获：搜索开放拾获记录（失主找物品 / 报失创建后匹配）。
        action_name = "search_found_items"
        found_search = SearchFoundItemsInput(**search_values)
        emit(tool_event(action_name, "started"))
        result = await api_client.search_found_items(
            verified.user_id,
            verified.user_role,
            found_search,
        )
        emit(tool_event(action_name, "completed"))
    # 从分页响应取候选列表；非列表（空/异常形状）按无候选处理。
    content = result.get("content", [])
    candidates = content if isinstance(content, list) else []
    # rank_candidates 对每个候选算加权分数，过滤低于 minimum_score 的，按分降序取前 5。
    matches = rank_candidates(query, candidates, minimum_score, language)
    # 组装本次搜索的动作记录，供上层 actions_taken 上报给前端。
    action = ActionTaken(
        action=action_name,
        params_summary=f"{target_report_type} + OPEN, size=100",
        result_summary=f"candidates={len(candidates)}, matches={len(matches)}",
        status="success",
    )
    return matches, action


def detect_language(message: str) -> str:
    """检测消息语言：含任意中文字符（U+4E00~U+9FFF）判为 zh，否则 en。

    用于统一后续所有回复文案（追问、确认、匹配摘要）的语言。
    """
    return "zh" if re.search(r"[\u4e00-\u9fff]", message) else "en"


def detect_intent(message: str, previous: Any = None) -> Intent:
    """意图识别（带上一轮意图兜底）：显式意图优先，否则沿用上一轮，否则默认搜索。

    入参：message（用户消息）、previous（可选，上一轮 intent，多轮追问时用于延续）。
    返回：五种 Intent 之一；previous 不在 ALLOWED_INTENTS 内（如脏值）时回退默认。
    调用场景：nlu_eval 等需要"规则意图"的轻量路径；在线对话由 handle() 内联等价逻辑。
    """
    explicit = detect_explicit_intent(message)
    if explicit:
        return explicit
    return previous if previous in ALLOWED_INTENTS else "search_found_items"


def detect_explicit_intent(message: str) -> Intent | None:
    """识别用户本轮明确表达的意图，并避免将有歧义的“找到”直接判为搜索。

    入参：message（用户消息）。返回：命中的意图；无法明确判断时返回 None
    （此时上层会回退到上一轮意图 / LLM / 默认搜索）。调用场景：handle 的意图决策、
    nlu_eval 的规则对照、detect_intent。
    判定顺序注意：claim 与 detail 最优先（词义最明确），随后才是“找到/捡到”
    的消歧逻辑与报失/搜索分支。
    """
    # 统一小写，便于英文正则的 \b 词边界匹配（中文不受影响）。
    lowered = message.lower()
    # 认领：英文 claim/ownership 或中文"认领" → claim_item（词义明确，最先判断）。
    if re.search(r"\bclaim\b|\bownership\b", lowered) or "认领" in message:
        return "claim_item"
    # 查看详情：英文 detail/details/view 或中文"详情/查看记录" → get_item_detail。
    if re.search(r"\bdetail(?:s)?\b|\bview\b", lowered) or any(
        keyword in message for keyword in ("详情", "查看记录")
    ):
        return "get_item_detail"

    # “找到”既可能表示失主搜索，也可能表示拾获者发现物品。只有同时表达
    # 创建、登记或发布时，规则层才明确将其识别为拾获登记；其余模糊情况交给 LLM。
    # 先标记"显式搜索词"，命中时优先按搜索意图处理，避免把"帮我找被捡到的伞"
    # 误判为拾获登记。
    explicit_search = any(
        keyword in message for keyword in ("搜索", "帮我找", "查找", "匹配", "有没有人捡到")
    )
    # 拾获登记：无显式搜索词 + 表达发布类动作（创建/登记/发布/上报/记录）
    # + 表达"找到/捡到"类动词，三者同时成立才判定为 report_found。
    found_publication = (
        not explicit_search
        and any(keyword in message for keyword in ("创建", "登记", "发布", "上报", "记录"))
        and any(keyword in message for keyword in ("找到", "捡到", "捡了", "拾到"))
    )
    if found_publication:
        return "report_found"

    # 搜索拾获物品：英文 search/find/found item 或中文搜索词 → search_found_items。
    if re.search(r"\bsearch\b|\bfind\b|\bfound item", lowered) or any(
        keyword in message for keyword in ("搜索", "帮我找", "查找", "匹配", "有没有人捡到")
    ):
        return "search_found_items"
    # 报失：英文 i lost / report lost / lost my 或中文"我丢了/丢了/丢失/遗失/报失"。
    if re.search(r"\bi lost\b|\breport(?:ed)? lost\b|\blost my\b", lowered) or any(
        keyword in message for keyword in ("我丢了", "丢了", "丢失", "遗失", "报失")
    ):
        return "report_lost"
    # “捡到/拾到”含义明确；单独的“找到”交给 LLM 结合上下文判断。
    if re.search(r"\bpick(?:ed)? up\b|\bfound\b", lowered) or any(
        keyword in message for keyword in ("捡到", "捡了", "拾到")
    ):
        return "report_found"
    # 未命中任何明确意图 → 返回 None，交由上层兜底。
    return None


# 规则引擎允许的意图全集（与 Intent 类型一致）。
# 用于校验上一轮 context 里的 intent 是否可信：只有落在集合内的才被沿用，
# 防止脏值（如 LLM 幻觉的非法意图）在 handle 中触发未知分派分支。
ALLOWED_INTENTS = {
    "report_lost",
    "report_found",
    "search_found_items",
    "get_item_detail",
    "claim_item",
}


def safe_context(shared_data: dict[str, Any]) -> dict[str, Any]:
    """把多轮 shared_data 过滤成安全的上下文 dict（白名单 + 类型校验）。

    入参：shared_data（编排层随请求传来的共享数据，内容来自前端/上一轮回传）。
    返回：只含 ALLOWED_CONTEXT_FIELDS 内 key 的干净 dict。
    作用：防止前端注入的脏字段、异常类型（如 dict/list 混进标量位）污染后续
    抽取/校验逻辑，也是 handle 中 interpreted_fields 合并前的同一套白名单。
    """
    result: dict[str, Any] = {}
    for key, value in shared_data.items():
        # 白名单过滤：非允许字段一律丢弃。
        if key not in ALLOWED_CONTEXT_FIELDS:
            continue
        if key == "system_facts" and isinstance(value, dict):
            # 编排层注入的系统事实包：只放行字符串值（today/now/timezone/user_language），
            # 丢弃可能存在的嵌套结构。
            result[key] = {k: v for k, v in value.items() if isinstance(v, str)}
        elif key == "recent_messages" and isinstance(value, list):
            # 编排层注入的最近对话历史：只保留 role/content 均为字符串的条目，
            # 丢弃格式异常（缺字段/类型错）的历史，避免注入任意对象。
            cleaned = []
            for item in value:
                if (
                    isinstance(item, dict)
                    and isinstance(item.get("role"), str)
                    and isinstance(item.get("content"), str)
                ):
                    cleaned.append({"role": item["role"], "content": item["content"]})
            result[key] = cleaned
        elif key in {"images", "visual_fingerprints", "visual_embeddings"} and isinstance(
            value, list
        ):
            # 面板暂存图片的 objectKey 与视觉指纹：只放行字符串列表。
            result[key] = [item for item in value if isinstance(item, str)]
        elif isinstance(value, (str, int, float, bool)):
            # 其余标量字段（intent/item_name/category/...）只接受基础类型。
            result[key] = value
    return result


def extract_colour(message: str) -> str | None:
    """从消息抽取颜色，命中 canonical 表后返回该组的展示形式。

    英文表面形式（如 ivory）命中时返回组内英文展示（White），中文表面形式
    （如 乳白）命中时返回中文展示（白色），尽量保持用户输入语言；"black" 不会
    因词边界规则误命中 "backpack"。
    入参：message（整条用户消息）。返回：颜色展示字符串（如 "白色"/"White"）
    或 None（未命中任何颜色）。调用场景：extract_fields 里补充 colour 字段。
    """
    # 统一小写：canonical 表里的表面形式都是小写，contains_colour_form 也要求已 lower。
    lowered = message.lower()
    # 按 COLOUR_GROUPS 顺序遍历每个颜色组，再遍历组内每个表面形式（white/白色/象牙白...）。
    for group in COLOUR_GROUPS:
        for form in group.forms:
            if contains_colour_form(lowered, form):
                # 命中后按"命中形式是否纯 ASCII"选择展示语言：命中英文形式返回
                # 组内英文展示，命中中文形式返回中文展示，尽量贴合用户输入语言。
                return group.en if COLOUR_FORM_ASCII_PATTERN.search(form) else group.zh
    # 未命中任何颜色 → None。
    return None


def extract_fields(message: str, intent: Intent, today: str | None = None) -> dict[str, Any]:
    """从消息提取结构化字段（规则正则抽取，不依赖 LLM）。

    入参：
    - message：用户消息原文；
    - intent：当前意图（部分抽取只针对特定意图做，如报失时补物品名/地点）；
    - today：相对日期词（今天/昨天/前天/明天/后天）的基准日期，优先传编排层注入的
      权威日期 system_facts.today（Asia/Singapore），避免服务器本地时区与权威日期
      差一天（2026-08-15 修复）；缺省回退 date.today() 保持兼容（如 nlu_eval）。

    返回：抽到的结构化字段 dict（如 item_name/category/colour/event_date/...），
    空值与 None 在末尾统一剔除。调用场景：handle 内联、nlu_eval 的字段预测。
    """
    fields: dict[str, Any] = {}
    # 带显式标签（形如"物品名称：xxx"/"description=xxx"）的字段：每个字段配一组
    # 中英正则，按顺序尝试，命中即停。捕获组取标签冒号后的非分隔符内容，
    # 分隔符（逗号/分号/换行）用于防止把整段话吞进去。
    labelled = {
        "item_name": [
            r"(?:物品(?:名称)?|名称)[:：]\s*([^,，;；\n]+)",
            r"item(?:\s+name)?\s*[:=]\s*([^,;\n]+)",
        ],
        "description": [r"(?:描述|特征)[:：]\s*([^;；\n]+)", r"description\s*[:=]\s*([^;\n]+)"],
        "location": [r"(?:地点|位置)[:：]\s*([^,，;；\n]+)", r"location\s*[:=]\s*([^,;\n]+)"],
        "colour": [r"(?:颜色)[:：]\s*([^,，;；\n]+)", r"colou?r\s*[:=]\s*([^,;\n]+)"],
        "time_description": [r"(?:时间描述)[:：]\s*([^,，;；\n]+)", r"time\s*[:=]\s*([^,;\n]+)"],
        # 认领证明不设分隔符截断（(.+) 取到行尾），因为证明往往较长且含标点。
        "proof_description": [
            r"(?:认领证明|证明)[:：]\s*(.+)",
            r"(?:proof|because)\s*[:=]?\s*(.+)",
        ],
        "keyword": [r"(?:关键词)[:：]\s*([^,，;；\n]+)", r"keyword\s*[:=]\s*([^,;\n]+)"],
    }
    # 逐个标签、逐个正则尝试；第一个命中就写入 fields 并跳出该字段的循环。
    for field, patterns in labelled.items():
        for pattern in patterns:
            match = re.search(pattern, message, re.IGNORECASE)
            if match:
                fields[field] = match.group(1).strip()
                break

    # —— 类别抽取 ——
    # 优先找显式标签："类别：xxx"（中文）或 "category=xxx"（英文），
    # 捕获词/中文/下划线，命中后交给 map_category 映射为 canonical 类别。
    category_match = re.search(r"(?:类别)[:：]\s*([\w_\u4e00-\u9fff ]+)", message, re.IGNORECASE)
    # —— 类别抽取（续） ——
    # 上方的 category_match 已匹配"类别：xxx"（中文），这里再匹配英文 "category=xxx"。
    # 命中后统一交给 map_category 把自然语言映射成 canonical 类别（如"手机"→ELECTRONICS）。
    english_category = re.search(r"category\s*[:=]\s*([\w_ ]+)", message, re.IGNORECASE)
    category_value = category_match or english_category
    if category_value:
        # 显式标签命中：直接映射捕获值。
        fields["category"] = map_category(category_value.group(1))
    else:
        # 无显式标签：对整条消息做关键词映射（如 "我丢了钱包" → WALLET_PURSE）。
        mapped = map_category(message)
        if mapped:
            fields["category"] = mapped

    # —— 颜色抽取 ——
    # 若上面显式标签已给出 colour 则不重复；否则用 canonical 颜色表从整条消息抽取
    # （extract_colour 会把 "黑色"/"black" 等同义词归并到同一展示形式）。
    if "colour" not in fields:
        colour = extract_colour(message)
        if colour:
            fields["colour"] = colour

    # —— 日期抽取 ——
    # 优先找 ISO 日期（YYYY-MM-DD；负向断言 (?<!\d)(?!\d) 避免命中更长数字串里的子串）。
    iso_dates = re.findall(r"(?<!\d)\d{4}-\d{2}-\d{2}(?!\d)", message)
    if iso_dates:
        fields["event_date"] = iso_dates[0]
        # 出现两个日期时，按"起始~结束"区间处理（date_from/date_to，供搜索范围使用）。
        if len(iso_dates) > 1:
            fields["date_from"], fields["date_to"] = iso_dates[:2]
    else:
        # 相对日期词 → 基于权威 today 计算（注意 "day before yesterday" 含
        # "yesterday"、"day after tomorrow" 含 "tomorrow"，长词必须先判）
        base_date = date.fromisoformat(today) if today else date.today()
        # 长词优先：前天 / day before yesterday（否则子串会先命中"昨天/yesterday"）。
        if "前天" in message or "day before yesterday" in message.lower():
            fields["event_date"] = (base_date - timedelta(days=2)).isoformat()
        elif "昨天" in message or "yesterday" in message.lower():
            fields["event_date"] = (base_date - timedelta(days=1)).isoformat()
        elif "今天" in message or "today" in message.lower():
            fields["event_date"] = base_date.isoformat()
        elif "明天" in message or "tomorrow" in message.lower():
            # 未来日期：report 会被未来日期校验器拒绝（转追问），
            # 搜索场景可作为查询条件使用
            fields["event_date"] = (base_date + timedelta(days=1)).isoformat()
        elif "后天" in message or "day after tomorrow" in message.lower():
            fields["event_date"] = (base_date + timedelta(days=2)).isoformat()

    # —— 记录 ID 抽取 ——
    # 支持 "#123" / "ID:123" / "ID=123" / "记录 123" / "item 123" / "report #123" 等写法，
    # 捕获数字部分转 int（供查看详情与认领使用）。
    report_id = re.search(
        r"(?:#|ID\s*[:=]?\s*|记录\s*|(?:item|report)\s+#?)(\d+)",
        message,
        re.IGNORECASE,
    )
    if report_id:
        fields["report_id"] = int(report_id.group(1))

    # —— 报失意图专用抽取 ——
    # 仅当意图是 report_lost 且上面没抽到物品名时，用口语正则补抽：
    # "我丢了 一个/把/只/本/张/副 钱包" → item_name="钱包"。
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
        # 地点：只在缺失时补抽，支持"于/在 ... 丢了"两种中文句式。
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
        # 描述兜底：若整条消息足够长（≥10 字符）且无显式描述，则用整句话当描述，
        # 避免报失报告缺少 description（后端要求 min_length=10）。
        if "description" not in fields and len(message.strip()) >= 10:
            fields["description"] = message.strip()
    # —— 拾获登记意图专用抽取 ——
    # 同样在 report_found 且缺物品名时补抽："（我）捡到了 一把 钥匙" → item_name="钥匙"。
    if intent == "report_found" and "item_name" not in fields:
        item = re.search(
            r"(?:我)?(?:找到|捡到|捡了|拾到)\s*(?:了)?\s*(?:一(?:个|把|只|本|张|副))?\s*"
            r"([^,，。;；\n]{2,30})",
            message,
        )
        # 英文："i found a wallet at..." / "picked up the phone in..."，
        # 前瞻 (?=...) 让捕获停在 at/in/on 等介词或标点/行尾处。
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
            # 中文地点："于/在 ... 捡到/拾到"；英文地点："at/in ..."（可选停在日期前）。
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
        # 描述兜底：与报失一致，整句够长则当描述。
        if "description" not in fields and len(message.strip()) >= 10:
            fields["description"] = message.strip()
    # —— 搜索意图专用抽取 ——
    if intent == "search_found_items" and "keyword" not in fields:
        # 从"帮我找 xxx / 查找 xxx / 搜索 xxx"（或英文 find/search for）抽取搜索词。
        search = re.search(r"(?:帮我找|查找|搜索)\s*([^,，;；\n]{2,40})", message)
        english_search = re.search(r"(?:find|search for)\s+([^,;\n]{2,40})", message, re.IGNORECASE)
        matched_search = search or english_search
        if matched_search:
            keyword = matched_search.group(1).strip()
            # 指示代词/量词（如仅发图占位语"帮我找这个"里的"这个"）不是真实搜索词，
            # 抽出来只会以近零相似度拖低纯视觉匹配分数，跳过不进入 context。
            if not is_stopword_keyword(keyword):
                fields["keyword"] = keyword
    # 结尾统一剔除空字符串与 None，避免把占位空值带进 context 污染后续校验/打分。
    return {key: value for key, value in fields.items() if value not in (None, "")}


def drop_invalid_fields(model_cls: type[BaseModel], context: dict[str, Any]) -> None:
    """校验 context 能否构造报告模型；失败时移除不可信字段并留日志。

    背景（2026-08-11）：LLM 可能在用户未提供日期时幻觉出未来 event_date，
    直接 model_validate 会抛 ValidationError 冒泡成"内部错误"整单 failed。
    这里把不可信字段（如未来日期）从 context 中清除，由调用方重新走缺失字段
    检查，降级为 needs_more_info 追问，而不是让用户看到内部错误。

    入参：model_cls（要构造的报告模型，如 ReportLostInput/ReportFoundInput）、
    context（可变字段字典，原地删除不可信字段）。无返回值。调用场景：
    _prepare_report / _prepare_found 在追问缺失字段之前先清洗 context。
    """
    try:
        # 试校验：能构造成功则 context 全部可信，直接通过。
        model_cls.model_validate(context)
    except ValidationError as exc:
        # 校验失败：从错误详情里收集"哪个字段非法"（loc 第一段），
        # 例如未来日期的 event_date、短于 min_length 的 item_name。
        invalid = {str(err.get("loc", ())[0]) for err in exc.errors() if err.get("loc")}
        # 留一条告警日志，便于排查 LLM 幻觉值。
        logger.warning(
            "drop_invalid_fields: model=%s invalid=%s err=%.200s",
            model_cls.__name__,
            sorted(invalid),
            exc,
        )
        # 原地删除不可信字段：调用方随后会重新走"缺失字段追问"逻辑，
        # 而不是让 ValidationError 继续冒泡成内部错误。
        for field in invalid:
            context.pop(field, None)


def map_category(value: str) -> str | None:
    """把自然语言类别映射为后端 canonical 类别枚举（ItemCategory）。

    入参：value（消息片段或用户输入，如 "手机"、"electronic"、"钱包"）。
    返回：canonical 类别字符串（如 ELECTRONICS）；无法识别时返回 None。
    调用场景：extract_fields 抽取类别、main.py 的 /agent/classify 分类建议。
    """
    normalized = value.lower().strip()
    # 第一优先级：直接命中枚举名本身（如用户输入 "electronics" / "ID card"），
    # 大小写不敏感、可含前后缀。
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
    # 第二优先级：按 CATEGORIES 表的关键词做子串匹配（插入顺序 = 优先级）。
    for keyword, category in CATEGORIES.items():
        if keyword in normalized:
            return category
    # 都无法识别 → None（调用方据此决定是否追问/交给 LLM 兜底）。
    return None


def parse_date(value: Any) -> date | None:
    """宽松地把任意值解析成 date；空值/非法值返回 None，绝不抛异常。

    入参：value（可能是 ISO 日期字符串、date 对象或 None）。
    返回：date；无法解析返回 None。调用场景：search_candidates 解析 event_date/
    date_from/date_to 并做 ±30 天窗口兜底。
    """
    if not value:
        return None
    try:
        # str() 兜底：兼容 date 对象与 "YYYY-MM-DD" 字符串。
        return date.fromisoformat(str(value))
    except ValueError:
        # 非法格式（如 "2026-13-99"、"昨天"）→ None，让调用方按缺省处理。
        return None


def missing_message(fields: list[str], language: str) -> str:
    """生成"还缺哪些必填信息"的追问文案（中英双语）。

    入参：fields（缺失的字段名列表，如 ["item_name", "event_date"]）、language（zh/en）。
    返回：给用户看的完整追问句子。调用场景：_prepare_report/_prepare_found/
    _prepare_claim 等缺字段时组织 needs_more_info 文案。
    """
    # 字段名 → 用户友好的展示标签（中英各一份）；不认识的字段会 KeyError 提示。
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
    # 按语言取标签表，再把缺失字段的标签用逗号拼起来。
    active = labels["zh" if language == "zh" else "en"]
    names = ", ".join(active[field] for field in fields)
    return f"还需要以下信息：{names}。" if language == "zh" else f"Please provide: {names}."


def report_summary(report: ReportLostInput | ReportFoundInput, language: str) -> str:
    """把报告内容拼成一句话摘要，用于确认单（confirmation.summary）。

    入参：report（报失或拾获报告模型）、language（zh/en）。
    返回：摘要字符串，如 "钱包，类别 WALLET_PURSE，地点 图书馆，日期 2026-08-20，描述：..."。
    调用场景：_prepare_report / _prepare_found 组装 ConfirmationRequired 摘要。
    """
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
    """报失记录创建成功后的完成文案：有匹配候选则附上摘要，否则告知暂无匹配。

    入参：created（后端创建响应，含 id）、matches（匹配候选列表）、language。
    返回：给用户看的完成消息。调用场景：_handle_confirmation 报失确认成功后。
    """
    report_id = created.get("id")
    if language == "zh":
        if matches:
            return f"报失记录 #{report_id} 已创建。\n{match_results_message(matches, language)}"
        return f"报失记录 #{report_id} 已创建，暂时未找到高匹配候选。"
    if matches:
        return f"Lost report #{report_id} was created.\n{match_results_message(matches, language)}"
    return f"Lost report #{report_id} was created with no strong match yet."


def found_created_message(created: dict[str, Any], matches: list[Any], language: str) -> str:
    """拾获记录创建成功后，附带可能对应的开放报失记录。

    与 report_created_message 对称，只是动作是拾获登记，且匹配方向是反查报失记录。
    返回：给用户看的完成消息（含匹配摘要或"暂无匹配"提示）。
    """
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
    """生成所有客户端都能直接展示的候选摘要，结构化结果仍单独返回。

    入参：matches（rank_candidates 返回的 MatchResult 列表）、language（zh/en）。
    返回：多行文本摘要（每条候选含编号、记录号、类型、名称、类别、颜色、地点、
    日期、匹配度百分比、描述与匹配原因）。调用场景：搜索/创建成功后拼聊天文案。
    """
    # 标题行：先说明命中数量。
    heading = (
        f"找到 {len(matches)} 个匹配度较高的候选物品："
        if language == "zh"
        else f"Found {len(matches)} strong candidate item(s):"
    )
    lines = [heading]
    # 逐条候选格式化为可读文本（编号从 1 开始）。
    for index, match in enumerate(matches, start=1):
        # 匹配度 0~1 转百分比（四舍五入到整数）。
        score = round(float(match.match_score) * 100)
        # 颜色缺失时给占位文案。
        colour = match.colour or ("未填写" if language == "zh" else "not provided")
        # 匹配原因列表按语言用不同分隔符拼接。
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
    """把单条物品详情 dict 拼成可读文本。

    入参：detail（后端 get_item_detail 返回的字段字典，注意是驼峰 key，如 itemName/
    eventDate）、language。返回：给用户看的详情文本。调用场景：RuleEngine._detail。
    注意：所有字段都用 .get() 取默认空值，避免后端缺字段时报错。
    """
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
    """构造"工具执行"事件（tool_execution），推给前端展示工具调用进度。

    入参：action（工具名，如 report_lost/claim_item）、status（started/completed）。
    返回：AgentEvent。调用场景：各工具调用前后成对 emit。
    """
    return AgentEvent("tool_execution", {"action": action, "status": status})


def response_with_token(
    message: str,
    status: Any,
    request_id: str,
    emit: Emit,
    **kwargs: Any,
) -> InvokeResponse:
    """统一出口：先把回复文本以 token 事件推给面板（流式展示），再返回结构化响应。

    入参：message（回复文本）、status（AgentStatus）、request_id、emit（事件回调）、
    kwargs（透传给 InvokeResponse 的其它字段，如 shared_context/match_results/
    confirmation_required/actions_taken）。返回：InvokeResponse。
    """
    # 推 token 事件：前端据此实现"打字机"效果，立即看到回复正文。
    emit(AgentEvent("token", {"text": message}))
    # 同时返回结构化响应（状态机/匹配结果/确认单等由 kwargs 透传）。
    return InvokeResponse(response=message, status=status, request_id=request_id, **kwargs)


def backend_error_response(
    error: BackendApiError,
    request_id: str,
    language: str,
    emit: Emit,
) -> InvokeResponse:
    """把后端错误（BackendApiError）转成用户友好的失败响应。

    入参：error（含 status_code/code/message）、request_id、language、emit。
    返回：状态为 failed 的 InvokeResponse。调用场景：_handle_confirmation/_search/
    _detail 捕获 BackendApiError 后统一调用，避免把内部异常/脱敏信息暴露给用户。
    """
    # 常见业务错误码 → 中文文案映射；未收录的码回退显示原始错误信息。
    chinese_messages = {
        "CLAIM_ALREADY_EXISTS": "你已经提交过该物品的待处理或已批准认领申请，不能重复认领。",
        "CANNOT_CLAIM_OWN_REPORT": "不能认领自己发布的拾获记录。",
        "ONLY_FOUND_REPORTS_CAN_BE_CLAIMED": "只有拾获记录可以提交认领。",
        "REPORT_NOT_OPEN": "该记录当前不是开放状态，不能继续认领。",
        "LOST_FOUND_REPORT_NOT_FOUND": "没有找到指定的失物招领记录。",
        "CLAIM_NOT_FOUND": "没有找到指定的认领申请。",
    }
    # 中文用户优先用映射文案；英文用户始终展示错误码 + 原文（便于排查）。
    message = (
        chinese_messages.get(error.code, str(error))
        if language == "zh"
        else f"Campus API error ({error.code}): {error}"
    )
    # 推送 agent_error 事件（前端可据此标记失败），并返回 failed 状态响应。
    emit(AgentEvent("agent_error", {"code": error.code, "message": message}))
    return response_with_token(message, "failed", request_id, emit)
