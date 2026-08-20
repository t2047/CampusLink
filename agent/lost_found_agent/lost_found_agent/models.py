"""失物招领 Agent 对外契约模型（Pydantic 数据模型层）。

本模块集中定义 Agent 与外部（Spring Boot 面板 / 编排层）交互的全部请求、
响应与共享数据结构，相当于 Agent 的“API 契约”。其中：

- 聊天 flow：InvokeRequest / InvokeResponse 承载一轮对话的输入输出。输入侧
  包含消息文本、面板暂存图片、确认凭据与链路追踪上下文；输出侧携带状态机
  结果（AgentStatus）、匹配候选（MatchResult）、写操作确认
  （ConfirmationRequired）与已执行动作（ActionTaken）。
- 轻量端点：ClassifyRequest / ClassifyResponse（物品名分类建议）、
  SearchRequest / SearchResponse（Browse 以图搜物，不经聊天/LLM，直接走
  候选检索与 rank_candidates 打分）。
- 内部校验：VerifiedRequest 是编排层对用户身份、意图与防重放令牌的验证
  结果，随每次请求注入。

所有字段约束（长度上下限、枚举取值、数值范围、跨字段 validator）都在反序列
化时被 Pydantic 强制执行，因此本文件同时也是输入净化的第一道防线。
"""

# 日期字段直接使用标准库 date（而非 str），让 Pydantic 负责 YYYY-MM-DD 的解析与校验
from datetime import date
from typing import Any, Literal

from pydantic import BaseModel, Field, model_validator

# 聊天 flow 一次调用的最终状态机枚举，贯穿 Agent 与编排层的语义约定：
#   completed           —— 流程完成（如创建/登记/详情已返回）
#   needs_more_info     —— 字段不足，需要追问补充
#   match_found         —— 匹配到候选物品（带 match_results）
#   no_match            —— 检索完成但没有达到最低分数的候选
#   needs_confirmation  —— 写操作（报失/登记/认领）等待用户确认
#   failed              —— 后端错误 / 确认失效 / 参数非法
AgentStatus = Literal[
    "completed",
    "needs_more_info",
    "match_found",
    "no_match",
    "needs_confirmation",
    "failed",
]


class TraceParent(BaseModel):
    """链路追踪父上下文（OpenTelemetry traceparent 语义的简化版）。

    编排层（Agent Supervisor）把整条链路的 trace_id 与本 agent 的父 span
    透传给 Agent，便于跨服务串联日志与追踪。字段可空：非必须开启追踪。"""

    # 整条调用链的追踪 ID
    trace_id: str | None = None
    # 当前调用在追踪树中的父 span ID
    parent_span_id: str | None = None


class ConversationContext(BaseModel):
    """多轮对话的会话上下文容器。

    previous_agent 记录上一跳是哪个 agent（用于 agent 间转交）；
    shared_data 保存本轮抽取/继承的结构化字段（intent、item_name 等），
    通过 InvokeResponse.shared_context 回传并在下一轮随请求带回，实现状态延续。"""

    # 上一个处理该会话的 agent 标识
    previous_agent: str | None = None
    # 会话 ID，用于在编排层对齐多轮消息
    session_id: str | None = None
    # 共享结构化字段字典；default_factory 保证每个实例拿到独立的空 dict，
    # 避免多个请求共享同一个可变默认值
    shared_data: dict[str, Any] = Field(default_factory=dict)


class AgentImage(BaseModel):
    """Agent 面板选中并已由 Spring Boot 暂存的一张图片。object_key 在确认创建时
    通过内部 API 关联为报告图片，visual_fingerprint 参与双向匹配打分。

    三组视觉描述字段服务于不同打分路径：
    - visual_fingerprint：本地确定性颜色直方图指纹（VF1: 前缀），离线降级匹配用；
    - visual_embedding(_model/_revision)：预训练模型产出的向量 base64 串与
      模型元信息，命中 pretrained 打分模式时使用；
    - url：图片可访问地址，供展示或抓取。"""

    # 暂存对象的 object key（Spring Boot 侧生成），创建报告时关联为正式图片
    object_key: str = Field(min_length=1, max_length=500)
    # 本地确定性颜色指纹（与 embeddings.visual_fingerprint 同格式），参与基础匹配
    visual_fingerprint: str | None = None
    # 预训练视觉向量的 base64 编码；限长 4096 防止异常大值打穿内存
    visual_embedding: str | None = Field(default=None, max_length=4096)
    # 预训练视觉向量的模型名（如 CLIP ViT-B/32），用于两端口径对齐
    visual_embedding_model: str | None = Field(default=None, max_length=200)
    # 预训练视觉向量的模型版本/commit
    visual_embedding_revision: str | None = Field(default=None, max_length=64)
    # 图片可访问 URL（可选）
    url: str | None = None


