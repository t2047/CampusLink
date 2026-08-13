import hmac
import io
from typing import Annotated

from fastapi import Depends, FastAPI, File, Header, HTTPException, UploadFile, status
from PIL import Image, UnidentifiedImageError

from .config import Settings
from .models import (
    ImageEmbeddingResponse,
    ImageEmbeddingResult,
    TextEmbeddingRequest,
    TextEmbeddingResponse,
    TextEmbeddingResult,
)
from .runtime import EmbeddingRuntime, SentenceTransformerRuntime

ALLOWED_IMAGE_TYPES = {"image/jpeg", "image/png", "image/webp"}


def create_app(
    settings: Settings | None = None,
    runtime: EmbeddingRuntime | None = None,
) -> FastAPI:
    active_settings = settings or Settings()
    active_runtime = runtime or SentenceTransformerRuntime(active_settings)
    app = FastAPI(title="CampusLink Lost & Found Embedding", version="0.1.0")

    def authenticate(
        x_embedding_service_key: str = Header(default=""),
    ) -> None:
        if len(active_settings.shared_secret) < 16 or not hmac.compare_digest(
            x_embedding_service_key.encode(), active_settings.shared_secret.encode()
        ):
            raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="invalid key")

    @app.get("/health/live")
    def live() -> dict[str, str]:
        return {"status": "UP"}

    @app.get("/health/ready")
    def ready() -> dict[str, str | bool]:
        try:
            active_runtime.ensure_loaded()
        except Exception as exc:
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                detail="pretrained models are not ready",
            ) from exc
        return {
            "status": "UP",
            "crossModalAvailable": active_runtime.cross_modal_available,
        }

    @app.post(
        "/v1/embed/text",
        response_model=TextEmbeddingResponse,
        dependencies=[Depends(authenticate)],
    )
    def embed_text(payload: TextEmbeddingRequest) -> TextEmbeddingResponse:
        if len(payload.items) > active_settings.max_texts:
            raise HTTPException(status_code=422, detail="too many text items")
        if any(len(item.text) > active_settings.max_text_length for item in payload.items):
            raise HTTPException(status_code=422, detail="text item is too long")
        try:
            vectors = active_runtime.encode_text(payload.items, payload.spaces)
        except Exception as exc:
            raise HTTPException(status_code=503, detail="embedding model unavailable") from exc
        return TextEmbeddingResponse(
            items=[
                TextEmbeddingResult(id=item.id, **vectors[index])
                for index, item in enumerate(payload.items)
            ],
            cross_modal_available=active_runtime.cross_modal_available,
        )

    @app.post(
        "/v1/embed/images",
        response_model=ImageEmbeddingResponse,
        dependencies=[Depends(authenticate)],
    )
    async def embed_images(
        images: Annotated[list[UploadFile], File(min_length=1, max_length=5)],
    ) -> ImageEmbeddingResponse:
        if len(images) > active_settings.max_images:
            raise HTTPException(status_code=422, detail="too many images")
        payloads: list[bytes] = []
        names: list[str] = []
        for image in images:
            if image.content_type not in ALLOWED_IMAGE_TYPES:
                raise HTTPException(status_code=415, detail="unsupported image type")
            payload = await image.read(active_settings.max_image_bytes + 1)
            if len(payload) > active_settings.max_image_bytes:
                raise HTTPException(status_code=413, detail="image is too large")
            validate_image(payload, image.content_type)
            payloads.append(payload)
            names.append(image.filename or "image")
        try:
            vectors = active_runtime.encode_images(payloads)
        except Exception as exc:
            raise HTTPException(status_code=503, detail="embedding model unavailable") from exc
        return ImageEmbeddingResponse(
            items=[
                ImageEmbeddingResult(filename=name, embedding=vectors[index])
                for index, name in enumerate(names)
            ]
        )

    return app


def validate_image(payload: bytes, content_type: str) -> None:
    try:
        with Image.open(io.BytesIO(payload)) as image:
            expected = {"image/jpeg": "JPEG", "image/png": "PNG", "image/webp": "WEBP"}
            if image.format != expected[content_type]:
                raise HTTPException(status_code=415, detail="image bytes do not match MIME type")
            if image.width > 8192 or image.height > 8192 or image.width * image.height > 40_000_000:
                raise HTTPException(status_code=422, detail="image dimensions are too large")
            image.verify()
    except HTTPException:
        raise
    except (UnidentifiedImageError, OSError, Image.DecompressionBombError) as exc:
        raise HTTPException(status_code=422, detail="invalid image") from exc


app = create_app()
