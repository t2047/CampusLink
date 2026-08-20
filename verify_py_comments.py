import ast
import io
import subprocess
import sys
import tokenize

base = "agent/lost_found_agent"


def get_head(path):
    out = subprocess.run(["git", "show", "HEAD:" + path],
                         capture_output=True, text=True, encoding="utf-8")
    if out.returncode != 0:
        return None
    return out.stdout


def find_docstring_spans(src):
    """Locate module/class/function docstring line-ranges."""
    tree = ast.parse(src)
    spans = []
    for node in ast.walk(tree):
        body = getattr(node, "body", None)
        if isinstance(body, list) and body:
            first = body[0]
            if isinstance(first, ast.Expr) and isinstance(first.value, ast.Constant) \
                    and isinstance(first.value.value, str):
                spans.append((first.lineno, getattr(first, "end_lineno", first.lineno)))
    return spans


def code_tokens(src):
    """Return ordered list of (type, string) for non-comment, non-docstring tokens."""
    doc = find_docstring_spans(src)
    toks = []
    for tok in tokenize.generate_tokens(io.StringIO(src).readline):
        t = tok.type
        if t in (tokenize.COMMENT, tokenize.NL, tokenize.NEWLINE,
                 tokenize.INDENT, tokenize.DEDENT, tokenize.ENDMARKER, tokenize.ENCODING):
            continue
        if t == tokenize.STRING:
            if any(s <= tok.start[0] <= e for s, e in doc):
                continue  # docstring
        toks.append((t, tok.string))
    return toks


changed = subprocess.run(["git", "diff", "--name-only", "--", base],
                         capture_output=True, text=True, encoding="utf-8").stdout.splitlines()
changed = [c for c in changed if c.endswith(".py")]

bad = []
ok_count = 0
for path in sorted(changed):
    head = get_head(path)
    if head is None:
        bad.append((path, "无法读取 HEAD 版本"))
        continue
    with open(path, encoding="utf-8") as f:
        work = f.read()
    try:
        h = code_tokens(head)
        w = code_tokens(work)
    except (SyntaxError, IndentationError) as e:
        bad.append((path, "语法错误: %s" % e))
        continue
    # 也做一次独立语法检查（确保 tokenize 通过不等于 py 语法完全正确）
    try:
        compile(work, path, "exec")
    except SyntaxError as e:
        bad.append((path, "compile 语法错误: %s" % e))
        continue
    if h != w:
        bad.append((path, "代码 token 序列不一致: HEAD=%d tokens, WORK=%d tokens" % (len(h), len(w))))
    else:
        ok_count += 1

if not bad:
    print("OK: 全部 %d 个 .py 文件剥离注释/docstring 后代码 token 完全一致，且均可编译" % ok_count)
else:
    for path, msg in bad:
        print("=== 问题: %s === %s" % (path, msg))
    sys.exit(1)
