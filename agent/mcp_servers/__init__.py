"""MCP Server 集合 — CampusLink Agent / Utility。

- domain_server.py：Domain Agent MCP Server（每 Agent 一个进程，暴露 invoke 工具）
- utility_server.py：Utility Tool MCP Server（4 个工具）
- security.py：MCP 入站安全中间件（Delegation Token 验签 + 时间窗口）
"""
