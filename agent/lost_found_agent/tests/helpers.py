import hashlib
import hmac
import io
import json
import time
import uuid
from typing import Any

import jwt
from PIL import Image

from lost_found_agent.config import Settings


def make_solid_png(rgb: tuple[int, int, int], size: int = 16) -> bytes:
    image = Image.new("RGB", (size, size), rgb)
    buffer = io.BytesIO()
    image.save(buffer, format="PNG")
    return buffer.getvalue()


def signed_request(
    settings: Settings,
    payload: dict[str, Any] | None,
    *,
    action: str = "invoke",
    user_id: str = "42",
    nonce: str | None = None,
    timestamp: int | None = None,
) -> tuple[bytes, dict[str, str]]:
    active_nonce = nonce or str(uuid.uuid4())
    active_timestamp = timestamp or int(time.time())
    body = (
        json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode()
        if payload is not None
        else b""
    )
    token = jwt.encode(
        {
            "sub": user_id,
            "role": "STUDENT",
            "aud": settings.agent_name,
            "iss": "chat-core",
            "iat": active_timestamp,
            "exp": active_timestamp + 30,
            "jti": active_nonce,
            "intended_action": action,
        },
        settings.agent_shared_secret,
        algorithm="HS256",
    )
    message = b":".join((body, active_nonce.encode(), str(active_timestamp).encode()))
    signature = hmac.new(settings.agent_shared_secret.encode(), message, hashlib.sha256).hexdigest()
    return body, {
        "Authorization": f"Bearer {token}",
        "Content-Type": "application/json",
        "X-Nonce": active_nonce,
        "X-Timestamp": str(active_timestamp),
        "X-Signature": signature,
        "X-Trace-Id": "trace-test",
    }
