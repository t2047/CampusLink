"""失物招领 Agent 的 pytest 测试包。

按被测模块组织：
- 规则 / 匹配 / 嵌入：test_matching_eval、test_embeddings 等；
- 模型评估：test_model_eval（用 httpx.MockTransport 冒烟真实 LLM 路径）；
- 安全与限流：test_rate_limit、test_confirmation 以及依赖 helpers.signed_request
  的集成测试；
- conftest 提供共享的 settings / fake_api / client 夹具与假后端客户端。
"""
