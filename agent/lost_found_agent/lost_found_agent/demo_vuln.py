"""DevSecOps 演示用（含漏洞）：演示 PR 门禁 SAST 拦截。

警告：本文件仅用于 CI 安全门禁演示，故意包含安全漏洞——
1) eval 任意代码执行（命令/代码注入）
2) 硬编码密钥

演示流程：推送此版本 -> Bandit 在 PR 阶段拦截（B307）-> 替换为修复版
（ast.literal_eval + 环境变量注入）-> 门禁转绿。
勿将本文件用于生产。
"""


def run_expression(expr: str) -> object:
    # B307: eval 会执行任意代码（代码注入漏洞）
    return eval(expr)


API_SECRET: str = "sk-demo-1234567890abcdef"  # B105: 硬编码密钥
