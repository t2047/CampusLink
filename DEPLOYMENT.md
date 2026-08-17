# CampusLink 云部署指南（AWS EC2 m7i-flex.large + Docker Compose）

容器化已就绪（`docker-compose.yml`，含前端 nginx）。本文从零部署到一台 **AWS EC2 m7i-flex.large**（单机，免费层适用）。
该方案同样适用于任何 Ubuntu 云服务器（Azure VM、DigitalOcean、Oracle 等）——仅第 1 节创建方式不同。

## 当前部署状态（2026-08）

- **当前可访问地址**：https://campuslink.tokeninf.xyz/（DNS 指向 `13.212.202.232`）
- **HTTPS 状态**：已启用 Let’s Encrypt 可信证书，HTTP 自动 301 跳转 HTTPS；当前证书覆盖
  `campuslink.tokeninf.xyz`，有效期为 2026-08-15 至 2026-11-13。
- **实例**：AWS EC2 `m7i-flex.large`（2 vCPU / 8 GiB，Free tier，12 个月 $0）
- **服务**：mysql / minio / chat-backend / orchestration / 5×MCP / lost-found / web（nginx）全部运行
- **已知事项**：
  - 正式域名、维护邮箱和 VM SSH 配置保存在 GitHub Secrets，由 CD 同步到服务器，真实值不提交 Git；
  - Certbot 每 12 小时检查续期，Web 容器检测证书变化后平滑 reload；
  - 可信证书签发、HTTP 301、容器健康检查和 CD 部署已经通过；
  - `certbot renew --dry-run` 续期演练仍需由云端负责人执行并记录；
  - 旧环境若存在 RSA 密钥卷属主问题，按第 8b 节执行一次性迁移。

## 1. 创建 AWS EC2（m7i-flex.large，Free tier eligible）

AWS 控制台 → EC2 → 启动实例：

- 名称：任意（如 `campuslink`）
- 镜像：**Ubuntu 24.04 LTS**（m7i-flex 是 x86，选 x86_64 AMI）
- 实例类型：**m7i-flex.large**（2 vCPU / **8 GiB**，x86，当前代，标记 **Free tier eligible**）
- 密钥对：新建或已有（`ssh-keygen -t ed25519` 生成后上传公钥）
- 网络设置：安全组放行 **22（SSH）、80（HTTP）、443（HTTPS）**
- 存储：**30 GB gp3** 起步（系统盘；数据在命名卷）
- 启动后记下**公共 IPv4**

> 额度说明：
> - **免费层**：m7i-flex.large 在免费层额度内（750 小时/月，常开 24×7 ≈ 730h 刚好覆盖），
>   新账号前 12 个月 **$0** 运行。仍建议设**费用告警**（$10/50 阈值）以防超额/超期。
> - **12 个月后**恢复按需价（Linux 约 $0.12/h）；届时按需关机（Stop）不扣运行费、保留磁盘，
>   或迁到 Oracle Always Free / Hetzner（方案零改动）。
> - 备选：**c7i-flex.large**（2 vCPU / 4 GiB，同样 Free tier eligible）。

## 2. 服务器初始化

```bash
# SSH 登录（Ubuntu AMI 默认用户是 ubuntu）
ssh ubuntu@<vm-ip>

# 安装 Docker + Compose 插件
curl -fsSL https://get.docker.com | sh
sudo systemctl enable --now docker

# 当前用户加入 docker 组（免 sudo 运行 docker），重新登录后生效
sudo usermod -aG docker $USER
# 退出重新 SSH 登录后再继续

# 安装 git
sudo apt-get update && sudo apt-get install -y git

# 克隆仓库（用你的 GitHub 仓库地址）
sudo mkdir -p /opt && sudo chown $USER /opt
cd /opt
git clone https://github.com/<org>/CampusLink.git campuslink
cd campuslink
```

## 3. 配置生产环境

**推荐：本地生成密钥后上传**（Windows 用 Git Bash / PowerShell 均可，仓库根执行）：

