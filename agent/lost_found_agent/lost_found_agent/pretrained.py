"""独立 Embedding 服务客户端；失败时返回原查询，由 matching.py 自动降级。

本模块是失物招领 Agent 中"预训练嵌入（以文/图搜物）"的客户端封装：
- 通过 httpx 异步调用独立的嵌入服务（/v1/embed/text），把查询文本编码为
  semantic（纯语义）与 cross_modal（跨模态，文本-图像共享空间）两个向量；
- 向量与校准区间（_calibration）一起追加进查询字典，供 matching.py 的
  rank_candidates 计算相似度并归一化到用户可读的匹配度百分比；
- 设计为"可选增强"：当嵌入服务未启用（baseline 模式或未配置共享密钥）或调用
  失败时，enrich_query 一律返回不带嵌入的原查询副本，由 matching.py 自动降级
  为基线（关键词/视觉指纹）匹配——嵌入故障绝不影响检索主流程的可用性。
"""

from typing import Any  # 宽松类型：query 是键值对字典，各键的值类型多样

import httpx  # 异步 HTTP 客户端：调用嵌入服务 /v1/embed/text

from .config import Settings  # Agent 配置入口（嵌入地址/共享密钥/超时/校准区间均取自这里）


class PretrainedEmbeddingClient:
    """预训练嵌入服务客户端：把查询文本编码为语义/跨模态向量。

    职责：
    - 在候选检索前调用嵌入服务，把查询文本（item_name/keyword/description 拼接）
      编码为向量，追加进查询字典，供 rank_candidates 做向量相似度匹配；
    - 注入 _calibration 校准区间，让匹配端能把模型原始分数归一化为用户可读的匹配度；
    - 任何失败都返回"不带嵌入的原查询副本"，把降级决定完全交给 matching.py，
      保证嵌入是可选增强而非硬依赖。

    生命周期：由应用初始化并持有；应用关闭时调用 close() 释放 HTTP 连接。
    """

    def __init__(
        self,
        settings: Settings,
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        self._settings = settings
        # 启用条件：嵌入模式不是 baseline，且共享密钥长度 >= 16（说明已配置密钥）。
        # baseline 模式或密钥未配置时 _enabled=False，enrich_query 只注入校准区间、
        # 不调用嵌入服务，让调用方自然走基线匹配。
        self._enabled = (
            settings.lost_found_embedding_mode != "baseline"
            and len(settings.lost_found_embedding_shared_secret) >= 16
        )
        # 复用自建的 AsyncClient：base_url 去尾部斜杠（便于拼 /v1/... 路径），
        # 超时用配置值；transport 允许测试时注入 mock 网络层
        self._client = httpx.AsyncClient(
            base_url=settings.lost_found_embedding_url.rstrip("/"),
            timeout=settings.lost_found_embedding_timeout_seconds,
            transport=transport,
        )

    async def enrich_query(self, query: dict[str, Any]) -> dict[str, Any]:
        """为搜索查询字典附加嵌入向量与校准信息。

        入参：query —— 候选检索的查询字典（含 item_name/keyword/description/category 等）。

        返回：query 的副本，并附加
        - _calibration：文本/视觉/跨模态相似度的 [min, max] 校准区间（匹配归一化用）；
        - semantic_text_embedding / cross_modal_text_embedding：文本的语义/跨模态向量；
        - cross_modal_available：跨模态空间是否可用。

        未启用或调用失败时返回"只带校准、不带嵌入"的副本，由调用方自动降级。
        注意：绝不修改入参字典（先 dict(query) 拷贝）。
        """
        enriched = dict(query)  # 拷贝一份，绝不改动调用方的原字典
        # 注入校准区间：模型原始相似度分数通常分布很窄（如 0.6~0.9），
        # 匹配端据此把原始分数线性归一化到 0~100% 的匹配度，用户更容易理解
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
        # 未启用（baseline 模式或密钥未配置）：只返回带校准的副本，不调用嵌入服务
        if not self._enabled:
            return enriched
        # 拼接查询文本：只取有值的字段，按 item_name→keyword→description 顺序
        # 空格连接，作为文本编码的输入；空查询（如纯视觉"帮我找这个"）不编码
        text = " ".join(
            str(query.get(field, ""))
            for field in ("item_name", "keyword", "description")
            if query.get(field)
        ).strip()
        if not text:
            return enriched  # 无文本可编码：返回原副本（可能是纯视觉查询，走视觉指纹匹配）
        try:
            # 调用嵌入服务：X-Embedding-Service-Key 头做服务间共享密钥鉴权；
            # body 声明查询角色（role=query）并同时请求 semantic 与 cross_modal 两个空间
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
            response.raise_for_status()  # 非 2xx 抛 httpx.HTTPError → 进入降级
            payload = response.json()
            item = payload["items"][0]  # 单条查询，取第一条结果
            # semantic 语义空间：纯文本语义相似度（文本↔文本）
            if item.get("semantic"):
                enriched["semantic_text_embedding"] = item["semantic"]["vector"]
            # cross_modal 跨模态空间：文本与图像共享的嵌入空间（文本→图像检索的关键）
            if item.get("cross_modal"):
                enriched["cross_modal_text_embedding"] = item["cross_modal"]["vector"]
            # 显式告知匹配端跨模态空间是否可用：不可用时只能用 semantic 或降级到基线
            enriched["cross_modal_available"] = bool(payload.get("cross_modal_available"))
            return enriched
        except (httpx.HTTPError, KeyError, TypeError, ValueError):
            # 任何失败都返回"不带嵌入"的副本：嵌入是可选增强，绝不能让检索主流程失败，
            # 由 matching.py 依据缺失的向量字段自动降级到基线匹配
            return enriched

    async def close(self) -> None:
        # 释放自建 AsyncClient 的连接资源（应用关闭时调用）
        await self._client.aclose()