class InvokeRequest(BaseModel):
    """聊天 flow 的输入请求：一次对话调用所携带的全部参数。

    confirmed + confirmation_id 组合用于“确认写操作”：首轮 Agent 返回
    ConfirmationRequired（含 confirmation_id 与过期时间），用户确认后编排层
    用同一 confirmation_id 且 confirmed=True 再次调用，Agent 才执行真实写操作。"""

    # 用户本轮消息；非空且最长 4000 字符
    message: str = Field(min_length=1, max_length=4000)
    # 会话上下文（含上一轮回传的 shared_context 结构化字段）
    conversation_context: ConversationContext = Field(default_factory=ConversationContext)
    # 是否是对上一条 confirmation 的确认（写操作的“二次确认”开关）
    confirmed: bool = False
    # 待确认操作的凭据 ID，由首轮确认请求下发给客户端
    confirmation_id: str | None = None
    # 链路追踪父上下文
    trace_parent: TraceParent = Field(default_factory=TraceParent)
    # 面板暂存图片（最多 5 张），同时支撑以图搜物与报告图片关联
    images: list[AgentImage] = Field(default_factory=list, max_length=5)


class MatchResult(BaseModel):
    """一个匹配候选物品的结构化描述，聊天 flow 与 Browse 以图搜物共用同一结构。

    内容字段直接映射后端失物招领报告（item_id 对应报告 id）；打分字段
    （match_score / match_reason / score_breakdown / matching_mode）由
    matching.rank_candidates 在重排时填充，供前端展示匹配度、原因与可解释的
    分项明细。"""

    # 后端报告记录 ID
    item_id: str
    # 报告类型：LOST=报失（我丢了），FOUND=拾获（捡到）
    report_type: Literal["LOST", "FOUND"]
    # 物品名称
    item_name: str
    # 物品类别（后端枚举，如 ELECTRONICS / ID_CARD / BAG ...）
    category: str
    # 物品描述文本
    description: str
    # 颜色（canonical 展示形式），可能未填写
    colour: str | None = None
    # 丢失/拾获地点
    location: str
    # 事件日期（YYYY-MM-DD 字符串，直接透传后端，不做 date 强解析以容忍脏数据）
    event_date: str
    # 事件时间的自由文本描述（如“下午三点”）
    time_description: str | None = None
    # 候选图片 URL 列表
    image_urls: list[str] = Field(default_factory=list)
    # 报告状态（如 OPEN / RESOLVED），由后端透传
    status: str
    # 最终匹配分，取值 [0,1]，由加权平均/校准计算后保留 4 位小数
    match_score: float = Field(ge=0, le=1)
    # 人类可读的匹配原因（如“文字描述相似”），前端直接展示
    match_reason: list[str] = Field(default_factory=list)
    # 各分量（text/visual/category/location/temporal/colour/cross_modal）的得分明细
    score_breakdown: dict[str, float] = Field(default_factory=dict)
    # 打分模式：是否用了预训练多模态/图片/文本向量；全都没有则 baseline
    matching_mode: Literal[
        "pretrained_multimodal",
        "pretrained_image",
        "pretrained_text",
        "baseline",
    ] = "baseline"


class ConfirmationRequired(BaseModel):
    """写操作（报失/登记/认领）的确认凭据，随响应返回给编排层。

    客户端确认后需原样带回 confirmation_id 且 confirmed=True，Agent 凭此
    从 ConfirmationStore 取出暂存的操作负载并执行；expires_at 之后凭据作废。"""

    # 确认凭据 ID（ConfirmationStore 生成）
    confirmation_id: str
    # 需要确认的具体写操作
    action: Literal["report_lost", "report_found", "claim_item"]
    # 待确认内容的摘要，展示给用户核对
    summary: str
    # 凭据过期时间（ISO 8601），超时后确认无效
    expires_at: str


class ActionTaken(BaseModel):
    """记录 Agent 在一次调用中已执行的动作，供前端展示工具调用轨迹。"""

    # 动作类型：写操作（report_lost/report_found/claim_item）或查询（search_*/get_item_detail）
    action: Literal[
        "report_lost",
        "report_found",
        "search_found_items",
        "search_lost_items",
        "get_item_detail",
        "claim_item",
    ]
    # 动作入参摘要（如搜索条件）
    params_summary: str | None = None
    # 动作结果摘要（如 report_id=xxx / candidates=100, matches=3）
    result_summary: str | None = None
    # 执行结果状态
    status: Literal["success", "failed", "skipped"]


