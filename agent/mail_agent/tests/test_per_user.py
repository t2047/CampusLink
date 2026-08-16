"""Tests for per-user Gmail token storage and per-user identity isolation.

Each CampusLink user's OAuth token is stored under a separate file keyed by
their verified identity; operations must never leak one user's credentials or
caches into another user's.
"""

from __future__ import annotations

import json

import pytest
from google.oauth2.credentials import Credentials

from mail_agent import config, gmail_service


class FakeCreds:
    """Minimal stand-in for google.oauth2.credentials.Credentials."""

    def __init__(self, email: str, valid: bool = True) -> None:
        self.email = email
        self.valid = valid
        self.expired = False
        self.refresh_token = "fake-refresh"

    def to_json(self) -> str:
        return json.dumps({"email": self.email})


@pytest.fixture(autouse=True)
def _token_dir(tmp_path, monkeypatch):
    monkeypatch.setattr(config, "GMAIL_TOKEN_DIR", tmp_path / "tokens")
    yield


@pytest.fixture(autouse=True)
def _fake_creds_loader(monkeypatch):
    """Load whatever was persisted (a FakeCreds) instead of a real Google token."""

    def fake_from_file(cls, path, scopes):
        data = json.loads(open(path, encoding="utf-8").read())
        return FakeCreds(data.get("email", "unknown"))

    monkeypatch.setattr(
        Credentials, "from_authorized_user_file", classmethod(fake_from_file)
    )


class TestPerUserTokenStorage:
    def test_save_and_load_are_isolated_per_user(self):
        gmail_service.save_credentials("a@campuslink.test", FakeCreds("a@campuslink.test"))
        assert gmail_service.load_credentials("a@campuslink.test") is not None
        # A different user has no token yet.
        assert gmail_service.load_credentials("b@campuslink.test") is None

    def test_token_path_is_stable_and_user_scoped(self):
        p1 = gmail_service._token_path("a@campuslink.test")
        p2 = gmail_service._token_path("a@campuslink.test")
        p3 = gmail_service._token_path("b@campuslink.test")
        assert p1 == p2
        assert p1 != p3
        assert p1.parent == config.GMAIL_TOKEN_DIR

    def test_reset_connection_removes_only_that_user(self):
        gmail_service.save_credentials("a@campuslink.test", FakeCreds("a@campuslink.test"))
        gmail_service.save_credentials("b@campuslink.test", FakeCreds("b@campuslink.test"))
        gmail_service.reset_connection("a@campuslink.test")
        assert gmail_service.load_credentials("a@campuslink.test") is None
        assert gmail_service.load_credentials("b@campuslink.test") is not None

    def test_connected_count(self):
        gmail_service.save_credentials("a@campuslink.test", FakeCreds("a@campuslink.test"))
        assert gmail_service.connected_user_count() == 1
        gmail_service.save_credentials("b@campuslink.test", FakeCreds("b@campuslink.test"))
        assert gmail_service.connected_user_count() == 2

    def test_not_connected_error_carries_user_id(self):
        err = gmail_service.GmailNotConnectedError("nope", "a@campuslink.test")
        assert err.user_id == "a@campuslink.test"

    def test_is_connected_false_without_token(self):
        assert gmail_service.is_connected("nobody@campuslink.test") is False
