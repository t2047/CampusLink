"""agent HTTP API 顶层行为测试：公开端点、鉴权、nonce 防重放与 action 作用域。

覆盖的功能点：
- 公开端点（/health、/agent/capabilities）无需鉴权即可访问，且不泄露密钥；
- /agent/invoke 缺省鉴权头时返回 401；
- 签名请求后的 invoke 会产生可在 /agent/stream 重放的事件流；
- nonce（防重放令牌）不可重复使用；
- JWT 中的 intended_action 与请求动作不匹配时被拒绝（403）。

被测模块：``lost_found_agent.main``（create_app 组装的应用）与 ``lost_found_agent.config``。
测试策略：集成测试——用 ``client`` fixture（rules 模式、FakeCampusApiClient 替代真实后端）
通过 TestClient 直接打真实 HTTP 端点；用 ``helpers.signed_request`` 生成合法签名请求。
"""

from fastapi.testclient import TestClient

from lost_found_agent.config import Settings

from .helpers import signed_request


def test_public_health_reports_rules_mode(client: TestClient) -> None:
    """/health 健康检查：公开可访问，并如实上报当前运行模式。

    断言整个响应体精确相等：服务名、版本号、mode（client fixture 锁定为 rules）、
    model_configured 为 False（rules 模式未配置模型）。
    """
    response = client.get("/health")

    assert response.status_code == 200
    # 整个 JSON 精确匹配，防止健康信息被意外改动
    assert response.json() == {
        "status": "ok",
        "service": "lost-found-agent",
        "version": "0.7.0",
        "mode": "rules",  # conftest 的 client fixture 强制 rules 模式
        "model_configured": False,  # 未配置 LLM 密钥
    }


def test_public_capabilities_do_not_expose_secrets(client: TestClient) -> None:
    """/agent/capabilities：公开返回能力清单，但绝不能把密钥类信息带上。

    写操作要求确认（write_confirmation_required=True）、动作枚举完整，
    同时整个响应文本中不能出现 "secret" 字样，防止密钥泄漏。
    """
    response = client.get("/agent/capabilities")

    assert response.status_code == 200
    data = response.json()
    # 所有写操作必须要求用户二次确认
    assert data["capabilities"]["write_confirmation_required"] is True
    # 动作清单必须与后端支持的工具一一对应
    assert data["capabilities"]["actions"] == [
        "report_lost",
        "report_found",
        "search_found_items",
        "search_lost_items",
        "get_item_detail",
        "claim_item",
    ]
    # 响应体全文（转小写后）不得包含 secret 相关字段
    assert "secret" not in response.text.lower()


def test_invoke_requires_security_headers(client: TestClient) -> None:
    """未携带签名/JWT 头直接调用 /agent/invoke → 401。

    验证所有交互入口都强制要求安全头，匿名请求一律被拒。
    """
    response = client.post("/agent/invoke", json={"message": "找雨伞"})

    assert response.status_code == 401


def test_authenticated_invoke_creates_replayable_events(
    client: TestClient, settings: Settings
) -> None:
    """合法签名请求的 invoke 会记录请求事件，并可通过 /agent/stream 按 request_id 重放。

    分两步验证：
    1. invoke 返回 200，request_id 回显为请求中的 trace_id（request-1）；
    2. 用 stream 动作的签名再次请求 /agent/stream 读取该请求的 SSE 事件流，
       至少包含 agent_start 与 agent_done 事件（首尾呼应，说明事件被完整持久化）。
    """
    payload = {
        "message": "找雨伞",
        "conversation_context": {"session_id": "session-1", "shared_data": {}},
        "trace_parent": {"trace_id": "request-1"},
    }
    # signed_request 用 settings 里的共享密钥生成 JWT + HMAC 签名头
    body, headers = signed_request(settings, payload)

    response = client.post("/agent/invoke", content=body, headers=headers)

    assert response.status_code == 200
    # 规则引擎对"找雨伞"可能直接 no_match，也可能追问，二者皆可
    assert response.json()["status"] in {"no_match", "needs_more_info"}
    assert response.json()["request_id"] == "request-1"

    # 用 action="stream" 生成独立的只读流式请求签名（payload 为 None → 空 body）
    stream_body, stream_headers = signed_request(settings, None, action="stream")
    stream = client.request(
        "GET",
        "/agent/stream",
        params={"request_id": "request-1"},  # 按 request_id 找回刚才的请求事件
        content=stream_body,
        headers=stream_headers,
    )
    assert stream.status_code == 200
    # SSE 事件流必须包含生命周期开始与结束事件
    assert "event: agent_start" in stream.text
    assert "event: agent_done" in stream.text


def test_nonce_cannot_be_reused(client: TestClient, settings: Settings) -> None:
    """nonce（JWT jti + X-Nonce 头）是防重放令牌：同一请求体不得被重放两次。

    用固定 nonce 签名同一个请求体：
    - 第一次调用返回 200（nonce 首次使用被接受并记录）；
    - 第二次携带相同 nonce 返回 401（已使用，判定为重放攻击）。
    """
    payload = {"message": "find my umbrella"}
    # nonce 参数固定为 "one-time-nonce"，两次请求共享同一 nonce
    body, headers = signed_request(settings, payload, nonce="one-time-nonce")

    # 首次使用：放行
    assert client.post("/agent/invoke", content=body, headers=headers).status_code == 200
    replay = client.post("/agent/invoke", content=body, headers=headers)

    # 重放：拒绝并给出中文错误说明
    assert replay.status_code == 401
    assert replay.json()["detail"] == "Nonce 已被使用"


def test_token_action_is_scoped(client: TestClient, settings: Settings) -> None:
    """JWT 中的 intended_action 必须与请求端点匹配：动作不匹配 → 403。

    用 action="stream" 签名的令牌去调用 /agent/invoke，
    服务端应判定动作用途不符，拒绝执行。
    """
    payload = {"message": "find my umbrella"}
    # action 仅授予 stream 权限
    body, headers = signed_request(settings, payload, action="stream")

    response = client.post("/agent/invoke", content=body, headers=headers)

    assert response.status_code == 403
