"""Agent 入站安全验证模块：JWT 委托令牌、HMAC 请求签名、时间窗口与 Nonce 防重放。

本模块为失物招领 Agent 的所有入站请求（/agent/invoke、/agent/stream、/agent/classify、
/agent/search）提供统一的多层校验，防止请求被伪造、篡改与重放：

- 时间窗口校验：X-Timestamp 与本地时间偏差超过配置阈值 → 请求过期，直接拒绝；
- HMAC 签名校验：请求体 + nonce + 时间戳 用共享密钥计算 HMAC-SHA256，
  与 X-Signature 恒时比较（hmac.compare_digest，防时序侧信道）→ 保证请求未被篡改；
- 委托令牌(JWT)校验：Authorization Bearer token 必须由 chat-core 以本 Agent 为
  audience 签发，包含 sub/role/intended_action 等声明，且 jti 必须等于本次 nonce；
- Nonce 防重放：NonceStore 用进程内内存 + 锁记录最近已消费的 nonce，同一 nonce
  只能成功使用一次，配合时间窗口构成双重防重放。

校验全部通过后返回 VerifiedRequest（用户身份 + 令牌声明 + 跟踪 ID），供路由后续使用；
任何一步失败均抛出带中文 detail 的 HTTPException(401/403)，由 FastAPI 统一转成 JSON 错误。
"""

# --- 标准库导入 ---
import hashlib  # 提供 SHA-256 等哈希算法，用于 HMAC 签名计算
import hmac     # 标准库 HMAC 实现：基于共享密钥的报文认证码
import json     # JSON 序列化/反序列化（canonical_json 使用）
import threading  # 非阻塞锁，保证 NonceStore 在并发请求下线程安全
import time     # 时间戳来源（可注入自定义 clock 便于测试）
from collections.abc import Callable  # 类型标注：可调用对象（clock 等参数的类型）
from typing import Any

# --- 第三方导入 ---
import jwt              # PyJWT：JWT 委托令牌的编码/解码
from fastapi import HTTPException, Request, status  # FastAPI 异常、请求对象、HTTP 状态码
from jwt import PyJWTError  # JWT 解析/校验失败时的统一异常基类

# --- 本包内部导入 ---
from .config import Settings       # 配置：读取共享密钥、时间窗口、nonce TTL 等
from .models import VerifiedRequest  # 校验通过后的"已认证请求"结果模型


class NonceStore:
    """进程内 nonce 防重放存储（TTL 过期 + 线程安全）。

    职责：记录"最近已消费的 nonce 及其消费时刻"，据此判断某个 nonce 是否已被
    使用过。由于本服务是单实例部署，进程内 dict + 锁即可满足并发安全。
    键是 nonce 字符串，值是首次消费时的时间戳；旧条目按 TTL 在每次 consume 时
    惰性清理，避免内存无限增长。AgentSecurity.verify 用它作为最后一道防重放闸门。
    """

    def __init__(self, ttl_seconds: int, clock: Callable[[], float] = time.time) -> None:
        # ttl_seconds: nonce 有效时长；clock: 时间来源（默认 time.time，测试可注入假时钟）。
        self._ttl_seconds = ttl_seconds
        self._clock = clock
        self._values: dict[str, float] = {}  # 映射：nonce -> 首次消费时间戳
        self._lock = threading.Lock()  # 保护 _values 的并发访问

    def consume(self, nonce: str) -> bool:
        """尝试消费一个 nonce。

        入参：nonce 字符串。返回：True 表示首次使用（放行）；False 表示重复使用（拒绝）。
        调用场景：AgentSecurity.verify 在签名/令牌校验通过后调用，作为防重放的最后一步。
        """
        now = self._clock()
        with self._lock:  # 加锁保证"检查-写入"原子性，防止并发下同一 nonce 被放行两次
            # 惰性清理：先把已超过 TTL 的旧 nonce 从记录中移除，防止内存泄漏。
            self._values = {
                key: created
                for key, created in self._values.items()
                if now - created < self._ttl_seconds
            }
            if nonce in self._values:
                # 已存在 → 该 nonce 之前被消费过 → 视为重放，拒绝放行。
                return False
            # 首次见到 → 记录当前时刻并放行。
            self._values[nonce] = now
            return True


