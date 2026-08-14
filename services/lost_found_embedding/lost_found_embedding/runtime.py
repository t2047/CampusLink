import base64
import io
import struct
import threading
from collections.abc import Sequence
from dataclasses import dataclass
from typing import Protocol

from PIL import Image

from .config import Settings
from .models import EmbeddingSpace, EncodedVector, TextEmbeddingItem


def encode_float32(vector: list[float]) -> str:
    payload = struct.pack(f"<{len(vector)}f", *vector)
    return base64.b64encode(payload).decode("ascii")


class EmbeddingRuntime(Protocol):
    @property
    def cross_modal_available(self) -> bool: ...

    def ensure_loaded(self) -> None: ...

    def encode_text(
        self, items: list[TextEmbeddingItem], spaces: Sequence[EmbeddingSpace]
    ) -> list[dict[str, EncodedVector | None]]: ...

    def encode_images(self, images: list[bytes]) -> list[EncodedVector]: ...


@dataclass(frozen=True)
class ModelDescriptor:
    name: str
    revision: str


class SentenceTransformerRuntime:
    """延迟加载固定 revision 的预训练模型，避免服务未启用时占用内存。"""

    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        self._semantic: object | None = None
        self._image: object | None = None
        self._cross_modal: object | None = None
        self._cross_modal_failed = False
        self._load_lock = threading.Lock()

    @property
    def cross_modal_available(self) -> bool:
        return self._cross_modal is not None

    def ensure_loaded(self) -> None:
        with self._load_lock:
            self._ensure_loaded()

    def _ensure_loaded(self) -> None:
        if self._semantic is None or self._image is None:
            from sentence_transformers import SentenceTransformer

            self._semantic = SentenceTransformer(
                self.settings.text_model,
                revision=self.settings.text_revision,
                device=self.settings.device,
                trust_remote_code=False,
                model_kwargs={"use_safetensors": True},
            )
            self._image = SentenceTransformer(
                self.settings.image_model,
                revision=self.settings.image_revision,
                device=self.settings.device,
                trust_remote_code=False,
                model_kwargs={"use_safetensors": True},
            )
        if self.settings.cross_modal_enabled == "off" or self._cross_modal_failed:
            return
        if self._cross_modal is None:
            try:
                from sentence_transformers import SentenceTransformer

                self._cross_modal = SentenceTransformer(
                    self.settings.cross_modal_model,
                    revision=self.settings.cross_modal_revision,
                    device=self.settings.device,
                    trust_remote_code=False,
                    model_kwargs={"use_safetensors": True},
                )
            except Exception:
                self._cross_modal_failed = True
                if self.settings.cross_modal_enabled == "on":
                    raise

    def encode_text(
        self, items: list[TextEmbeddingItem], spaces: Sequence[EmbeddingSpace]
    ) -> list[dict[str, EncodedVector | None]]:
        self.ensure_loaded()
        semantic_vectors: list[list[float]] | None = None
        cross_vectors: list[list[float]] | None = None
        if "semantic" in spaces:
            if self._semantic is None:
                raise RuntimeError("semantic model is not loaded")
            prefixed = [
                f"{'query' if item.role == 'query' else 'passage'}: {item.text}" for item in items
            ]
            semantic_vectors = self._encode(self._semantic, prefixed)
        if "cross_modal" in spaces and self._cross_modal is not None:
            cross_vectors = self._encode(self._cross_modal, [item.text for item in items])

        results: list[dict[str, EncodedVector | None]] = []
        for index, _item in enumerate(items):
            results.append(
                {
                    "semantic": self._vector(
                        semantic_vectors[index],
                        ModelDescriptor(self.settings.text_model, self.settings.text_revision),
                    )
                    if semantic_vectors is not None
                    else None,
                    "cross_modal": self._vector(
                        cross_vectors[index],
                        ModelDescriptor(
                            self.settings.cross_modal_model,
                            self.settings.cross_modal_revision,
                        ),
                    )
                    if cross_vectors is not None
                    else None,
                }
            )
        return results

    def encode_images(self, images: list[bytes]) -> list[EncodedVector]:
        self.ensure_loaded()
        if self._image is None:
            raise RuntimeError("image model is not loaded")
        opened: list[Image.Image] = []
        try:
            for payload in images:
                source_image = Image.open(io.BytesIO(payload))
                source_image.load()
                converted_image: Image.Image = source_image.convert("RGB")
                opened.append(converted_image)
                source_image.close()
            vectors = self._encode(self._image, opened)
            descriptor = ModelDescriptor(self.settings.image_model, self.settings.image_revision)
            return [self._vector(vector, descriptor) for vector in vectors]
        finally:
            for image in opened:
                image.close()

    @staticmethod
    def _encode(model: object, values: Sequence[object]) -> list[list[float]]:
        encoded = model.encode(values, normalize_embeddings=True, convert_to_numpy=True)  # type: ignore[attr-defined]
        return [[float(component) for component in row] for row in encoded]

    @staticmethod
    def _vector(vector: list[float], descriptor: ModelDescriptor) -> EncodedVector:
        return EncodedVector(
            model=descriptor.name,
            revision=descriptor.revision,
            dimension=len(vector),
            vector=encode_float32(vector),
        )
