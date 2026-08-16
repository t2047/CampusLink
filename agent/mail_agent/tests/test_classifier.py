"""Classifier tests: LLM first, ML fallback, ``other`` last.

The classification priority is the contract under test:
  1. LLM (DeepSeek via ``MAIL_LLM_*`` / ``DEEPSEEK_*``) when configured;
  2. the trained ML model;
  3. ``other``.

``MAIL_CLASSIFIER_MODE`` (auto | llm | ml) controls whether each tier runs.
"""

from __future__ import annotations

import pytest

from mail_agent import classifier, config


@pytest.fixture(autouse=True)
def _clean_classifier():
    classifier.reset()
    yield
    classifier.reset()


def _fake_ml(category: str = "career"):
    """A fake ML classifier returning a fixed category."""

    class FakeML:
        categories = list(classifier.CATEGORIES)

        def classify(self, **kwargs):
            return {"category": category, "confidence": 1.0, "scores": {}}

    return FakeML()


def _enable_llm(monkeypatch, llm_result=None, llm_error=None):
    """Configure the LLM path; stub the chat call itself."""
    monkeypatch.setattr(config, "MAIL_LLM_API_KEY", "test-key")
    if llm_error is not None:

        def fail(records):  # pragma: no cover - exercised via raise
            raise llm_error

        monkeypatch.setattr(classifier, "_classify_with_llm", fail)
    else:

        def fake_llm(records):
            result = {}
            for record in records:
                message_id = record["message_id"]
                if isinstance(llm_result, dict):
                    if message_id in llm_result:
                        result[message_id] = llm_result[message_id]
                else:
                    result[message_id] = llm_result or "finance"
            return result

        monkeypatch.setattr(classifier, "_classify_with_llm", fake_llm)


def _record(message_id: str, **overrides) -> dict:
    record = {
        "message_id": message_id,
        "subject": "Subject",
        "body": "Body text",
        "sender": "sender@example.com",
    }
    record.update(overrides)
    return record


