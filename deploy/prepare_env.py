#!/usr/bin/env python3
"""本地生成生产 .env：读 .env.prod.example，把 change-me 占位替换为强随机值。

用法（在仓库根）:
    python deploy/prepare_env.py                 # 生成 .env（密钥留空项保持空）
    python deploy/prepare_env.py --api-key sk-xxx  # 同时填入 DeepSeek API key
    python deploy/prepare_env.py --out my.env    # 输出到指定文件

生成的 .env 被 .gitignore 忽略，不会提交；之后 scp 到服务器即可。
"""

from __future__ import annotations

import argparse
import os
import secrets
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

_STRONG_HEX16 = ("MYSQL_PASSWORD", "MINIO_ACCESS_KEY", "MINIO_SECRET_KEY")
_STRONG_HEX32 = (
    "JWT_SECRET",
    "AGENT_SHARED_SECRET",
    "AGENT_BACKEND_SHARED_SECRET",
    "LOST_FOUND_CONFIRMATION_SECRET",
    "LOST_FOUND_EMBEDDING_SHARED_SECRET",
)
_STRONG_PASSWORD = ("SUPER_ADMIN_PASSWORD",)


def _random_hex(length: int) -> str:
    return secrets.token_hex(length)


def _random_password() -> str:
    return secrets.token_urlsafe(16)


def prepare_env(api_key: str | None = None, out: Path | None = None) -> Path:
    template = ROOT / ".env.prod.example"
    if not template.exists():
        raise SystemExit(f"未找到 {template}")
    target = out or ROOT / ".env"

    if target.exists():
        raise SystemExit(
            f"[ABORT] {target} 已存在，不覆盖（重跑会更换 DB 密码/JWT 等密钥，"
            "导致与已部署服务失配）。如需重新生成请先删除旧文件。"
        )

    lines = []
    for raw in template.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            lines.append(raw)
            continue
        key, _, value = line.partition("=")
        key = key.strip()
        value = value.strip()
        if key in _STRONG_HEX32:
            value = _random_hex(32)
        elif key in _STRONG_HEX16:
            value = _random_hex(16)
        elif key in _STRONG_PASSWORD:
            value = _random_password()
        elif key in ("DEEPSEEK_API_KEY", "LOST_FOUND_LLM_API_KEY") and api_key:
            value = api_key
        lines.append(f"{key}={value}")

    target.write_text("\n".join(lines) + "\n", encoding="utf-8")
    try:
        os.chmod(target, 0o600)  # 仅属主可读写（Windows 上为 no-op 或受限支持）
    except OSError:
        pass
    return target


def main() -> None:
    parser = argparse.ArgumentParser(description="生成生产 .env")
    parser.add_argument("--api-key", help="DeepSeek API key（可留空后手动填）")
    parser.add_argument("--out", help="输出路径（默认仓库根 .env）")
    args = parser.parse_args()

    try:
        target = prepare_env(args.api_key, Path(args.out) if args.out else None)
    except SystemExit as exc:
        print(exc)
        sys.exit(1)

    print(f"[OK] generated {target}")
    print("     check then upload: scp .env ubuntu@<vm-ip>:/opt/campuslink/.env")
    print("     WARNING: file contains secrets - do not commit or share")


if __name__ == "__main__":
    main()
