"""Agent 配置模块：集中声明失物招领 Agent 的全部运行参数。

本模块是失物招领 Agent 的"配置中枢"。所有可调参数（安全密钥、限流阈值、
Embedding 服务、LLM 模型、匹配打分校准值等）都以 pydantic-settings 的字段形式
集中在 Settings 类中，既支持通过环境变量 / .env 文件注入，又带有默认值与取值
范围约束（Field 的 ge/le/min_length），确保在启动阶段就对非法配置快速失败，
而不是在运行时才暴露问题。模块还负责把 "auto" 运行模式解析为实际生效的
rules / llm，并提供全局唯一（lru_cache 单例）的 Settings 实例供依赖注入复用。
"""

# --- 标准库导入 ---
from functools import lru_cache  # 结果缓存装饰器：用于 get_settings 实现进程内单例
from typing import Literal  # 声明"仅允许取枚举集合中某个值"的字面量类型

# --- 第三方导入 ---
from pydantic import Field, model_validator
# Field: 为配置字段声明默认值 + 取值范围/长度约束；model_validator: 模型校验完成后的钩子
from pydantic_settings import BaseSettings, SettingsConfigDict
# BaseSettings: 支持从环境变量 / .env 读取字段的配置基类；SettingsConfigDict: 模型级配置对象


