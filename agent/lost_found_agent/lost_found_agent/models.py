"""Agent 对外契约模型。"""

from typing import Any, Literal

from pydantic import BaseModel, Field

AgentStatus = Literal[
    "completed",
    "needs_more_info",
    "match_found",
    "no_match",
    "needs_confirmation",
    "failed",
]


class TraceParent(BaseModel):
    trace_id: str | None = None
    parent_span_id: str | None = None


class ConversationContext(BaseModel):
    previous_agent: str | None = None
    session_id: str | None = None
    shared_data: dict[str, Any] = Field(default_factory=dict)


class AgentImage(BaseModel):
    """Agent 面板选中并已由 Spring Boot 暂存的一张图片。object_key 在确认创建时
    通过内部 API 关联为报告图片，visual_fingerprint 参与双向匹配打分。"""

    object_key: str = Field(min_length=1, max_length=500)
    visual_fingerprint: str | None = None
    url: str | None = None


class InvokeRequest(BaseModel):
    message: str = Field(min_length=1, max_length=4000)
    conversation_context: ConversationContext = Field(default_factory=ConversationContext)
    confirmed: bool = False
    confirmation_id: str | None = None
    trace_parent: TraceParent = Field(default_factory=TraceParent)
    images: list[AgentImage] = Field(default_factory=list, max_length=5)


class MatchResult(BaseModel):
    item_id: str
    report_type: Literal["LOST", "FOUND"]
    item_name: str
    category: str
    description: str
    colour: str | None = None
    location: str
    event_date: str
    time_description: str | None = None
    image_urls: list[str] = Field(default_factory=list)
    status: str
    match_score: float = Field(ge=0, le=1)
    match_reason: list[str] = Field(default_factory=list)


class ConfirmationRequired(BaseModel):
    confirmation_id: str
    action: Literal["report_lost", "report_found", "claim_item"]
    summary: str
    expires_at: str


class ActionTaken(BaseModel):
    action: Literal[
        "report_lost",
        "report_found",
        "search_found_items",
        "search_lost_items",
        "get_item_detail",
        "claim_item",
    ]
    params_summary: str | None = None
    result_summary: str | None = None
    status: Literal["success", "failed", "skipped"]


class InvokeResponse(BaseModel):
    response: str
    status: AgentStatus
    match_results: list[MatchResult] = Field(default_factory=list)
    confirmation_required: ConfirmationRequired | None = None
    shared_context: dict[str, Any] = Field(default_factory=dict)
    actions_taken: list[ActionTaken] = Field(default_factory=list)
    request_id: str


class ClassifyRequest(BaseModel):
    """物品名分类建议请求（轻量端点，仅返回分类枚举）。"""

    item_name: str = Field(min_length=1, max_length=200)


class ClassifyResponse(BaseModel):
    """分类建议响应；category 为 None 表示规则与 LLM 均无法判断。"""

    category: str | None = None


class VerifiedRequest(BaseModel):
    user_id: str
    user_role: str
    intended_action: str
    nonce: str
    trace_id: str | None = None
    claims: dict[str, Any] = Field(default_factory=dict)
