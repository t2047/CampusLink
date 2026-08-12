from typing import Literal

from pydantic import BaseModel, Field, field_validator

EmbeddingSpace = Literal["semantic", "cross_modal"]


def default_embedding_spaces() -> list[EmbeddingSpace]:
    return ["semantic"]


class TextEmbeddingItem(BaseModel):
    id: str = Field(min_length=1, max_length=100)
    text: str = Field(min_length=1, max_length=4000)
    role: Literal["query", "document"] = "document"


class TextEmbeddingRequest(BaseModel):
    items: list[TextEmbeddingItem] = Field(min_length=1, max_length=100)
    spaces: list[EmbeddingSpace] = Field(default_factory=default_embedding_spaces)

    @field_validator("spaces")
    @classmethod
    def unique_non_empty_spaces(cls, value: list[EmbeddingSpace]) -> list[EmbeddingSpace]:
        if not value:
            raise ValueError("spaces must not be empty")
        return list(dict.fromkeys(value))


class EncodedVector(BaseModel):
    model: str
    revision: str
    dimension: int = Field(gt=0)
    encoding: Literal["float32-le-base64"] = "float32-le-base64"
    vector: str


class TextEmbeddingResult(BaseModel):
    id: str
    semantic: EncodedVector | None = None
    cross_modal: EncodedVector | None = None


class TextEmbeddingResponse(BaseModel):
    items: list[TextEmbeddingResult]
    cross_modal_available: bool


class ImageEmbeddingResult(BaseModel):
    filename: str
    embedding: EncodedVector


class ImageEmbeddingResponse(BaseModel):
    items: list[ImageEmbeddingResult]
