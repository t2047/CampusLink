"""Agent 配置入口。"""

from functools import lru_cache
from typing import Literal

from pydantic import Field, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """所有配置均可由环境变量注入，密钥不会写入日志。"""

    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    agent_name: str = "lost-found-agent"
    agent_version: str = "0.4.0"
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
    lost_found_llm_api_key: str = ""
    lost_found_llm_base_url: str = "https://api.deepseek.com"
    lost_found_llm_model: str = "deepseek-v4-flash"
    lost_found_llm_timeout_seconds: float = Field(default=10, ge=1, le=60)

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