```bash
# 本地（仓库根）：一键生成强随机密钥的 .env
python deploy/prepare_env.py                          # 全部密钥自动生成
python deploy/prepare_env.py --api-key sk-xxx         # 有 DeepSeek key 时一并填入
# 检查生成的 .env（确认 DeepSeek key、管理员邮箱等）
# 上传到服务器：
scp .env ubuntu@<vm-ip>:/opt/campuslink/.env   # Windows 用 scp 或 WinSCP；非 ubuntu 用户时相应替换
```

生成的 `.env` 被 `.gitignore` 忽略，不会误提交；文件含密钥，勿分享。

**或**：服务器上手动配置：

```bash
cd /opt/campuslink
cp .env.prod.example .env
nano .env
```

**必改项**（生成强随机值）：`MYSQL_PASSWORD`、`JWT_SECRET`、`AGENT_SHARED_SECRET`、`AGENT_BACKEND_SHARED_SECRET`、`LOST_FOUND_CONFIRMATION_SECRET`、`LOST_FOUND_EMBEDDING_SHARED_SECRET`、`SUPER_ADMIN_PASSWORD`、`MINIO_*`。

```bash
# 生成密钥示例
openssl rand -hex 32   # 粘贴到 JWT_SECRET / 各 shared secret
```

**必填项**（需从 Google Cloud Console 获取，非随机生成）：`GMAIL_CLIENT_ID` / `GMAIL_CLIENT_SECRET`（mail 服务已移除内置默认客户端，缺失时 OAuth fail-closed）。也可配置到 GitHub Secrets（`GMAIL_CLIENT_ID` / `GMAIL_CLIENT_SECRET`），由 CD 每次部署自动同步进服务器 `.env`。

DeepSeek 的 `DEEPSEEK_API_KEY` / `LOST_FOUND_LLM_API_KEY`：可选，留空则 L&F 走规则引擎、设施 planner 不可用。

## 4. 防火墙

AWS 安全组已放行 22/80/443（第 1 节）；若用云防火墙/U 层防护可再加：

```bash
sudo ufw allow OpenSSH
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp   # HTTPS 用
sudo ufw enable
```

## 5. 启动全栈（生产：拉取镜像，不在 VM 构建）

```bash
cd /opt/campuslink
# .env 里配置镜像仓库（GHCR 包设 public 后免登录）
# REGISTRY=ghcr.io/<github-owner>

docker compose -f docker-compose.yml -f docker-compose.prod.yml \
  --profile agent --profile multimodal \
  up -d --pull always --wait --wait-timeout 900
docker compose -f docker-compose.yml -f docker-compose.prod.yml \
  --profile agent --profile multimodal ps   # 全部 running/healthy
curl http://localhost/api/chat/stream 2>/dev/null || true   # 或直接访问 http://<vm-ip>
```

> 政策/规章制度 RAG（`search_policy`）的 Qdrant 向量库随默认栈启动；索引由 CD 部署
> 自动构建（见第 9 节）。手动重建：
>
> ```bash
> docker compose -f docker-compose.yml -f docker-compose.prod.yml --profile multimodal up -d --pull always lost-found-embedding
> docker compose -f docker-compose.yml -f docker-compose.prod.yml --profile multimodal run --rm policy-index-builder
> ```

> 本地开发仍用 `docker compose up -d --build`（compose 默认本地构建）。
> `multimodal` profile 会启动预训练 Embedding 服务，首次启动需下载模型并可能耗时数分钟。
> 完整全栈建议使用至少 8 GB RAM 的 VM，并为模型缓存保留足够磁盘空间。

## 6. 域名 + HTTPS（Certbot Webroot）

DNS 已将 `campuslink.tokeninf.xyz` 指向当前 EC2。首次签发时只需在服务器 `.env` 配置：

```dotenv
CERT_DOMAIN=campuslink.tokeninf.xyz
CERT_EMAIL=<项目证书维护邮箱>
# 可选；EC2 Metadata 不可用时填写
SERVER_PUBLIC_IP=13.212.202.232
```

