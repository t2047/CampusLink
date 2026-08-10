"""开发探针：查看 L&F MCP 网关的原始输出（绕过编排层进程）。

用法（PowerShell）：
    cd agent/chat_core
    & "D:/Programing/Anaconda3/envs/RAG/python.exe" _probe_lf.py "我掉了一个黑色手机"

输出字段说明：
- response          : L&F 返回的原始回复文本（就是前端最终显示的内容来源）
- status            : completed / needs_more_info / needs_confirmation / failed
- shared_context    : LLM 从消息里提取的字段（对比 rules.py 的 required 可知缺什么）
                      report_lost 必填: item_name, category, description, location, event_date
- confirmation_required : 需要确认时携带的确认信息（confirmation_id 等）
"""
import asyncio
import json
import sys
from pathlib import Path

# Windows 控制台默认 GBK，强制 UTF-8 输出避免中文乱码（终端需支持 UTF-8）
try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

sys.path.insert(0, str(Path(__file__).resolve().parent))

from orchestration.mcp.client import AgentClient  # noqa: E402

REQUIRED_REPORT = ["item_name", "category", "description", "location", "event_date"]


async def main(message: str) -> None:
    client = AgentClient.from_yaml("config/services.yaml")
    r = await client.invoke_agent(
        "lost-found-agent",
        message,
        "6",          # 替换为你的 userId（后端日志可见）
        "STUDENT",
        trace_id="dev-probe",
    )
    print("=== L&F 原始输出 ===")
    print(json.dumps(r, ensure_ascii=False, indent=2))

    shared = r.get("shared_context") or {}
    missing = [f for f in REQUIRED_REPORT if not shared.get(f)]
    print("\n=== 缺失字段（report_lost 必填）===")
    print(missing if missing else "（无缺失）")


if __name__ == "__main__":
    asyncio.run(main(sys.argv[1] if len(sys.argv) > 1 else "我掉了一个黑色手机"))