class TestClassifySingle:
    def test_llm_first_when_configured(self, monkeypatch):
        _enable_llm(monkeypatch, llm_result={"m1": "finance"})
        ml_calls: list = []

        def fake_ml(*args, **kwargs):
            ml_calls.append(args)
            return "career"

        monkeypatch.setattr(classifier, "_classify_with_ml", fake_ml)
        assert classifier.classify("m1", "Tuition", "Payment due", "a@x.com") == "finance"
        assert ml_calls == []

    def test_llm_other_answer_is_respected(self, monkeypatch):
        _enable_llm(monkeypatch, llm_result={"m1": "other"})

        def fake_ml(*args, **kwargs):
            raise AssertionError("ML must not run when the LLM answered")

        monkeypatch.setattr(classifier, "_classify_with_ml", fake_ml)
        assert classifier.classify("m1", "Hello", "newsletter", "a@x.com") == "other"

    def test_llm_failure_falls_back_to_ml(self, monkeypatch):
        _enable_llm(monkeypatch, llm_error=RuntimeError("boom"))
        monkeypatch.setattr(classifier, "_ensure_classifier", lambda: _fake_ml("career"))
        assert classifier.classify("m1", "Intern", "Apply now", "a@x.com") == "career"

    def test_llm_failure_and_ml_unavailable_falls_back_to_other(self, monkeypatch):
        _enable_llm(monkeypatch, llm_error=RuntimeError("boom"))
        monkeypatch.setattr(classifier, "_ensure_classifier", lambda: None)
        assert classifier.classify("m1", "Hi", "hi", "a@x.com") == "other"

    def test_no_llm_key_uses_ml(self, monkeypatch):
        monkeypatch.setattr(config, "MAIL_LLM_API_KEY", "")

        def fail_llm(records):
            raise AssertionError("LLM must not run without a key")

        monkeypatch.setattr(classifier, "_classify_with_llm", fail_llm)
        monkeypatch.setattr(classifier, "_ensure_classifier", lambda: _fake_ml("finance"))
        assert classifier.classify("m1", "Invoice", "Pay", "b@x.com") == "finance"

    def test_ml_unknown_category_falls_back_to_other(self, monkeypatch):
        monkeypatch.setattr(config, "MAIL_LLM_API_KEY", "")

        class BadML:
            categories = list(classifier.CATEGORIES)

            def classify(self, **kwargs):
                return {"category": "mystery", "confidence": 1.0, "scores": {}}

        monkeypatch.setattr(classifier, "_ensure_classifier", lambda: BadML())
        assert classifier.classify("m1", "Hi", "hi", "a@x.com") == "other"

    def test_cache_avoids_second_llm_call(self, monkeypatch):
        monkeypatch.setattr(config, "MAIL_LLM_API_KEY", "test-key")
        calls: list[list[dict]] = []

        def fake_llm(records):
            calls.append(records)
            return {records[0]["message_id"]: "campus"}

        monkeypatch.setattr(classifier, "_classify_with_llm", fake_llm)
        assert classifier.classify("m1", "Exam", "Room", "a@x.com") == "campus"
        assert classifier.classify("m1", "Exam", "Room", "a@x.com") == "campus"
        assert len(calls) == 1

    def test_mode_ml_disables_llm(self, monkeypatch):
        monkeypatch.setenv("MAIL_CLASSIFIER_MODE", "ml")
        monkeypatch.setattr(config, "MAIL_LLM_API_KEY", "test-key")

        def fail_llm(records):
            raise AssertionError("LLM disabled in ml mode")

        monkeypatch.setattr(classifier, "_classify_with_llm", fail_llm)
        monkeypatch.setattr(classifier, "_ensure_classifier", lambda: _fake_ml("career"))
        assert classifier.classify("m1", "Career", "Job", "a@x.com") == "career"

    def test_mode_llm_skips_ml_on_failure(self, monkeypatch):
        monkeypatch.setenv("MAIL_CLASSIFIER_MODE", "llm")
        monkeypatch.setattr(config, "MAIL_LLM_API_KEY", "test-key")

        def fail_llm(records):
            raise RuntimeError("boom")

        monkeypatch.setattr(classifier, "_classify_with_llm", fail_llm)

        def fail_ml(*args, **kwargs):
            raise AssertionError("ML must not run in llm mode")

        monkeypatch.setattr(classifier, "_classify_with_ml", fail_ml)
        assert classifier.classify("m1", "Hi", "hi", "a@x.com") == "other"


