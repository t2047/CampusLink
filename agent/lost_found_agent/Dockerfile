# ============================================================
# 文件作用：campuslink-lost-found-agent 的容器镜像构建脚本（Dockerfile）
#   - 采用多阶段构建：builder 阶段基于 uv 导出依赖清单并编译 wheel，运行阶段仅安装产物，减小镜像体积
#   - 基于 Python 3.12 slim 基础镜像，以非 root 用户运行，暴露 8083 端口
#   - 启动入口为 uvicorn 运行 lost_found_agent.main:app
# 说明：仅添加注释，未改动任何指令、参数或顺序。
# ============================================================

# ---------- 第一阶段：builder（构建阶段，产出依赖清单与 wheel 包） ----------
# 使用 Python 3.12 slim 基础镜像作为构建环境
FROM python:3.12-slim AS builder

# 关闭 pip 版本检查提示与缓存，加快构建、减小镜像层体积
ENV PIP_DISABLE_PIP_VERSION_CHECK=1 \
    PIP_NO_CACHE_DIR=1

# Build context: ./agent（同时打包 mcp_servers 供 MCP 适配层使用）
WORKDIR /build
# 先拷贝工程元信息与 uv 锁文件，用于后续按锁定版本导出依赖
COPY lost_found_agent/pyproject.toml lost_found_agent/uv.lock lost_found_agent/README.md ./
# 拷贝 Agent 源码包
COPY lost_found_agent/lost_found_agent ./lost_found_agent
# 拷贝 MCP 服务器代码（供 MCP 适配层使用）
COPY mcp_servers ./mcp_servers
# 安装 uv：导出固定依赖清单（requirements.txt），并构建 wheel 包到 /dist
RUN pip install --no-cache-dir uv \
    && uv export --frozen --no-dev --no-emit-project --format requirements-txt --output-file /dist/requirements.txt \
    && uv build --wheel --out-dir /dist

# ---------- 第二阶段：运行阶段（精简镜像，仅保留运行所需内容） ----------
FROM python:3.12-slim

# 运行环境变量：不生成字节码、日志实时输出便于容器日志采集；设置 PYTHONPATH
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    PIP_NO_CACHE_DIR=1 \
    PYTHONPATH=/app

# 创建非 root 系统用户 campuslink，以最小权限运行服务
RUN groupadd --system campuslink \
    && useradd --system --gid campuslink --create-home campuslink

# 切换工作目录，并从 builder 阶段拷贝 wheel、依赖清单与 mcp_servers
WORKDIR /app
COPY --from=builder /dist/*.whl /tmp/
COPY --from=builder /dist/requirements.txt /tmp/requirements.txt
COPY --from=builder /build/mcp_servers /app/mcp_servers
# 按锁定的 requirements 安装依赖（--require-hashes 校验哈希），再单独安装 mcp，
# 最后以无依赖方式安装 wheel（自身依赖已由 requirements 提供），并清理临时文件
RUN pip install --no-cache-dir --require-hashes -r /tmp/requirements.txt \
    && pip install --no-cache-dir "mcp>=1.28,<2" \
    && pip install --no-cache-dir --no-deps /tmp/*.whl \
    && rm -f /tmp/*.whl /tmp/requirements.txt

# 以非 root 用户运行容器
USER campuslink
# 暴露服务端口 8083
EXPOSE 8083
# 健康检查：每 30s 请求一次 /health 接口，超时 3s，启动后 10s 开始，失败 3 次判定不健康
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8083/health', timeout=2)"

# 容器启动命令：运行 FastAPI 应用，监听 0.0.0.0:8083
CMD ["uvicorn", "lost_found_agent.main:app", "--host", "0.0.0.0", "--port", "8083"]
