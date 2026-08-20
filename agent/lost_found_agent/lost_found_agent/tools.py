"""调用 Spring Boot 内部 API 的真实 Lost & Found 工具。

本模块是失物招领 Agent 与后端 Campus（Spring Boot）服务通信的「工具层」：

- 定义所有工具入参的 Pydantic 模型（报失 / 报捡 / 搜索 / 详情 / 认领），
  在进入网络层之前完成字段约束与校验（长度、日期、取值范围等）；
- 提供 CampusApiClient：基于 httpx.AsyncClient 的异步 HTTP 客户端，封装对
  Campus 内部 API（/api/internal/lost-found/...）的各类调用，并统一处理
  超时、网络异常、后端错误与响应格式校验；
- 每次调用都签发独立、短期、一次性使用的「委派令牌」（Delegation Token，
  JWT），把当前用户身份与意图（intended_action）授权给后端，30 秒即过期，
  避免长期持有凭据；
- 通过 BackendApiError 把后端错误统一为「脱敏」异常（不含令牌或底层响应
  正文），上层可直接据此生成面向用户的提示。

rules.py 中的规则引擎通过本模块的客户端执行真正的读写操作。
"""

from datetime import UTC, date, datetime, timedelta  # 时间处理：UTC 时区、日期、令牌过期计算
from typing import Any, Literal  # 通用类型：任意字典、字面量联合类型
from uuid import uuid4  # 生成委派令牌的唯一 id（jti）

import httpx  # 异步 HTTP 客户端
import jwt  # 生成 / 编码委派令牌（HS256）
from pydantic import BaseModel, Field, field_validator, model_validator  # 入参模型与校验器

from .config import Settings  # 运行时配置（含后端地址与共享密钥）

# 物品类别（字面量联合类型）：与后端 Campus API 的分类口径一一对应。
# 它既作为 Pydantic 字段的类型约束（非法类别直接校验失败），
# 也是工具调用方可读的受支持类别清单。
ItemCategory = Literal[
    "ELECTRONICS",  # 电子产品
    "ID_CARD",  # 证件（身份证 / 学生证等）
    "WALLET_PURSE",  # 钱包 / 零钱包
    "KEYS",  # 钥匙
    "BAG",  # 包
    "CLOTHING",  # 衣物
    "BOOKS_STATIONERY",  # 书本 / 文具
    "UMBRELLA",  # 伞
    "OTHER",  # 其他
]


class ReportLostInput(BaseModel):
    """报失（LOST）报告入参模型。

    校验规则：物品名 2-100 字、描述 10-2000 字、地点 1-200 字；
    event_date 不得晚于今天；颜色与时间描述可选。images /
    visual_fingerprints / visual_embeddings 三个视觉相关字段不随 body 直接
    发给后端（见 report_lost 的 body 构建器只挑字段），而是用于确认载荷
    与自动匹配 query。
    """

    # 中文物品名常为 2 字符，min_length=2 与 llm.py 提取口径一致
    item_name: str = Field(min_length=2, max_length=100)  # 物品名称（2-100 字符）
    category: ItemCategory  # 物品类别，须在 ItemCategory 允许列表内
    description: str = Field(min_length=10, max_length=2000)  # 详细描述（10-2000 字符）
    location: str = Field(min_length=1, max_length=200)  # 丢失地点（1-200 字符）
    event_date: date  # 事件日期（丢失日期），不可晚于今天
    colour: str | None = Field(default=None, max_length=50)  # 可选：物品主颜色
    time_description: str | None = Field(default=None, max_length=100)  # 可选：时间段描述
    # 面板已暂存图片的 objectKey；确认创建时经内部 API 关联为报告图片。
    # 字段本身不发给后端（body 构建器只挑字段），仅用于确认载荷与自动匹配 query。
    images: list[str] = Field(default_factory=list, max_length=5)  # 已上传图片 objectKey（最多 5 张）
    # 查询端视觉指纹（与 images 同序），创建后并入自动匹配 query 参与打分。
    visual_fingerprints: list[str] = Field(default_factory=list, max_length=5)  # 与 images 同序的视觉指纹
    visual_embeddings: list[str] = Field(default_factory=list, max_length=5)  # 与 images 同序的预训练向量

    @field_validator("event_date")
    @classmethod
    def event_date_cannot_be_future(cls, value: date) -> date:
        # 校验器：event_date 不能是未来日期，防止误填 / 伪造「未来丢失」时间
        if value > date.today():
            raise ValueError("event_date cannot be in the future")
        return value


