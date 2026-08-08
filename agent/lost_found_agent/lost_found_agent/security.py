"""Agent 入站安全验证：JWT、HMAC、时间窗口和 Nonce 防重放。"""

import hashlib
import hmac
import json
import threading
import time
from collections.abc import Callable
from typing import Any

import jwt
from fastapi import HTTPException, Request, status
from jwt import PyJWTError

from .config import Settings
from .models import VerifiedRequest


class NonceStore:
    def __init__(self, ttl_seconds: int, clock: Callable[[], float] = time.time) -> None:
        self._ttl_seconds = ttl_seconds
        self._clock = clock
        self._values: dict[str, float] = {}
        self._lock = threading.Lock()

    def consume(self, nonce: str) -> bool:
        now = self._clock()
        with self._lock:
            self._values = {
                key: created
                for key, created in self._values.items()
                if now - created < self._ttl_seconds
            }
            if nonce in self._values:
                return False
            self._values[nonce] = now
            return True


class AgentSecurity:
    def __init__(
        self,
        settings: Settings,
        nonce_store: NonceStore | None = None,
        clock: Callable[[], float] = time.time,
    ) -> None:
        self._settings = settings
        self._clock = clock
        self._nonces = nonce_store or NonceStore(settings.agent_nonce_ttl_seconds, clock)

    async def verify(self, request: Request, required_action: str) -> VerifiedRequest:
        body = await request.body()
        nonce = request.headers.get("X-Nonce", "").strip()
        timestamp_value = request.headers.get("X-Timestamp", "").strip()
        signature = request.headers.get("X-Signature", "").strip()
        authorization = request.headers.get("Authorization", "").strip()
        if not all((nonce, timestamp_value, signature, authorization)):
            raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="缺少安全请求头")

        try:
            timestamp = int(timestamp_value)
        except ValueError as exc:
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED, detail="时间戳格式无效"
            ) from exc
        if abs(int(self._clock()) - timestamp) > self._settings.agent_security_time_window_seconds:
            raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="请求已过期")

        expected = self.sign(body, nonce, timestamp)
        if not hmac.compare_digest(expected, signature):
            raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="请求签名无效")

        token = authorization.removeprefix("Bearer ").strip()
        if not token or authorization == token:
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED, detail="Bearer Token 无效"
            )
        claims = self._decode_token(token)
        if claims.get("jti") != nonce:
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED, detail="Token 与 Nonce 不匹配"
            )
        if claims.get("intended_action") != required_action:
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN, detail="Token 无权执行该操作"
            )
        if not self._nonces.consume(nonce):
            raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Nonce 已被使用")

        return VerifiedRequest(
            user_id=str(claims["sub"]),
            user_role=str(claims["role"]),
            intended_action=str(claims["intended_action"]),
            nonce=nonce,
            trace_id=request.headers.get("X-Trace-Id") or None,
            claims=claims,
        )

    def sign(self, body: bytes, nonce: str, timestamp: int) -> str:
        message = b":".join((body, nonce.encode(), str(timestamp).encode()))
        return hmac.new(
            self._settings.agent_shared_secret.encode(), message, hashlib.sha256
        ).hexdigest()

    def _decode_token(self, token: str) -> dict[str, Any]:
        try:
            claims: dict[str, Any] = jwt.decode(
                token,
                self._settings.agent_shared_secret,
                algorithms=["HS256"],
                audience=self._settings.agent_name,
                issuer="chat-core",
                options={
                    "require": [
                        "sub",
                        "role",
                        "aud",
                        "iss",
                        "iat",
                        "exp",
                        "jti",
                        "intended_action",
                    ]
                },
            )
            return claims
        except PyJWTError as exc:
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED, detail="Delegation Token 无效"
            ) from exc


def canonical_json(data: dict[str, Any]) -> bytes:
    return json.dumps(data, ensure_ascii=False, separators=(",", ":")).encode()
