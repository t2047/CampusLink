"""Shared identity helpers for mail service tests.

Mints the two Bearer formats the service accepts:
  * ``user_jwt(email)``     -- a CampusLink user JWT (HS256, JWT_SECRET).
  * ``internal_token(id)``  -- an internal MCP-gateway token (HS256,
    MAIL_INTERNAL_SECRET/AGENT_SHARED_SECRET, aud=mail-service).
"""

from __future__ import annotations

import time

import jwt

from mail_agent import config


def user_jwt(email: str, role: str = "STUDENT") -> str:
    """Mint a user JWT exactly as the Java backend would (HS256, sub=email)."""
    raw = config.JWT_SECRET.encode("utf-8")
    key = raw[:32].ljust(32, b"\x00")
    now = int(time.time())
    return jwt.encode(
        {"sub": email, "role": role, "iat": now, "exp": now + 3600},
        key,
        algorithm="HS256",
    )


def internal_token(user_id: str) -> str:
    """Mint the internal token the mail MCP gateway forwards."""
    secret = config.MAIL_INTERNAL_SECRET.encode("utf-8")
    now = int(time.time())
    return jwt.encode(
        {
            "sub": user_id,
            "aud": "mail-service",
            "iat": now,
            "exp": now + 60,
        },
        secret,
        algorithm="HS256",
    )


def auth_header(value: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {value}"}