class ReportFoundInput(BaseModel):
    """捡到（FOUND）报告：字段与报失对称（捡到物品登记）。

    校验与语义同 ReportLostInput，仅物品名下限放宽到 3 字符
    （拾获物名称通常略长），用于 /agent/invoke 中的「捡到」类写操作。
    """

    item_name: str = Field(min_length=3, max_length=100)  # 物品名称（3-100 字符）
    category: ItemCategory  # 物品类别
    description: str = Field(min_length=10, max_length=2000)  # 详细描述（10-2000 字符）
    location: str = Field(min_length=1, max_length=200)  # 捡到地点（1-200 字符）
    event_date: date  # 捡到日期，不可晚于今天
    colour: str | None = Field(default=None, max_length=50)  # 可选：物品主颜色
    time_description: str | None = Field(default=None, max_length=100)  # 可选：时间段描述
    images: list[str] = Field(default_factory=list, max_length=5)  # 已上传图片 objectKey（最多 5 张）
    visual_fingerprints: list[str] = Field(default_factory=list, max_length=5)  # 与 images 同序的视觉指纹
    visual_embeddings: list[str] = Field(default_factory=list, max_length=5)  # 与 images 同序的预训练向量

    @field_validator("event_date")
    @classmethod
    def event_date_cannot_be_future(cls, value: date) -> date:
        # 与报失一致：捡到日期不可晚于今天
        if value > date.today():
            raise ValueError("event_date cannot be in the future")
        return value


class SearchItemsInput(BaseModel):
    """搜索条件入参模型（候选检索的公共基类）。

    除 page / size 外所有字段均可选；日期范围 date_from <= date_to 由
    model_validator 保证。page 从 0 开始，size 上限 100。
    子类 SearchFoundItemsInput / SearchLostItemsInput 仅作语义区分。
    """

    keyword: str | None = None  # 可选：搜索关键词
    category: ItemCategory | None = None  # 可选：按类别过滤
    colour: str | None = None  # 可选：按颜色过滤
    location: str | None = None  # 可选：按地点过滤
    date_from: date | None = None  # 可选：事件日期范围起点（含）
    date_to: date | None = None  # 可选：事件日期范围终点（含）
    page: int = Field(default=0, ge=0)  # 分页页码，从 0 开始
    size: int = Field(default=100, ge=1, le=100)  # 每页条数，1-100，默认 100

    @model_validator(mode="after")
    def validate_date_range(self) -> "SearchItemsInput":
        # 整体校验（mode="after" 在字段解析完成后执行）：日期范围必须合法
        if self.date_from and self.date_to and self.date_from > self.date_to:
            raise ValueError("date_from must be on or before date_to")
        return self


class SearchFoundItemsInput(SearchItemsInput):
    """搜索开放的拾获记录（FOUND 候选）。"""


class SearchLostItemsInput(SearchItemsInput):
    """搜索开放的报失记录（LOST 候选）。"""


class GetItemDetailInput(BaseModel):
    """查询单条报告详情的入参模型。"""

    report_id: int = Field(gt=0)  # 报告 id，须为正整数


class ClaimItemInput(BaseModel):
    """发起认领（claim）的入参模型。"""

    report_id: int = Field(gt=0)  # 要认领的报告 id，须为正整数
    proof_description: str = Field(min_length=10, max_length=1000)  # 认领凭证描述（10-1000 字符）


