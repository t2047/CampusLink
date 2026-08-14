"""独立 Embedding 服务客户端；失败时返回原查询，由 matching.py 自动降级。"""

from typing import Any

import httpx

from .config import Settings


class PretrainedEmbeddingClient:
    def __init__(
        self,
        settings: Settings,
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        self._settings = settings
        self._enabled = (
            settings.lost_found_embedding_mode != "baseline"
            and len(settings.lost_found_embedding_shared_secret) >= 16
        )
        self._client = httpx.AsyncClient(
            base_url=settings.lost_found_embedding_url.rstrip("/"),
            timeout=settings.lost_found_embedding_timeout_seconds,
            transport=transport,
        )

    async def enrich_query(self, query: dict[str, Any]) -> dict[str, Any]:
        enriched = dict(query)
        enriched["_calibration"] = {
            "text": [
                self._settings.lost_found_text_calibration_min,
                self._settings.lost_found_text_calibration_max,
            ],
            "visual": [
                self._settings.lost_found_image_calibration_min,
                self._settings.lost_found_image_calibration_max,
            ],
            "cross_modal": [
                self._settings.lost_found_cross_modal_calibration_min,
                self._settings.lost_found_cross_modal_calibration_max,
            ],
        }
        if not self._enabled:
            return enriched
        text = " ".join(
            str(query.get(field, ""))
            for field in ("item_name", "keyword", "description")
            if query.get(field)
        ).strip()
        if not text:
            return enriched
        try:
            response = await self._client.post(
                "/v1/embed/text",
                headers={
                    "X-Embedding-Service-Key": self._settings.lost_found_embedding_shared_secret
                },
                json={
                    "items": [{"id": "query", "text": text, "role": "query"}],
                    "spaces": ["semantic", "cross_modal"],
                },
            )
            response.raise_for_status()
            payload = response.json()
            item = payload["items"][0]
            if item.get("semantic"):
                enriched["semantic_text_embedding"] = item["semantic"]["vector"]
            if item.get("cross_modal"):
                enriched["cross_modal_text_embedding"] = item["cross_modal"]["vector"]
            enriched["cross_modal_available"] = bool(payload.get("cross_modal_available"))
            return enriched
        except (httpx.HTTPError, KeyError, TypeError, ValueError):
            return enriched

    async def close(self) -> None:
        await self._client.aclose()
