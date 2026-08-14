# Lost & Found 预训练多模态匹配技术说明

> 状态：代码已完成，真实模型默认按需启动；最后更新：2026-08-12。

## 1. 目标和边界

本迭代不训练校园专用模型，也不引入向量数据库。MySQL 先按相反报告类型、`OPEN` 状态、类别和日期等结构化条件取最多 100 条候选，Agent 再在内存中融合预训练向量和业务字段，返回 Top 5。

独立服务位于 `services/lost_found_embedding`，不会把 PyTorch 加入 REST Agent、MCP 或 Spring Boot 镜像。固定模型如下：

| 能力 | 模型 | Revision | 维度 |
|---|---|---|---:|
| 中英文文本语义 | `intfloat/multilingual-e5-small` | `614241f622f53c4eeff9890bdc4f31cfecc418b3` | 384 |
| 图片对图片 | `sentence-transformers/clip-ViT-B-32` | `327ab6726d33c0e22f920c83f2ff9e4bd38ca37f` | 512 |
| 多语言文字对图片（可选） | `sentence-transformers/clip-ViT-B-32-multilingual-v1` | `58edf8cada9e398793dca955574a48cbb7f18be2` | 512 |

所有输出均为 L2 归一化的 little-endian float32 Base64。服务禁止 `trust_remote_code`，只接受 JPEG、PNG、WebP，单图不超过 10 MB、单次最多 5 张，并校验 MIME、实际格式和图片尺寸。

## 2. 数据流

```text
发布/编辑/Agent 确认创建
  → Spring Boot 调用内部 Embedding 服务
  → MySQL 保存向量、模型、revision、状态和更新时间
  → 失败时照常保存报告并标记 PENDING
  → 幂等定时任务按 20 条一批回填缺失或旧 revision

LOST 或 FOUND 查询
  → MySQL 取相反类型 OPEN 候选（≤100）
  → Agent 生成查询文本向量并读取可信暂存图片向量
  → 多分量校准与缺失权重归一化
  → Top 5 + 总分 + 分项分数 + 匹配模式 + 双语原因
```

浏览器永远不能提交可信向量。图片先由 Spring Boot 暂存，暂存表绑定真实登录用户、对象键、过期时间和服务端生成的向量；网关转发给 Agent 前会丢弃浏览器传来的指纹/向量并重新读取可信数据。公开 DTO 不返回向量、MinIO Object Key 或联系方式。

## 3. 评分与降级

| 分量 | 默认权重 | 初始校准区间 |
|---|---:|---:|
| E5 文本语义 | 25% | 0.65–0.95 |
| CLIP 图片对图片 | 20% | 0.50–0.95 |
| 多语言文字对图片 | 10% | 0.15–0.40 |
| 类别 | 20% | 0 或 1 |
| 地点 | 10% | 规范化、包含和字符串相似度 |
| 日期与时间 | 10% | 日期距离为主，可解析时间为辅 |
| 颜色 | 5% | 规范化字符串相似度 |

多图取所有组合中的最高图片余弦；跨模态取“查询文字→候选图片”和“候选文字→查询图片”的最高有效值。缺少图片、颜色或时间时，该分量不进入分母。最低综合分数默认 `0.35`。

降级顺序：

```text
E5 + CLIP 图片 + 多语言图文
→ E5 + CLIP 图片
→ E5 文本
→ 哈希文本 + 颜色直方图 + 结构化规则
```

响应中的 `matching_mode` 为 `pretrained_multimodal`、`pretrained_image`、`pretrained_text` 或 `baseline`。Web 与 Chat Core 遇到 `baseline` 时显示非阻塞降级提示。

## 4. 启动和配置

```bash
cp .env.example .env
# 至少替换 LOST_FOUND_EMBEDDING_SHARED_SECRET
docker compose --profile agent --profile multimodal up -d --build
```

普通 `docker compose up -d` 不启动模型服务，不会持续占用约 2–3 GB 内存。模型缓存在 `lost_found_model_cache` 命名卷。跨模态开关：

- `LOST_FOUND_CROSS_MODAL_ENABLED=auto`：尝试加载，失败后继续图片对图片；
- `on`：加载失败时 readiness 返回失败；
- `off`：不下载、不加载跨模态文本模型。

历史回填默认关闭。确认模型服务 ready 后设置 `LOST_FOUND_EMBEDDING_BACKFILL_ENABLED=true`，后端会重新计算缺失向量以及 revision 不匹配的数据。

## 5. 验证、CI 和已知限制

普通 PR CI 使用 Fake Runtime，不下载模型权重，并执行 Ruff、格式检查、mypy、pytest、Bandit、pip-audit、Python CodeQL 和非 root 镜像构建。GitHub Actions 的“手动 - Lost & Found 真实多模态模型验证”会缓存固定 revision，验证模型可重复性，并对 `evaluation/cases.json` 的 30 组中英文困难样本生成 Recall@5、图片相对颜色直方图的提升和单图 CPU P95 报告。

必交门槛为 Recall@5 ≥ 0.80、图片子集相对基线提升 ≥ 10 个百分点。跨模态只有在质量不低于图片基线且单次 CPU 查询 P95 ≤ 2 秒时才适合默认开启，否则维持 `auto` 或 `off`。

当前限制：没有 ANN 向量检索；没有校园数据微调；中文图文效果取决于真实照片质量；同步生成受 CPU 性能影响；聊天统一入口暂未传输图片附件。后续优先收集脱敏的真实困难负样本与用户反馈，再决定是否微调或引入 Qdrant。
