"""给 mail 服务测试套件固定一个确定性的本地时区。

生产代码用 ``datetime.astimezone()``（机器本地时区）渲染邮件时间，部分测试
（test_agent.py 的 local-time 用例、test_gmail_fuzzy.py 的日期范围用例）依赖
"本地时区 != UTC" 这一假设：例如断言原始 UTC 时间串不出现在渲染结果里。在
UTC runner（如 GitHub Actions）上本地时区就是 UTC，这些断言会失败。

因此在 POSIX 平台（有 ``time.tzset()``，CI 即 Linux）把 TZ 强制钉在
``Asia/Singapore``（UTC+8、无夏令时，全年确定性）：无论 runner 是否预置
``TZ=UTC`` 都能得到一致结果。Windows 没有 ``tzset`` 且对 IANA 时区名的
``TZ`` 支持不可靠，保持系统时区不动（开发者本机通常不是 UTC）。
"""

import os
import time

if hasattr(time, "tzset"):
    os.environ["TZ"] = "Asia/Singapore"  # UTC+8，无 DST，CI 上全年确定
    time.tzset()
