"""Tests for request identity resolution (user JWT + internal MCP token).

Covers the two Bearer formats the mail service accepts, the Java-compatible
HS256 key derivation, and the 401 paths of ``resolve_identity``.
"""

from __future__ import annotations

import time

import jwt
import pytest

from mail_agent import auth, config

USER_SECRET = "test-user-secret-that-is-32-bytes-minimum!"
INTERNAL_SECRET = "test-internal-shared-secret"


@pytest.fixture(autouse=True)
def _secrets(monkeypatch):
    monkeypatch.setattr(config, "JWT_SECRET", USER_SECRET)
    monkeypatch.setattr(config, "MAIL_INTERNAL_SECRET", INTERNAL_SECRET)
    yield


def _user_key(secret: str) -> bytes:
    raw = secret.encode("utf-8")
    return raw[:32].ljust(32, b"\x00")


def _user_jwt(email: str = "stu@campuslink.test", secret: str = USER_SECRET) -> str:
    now = int(time.time())
    return jwt.encode(
        {"sub": email, "role": "STUDENT", "iat": now, "exp": now + 600},
        _user_key(secret),
        algorithm="HS256",
    )


def _internal_jwt(
    user_id: str = "42",
    secret: str = INTERNAL_SECRET,
    aud: str = "mail-service",
    user_email: str | None = None,
) -> str:
    now = int(time.time())
    claims = {"sub": user_id, "aud": aud, "iat": now, "exp": now + 60}
    if user_email:
        claims["user_email"] = user_email
    return jwt.encode(
        claims,
        secret.encode("utf-8"),
        algorithm="HS256",
    )


class TestVerifyUserJwt:
    def test_accepts_valid_jwt(self):
        assert auth.verify_user_jwt(_user_jwt()) == "stu@campuslink.test"

    def test_rejects_wrong_secret(self):
        token = _user_jwt(secret="a-completely-different-secret-value-0123456789")
        assert auth.verify_user_jwt(token) is None

    def test_rejects_garbage(self):
        assert auth.verify_user_jwt("not.a.jwt") is None

    def test_rejects_expired(self):
        now = int(time.time())
        token = jwt.encode(
            {"sub": "stu@campuslink.test", "iat": now - 120, "exp": now - 60},
            _user_key(USER_SECRET),
            algorithm="HS256",
        )
        assert auth.verify_user_jwt(token) is None

    def test_returns_none_when_secret_unset(self, monkeypatch):
        monkeypatch.setattr(config, "JWT_SECRET", "")
        assert auth.verify_user_jwt(_user_jwt()) is None

    def test_key_derivation_matches_java_truncation(self):
        # Java copies up to 32 bytes of the UTF-8 secret; longer secrets are
        # truncated. A token signed with the *raw* long secret must NOT verify,
        # while the truncated derivation must.
        long_secret = "x" * 64
        token = jwt.encode(
            {"sub": "a@b.c"},
            long_secret.encode("utf-8"),
            algorithm="HS256",
        )
        monkeypatch = pytest.MonkeyPatch()
        monkeypatch.setattr(config, "JWT_SECRET", long_secret)
        try:
            assert auth.verify_user_jwt(token) is None
        finally:
            monkeypatch.undo()


class TestVerifyInternalToken:
    def test_accepts_valid_token(self):
        assert auth.verify_internal_token(_internal_jwt()) == "42"

    def test_prefers_backend_resolved_email_binding(self):
        token = _internal_jwt(user_email="stu@campuslink.test")
        assert auth.verify_internal_token(token) == "stu@campuslink.test"

    def test_rejects_wrong_audience(self):
        assert auth.verify_internal_token(_internal_jwt(aud="other-service")) is None

    def test_rejects_wrong_secret(self):
        token = _internal_jwt(secret="some-other-shared-secret-value-123456")
        assert auth.verify_internal_token(token) is None

    def test_returns_none_when_secret_unset(self, monkeypatch):
        monkeypatch.setattr(config, "MAIL_INTERNAL_SECRET", "")
        assert auth.verify_internal_token(_internal_jwt()) is None


class TestResolveIdentity:
    def test_user_jwt_wins(self):
        token = _user_jwt("stu@campuslink.test")
        assert auth.resolve_identity(f"Bearer {token}") == "stu@campuslink.test"

    def test_internal_token_accepted(self):
        token = _internal_jwt("42")
        assert auth.resolve_identity(f"Bearer {token}") == "42"

    def test_missing_header(self):
        with pytest.raises(auth.UnauthorizedError):
            auth.resolve_identity(None)

    def test_non_bearer(self):
        with pytest.raises(auth.UnauthorizedError):
            auth.resolve_identity("Token abc")

    def test_bad_token(self):
        with pytest.raises(auth.UnauthorizedError):
            auth.resolve_identity("Bearer garbage")

    def test_empty_bearer(self):
        with pytest.raises(auth.UnauthorizedError):
            auth.resolve_identity("Bearer ")


class TestIdentityDigest:
    def test_stable_and_different(self):
        a = auth.identity_digest("a@b.c")
        b = auth.identity_digest("a@b.c")
        c = auth.identity_digest("x@y.z")
        assert a == b
        assert a != c
        assert len(a) == 64