class BackendApiError(RuntimeError):
    """已脱敏的后端错误，不包含令牌或底层响应正文。

    作用：把 Campus API 返回 / 网络层的各类异常统一成一个携带
    status_code（HTTP 状态码）与 code（稳定错误码）的业务异常，
    供上层（rules.py / main.py）捕获后据此生成面向用户的提示；
    message 为面向用户的中文文案，且不会泄露令牌或敏感响应正文。
    """

    def __init__(self, status_code: int, code: str, message: str) -> None:
        super().__init__(message)  # 以 message 作为异常的 str() 表示
        self.status_code = status_code  # HTTP 状态码（如 503 / 504 / 502）
        self.code = code  # 稳定错误码（如 CAMPUS_API_TIMEOUT）


class CampusApiClient:
    """Campus 内部 API 的异步 HTTP 客户端。

    每次工具调用都签发独立、短期、一次性的 Delegation Token（JWT，
    见 _delegation_token）：以 lost-found-agent 为签发者、campus-api 为
    受众，绑定当前用户 id、角色与「意图动作」，30 秒后即过期，从而让后端
    按最小权限授权本次读 / 写操作。

    所有对外方法（report_lost / report_found / search_found_items /
    search_lost_items / get_item_detail / claim_item）最终汇聚到
    _request()：统一构造请求、附加 Bearer 令牌、映射超时 / 网络 / 后端错误。
    """

    def __init__(
        self,
        settings: Settings,
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        """初始化异步客户端。

        入参：
        - settings: 运行时配置，读取 campus_api_url 与共享密钥；
        - transport: 可选的 httpx 传输层（测试时注入 MockTransport 拦截请求）。
        """
        self._settings = settings
        # 构造底层异步客户端：base_url 去掉末尾斜杠避免路径拼接出双斜杠，
        # 统一 10 秒超时；transport 默认使用真实网络
        self._client = httpx.AsyncClient(
            base_url=settings.campus_api_url.rstrip("/"),
            timeout=httpx.Timeout(10.0),
            transport=transport,
        )

    async def close(self) -> None:
        """关闭底层异步客户端，释放连接池（应用 shutdown 时调用）。"""
        await self._client.aclose()

    async def report_lost(
        self, user_id: str, user_role: str, payload: ReportLostInput
    ) -> dict[str, Any]:
        """提交一条报失（LOST）报告。

        入参：
        - user_id / user_role: 当前用户 id 与角色，用于签发委派令牌；
        - payload: 报失表单模型。

        返回：Campus 后端创建成功后的响应字典（含 report_id 等）。
        """
        return await self._request(
            "POST",
            "/api/internal/lost-found/reports/lost",  # 后端内部接口路径
            "report_lost",  # 委派令牌声明的意图动作
            user_id,
            user_role,
            # 组装后端期望的 JSON body：把 Pydantic 字段映射为后端的驼峰命名，
            # eventDate 用 ISO 日期字符串；imageKeys 仅在存在图片时携带（无图不发）
            json={
                "itemName": payload.item_name,
                "category": payload.category,
                "description": payload.description,
                "colour": payload.colour,
                "location": payload.location,
                "eventDate": payload.event_date.isoformat(),
                "timeDescription": payload.time_description,
                **({"imageKeys": payload.images} if payload.images else {}),
            },
        )

    async def report_found(
        self, user_id: str, user_role: str, payload: ReportFoundInput
    ) -> dict[str, Any]:
        """提交一条捡到（FOUND）报告。

        入参：
        - user_id / user_role: 当前用户 id 与角色，用于签发委派令牌；
        - payload: 捡到表单模型。

        返回：Campus 后端创建成功后的响应字典（含 report_id 等）。
        请求构造与 report_lost 完全对称，仅路径与意图动作不同。
        """
        return await self._request(
            "POST",
            "/api/internal/lost-found/reports/found",  # 后端内部接口路径
            "report_found",  # 委派令牌声明的意图动作
            user_id,
            user_role,
            # 同 report_lost：驼峰命名映射 + ISO 日期 + 条件携带 imageKeys
            json={
                "itemName": payload.item_name,
                "category": payload.category,
                "description": payload.description,
                "colour": payload.colour,
                "location": payload.location,
                "eventDate": payload.event_date.isoformat(),
                "timeDescription": payload.time_description,
                **({"imageKeys": payload.images} if payload.images else {}),
            },
        )

    async def search_found_items(
        self, user_id: str, user_role: str, payload: SearchFoundItemsInput
    ) -> dict[str, Any]:
        """搜索拾获候选记录（GET /candidates）。

        入参：
        - user_id / user_role: 用于签发委派令牌；
        - payload: 搜索条件（关键词 / 类别 / 颜色 / 地点 / 日期范围 / 分页）。

        返回：后端返回的候选列表响应字典（包含 items 与分页信息）。
        查询参数为 GET query string；可选过滤条件只有在非空时才加入参数，
        避免把 None 传成 "None" 等无意义字符串。
        """
        # 必填分页参数：始终携带
        params: dict[str, str | int] = {"page": payload.page, "size": payload.size}
        # 可选过滤参数：日期先转 ISO 字符串，未填则置 None（稍后过滤掉）
        optional: dict[str, str | None] = {
            "keyword": payload.keyword,
            "category": payload.category,
            "colour": payload.colour,
            "location": payload.location,
            "dateFrom": payload.date_from.isoformat() if payload.date_from else None,
            "dateTo": payload.date_to.isoformat() if payload.date_to else None,
        }
        # 仅保留非 None 的可选参数并入最终 query string
        params.update({key: value for key, value in optional.items() if value is not None})
        return await self._request(
            "GET",
            "/api/internal/lost-found/candidates",  # 后端拾获候选接口
            "search_found_items",  # 委派令牌声明的意图动作
            user_id,
            user_role,
            params=params,
        )

    async def search_lost_items(
        self, user_id: str, user_role: str, payload: SearchLostItemsInput
    ) -> dict[str, Any]:
        """搜索报失候选记录（GET /lost-candidates）。

        入参与构造逻辑与 search_found_items 完全一致，仅目标接口不同
        （报失候选 vs 拾获候选），用于反向匹配（捡到的人找失主）。
        """
        params: dict[str, str | int] = {"page": payload.page, "size": payload.size}  # 必填分页参数
        optional: dict[str, str | None] = {
            "keyword": payload.keyword,
            "category": payload.category,
            "colour": payload.colour,
            "location": payload.location,
            "dateFrom": payload.date_from.isoformat() if payload.date_from else None,
            "dateTo": payload.date_to.isoformat() if payload.date_to else None,
        }
        # 仅保留非 None 的可选过滤条件并入 query string
        params.update({key: value for key, value in optional.items() if value is not None})
        return await self._request(
            "GET",
            "/api/internal/lost-found/lost-candidates",  # 后端报失候选接口
            "search_lost_items",  # 委派令牌声明的意图动作
            user_id,
            user_role,
            params=params,
        )

    async def get_item_detail(
        self, user_id: str, user_role: str, payload: GetItemDetailInput
    ) -> dict[str, Any]:
        """查询单条报告的完整详情。

        入参：
        - user_id / user_role: 用于签发委派令牌；
        - payload: 报告 id。

        返回：后端返回的报告详情字典。
        路径按 report_id 动态拼接（f-string 内嵌 id）。
        """
        return await self._request(
            "GET",
            # 按 report_id 拼接详情路径
            f"/api/internal/lost-found/reports/{payload.report_id}",
            "get_item_detail",  # 委派令牌声明的意图动作
            user_id,
            user_role,
        )

    async def claim_item(
        self, user_id: str, user_role: str, payload: ClaimItemInput
    ) -> dict[str, Any]:
        """对某条报告发起认领（claim）。

        入参：
        - user_id / user_role: 用于签发委派令牌；
        - payload: 目标报告 id 与认领凭证描述。

        返回：后端认领创建成功后的响应字典。
        这是写操作，会经过确认流程后才会真正执行。
        """
        return await self._request(
            "POST",
            # 认领子路径：/reports/{report_id}/claims
            f"/api/internal/lost-found/reports/{payload.report_id}/claims",
            "claim_item",  # 委派令牌声明的意图动作
            user_id,
            user_role,
            json={"proofDescription": payload.proof_description},  # 凭证描述（后端驼峰字段名）
        )

    async def _request(
        self,
        method: str,
        path: str,
        action: str,
        user_id: str,
        user_role: str,
        **kwargs: Any,
    ) -> dict[str, Any]:
        """执行一次带委派令牌的内部 API 请求，并统一错误映射。

        入参：
        - method: HTTP 方法（如 POST / GET）；
        - path: 相对路径（相对 base_url）；
        - action: 意图动作名，写入委派令牌的 intended_action；
        - user_id / user_role: 用于签发委派令牌；
        - kwargs: 其余传给 httpx 的关键字（json / params 等）。

        返回：后端返回的 JSON 字典。

        异常（BackendApiError）：
        - 504 CAMPUS_API_TIMEOUT：请求超时；
        - 503 CAMPUS_API_UNAVAILABLE：网络 / 传输层错误；
        - 后端返回错误状态码：透传其 code / message；
        - 502 INVALID_CAMPUS_API_RESPONSE：响应不是 JSON 对象。
        """
        # 1) 每次调用签发独立的一次性委派令牌（见 _delegation_token）
        token = self._delegation_token(user_id, user_role, action)
        try:
            # 2) 发起请求：把令牌放进 Authorization Bearer 头
            response = await self._client.request(
                method, path, headers={"Authorization": f"Bearer {token}"}, **kwargs
            )
        except httpx.TimeoutException as exc:
            # 3a) 超时：映射为 504（Bad Gateway 家族中的超时语义）
            raise BackendApiError(504, "CAMPUS_API_TIMEOUT", "Campus API 请求超时") from exc
        except httpx.HTTPError as exc:
            # 3b) 其他传输层错误（连接失败等）：映射为 503 服务暂不可用
            raise BackendApiError(503, "CAMPUS_API_UNAVAILABLE", "Campus API 暂时不可用") from exc

        if response.is_error:
            # 4a) 后端返回了错误状态码：尽量解析 JSON 错误体，解析失败则用空字典
            try:
                error = response.json()
            except ValueError:
                error = {}
            # 提取错误码：优先取后端的 code，缺失时退化为 CAMPUS_API_<状态码>
            code = str(error.get("code") or f"CAMPUS_API_{response.status_code}")
            # 提取提示文案：message 优先，其次 error，最后用通用拒绝文案
            message = str(error.get("message") or error.get("error") or "Campus API 拒绝了该操作")
            raise BackendApiError(response.status_code, code, message)
        # 4b) 成功响应：解析 JSON；必须为字典，否则视为异常响应
        data = response.json()
        if not isinstance(data, dict):
            raise BackendApiError(502, "INVALID_CAMPUS_API_RESPONSE", "Campus API 响应格式无效")
        return data

    def _delegation_token(self, user_id: str, user_role: str, action: str) -> str:
        """签发一个短期的、针对单次操作的委派令牌（JWT, HS256）。

        入参：
        - user_id / user_role: 要委托给后端的用户身份；
        - action: 允许后端执行的操作（intended_action），如 report_lost。

        返回：编码后的 JWT 字符串。

        设计要点：
        - iat / exp：30 秒后即过期，把令牌的暴露窗口压到最小；
        - jti：随机唯一 id，配合后端防重放；
        - aud / iss：受众与签发者，后端校验令牌归属。
        """
        now = datetime.now(UTC)  # 统一用 UTC 时间，避免时区导致过期判断不一致
        return jwt.encode(
            {
                "aud": "campus-api",  # 受众：仅 Campus API 接受
                "iss": "lost-found-agent",  # 签发者：本 Agent
                "sub": user_id,  # 主体：当前用户 id
                "role": user_role,  # 用户角色
                "iat": now,  # 签发时间
                "exp": now + timedelta(seconds=30),  # 过期时间：30 秒后
                "jti": str(uuid4()),  # 唯一 id，防重放
                "intended_action": action,  # 本令牌仅允许的动作
            },
            self._settings.agent_backend_shared_secret,  # 与后端共享的密钥
            algorithm="HS256",
        )
