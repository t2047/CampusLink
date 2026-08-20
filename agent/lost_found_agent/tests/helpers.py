"""测试辅助函数（helpers）。

为安全 / 视觉相关的集成测试提供两个可复用工具：
- make_solid_png：用 Pillow 生成纯色 PNG 字节，供图像指纹 / 视觉相似度测试使用；
- signed_request：构造一个"合法签名"的入站请求——签发带 audience / issuer / jti /
  intended_action 等声明的 HS256 JWT，并按 AgentSecurity.sign 的规则计算
  HMAC-SHA256 签名，返回 (请求体, 请求头字典)，可直接塞给 TestClient。
"""

import hashlib
import hmac
import io
import json
import time
import uuid
from typing import Any

import jwt
from PIL import Image

from lost_found_agent.config import Settings


def make_solid_png(rgb: tuple[int, int, int], size: int = 16) -> bytes:
    """生成给定 RGB 颜色的纯色 PNG 字节（默认 16x16，无 alpha 通道）。"""
    image = Image.new("RGB", (size, size), rgb)  # 创建纯色画布
    buffer = io.BytesIO()  # 用内存缓冲承接编码结果，避免写盘
    image.save(buffer, format="PNG")
    return buffer.getvalue()


def signed_request(
    settings: Settings,
    payload: dict[str, Any] | None,
    *,
    action: str = "invoke",
    user_id: str = "42",
    nonce: str | None = None,
    timestamp: int | None = None,
) -> tuple[bytes, dict[str, str]]:
    """构造一个能通过 AgentSecurity.verify 的完整请求，返回 (请求体, 请求头)。

    参数：
    - settings：提供 agent_name（JWT audience）与 agent_shared_secret（签名密钥）；
    - payload：请求 JSON 体；None 表示空 body；
    - action：JWT 的 intended_action 声明，需与目标接口的 required_action 一致；
    - user_id：JWT 的 sub 声明（模拟登录用户）；
    - nonce / timestamp：可显式指定，否则自动生成，用于构造特定时间窗口 / 重放场景。

    返回的请求头包含 Authorization（HS256 JWT）、X-Nonce、X-Timestamp、
    X-Signature（HMAC-SHA256）与 X-Trace-Id，是进入被测接口的标准载荷。
    """
    active_nonce = nonce or str(uuid.uuid4())  # 未指定时生成随机 nonce
    active_timestamp = timestamp or int(time.time())  # 未指定时用当前时间戳
    body = (
        # 与 canonical_json 相同的紧凑序列化，保证签名前后 body 字节一致
        json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode()
        if payload is not None
        else b""  # 无载荷时 body 为空字节串，签名仍按空 body 计算
    )
    # 签发 HS256 JWT：aud 取 agent_name、iss 固定为 chat-core、exp 为 now+30 秒，
    # jti 绑定 nonce、intended_action 绑定动作，字段与 verify 的校验一一对应
    token = jwt.encode(
        {
            "sub": user_id,
            "role": "STUDENT",
            "aud": settings.agent_name,
            "iss": "chat-core",
            "iat": active_timestamp,
            "exp": active_timestamp + 30,
            "jti": active_nonce,
            "intended_action": action,
        },
        settings.agent_shared_secret,
        algorithm="HS256",
    )
    # 计算 HMAC-SHA256 签名：消息 = body : nonce : timestamp（与 AgentSecurity.sign 完全一致）
    message = b":".join((body, active_nonce.encode(), str(active_timestamp).encode()))
    signature = hmac.new(settings.agent_shared_secret.encode(), message, hashlib.sha256).hexdigest()
    return body, {
        "Authorization": f"Bearer {token}",
        "Content-Type": "application/json",
        "X-Nonce": active_nonce,
        "X-Timestamp": str(active_timestamp),
        "X-Signature": signature,
        "X-Trace-Id": "trace-test",  # 便于按链路追踪测试请求
    }
