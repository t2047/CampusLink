# ─── CampusLink Mail Service（Gmail + Calendar 后端）────────────
# 自包含镜像：build context = agent/mail_agent（可独立构建，无需仓库根上下文）。
#
# 构建 / 运行：
#   docker build -t campuslink-mail-agent agent/mail_agent
#   docker compose -f agent/mail_agent/docker-compose.yml up -d   # 独立部署
#   或并入根 docker-compose.yml 的 mail-service 服务
FROM python:3.12-slim

WORKDIR /app

# 源码：mail_agent 包 + ml 分类器（ml/models/*.joblib 经 package-data 一并安装）。
# 需先 COPY 再 pip install .（setuptools 构建时校验 package 目录存在）。
COPY pyproject.toml ./
COPY mail_agent ./mail_agent
COPY ml ./ml

RUN pip install --no-cache-dir .

# 运行时数据目录：token.json（Gmail OAuth 刷新令牌）与 calendar.db（SQLite 日历）
# 均落到 /data，由 compose 挂载命名卷持久化——容器重建/升级不丢失授权与日历数据。
ENV GMAIL_TOKEN_PATH=/data/token.json \
    MAIL_CALENDAR_DB=/data/calendar.db

RUN mkdir -p /data \
    && useradd --create-home --shell /bin/bash app \
    && chown -R app:app /app /data

USER app

# Port 5000 与 Google Cloud Console 注册的回调一致
# （http://localhost:5000/callback）：浏览器回调经宿主机 5000 端口进入容器，
# 因此宿主机端口必须保持 5000（详见 README 的 Docker 小节）。
EXPOSE 5000

HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
  CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:5000/health', timeout=2)"

CMD ["uvicorn", "mail_agent.main:app", "--host", "0.0.0.0", "--port", "5000"]
