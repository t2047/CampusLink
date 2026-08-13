"""Agent 配置入口。"""

from functools import lru_cache
from typing import Literal

from pydantic import Field, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """所有配置均可由环境变量注入，密钥不会写入日志。"""

    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    agent_name: str = "lost-found-agent"
    agent_version: str = "0.7.0"
    lost_found_agent_mode: Literal["auto", "rules", "llm"] = "auto"
    agent_shared_secret: str = Field(min_length=32)
    agent_backend_shared_secret: str = Field(min_length=32)
    lost_found_confirmation_secret: str = Field(min_length=32)
    campus_api_url: str = "http://localhost:8080"
    agent_security_time_window_seconds: int = Field(default=30, ge=5, le=300)
    agent_nonce_ttl_seconds: int = Field(default=60, ge=30, le=600)
    agent_event_ttl_seconds: int = Field(default=300, ge=30, le=3600)
    agent_rate_limit_per_minute: int = Field(default=5, ge=1, le=1000)
    agent_rate_limit_per_session: int = Field(default=20, ge=1, le=10000)
    lost_found_match_min_score: float = Field(default=0.35, ge=0, le=1)
    lost_found_embedding_mode: Literal["auto", "pretrained", "baseline"] = "auto"
    lost_found_embedding_url: str = "http://localhost:8091"
    lost_found_embedding_shared_secret: str = ""
    lost_found_embedding_timeout_seconds: float = Field(default=8, ge=1, le=60)
    lost_found_text_calibration_min: float = Field(default=0.65, ge=-1, le=1)
    lost_found_text_calibration_max: float = Field(default=0.95, ge=-1, le=1)
    lost_found_image_calibration_min: float = Field(default=0.50, ge=-1, le=1)
    lost_found_image_calibration_max: float = Field(default=0.95, ge=-1, le=1)
    lost_found_cross_modal_calibration_min: float = Field(default=0.15, ge=-1, le=1)
    lost_found_cross_modal_calibration_max: float = Field(default=0.40, ge=-1, le=1)
    lost_found_llm_api_key: str = ""
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
    llm_fail_closed: bool = False

    @model_validator(mode="after")
    def validate_llm_mode(self) -> "Settings":
        if self.lost_found_agent_mode == "llm" and not self.lost_found_llm_api_key.strip():
            raise ValueError("LOST_FOUND_LLM_API_KEY is required when mode=llm")
        return self

    @property
    def effective_mode(self) -> Literal["rules", "llm"]:
        if self.lost_found_agent_mode == "rules":
            return "rules"
        if self.lost_found_agent_mode == "llm":
            return "llm"
        return "llm" if self.lost_found_llm_api_key.strip() else "rules"


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    return Settings()
