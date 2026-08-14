import base64
import io
import struct
from collections.abc import Sequence

from fastapi.testclient import TestClient
from PIL import Image

from lost_found_embedding.config import Settings
from lost_found_embedding.main import create_app
from lost_found_embedding.models import EmbeddingSpace, EncodedVector, TextEmbeddingItem


def vector(values: list[float], model: str = "fake") -> EncodedVector:
    payload = struct.pack(f"<{len(values)}f", *values)
    return EncodedVector(
        model=model,
        revision="test-revision",
        dimension=len(values),
        vector=base64.b64encode(payload).decode(),
    )


class FakeRuntime:
    cross_modal_available = True

    def ensure_loaded(self) -> None:
        return None

    def encode_text(
        self, items: list[TextEmbeddingItem], spaces: Sequence[EmbeddingSpace]
    ) -> list[dict[str, EncodedVector | None]]:
        return [
            {
                "semantic": vector([1.0, 0.0], "e5") if "semantic" in spaces else None,
                "cross_modal": vector([0.0, 1.0], "clip-text") if "cross_modal" in spaces else None,
            }
            for _item in items
        ]

    def encode_images(self, images: list[bytes]) -> list[EncodedVector]:
        return [vector([0.0, 1.0], "clip-image") for _image in images]


def client() -> TestClient:
    settings = Settings(shared_secret="test-secret-1234")
    return TestClient(create_app(settings, FakeRuntime()))


def client_with(settings: Settings) -> TestClient:
    return TestClient(create_app(settings, FakeRuntime()))


def png_bytes() -> bytes:
    output = io.BytesIO()
    Image.new("RGB", (8, 8), (0, 0, 255)).save(output, format="PNG")
    return output.getvalue()


def test_health_is_public_and_ready_reports_cross_modal() -> None:
    test_client = client()
    assert test_client.get("/health/live").json() == {"status": "UP"}
    assert test_client.get("/health/ready").json()["crossModalAvailable"] is True


def test_text_endpoint_requires_shared_key() -> None:
    response = client().post(
        "/v1/embed/text",
        json={"items": [{"id": "1", "text": "黑色耳机", "role": "query"}]},
    )
    assert response.status_code == 401

    forged = client().post(
        "/v1/embed/text",
        headers={"X-Embedding-Service-Key": "forged-secret-1234"},
        json={"items": [{"id": "1", "text": "黑色耳机", "role": "query"}]},
    )
    assert forged.status_code == 401


def test_text_endpoint_returns_both_spaces() -> None:
    response = client().post(
        "/v1/embed/text",
        headers={"X-Embedding-Service-Key": "test-secret-1234"},
        json={
            "items": [{"id": "q1", "text": "黑色耳机", "role": "query"}],
            "spaces": ["semantic", "cross_modal"],
        },
    )
    assert response.status_code == 200
    item = response.json()["items"][0]
    assert item["semantic"]["dimension"] == 2
    assert item["cross_modal"]["model"] == "clip-text"


def test_image_endpoint_validates_type_and_content() -> None:
    test_client = client()
    headers = {"X-Embedding-Service-Key": "test-secret-1234"}
    valid = test_client.post(
        "/v1/embed/images",
        headers=headers,
        files=[("images", ("item.png", png_bytes(), "image/png"))],
    )
    assert valid.status_code == 200
    assert valid.json()["items"][0]["embedding"]["model"] == "clip-image"

    invalid_type = test_client.post(
        "/v1/embed/images",
        headers=headers,
        files=[("images", ("item.gif", b"GIF89a", "image/gif"))],
    )
    assert invalid_type.status_code == 415

    invalid_image = test_client.post(
        "/v1/embed/images",
        headers=headers,
        files=[("images", ("item.png", b"not-an-image", "image/png"))],
    )
    assert invalid_image.status_code == 422


def test_image_endpoint_rejects_too_many_and_oversized_images() -> None:
    headers = {"X-Embedding-Service-Key": "test-secret-1234"}
    too_many = client().post(
        "/v1/embed/images",
        headers=headers,
        files=[("images", (f"item-{index}.png", png_bytes(), "image/png")) for index in range(6)],
    )
    assert too_many.status_code == 422

    large_output = io.BytesIO()
    Image.effect_noise((128, 128), 100).convert("RGB").save(large_output, format="PNG")
    oversized_client = client_with(Settings(shared_secret="test-secret-1234", max_image_bytes=1024))
    oversized = oversized_client.post(
        "/v1/embed/images",
        headers=headers,
        files=[("images", ("large.png", large_output.getvalue(), "image/png"))],
    )
    assert oversized.status_code == 413
