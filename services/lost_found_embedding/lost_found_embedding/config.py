from typing import Literal

from pydantic import AliasChoices, Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env",
        env_prefix="LOST_FOUND_EMBEDDING_",
        extra="ignore",
    )

    # 空值允许进程启动并暴露 liveness，但所有 embed 请求都会被拒绝。
    shared_secret: str = ""
    device: str = "cpu"
    text_model: str = "intfloat/multilingual-e5-small"
    text_revision: str = "614241f622f53c4eeff9890bdc4f31cfecc418b3"
    image_model: str = "sentence-transformers/clip-ViT-B-32"
    image_revision: str = "327ab6726d33c0e22f920c83f2ff9e4bd38ca37f"
    cross_modal_model: str = "sentence-transformers/clip-ViT-B-32-multilingual-v1"
    cross_modal_revision: str = "58edf8cada9e398793dca955574a48cbb7f18be2"
    cross_modal_enabled: Literal["auto", "on", "off"] = Field(
        default="auto",
        validation_alias=AliasChoices("cross_modal_enabled", "LOST_FOUND_CROSS_MODAL_ENABLED"),
    )
    max_texts: int = Field(default=100, ge=1, le=100)
    max_text_length: int = Field(default=4000, ge=100, le=10000)
    max_images: int = Field(default=5, ge=1, le=5)
    max_image_bytes: int = Field(default=10 * 1024 * 1024, ge=1024)
