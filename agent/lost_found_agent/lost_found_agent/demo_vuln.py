"""DevSecOps demo (intentionally vulnerable): demonstrates PR-gate SAST interception.

WARNING: Demo file only - contains deliberate security vulnerabilities:
1) eval - arbitrary code execution (command/code injection)
2) hardcoded secret

Demo flow: push this version -> Bandit/Ruff block the PR (B307/S307/S105)
-> replace with the fixed version (ast.literal_eval + env var) -> gate turns green.
Never use in production.
"""


def run_expression(expr: str) -> object:
    # B307/S307: eval executes arbitrary code (code injection vulnerability)
    return eval(expr)


API_SECRET: str = "sk-demo-1234567890abcdef"  # B105/S105: hardcoded secret
