from __future__ import annotations

import sys
import time
import uuid
from pathlib import Path
from types import SimpleNamespace

import jwt
import pytest
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa

_AGENT_ROOT = Path(__file__).resolve().parents[1]
if str(_AGENT_ROOT) not in sys.path:
    sys.path.insert(0, str(_AGENT_ROOT))

from mcp_servers import security
from mcp_servers.security import TokenVerifier

PRIVATE_KEY = rsa.generate_private_key(public_exponent=65537, key_size=2048)
OTHER_PRIVATE_KEY = rsa.generate_private_key(public_exponent=65537, key_size=2048)
PUBLIC_PEM = PRIVATE_KEY.public_key().public_bytes(
    serialization.Encoding.PEM,
    serialization.PublicFormat.SubjectPublicKeyInfo,
)
JWKS_URL = "http://test.local/.well-known/jwks.json"


class FakeJWKSClient:
    def __init__(self, url: str):
        self.url = url

    def get_signing_key_from_jwt(self, _token: str):
        return SimpleNamespace(key=PUBLIC_PEM)


def make_token(*, overrides=None, omit=(), key=PRIVATE_KEY, algorithm="RS256"):
    now = int(time.time())
    claims = {
        "sub": "1",
        "role": "STUDENT",
        "aud": ["facility-agent"],
        "iss": "token-service",
        "iat": now,
        "exp": now + 30,
        "intended_action": "invoke",
        "jti": str(uuid.uuid4()),
    }
    claims.update(overrides or {})
    for claim in omit:
        claims.pop(claim, None)
    signing_key = key if algorithm == "RS256" else "not-a-production-secret"
    return jwt.encode(
        claims,
        signing_key,
        algorithm=algorithm,
        headers={"kid": "test-key"},
    )


@pytest.fixture(autouse=True)
def configure_verifier(monkeypatch):
    monkeypatch.setenv("TOKEN_SERVICE_JWKS_URL", JWKS_URL)
    monkeypatch.setattr(security.jwt, "PyJWKClient", FakeJWKSClient)
    security._VERIFIERS.clear()


def verify(token: str):
    return TokenVerifier("facility-agent").verify_token(token)


def test_valid_facilities_delegation_token_is_accepted():
    claims = verify(make_token())

    assert claims["sub"] == "1"
    assert claims["role"] == "STUDENT"


def test_audience_list_containing_facility_agent_is_accepted():
    claims = verify(make_token(overrides={"aud": ["mail-agent", "facility-agent"]}))

    assert "facility-agent" in claims["aud"]


def test_wrong_signature_is_rejected():
    with pytest.raises(ValueError, match="invalid delegation token"):
        verify(make_token(key=OTHER_PRIVATE_KEY))


def test_wrong_issuer_is_rejected():
    with pytest.raises(ValueError):
        verify(make_token(overrides={"iss": "other-service"}))


def test_wrong_audience_is_rejected():
    with pytest.raises(ValueError, match="not 'facility-agent'"):
        verify(make_token(overrides={"aud": ["mail-agent"]}))


def test_wrong_intended_action_is_rejected():
    with pytest.raises(ValueError, match="action"):
        verify(make_token(overrides={"intended_action": "admin"}))


def test_expired_token_is_rejected():
    with pytest.raises(ValueError):
        verify(make_token(overrides={"exp": int(time.time()) - 1}))


def test_invalid_role_is_rejected():
    with pytest.raises(ValueError, match="role"):
        verify(make_token(overrides={"role": "STAFF"}))


@pytest.mark.parametrize("subject", ["user-1", "0", "-1", ""])
def test_subject_must_be_a_positive_numeric_id(subject):
    with pytest.raises(ValueError, match="positive numeric ID"):
        verify(make_token(overrides={"sub": subject}))


def test_missing_iat_is_rejected():
    with pytest.raises(ValueError):
        verify(make_token(omit={"iat"}))


def test_missing_jti_is_rejected():
    with pytest.raises(ValueError):
        verify(make_token(omit={"jti"}))


def test_blank_jti_is_rejected():
    with pytest.raises(ValueError, match="jti"):
        verify(make_token(overrides={"jti": " "}))


def test_malformed_token_is_rejected():
    with pytest.raises(ValueError, match="invalid delegation token"):
        verify("not-a-jwt")


def test_hs256_token_is_rejected():
    with pytest.raises(ValueError, match="invalid delegation token"):
        verify(make_token(algorithm="HS256"))
