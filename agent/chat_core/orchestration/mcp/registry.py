"""服务注册表 — Chat Core 编排层。

从 services.yaml 加载所有服务配置（Domain Agent / Utility Tool / Token Service），
供 AgentClient 与编排层入站安全中间件共享。

安全说明：
- shared_secret 通过环境变量注入（services.yaml 中为 ${AGENT_SHARED_SECRET} 占位符）
- 所有 ${ENV} 占位符（含 URL）在加载时解析，支持本地联调（localhost）与
  Docker Compose（服务名）两种环境
"""

from __future__ import annotations

import logging
import os
import re
from dataclasses import dataclass, field
from typing import Any

import yaml

try:
    from dotenv import find_dotenv, load_dotenv

    _env_loaded = load_dotenv(find_dotenv())  # 向上查找项目根 .env
except ImportError:  # pragma: no cover
    _env_loaded = False

logger = logging.getLogger(__name__)

DEFAULT_CONFIG_PATH = os.environ.get(
    "ORCHESTRATION_CONFIG", "config/services.yaml"
)

_ENV_PATTERN = re.compile(r"\$\{([A-Za-z_][A-Za-z0-9_]*)(?::([^}]*))?\}")


@dataclass
class AgentConfig:
    """单个服务实例配置。"""

    name: str
    url: str
    timeout_ms: int = 30000
    type: str = "domain_agent"  # domain_agent | tool_provider
    # Sprint 3：MCP streamable HTTP 端点（如 http://host:port/mcp/）；设置后走 MCP 协议
    mcp_url: str | None = None


def _resolve_env(value: Any) -> Any:
    """递归解析 ${ENV_VAR} 与 ${ENV_VAR:default} 占位符（字符串内全部替换）。"""
    if isinstance(value, str):
        def repl(match: re.Match) -> str:
            var = match.group(1)
            default = match.group(2)
            env_value = os.environ.get(var)
            if env_value is not None:
                return env_value
            return default if default is not None else ""
        return _ENV_PATTERN.sub(repl, value)
    if isinstance(value, dict):
        return {k: _resolve_env(v) for k, v in value.items()}
    if isinstance(value, list):
        return [_resolve_env(v) for v in value]
    return value


@dataclass
class ServiceRegistry:
    """服务注册表：加载配置、解析环境变量、提供查询。"""

    agents: dict[str, AgentConfig] = field(default_factory=dict)
    utility_url: str | None = None
    utility_mcp_url: str | None = None
    token_service_url: str | None = None
    shared_secret: str = ""
    time_window_seconds: int = 30

    @classmethod
    def from_yaml(cls, path: str = DEFAULT_CONFIG_PATH) -> ServiceRegistry:
        """从 YAML 配置文件构建注册表。"""
        if not os.path.exists(path):
            logger.warning("Config file not found: %s, using empty registry", path)
            return cls()

        with open(path, encoding="utf-8") as f:
            config = yaml.safe_load(f) or {}

        config = _resolve_env(config)
        services = config.get("services", {})

        registry = cls()
        registry.shared_secret = config.get("security", {}).get("shared_secret", "")
        registry.time_window_seconds = int(
            config.get("security", {}).get("time_window_seconds", 30)
        )

        # Token Service
        token_cfg = services.get("token_service")
        if token_cfg:
            registry.token_service_url = token_cfg.get("url")

        # Domain Agents
        for agent in services.get("domain_agents", []):
            registry.agents[agent["name"]] = AgentConfig(
                name=agent["name"],
                url=agent["url"],
                timeout_ms=agent.get("timeout_ms", 30000),
                type="domain_agent",
                mcp_url=agent.get("mcp_url"),
            )

        # Utility Tool Provider
        for tool in services.get("utility_tools", []):
            registry.agents[tool["name"]] = AgentConfig(
                name=tool["name"],
                url=tool["url"],
                timeout_ms=tool.get("timeout_ms", 5000),
                type="tool_provider",
                mcp_url=tool.get("mcp_url"),
            )
            if registry.utility_url is None:
                registry.utility_url = tool["url"]
            if registry.utility_mcp_url is None:
                registry.utility_mcp_url = tool.get("mcp_url")

        if not registry.shared_secret:
            logger.warning(
                "AGENT_SHARED_SECRET not set — orchestration inbound HMAC will reject all requests"
            )

        return registry

    def get_agent(self, name: str) -> AgentConfig | None:
        return self.agents.get(name)

    def list_agents(self) -> list[str]:
        return list(self.agents.keys())