class AgentSecurity:
    """入站请求安全校验器：时间窗口 + HMAC 签名 + JWT 委托令牌 + Nonce 防重放。

    通过 verify() 统一校验每个受保护端点；sign() 可被其他模块复用（例如生成回写
    后端请求时的签名）。所有校验失败均抛出带中文 detail 的 HTTPException
    （401/403），由 FastAPI 统一转换为 JSON 错误响应，不暴露内部实现细节。
    实例生命周期与应用一致：由 create_app 在启动时构造一次并注入路由闭包复用。
    """

    def __init__(
        self,
        settings: Settings,
        nonce_store: NonceStore | None = None,
        clock: Callable[[], float] = time.time,
    ) -> None:
        # settings: 从中读取共享密钥、时间窗口、nonce TTL；
        # nonce_store: 可注入自定义实现（测试用）；clock: 可注入假时钟以测试时间窗口逻辑。
        self._settings = settings
        self._clock = clock
        # 默认按配置的 nonce TTL 构造进程内 NonceStore。
        self._nonces = nonce_store or NonceStore(settings.agent_nonce_ttl_seconds, clock)

    async def verify(self, request: Request, required_action: str) -> VerifiedRequest:
        """校验一个入站请求并返回已认证的请求上下文（VerifiedRequest）。

        入参：
        - request: FastAPI 原始请求（异步读取 body，并解析各类安全请求头）；
        - required_action: 本次操作要求令牌具备的操作名（"invoke"/"stream"/"classify"/"search"）。
        返回：VerifiedRequest（含 user_id、role、intended_action、nonce、trace_id、claims）。
        异常：任意一步不通过即抛 HTTPException(401/403)。
        调用场景：每个受保护路由在执行业务逻辑之前调用。

        校验顺序（从快到慢）：缺失头 → 时间窗口 → HMAC 签名 → JWT 令牌 → jti 匹配 nonce
        → intended_action 匹配 → nonce 消费防重放。越靠前的检查越廉价，尽早拒绝垃圾请求。
        """
        # 一次性读取完整请求体；签名覆盖原始 body 字节，保证内容与签名严格一致、防篡改。
        body = await request.body()
        # 读取四个安全请求头：X-Nonce、X-Timestamp、X-Signature、Authorization。
        nonce = request.headers.get("X-Nonce", "").strip()
        timestamp_value = request.headers.get("X-Timestamp", "").strip()
        signature = request.headers.get("X-Signature", "").strip()
        authorization = request.headers.get("Authorization", "").strip()
        # 任一安全头缺失/为空 → 直接 401，不做后续昂贵的签名运算（快速失败）。
        if not all((nonce, timestamp_value, signature, authorization)):
            raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="缺少安全请求头")

        # 时间戳必须是十进制整数，否则视为格式非法（from exc 保留原始异常上下文）。
        try:
            timestamp = int(timestamp_value)
        except ValueError as exc:
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED, detail="时间戳格式无效"
            ) from exc
        # 时间窗口校验：本地时间与请求时间戳的绝对偏差超过配置窗口 → 视为过期/重放。
        if abs(int(self._clock()) - timestamp) > self._settings.agent_security_time_window_seconds:
            raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="请求已过期")

        # 服务端按相同规则重算签名，与请求携带的签名做恒时比较：
        # hmac.compare_digest 的时间开销与内容无关，可抵御时序侧信道攻击。
        expected = self.sign(body, nonce, timestamp)
        if not hmac.compare_digest(expected, signature):
            raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="请求签名无效")

        # 剥离 "Bearer " 前缀取出原始 JWT；若 token 为空或头部本就没有前缀
        # （authorization==token）→ 视为无效令牌。
        token = authorization.removeprefix("Bearer ").strip()
        if not token or authorization == token:
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED, detail="Bearer Token 无效"
            )
        # 解码 JWT（内部校验签名、audience、issuer、过期时间及必填声明），失败即 401。
        claims = self._decode_token(token)
        # 令牌 jti 必须等于本次请求的 nonce：把"签名令牌"与"防重放 nonce"绑定，
        # 保证令牌是一次性且与当前请求一一对应，无法被复制到其它请求复用。
        if claims.get("jti") != nonce:
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED, detail="Token 与 Nonce 不匹配"
            )
        # 令牌声明的 intended_action 必须与当前端点要求的操作一致，否则 403 无权访问。
        if claims.get("intended_action") != required_action:
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN, detail="Token 无权执行该操作"
            )
        # 消费 nonce：失败说明该 nonce 已被使用过（重放攻击）→ 401。
        if not self._nonces.consume(nonce):
            raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Nonce 已被使用")

        # 全部校验通过 → 组装已认证上下文供路由使用（sub 等声明统一转为字符串）。
        return VerifiedRequest(
            user_id=str(claims["sub"]),
            user_role=str(claims["role"]),
            intended_action=str(claims["intended_action"]),
            nonce=nonce,
            trace_id=request.headers.get("X-Trace-Id") or None,
            claims=claims,
        )

    def sign(self, body: bytes, nonce: str, timestamp: int) -> str:
        """用共享密钥对 (body, nonce, timestamp) 计算 HMAC-SHA256 十六进制签名。

        入参：原始请求体字节、nonce、时间戳。返回：64 位小写十六进制字符串。
        签名规则必须与 Web 端完全一致：既用于比对入站 X-Signature，也可复用于生成
        回写后端请求的签名。
        """
        # 规范化消息：body | nonce | timestamp 以字节 ':' 连接，把二进制请求体与
        # 文本头统一编码，保证通信两端能计算出完全相同的输入串。
        message = b":".join((body, nonce.encode(), str(timestamp).encode()))
        # hmac.new(密钥, 消息, 摘要算法).hexdigest() 输出十六进制 HMAC 摘要。
        return hmac.new(
            self._settings.agent_shared_secret.encode(), message, hashlib.sha256
        ).hexdigest()

    def _decode_token(self, token: str) -> dict[str, Any]:
        """解码并校验 JWT 委托令牌，返回声明字典；任何失败统一抛 401。

        入参：不带 "Bearer " 前缀的原始 JWT 字符串。返回：JWT 声明（claims）字典。
        校验项：算法必须为 HS256、签名必须用 agent_shared_secret 验证通过、
        audience 必须等于本 Agent 名、issuer 必须为 chat-core，且强制要求
        sub/role/aud/iss/iat/exp/jti/intended_action 全部声明存在。
        """
        try:
            claims: dict[str, Any] = jwt.decode(
                token,
                self._settings.agent_shared_secret,
                algorithms=["HS256"],  # 白名单只收 HS256，杜绝算法混淆攻击（如 alg=none）
                audience=self._settings.agent_name,  # 令牌必须专为本 Agent 签发
                issuer="chat-core",  # 令牌必须由 chat-core 签发，拒绝第三方代签
                options={
                    # require：以下声明缺一不可，缺任一项都会抛 PyJWTError。
                    "require": [
                        "sub",  # 用户 id（主体）
                        "role",  # 用户角色
                        "aud",  # 接收方（本 Agent）
                        "iss",  # 签发方（chat-core）
                        "iat",  # 签发时间
                        "exp",  # 过期时间
                        "jti",  # 令牌唯一 id（须等于本次请求 nonce）
                        "intended_action",  # 令牌被授权执行的操作
                    ]
                },
            )
            return claims
        except PyJWTError as exc:
            # 任何解析/校验失败（签名错误、已过期、缺声明、格式非法）统一映射为 401，
            # 不向外暴露具体失败原因，避免信息泄露；from exc 保留异常链便于排查。
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED, detail="Delegation Token 无效"
            ) from exc


def canonical_json(data: dict[str, Any]) -> bytes:
    """把字典规范化为紧凑、无多余空格的 UTF-8 JSON 字节串，用作签名/哈希的确定性输入。

    入参：待序列化字典。返回：UTF-8 编码的字节串。
    ensure_ascii=False 保留中文原样；separators=(",", ":") 去掉默认的空格分隔，
    保证同一字典在任何机器/任何 json 实现下都序列化出相同的字节，签名才可复现。
    """
    return json.dumps(data, ensure_ascii=False, separators=(",", ":")).encode()