然后在仓库根目录执行幂等脚本：

```bash
set -a && . ./.env && set +a
./deploy/bootstrap_https.sh
```

脚本会验证 DNS 确实指向当前服务器，仅删除该域名的自签占位证书，使用 Webroot 申请或复用 Let's Encrypt
证书，重启 Nginx，并验证 HTTPS 与 HTTP 301 跳转。Certbot 容器每 12 小时检查续期；
Web 容器每小时检测证书摘要，变化后执行无中断 `nginx -s reload`。

CD 会在重启容器前检查 `CERT_DOMAIN` 与 `CERT_EMAIL`。配置缺失时部署会停止，旧容器保持运行，
避免把现有 HTTP 网站错误跳转到自签名 HTTPS。

严禁在客户端关闭证书校验或信任自签名证书。验证命令：

```bash
curl -I http://campuslink.tokeninf.xyz/
curl -I https://campuslink.tokeninf.xyz/
openssl s_client -connect campuslink.tokeninf.xyz:443 -servername campuslink.tokeninf.xyz </dev/null
```

## 7. 更新部署

生产（镜像化）：推 `main` 触发 CD，或手动：

```bash
cd /opt/campuslink
git pull
REGISTRY=ghcr.io/<owner> docker compose \
  -f docker-compose.yml -f docker-compose.prod.yml \
  --profile agent --profile multimodal \
  up -d --pull always --wait --wait-timeout 900
```

## 8. 备份

- 数据全在命名卷（`mysql_data` / `minio_data` / `delegation_keys` / `lost_found_model_cache` / `qdrant_data`）
- 推荐 **EBS 快照**（AWS 控制台手动/定时）或定时导出：
  ```bash
  docker compose exec -T mysql sh -c 'exec mysqldump -uroot -p"$MYSQL_PASSWORD" campusLink_db' > backup.sql
  ```

## 8b. 升级/迁移注意（RSA 密钥卷权限）

backend 以非 root 用户（UID 1001）运行，`delegation_keys` 命名卷**首次挂载**会继承镜像目录 owner（campus）。

**若该卷已由旧版本（root 属主）创建**（如曾因权限问题启动失败过），升级后 backend 仍会因无法写 `/app/keys` 崩溃。迁移修复：

```bash
# 用固定 UID 修复已有卷属主（一次性；backend 停止时执行）
docker compose stop chat-backend
docker compose run --rm -u root chat-backend chown -R 1001:1001 /app/keys
docker compose start chat-backend
```

> 若选择删卷重建（`docker compose down -v`），会**轮换 RSA 密钥**——Agent 端 JWKS 缓存
> （依赖密钥稳定）会失效，需重启编排层/MCP 容器重新拉取公钥；优先用 chown 方案。

## 9. CD（持续部署：CI 构建镜像 + VM 拉取）

见 `.github/workflows/cd-deploy.yml`，流程：
1. 推 `main` → GitHub Actions 构建 7 个镜像（backend/orchestration/mcp-servers/mail-agent/lost-found-agent/lost-found-embedding/web）推 **GHCR**（`ghcr.io/<owner>/campuslink-*`）
2. SSH 到 VM（Secrets：`VM_HOST`/`VM_USER`/`VM_SSH_KEY`）→ 校验 `.env` 与 `CERT_DOMAIN`/`CERT_EMAIL` 配置 → `git pull` + 以 `agent`/`multimodal` profiles 拉取重启（`--wait` 最长 15 分钟）→ `deploy/bootstrap_https.sh`
3. 构建/刷新政策 RAG 索引（`policy-index-builder`，失败仅 WARN 不阻塞部署）

**首次启用需两步**：
- GitHub 仓库 Settings → Packages → 把 `campuslink-*` 包设为 **public**（VM 免登录拉取）
- `.env` 里设置 `REGISTRY=ghcr.io/<github-owner>`

> 安全提示：生产请务必修改 `.env.prod.example` 里所有 `change-me` 值；不要用仓库里的 `.env` 默认值。
