"""Request identity resolution for the CampusLink mail service.

Every ``/api/mail/**`` request must identify *which* CampusLink user it is
acting for, because Gmail credentials are now stored per user. Two token
formats are accepted:

1. **User JWT** (HS256, ``JWT_SECRET``) — the token the web frontend already
   sends as ``Authorization: Bearer <jwt>``. Its ``sub`` is the user's email
   (the Java backend signs ``sub(email)`` + ``role`` with HS256). This is the
   primary "website user" binding.
2. **Internal service token** (HS256, ``MAIL_INTERNAL_SECRET``, fallback
   ``AGENT_SHARED_SECRET``) — a short-lived token minted by the mail MCP
   gateway on behalf of the chat path, where the user JWT never leaves the
   chat backend. The backend-resolved email is used as the canonical binding;
   older tokens without it fall back to ``sub``. ``aud`` must be
   ``mail-service``.

The identity returned by :func:`resolve_identity` is used verbatim as the
``user_id`` key for Gmail token storage, caches and calendar events.
"""

from __future__ import annotations

import hashlib

import jwt

from . import config


class UnauthorizedError(Exception):
    """Raised when the request carries no valid identity token."""


# Audience the internal (MCP-gateway) tokens must carry.
INTERNAL_AUDIENCE = "mail-service"


def _user_jwt_key() -> bytes:
    """Derive the HS256 key exactly like the Java ``JwtTokenProvider``.

    Java builds a 32-byte array, copies up to 32 bytes of the UTF-8 secret and
    zero-pads the rest, then uses ``Keys.hmacShaKeyFor``. PyJWT must use the
    identical raw key bytes or verification fails.
    """
    raw = config.JWT_SECRET.encode("utf-8")
    return raw[:32].ljust(32, b"\x00")


def verify_user_jwt(token: str) -> str | None:
    """Verify a CampusLink user JWT; return its ``sub`` (email) or ``None``."""
    if not config.JWT_SECRET:
        return None
    try:
        claims = jwt.decode(token, _user_jwt_key(), algorithms=["HS256"])
    except jwt.PyJWTError:
        return None
    sub = claims.get("sub")
    return sub if isinstance(sub, str) and sub.strip() else None


def verify_internal_token(token: str) -> str | None:
    """Verify an internal MCP token; prefer its backend-resolved email binding."""
    if not config.MAIL_INTERNAL_SECRET:
        return None
    try:
        claims = jwt.decode(
            token,
            config.MAIL_INTERNAL_SECRET.encode("utf-8"),
            algorithms=["HS256"],
            audience=INTERNAL_AUDIENCE,
            options={"require": ["sub", "exp", "iat"]},
        )
    except jwt.PyJWTError:
        return None
    user_email = claims.get("user_email")
    if isinstance(user_email, str) and user_email.strip():
        return user_email.strip()
    sub = claims.get("sub")
    return sub if isinstance(sub, str) and sub.strip() else None


def resolve_identity(authorization: str | None) -> str:
    """Extract and verify the caller identity from the ``Authorization`` header.

    Returns the user id (email for user JWTs, ``sub`` for internal tokens).

    Raises:
        UnauthorizedError: missing header, malformed bearer, or an identity
            that verifies against neither the user-JWT key nor the internal key.
    """
    if not authorization or not authorization.lower().startswith("bearer "):
        raise UnauthorizedError("Missing or invalid Authorization")
    token = authorization[len("bearer "):].strip()
    if not token:
        raise UnauthorizedError("Missing or invalid Authorization")

    identity = verify_user_jwt(token)
    if identity is None:
        identity = verify_internal_token(token)
    if identity is None:
        raise UnauthorizedError("Invalid or expired token")
    return identity


def identity_digest(user_id: str) -> str:
    """Stable filesystem-safe digest of a user id (used for token file names)."""
    return hashlib.sha256(user_id.encode("utf-8")).hexdigest()