class InvokeResponse(BaseModel):
    """聊天 flow 的输出响应，承载最终回复与全部结构化结果。

    编排层据此驱动面板渲染：response 为展示文本（也作为 token 流式下发），
    match_results 供“匹配结果”卡片展示，confirmation_required 触发确认弹窗，
    shared_context 需原样随下一轮 InvokeRequest 回传以维持多轮状态。"""

    # 面向用户的回复文本
    response: str
    # 本次调用的状态机结果（AgentStatus）
    status: AgentStatus
    # 匹配到的候选列表（status 为 match_found 时非空）
    match_results: list[MatchResult] = Field(default_factory=list)
    # 写操作待确认凭据（status 为 needs_confirmation 时非空）
    confirmation_required: ConfirmationRequired | None = None
    # 需要回传给下一轮的共享上下文（多轮状态载体）
    shared_context: dict[str, Any] = Field(default_factory=dict)
    # 本次调用已执行的动作轨迹
    actions_taken: list[ActionTaken] = Field(default_factory=list)
    # 请求 ID，用于编排层与日志对齐请求-响应
    request_id: str


class ClassifyRequest(BaseModel):
    """物品名分类建议请求（轻量端点，仅返回分类枚举）。

    由规则层 map_category 兜底判断，规则不确定时交给 LLM；端点独立于聊天 flow。"""

    # 待分类的物品名；非空且最长 200 字符
    item_name: str = Field(min_length=1, max_length=200)


class ClassifyResponse(BaseModel):
    """分类建议响应；category 为 None 表示规则与 LLM 均无法判断。"""

    # 建议分类（后端枚举值），无法判断时为 None
    category: str | None = None


# 轻量搜索端点的状态：仅三种，区别于聊天 flow 的完整 AgentStatus
SearchStatus = Literal["match_found", "no_match", "failed"]


class SearchRequest(BaseModel):
    """Browse 以图搜物的轻量搜索请求：不经聊天/LLM，直接走候选检索与 rank_candidates 打分。

    report_type 决定候选方向（FOUND 视图搜 FOUND 候选，LOST 视图搜 LOST 候选），
    与 chat flow 的 search_found_items / search_lost_items 语义一致。

    images 至少 1 张（以图搜物），关键字/类别/颜色/地点/日期为可选过滤条件，
    全部条件最终汇入查询 dict 后交给 rank_candidates 打分。"""

    # 候选方向：FOUND=搜拾获候选，LOST=搜报失候选
    report_type: Literal["FOUND", "LOST"]
    # 搜索关键字（可选），最长 100
    keyword: str | None = Field(default=None, max_length=100)
    # 类别过滤（可选），最长 50
    category: str | None = Field(default=None, max_length=50)
    # 颜色过滤（可选），最长 50
    colour: str | None = Field(default=None, max_length=50)
    # 地点过滤（可选），最长 200
    location: str | None = Field(default=None, max_length=200)
    # 日期区间下界（可选），Pydantic 解析为 date
    date_from: date | None = None
    # 日期区间上界（可选）
    date_to: date | None = None
    # 以图搜物的查询图片：至少 1 张、最多 5 张
    images: list[AgentImage] = Field(min_length=1, max_length=5)

    # 跨字段校验：mode="after" 表示整个模型解析完成后执行，此时所有字段已就绪
    @model_validator(mode="after")
    def validate_date_range(self) -> "SearchRequest":
        # 两个日期都给了但顺序颠倒（date_from > date_to）视为非法请求，
        # 抛出 ValueError 由 Pydantic 统一转成 422 校验错误
        if self.date_from and self.date_to and self.date_from > self.date_to:
            raise ValueError("date_from must be on or before date_to")
        return self


class SearchResponse(BaseModel):
    """轻量搜索结果；match_results 与聊天 flow 的 MatchResult 结构一致。"""

    # 搜索结果状态（match_found / no_match / failed）
    status: SearchStatus
    # 匹配候选列表（结构与聊天 flow 的 MatchResult 完全一致）
    match_results: list[MatchResult] = Field(default_factory=list)
    # 请求 ID，用于对齐请求-响应
    request_id: str
    # 附加说明（如失败原因），可为 None
    message: str | None = None


class VerifiedRequest(BaseModel):
    """编排层注入的身份/意图验证结果，随每次调用传给 Agent。

    Agent 据此判断当前用户是否有权执行 intended_action（如认领需防止越权），
    nonce 用于防重放，claims 承载验证过程的附加声明。"""

    # 用户 ID
    user_id: str
    # 用户角色（如 STUDENT / STAFF / ADMIN）
    user_role: str
    # 用户被验证的意图，Agent 需与实际执行的动作比对
    intended_action: str
    # 一次性随机数，用于防重放攻击
    nonce: str
    # 链路追踪 ID（可选）
    trace_id: str | None = None
    # 验证过程产生的附加声明（键值对）
    claims: dict[str, Any] = Field(default_factory=dict)