class Settings(BaseSettings):
    """失物招领 Agent 的全部运行配置。所有配置均可由环境变量注入，密钥不会写入日志。

    继承 pydantic-settings 的 BaseSettings：字段定义即为唯一事实来源，取值优先级为
    "构造时显式传入参数 > 环境变量 > .env 文件 > 字段默认值"。
    配置大体分为四类：
    - 标识与运行模式：agent_name / agent_version / lost_found_agent_mode；
    - 安全配置：入站签名密钥、委托令牌密钥、确认令牌密钥、时间窗口、nonce TTL；
    - 服务与限流参数：campus_api_url、限流阈值、事件 TTL；
    - 匹配能力参数：Embedding 服务、打分校准区间、LLM 模型接入。
    模块生命周期：由 get_settings() 在进程内构造一次并缓存复用。
    """

    # SettingsConfigDict：从项目根目录 .env 文件读取配置；extra="ignore" 忽略环境中
    # 与本模型无关的变量，避免多余环境变量导致字段解析报错。
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    # --- 标识与运行模式 ---
    # Agent 服务名与版本号，会写入健康检查、能力描述等对外响应。
    agent_name: str = "lost-found-agent"
    agent_version: str = "0.7.0"
    # 运行模式：auto 按是否配置 LLM key 自动选择；rules 强制规则引擎；llm 强制 LLM 意图识别。
    lost_found_agent_mode: Literal["auto", "rules", "llm"] = "auto"

    # --- 安全配置 ---
    # 入站请求签名与委托令牌(JWT)共享密钥，最少 32 字符，Web 端与本服务持有同一份。
    agent_shared_secret: str = Field(min_length=32)
    # Agent 与 Campus 后端(Spring Boot)内部 API 之间的共享密钥，供后端校验 Agent 回写。
    agent_backend_shared_secret: str = Field(min_length=32)
    # 生成/校验失物招领确认令牌(confirmation)所用的共享密钥。
    lost_found_confirmation_secret: str = Field(min_length=32)
    # Campus 后端内部 API 基础地址，默认本机 8080（开发期）。
    campus_api_url: str = "http://localhost:8080"
    # 入站请求时间戳允许的最大偏差（秒）：超窗的请求一律判为过期/重放，直接拒绝。
    agent_security_time_window_seconds: int = Field(default=30, ge=5, le=300)
    # 防重放 nonce 存活时长（秒）：同一 nonce 在该窗口内只允许成功消费一次。
    agent_nonce_ttl_seconds: int = Field(default=60, ge=30, le=600)
    # SSE 事件存储保留时长（秒）：/agent/stream 只能拉到该窗口内产生的事件。
    agent_event_ttl_seconds: int = Field(default=300, ge=30, le=3600)
    # 每用户每分钟最大请求数（限流），防止异常/恶意调用打爆后端。
    agent_rate_limit_per_minute: int = Field(default=5, ge=1, le=1000)
    # 每个会话累计最大请求数（限流），约束单次对话的调用总量。
    agent_rate_limit_per_session: int = Field(default=20, ge=1, le=10000)
    # 匹配判定最低分数（0~1）：低于该分的候选一律视为不匹配。
    lost_found_match_min_score: float = Field(default=0.35, ge=0, le=1)
    # --- Embedding（以图搜物 / 图文匹配）配置 ---
    # 嵌入计算模式：pretrained 调用独立嵌入服务；baseline 退化为基础文本匹配；auto 自动选择。
    lost_found_embedding_mode: Literal["auto", "pretrained", "baseline"] = "auto"
    # 嵌入服务地址与共享密钥；key 为空串表示未启用鉴权。
    lost_found_embedding_url: str = "http://localhost:8091"
    lost_found_embedding_shared_secret: str = ""
    # 调用嵌入服务的超时上限（秒），避免外呼拖垮整条请求链路。
    lost_found_embedding_timeout_seconds: float = Field(default=8, ge=1, le=60)
    # 文本 / 图像 / 跨模态相似度打分的线性校准区间（min~max）：把原始相似度线性
    # 映射到可比分数带，min/max 仅作缩放边界（允许 min>max 做反向缩放）。
    lost_found_text_calibration_min: float = Field(default=0.65, ge=-1, le=1)
    lost_found_text_calibration_max: float = Field(default=0.95, ge=-1, le=1)
    lost_found_image_calibration_min: float = Field(default=0.50, ge=-1, le=1)
    lost_found_image_calibration_max: float = Field(default=0.95, ge=-1, le=1)
    lost_found_cross_modal_calibration_min: float = Field(default=0.15, ge=-1, le=1)
    lost_found_cross_modal_calibration_max: float = Field(default=0.40, ge=-1, le=1)
    # --- LLM（意图识别 / 分类兜底）配置 ---
    # LLM API key：为空串表示未配置（此时 LLM 相关能力不可用）。
    lost_found_llm_api_key: str = ""
    # LLM 服务 base_url 与模型名（默认 DeepSeek）。
    lost_found_llm_base_url: str = "https://api.deepseek.com"
    lost_found_llm_model: str = "deepseek-v4-flash"
    # 必须短于 Web Agent 请求的 25 秒超时，才能在模型故障时及时降级。
    lost_found_llm_timeout_seconds: float = Field(default=15, ge=1, le=120)
    # 单次调用最大生成 token（推理模型需预留思考空间，1200 会被
    # reasoning_content 耗尽导致 content 为空；默认 4000 可调）
    lost_found_llm_max_tokens: int = Field(default=4000, ge=256, le=8192)
    # 估算批量评估费用的单价（美元/百万 token），默认 0 表示未配置单价
    lost_found_llm_input_cost_per_1m: float = Field(default=0, ge=0)
    lost_found_llm_output_cost_per_1m: float = Field(default=0, ge=0)
    # 模型只做意图识别；默认故障降级到受限规则引擎，不会绕过确认或工具白名单。
    # fail_closed（关闭即失败）：置 True 时，LLM 不可用/输出不可信会直接显式失败
    # （fail-closed），而非降级到规则引擎；False（默认，fail-open）则降级保证可用性。
    llm_fail_closed: bool = False

    @model_validator(mode="after")
    def validate_llm_mode(self) -> "Settings":
        # mode="after"：在所有字段完成校验/赋值后才执行，此时可安全读取 self 各字段。
        # 强制约束：显式指定 mode=llm 时必须配置 LLM key，否则启动即报错，
        # 防止"看似 llm 模式、实际没有模型可调"的静默降级。
        if self.lost_found_agent_mode == "llm" and not self.lost_found_llm_api_key.strip():
            raise ValueError("LOST_FOUND_LLM_API_KEY is required when mode=llm")
        return self

    @property
    def effective_mode(self) -> Literal["rules", "llm"]:
        """把可能为 "auto" 的配置解析为实际生效的模式（rules / llm）。

        返回规则：rules / llm 直接透传；auto 则看是否配置了非空 LLM key——
        有 key 走 llm，否则走 rules。该属性被 main.py、健康检查等广泛使用。
        """
        if self.lost_found_agent_mode == "rules":
            return "rules"
        if self.lost_found_agent_mode == "llm":
            return "llm"
        return "llm" if self.lost_found_llm_api_key.strip() else "rules"


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    """返回进程内唯一的 Settings 实例（单例）。

    供 FastAPI 依赖注入、路由与安全校验等多处复用，避免每次请求都重新解析
    .env / 环境变量。lru_cache(maxsize=1) 保证整个进程生命周期内只构造一次。
    """
    return Settings()