class TestClassifyMany:
    def test_one_llm_call_for_whole_batch(self, monkeypatch):
        monkeypatch.setattr(config, "MAIL_LLM_API_KEY", "test-key")
        calls: list[int] = []

        def fake_llm(records):
            calls.append(len(records))
            return {record["message_id"]: "finance" for record in records}

        monkeypatch.setattr(classifier, "_classify_with_llm", fake_llm)
        records = [_record(f"m{i}") for i in range(3)]
        assert classifier.classify_many(records) == {
            "m0": "finance",
            "m1": "finance",
            "m2": "finance",
        }
        assert calls == [3]

    def test_ml_fills_llm_misses(self, monkeypatch):
        monkeypatch.setattr(config, "MAIL_LLM_API_KEY", "test-key")

        def fake_llm(records):
            return {"m0": "campus"}  # m1 / m2 left unanswered

        monkeypatch.setattr(classifier, "_classify_with_llm", fake_llm)
        by_id = {"m0": "campus", "m1": "career", "m2": "finance"}

        def fake_ml(message_id, subject, body, sender, sender_email=None):
            return by_id.get(message_id)

        monkeypatch.setattr(classifier, "_classify_with_ml", fake_ml)
        records = [_record(f"m{i}") for i in range(3)]
        assert classifier.classify_many(records) == {
            "m0": "campus",
            "m1": "career",
            "m2": "finance",
        }

    def test_llm_other_answers_are_not_overridden(self, monkeypatch):
        monkeypatch.setattr(config, "MAIL_LLM_API_KEY", "test-key")

        def fake_llm(records):
            return {"m0": "other"}  # a legitimate LLM answer

        monkeypatch.setattr(classifier, "_classify_with_llm", fake_llm)

        def fake_ml(*args, **kwargs):
            return "career"

        monkeypatch.setattr(classifier, "_classify_with_ml", fake_ml)
        records = [_record("m0"), _record("m1")]
        result = classifier.classify_many(records)
        assert result == {"m0": "other", "m1": "career"}

    def test_llm_failure_falls_back_all_to_ml(self, monkeypatch):
        monkeypatch.setattr(config, "MAIL_LLM_API_KEY", "test-key")

        def fail_llm(records):
            raise RuntimeError("boom")

        monkeypatch.setattr(classifier, "_classify_with_llm", fail_llm)
        monkeypatch.setattr(classifier, "_ensure_classifier", lambda: _fake_ml("career"))
        records = [_record(f"m{i}") for i in range(2)]
        assert classifier.classify_many(records) == {"m0": "career", "m1": "career"}

    def test_cached_records_skip_the_llm(self, monkeypatch):
        monkeypatch.setattr(config, "MAIL_LLM_API_KEY", "test-key")
        calls: list[list[dict]] = []

        def fake_llm(records):
            calls.append(records)
            return {record["message_id"]: "campus" for record in records}

        monkeypatch.setattr(classifier, "_classify_with_llm", fake_llm)
        assert classifier.classify("m0", "a", "b", "c") == "campus"  # caches m0
        result = classifier.classify_many([_record("m0"), _record("m1")])
        assert result == {"m0": "campus", "m1": "campus"}
        # Two LLM calls total: the single classify() above, then classify_many
        # sending only the uncached record (m1) to the LLM.
        assert len(calls) == 2
        assert calls[1] == [_record("m1")]

    def test_empty_input_returns_empty(self, monkeypatch):
        monkeypatch.setattr(config, "MAIL_LLM_API_KEY", "test-key")

        def fail_llm(records):
            raise AssertionError("LLM must not run for an empty batch")

        monkeypatch.setattr(classifier, "_classify_with_llm", fail_llm)
        assert classifier.classify_many([]) == {}

    def test_mode_ml_disables_llm(self, monkeypatch):
        monkeypatch.setenv("MAIL_CLASSIFIER_MODE", "ml")
        monkeypatch.setattr(config, "MAIL_LLM_API_KEY", "test-key")

        def fail_llm(records):
            raise AssertionError("LLM disabled in ml mode")

        monkeypatch.setattr(classifier, "_classify_with_llm", fail_llm)
        monkeypatch.setattr(classifier, "_ensure_classifier", lambda: _fake_ml("other"))
        assert classifier.classify_many([_record("m1")]) == {"m1": "other"}


class TestPromptParsing:
    def test_parse_llm_categories_fenced(self):
        text = '```json\n[{"email_index": 1, "category": "campus"}]\n```'
        assert classifier._parse_llm_categories(text) == [
            {"email_index": 1, "category": "campus"}
        ]

    def test_parse_llm_categories_rejects_non_array(self):
        with pytest.raises(ValueError):
            classifier._parse_llm_categories('{"category": "campus"}')

    def test_classify_with_llm_ignores_bad_entries(self, monkeypatch):
        monkeypatch.setattr(config, "MAIL_LLM_API_KEY", "test-key")
        monkeypatch.setattr(
            config, "MAIL_LLM_MODEL", "test-model"
        )
        monkeypatch.setattr(config, "MAIL_LLM_BASE_URL", "https://llm.test")

        class FakeResponse:
            content = (
                '[{"email_index": 1, "category": "campus"}, '
                '{"email_index": 2, "category": "NOT_A_CATEGORY"}, '
                '{"email_index": 3, "category": "finance"}]'
            )

        class FakeLLM:
            def invoke(self, prompt):
                return FakeResponse()

        import langchain_openai

        monkeypatch.setattr(
            langchain_openai, "ChatOpenAI", lambda **kwargs: FakeLLM()
        )
        records = [_record("m1"), _record("m2"), _record("m3")]
        assert classifier._classify_with_llm(records) == {
            "m1": "campus",
            "m3": "finance",
        }
