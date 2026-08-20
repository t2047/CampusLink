# 失物招领（Lost & Found）模块汇报 —— 老师提问清单与参考答案

> 用法：演示按你准备的 9 步进行。老师大概率会围绕**图像识别如何匹配**、**匹配分数如何计算**提问，这两块（A、B 两节）是重点。本文每个答案都标注了对应代码位置，方便你当场指认。
>
> 关键结论先记住三句话：
> 1. **“以图搜图”= 传统颜色直方图指纹（VF1）+ 可选预训练 Embedding 向量，余弦相似度打分，多因子加权重排。**
> 2. **匹配分数 = 7 个维度（文本/图片/跨模态/类别/地点/时间/颜色）的加权平均，各维度权重不同，分数低于 0.35 阈值就不展示。**
> 3. **Agent 交互 = 规则引擎 + LLM（DeepSeek）做意图与字段提取，所有写操作（报失/拾获/认领）都必须二次确认，全链路有签名鉴权。**

---

## 一、9 步演示 → 一句话要点速查

| 步骤 | 你演示的内容 | 演示时一句话讲清楚 |
|---|---|---|
| 1 | 上传图 + `I found a pink cup at LT13`，Agent 追问日期 → 补 `2026-08-16` → Confirm → 出结果 | 发图 + 一句话，Agent 自动抽取"物品/颜色/地点"，缺日期会追问，写操作要 Confirm |
| 2 | 报物品填 `cheatsheet` 自动分类到 `books&stationery` | 表单失焦自动调 `/agent/classify`，规则表没命中就交给 LLM 分类器 |
| 3 | `I lost a black umbrella at LT21 yesterday` | 自然语言直接生成结构化报失记录，日期“昨天”基于服务器权威今天换算 |
| 4 | 匹配到后 Submit claim，输入 `I have the purchase proof`，重复提交触发“已有 claim” | 认领需要 ≥10 字证明；同一物品不能重复提交待审/已批认领 |
| 5 | 管理员账号审批该 claim | 管理员审批，同一物品其它待审认领被自动拒绝，全程有审计日志 |
| 6 | 审批后刷新：claim 变 APPROVED，Browse 里该记录消失 | 报告状态 OPEN→CLAIMED，不再出现在公开 OPEN 列表 |
| 7 | 个人中心 My lost item 上传黑伞图片 | 上传时后端同步算 VF1 颜色指纹 + 预训练图片向量 |
| 8 | 个人中心改头像/昵称/密码 | 改密码后旧 JWT 立即失效（安全项，可一带而过） |
| 9 | 筛选中“以图搜图”，以黑伞为例 | 图上传后走 `/agent/search`：SQL 预筛 → 多因子打分 → Top5 |

---

## 二、老师高频提问与参考答案

### A. 图像识别与匹配（重点区，老师最可能问）

**Q1：图像识别是怎么实现的？是深度学习还是传统 CV？**
A：**两条路，自动降级**。
- **预训练模式（pretrained）**：独立的 Embedding 服务（默认端口 8091）用预训练多模态模型把图片编码成向量（维度 ≤2048，`float32-le-base64`），查询图和候选图之间算**余弦相似度**。这是"智能"模式。
- **基础模式（baseline）**：不依赖任何模型，用**颜色直方图指纹**——把图片按 8×8 网格采样，每像素 RGB 各取高 2 位合成一个 64 桶直方图，L1 归一化后编码成 `VF1:` 前缀的 base64 字符串（`agent/lost_found_agent/lost_found_agent/embeddings.py:68`）。两张图比较的是**直方图的 L1 距离**：`similarity = 1 - L1距离/2`（`embeddings.py:84`）。
- 哪个可用用哪个：查 `matching.py` 的 `matching_mode`，有跨模态向量=`pretrained_multimodal`，有图片向量=`pretrained_image`，只有文本向量=`pretrained_text`，都没有=`baseline`（`matching.py:333-341`）。

**Q2：“以图搜图”从点击到出结果，完整链路是什么？**
A：分三段（前端 `ReportsPage.tsx` → 后端网关 `LostFoundAgentGateway` → Agent `main.py /agent/search`）：
1. 你在 Browse 页点"Search by image"选图 → `POST /api/lost-found/agent/upload-image`，后端 `LostFoundImageStagingService.upload` 把图暂存到 MinIO，**同异步算好 VF1 指纹 + 预训练图片向量**，返回 objectKey/指纹/回显 URL。
2. 点 Search → `POST /api/lost-found/agent/search`（网关）→ Agent 的 `/agent/search`（`main.py:223`），把你选的图（指纹+向量）和筛选条件一起打包。
3. Agent 调后端 `/api/internal/lost-found/candidates` 拿候选（SQL 预筛，见 A 的 Q7），再 `rank_candidates` 打分排序，返回**最高分的 Top5**（`matching.py:179`）。
4. 前端把候选渲染成 ReportCard（`ReportsPage.tsx:236-243`）。

**Q3：上传的图片是怎么变成"可比较的特征"的？**
A：两层特征，上传时一次性算好存库（`LostFoundReportService.create`）：
- **浅层（必算）**：`VisualFingerprintExtractor`（Java）算 VF1 颜色直方图指纹。8×8 网格、每像素 `(r>>6&3)<<4 | (g>>6&3)<<2 | (b>>6&3)` 进 64 桶、L1 归一化、小端 float32 序列化成 base64（`VisualFingerprintExtractor.java:18-25`）。
- **深层（可选）**：`LostFoundEmbeddingClient.embedImages` 调预训练服务算图片向量，存进 `lost_found_images.visual_embedding`（原始 float32 小端字节；对外/给 Agent 时转 base64，`LostFoundReportService.encode`）。
- 同一张图，指纹两端（Java 算入库、Python 算查询）**字节级一致**，因为两边共用同一份规格注释互相引用。

**Q4：VF1 指纹是什么？为什么 Java 和 Python 各写一份？**
A：VF1 = Visual Fingerprint v1，一个**确定性的颜色直方图指纹**：64 维、归一化、`VF1:` 前缀、base64 小端 float32。它不依赖第三方模型，任何环境（含 CI）都能复现。
- 为什么两份：图片指纹在**后端上传时用 Java 算**（`VisualFingerprintExtractor.java`），查询端**在 Agent 里用 Python 算**（`embeddings.py`），两边必须算出一模一样的值，所以各自实现并注释互相引用、用测试锁定（`ColourNormalizerTest` / `test_matching.py` 同理）。
- 特殊处理：WebP 格式 JDK 的 ImageIO 解不了，两端约定**回退到 SHA-256(前 1KiB) 伪直方图**，保证仍然一致（`VisualFingerprintExtractor.java:36`）。

**Q5：pretrained 模式和 baseline 模式什么时候切换？会不会静默降级？**
A：由**候选物品有没有预训练向量**决定，不是由查询端决定：
- 查询端的向量总能现算（Agent 调 `/v1/embed/text`，后端上传时调 `/v1/embed/images`）。
- **候选端依赖历史数据**：如果库里老报告 `embedding_status=BASELINE`（没有预训练向量），那么 `_pretrained_visual_similarity` 返回 None，自动落到颜色直方图 VF1 匹配。前端会显示一个蓝色提示条"**智能模型暂不可用，当前使用基础匹配**"（`LostFoundAgentPanel.tsx:246-249`），**不是静默**。
- 历史数据回填：`LOST_FOUND_EMBEDDING_BACKFILL_ENABLED=true` 会启动 `LostFoundEmbeddingBackfillJob`（首延迟 30s、每 5 分钟一批、每批 20 条），把老报告和图片向量补算，状态 BASELINE→READY。

**Q6：文本向量和图片向量都存哪？格式有什么坑？**
A：
- 文本：`lost_found_reports.semantic_text_embedding`（语义空间）和 `cross_modal_text_embedding`（跨模态空间）。
- 图片：`lost_found_images.visual_embedding`。
- **格式坑（必背）**：数据库里存的是**原始 float32 小端字节**，而 Agent 打分端 `_decode_vector` 期望 **base64 字符串**。所以后端在给 Agent 的候选响应里要 `Base64.encodeToString`（`LostFoundReportService.encode`），两处格式不一致是已知最容易踩的坑。

**Q7：查询是一张图，候选也是多张图，两边怎么对齐求相似度？**
A：取**最大配对**：查询图向量与候选的所有图片向量两两算余弦，取最高值作为该候选的 visual 分量（`matching.py:395-399` `_best_embedding_pair`）。同理跨模态是"查询文本×候选图片"与"候选文本×查询图片"两方向的最大值（`matching.py:402-418`）。一句话：**任意一对相似就算相似，取最像的那一对**。

**Q8：为什么明明一样的图却匹配不到？常见原因？**
A：按概率排序：
1. **候选没有预训练向量** → 只走颜色直方图，两张图颜色分布差异大就分低（排查看前端是否显示"基础匹配"横幅）。
2. **文本分量把总分拉低**：纯发图时如果还抽出一个无语义词（如"这个"）当 keyword，`KEYWORD_STOPWORDS` 会把它过滤掉（`rules.py:114`），否则近零相似度会拉低加权平均到阈值以下。
3. **SQL 预筛把候选排掉了**：颜色、类别、日期窗口（±30 天）、`status=OPEN` 任一不满足，候选根本进不了打分阶段。
4. **低于阈值**：最终分 < 0.35 就不返回（Q12）。

### B. 匹配分数计算（老师第二大概率追问区）

**Q9：匹配分数（match score）到底怎么算的？**
A：`rank_candidates` → `_score_candidate_detailed`（`matching.py:231`）。把查询与候选在 **7 个维度**打分，再做**加权平均**：
```
权重：text 0.25 · visual 0.20 · cross_modal 0.10 · category 0.20 · location 0.10 · temporal 0.10 · colour 0.05
score = Σ(权重 × 该维度分) / Σ(出现维度的权重之和)
```
注意分母是"出现的维度权重和"——**查询里没提供的维度不进分母**，所以"纯发图搜索"不会因没有文本而被打压（`matching.py:324-325`）。
各维度含义：
- **text**：优先用预训练语义向量余弦（再经 calibration，见 Q11）；没有则用 `SequenceMatcher / Jaccard / 包含度 / 本地向量` 四者取最大（`matching.py:344`）。
- **visual**：预训练图片向量余弦（calibration 后），或降级 VF1 颜色直方图相似度（此时权重降为 **0.10**，因为颜色直方图只是弱信号，避免同色误杀文本，`matching.py:307-311`）。
- **cross_modal**：文本向量 × 图片向量（跨模态），说明"描述和图片对得上"。
- **category**：完全相同=1，否则=0（`matching.py:264`）。
- **location**：短文本相似度（子串/SequenceMatcher）。
- **temporal**：日期差衰减（同天=1，1 天=0.9，3 天=0.7，7 天=0.5，30 天=0.2，`matching.py:480`）+ 可选的当日时间相近度（`0.8×日期+0.2×时间`）。
- **colour**：先映射到 canonical 颜色 code（`pink/粉色→PINK`），code 集合有交集=1，否则=0；没识别出颜色才回退文本相似（`matching.py:167`）。

**Q10：为什么权重这样分配？好调吗？**
A：文本和类别最重要（0.25/0.20），因为失物招领文本描述最可靠；图片特征给 0.20；颜色只给 0.05 防止"同色不同物"误召回；地点、时间各 0.10 属于辅助。权重是 `WEIGHTS` 常量（`matching.py:14`），calibration 上下限和最低阈值都可以通过环境变量调（`lost_found_*_calibration_min/max`、`lost_found_match_min_score`）。

**Q11：界面上"匹配度 85%"这个 85 是怎么来的？calibration 是什么？**
A：显示的是 `round(match_score × 100)`（`rules.py:1061`）。
calibration（标定）解决的是"**原始向量余弦分普遍偏高/偏低**"的问题：预训练向量的原始余弦往往集中在 0.6~0.9，直接当分数没法看。所以把原始相似度线性映射到 [0,1]：
```
calibrated = (raw - min) / (max - min)   # 上限是 DEFAULT_CALIBRATION 里的值
文本(0.65,0.95) · 图片(0.50,0.95) · 跨模态(0.15,0.40)
```
即：原始文本相似度 0.65 映射为 0 分、0.95 映射为满分 1.0（`matching.py:458`）。上下限在 `PretrainedEmbeddingClient.enrich_query` 里注入到 query 的 `_calibration`。

**Q12：最低匹配分数阈值是多少？为什么要设？**
A：默认 **0.35**（`config.py` `lost_found_match_min_score=0.35`）。打分低于阈值的候选直接丢弃（`matching.py:192`），目的是**宁缺毋滥**——避免把大量低相关候选抛给用户造成噪音。返回列表上限 **Top5**（`matching.py:215`）。

**Q13：每个候选下的"匹配原因"和彩色小标签（score breakdown）怎么生成的？**
A：
- `match_reason`：每个分量分 ≥0.6 才生成一句人话（"文字描述相似"/"物品类别一致"/"图片特征相似"…，`matching.py:543` `reason()`；≥0.85 省略百分比）。
- `score_breakdown`：把每个分量的最终分放进字典，前端渲染成小 Chip（`LostFoundAgentPanel.tsx:251-256`），方便**当场向老师解释"这个 92% 由哪些维度组成"**——这是演示里非常加分的一个细节。

**Q14：怎么保证 Java（后端）和 Python（Agent）两边分数完全一致？**
A：**只有 Agent 算分，后端不做打分**。后端的 `/candidates` 只做 SQL 预筛并返回候选原始字段+向量，打分流永远走 Agent 的 `rank_candidates`。Browse 的以图搜物和聊天面板复用同一个 `search_candidates` 链路（`rules.py:630` 注释明确写了"两端打分逐字节一致"），所以同一个查询两边结果完全一致。

### C. 自动分类

**Q15：自动分类怎么实现的？"cheatsheet"为什么会分到 books&stationery？**
A：分类接口 `/agent/classify`（`main.py:206`）分两级：
1. **规则表** `CATEGORIES`（`rules.py:49`）：关键词直接命中，如 耳机/手机→ELECTRONICS、学生卡→ID_CARD、钥匙→KEYS、钱包→WALLET_PURSE、雨伞/伞→UMBRELLA、书/文具→BOOKS_STATIONERY。`cheatsheet` 不在表里。
2. **LLM 兜底**：规则没命中且配置了 `LOST_FOUND_LLM_API_KEY`，就用 `CLASSIFY_PROMPT`（`llm.py:154`）把物品名喂给 DeepSeek，在**封闭的 9 个枚举**里选一个 → `BOOKS_STATIONERY`。
前端触发：`CreateReportPage` 物品名输入框失焦时 `onBlur={autoSuggestCategory}`（`CreateReportPage.tsx:37`），如果用户还没手动选过类别就自动填上。聊天/面板里写报告时，LLM 抽取阶段也会直接带出 category。

**Q16：分类模型挂了怎么办？会不会把创建报告卡死？**
A：**fail-open（兜底失败放行）**。`/agent/classify` 任何异常或不确定都返回 `category=null`（200），前端就不自动填、等用户手选（`main.py:210-221`）。注释明确："分类建议只是表单预填，阻塞创建报告不可接受"。聊天流同理：LLM 抽取失败默认降级到规则引擎。

### D. Agent 交互与安全

**Q17：Agent 是怎么理解"我昨天丢了黑伞"这种话的？**
A：双通道：
- **LLM 意图/字段抽取**（`llm.py interpret`）：把消息 + 可信上下文（含服务器权威"今天"日期）发给 DeepSeek，返回受限 JSON（intent + 字段），`temperature=0`、`response_format=json_object`、`extra=forbid` 防模型编造。
- **规则引擎兜底**（`rules.py`）：正则抽物品名（`我丢了/ i lost ...`）、颜色（canonical 表）、地点、相对日期（昨天=今天-1天，`rules.py:851`）、report_id 等；意图靠关键词规则。
- 相对日期"昨天"的基准是**编排层注入的 `system_facts.today`（Asia/Singapore）**，不是服务器本地时区，避免差一天。

**Q18：为什么 Agent 会追问"Please provide: date (YYYY-MM-DD)"？**
A：报失/拾获的**必填字段**是 `item_name / category / description / location / event_date`（`rules.py:255,306`）。你给的信息不全时，`missing_message` 按缺失字段拼提示（`rules.py:990`）：缺日期 → `Please provide: date (YYYY-MM-DD).`（英文）/ `还需要以下信息：日期（YYYY-MM-DD）。`（中文）。字段抽齐才进确认阶段。如果 LLM 幻觉出未来日期，`drop_invalid_fields`（`rules.py:938`）会把不可信字段先清掉再统一走追问，**绝不会抛"内部错误"**。

**Q19：Confirm 确认机制是干什么的？安全吗？**
A：**所有写操作（报失/报拾获/认领）都必须二次确认**，防止一句话误触发生成正式记录。实现：`ConfirmationStore`（`confirmation.py`）内存态，确认令牌 `secrets.token_urlsafe(32)`，**绑定用户、600 秒过期、一次性使用**（用完即删、重复使用报错）。Confirm 后才真正调后端写库。

**Q20：安全性怎么保证？（Agent→后端、Web→Agent）**
A：三层：
1. **Web→Agent 网关**：共享密钥只存在后端 `LostFoundAgentGateway`，浏览器永远拿不到。每次请求签发**短期委托 JWT**（HS256、30 秒过期、`intended_action` 绑定动作、`jti=nonce`，`LostFoundAgentGateway.java:247`）+ HMAC 请求体签名（`X-Nonce/X-Timestamp/X-Signature`）。
2. **Agent→后端内部 API**：每次工具调用再签一个独立的 30 秒 Delegation Token（`tools.py:274`）。
3. **LLM 沙箱**：模型只做意图/字段抽取，提示词明确"不能调用工具/访问数据库/审批/删改记录"，输出 schema 白名单校验（`extra=forbid`），失败可 fail-closed（默认 fail-open 到规则引擎）。另有限流（默认 5 次/分钟/用户）。

### E. 认领与审批

**Q21：认领流程的状态机？**
A：认领 `ClaimStatus`：`SUBMITTED → APPROVED / REJECTED`；报告 `ReportStatus`：`OPEN → CLAIMED / CLOSED`。只有 **FOUND 类型 + OPEN 状态 + 非本人** 的报告才能被认领（`LostFoundClaimService.create`）。

**Q22：为什么会提示"你已经提交过该物品的待处理或已批准认领申请，不能重复认领"？**
A：后端在 `create` 里查 `existsByReportIdAndClaimantIdAndStatusIn(SUBMITTED, APPROVED)`，有就返回 `CLAIM_ALREADY_EXISTS`（409，`LostFoundClaimService.java:56`），Agent 把它翻译成中文提示（`rules.py:1119`）。你演示第 4 步"再提交一次"就是触发这条防重复规则。

**Q23：管理员审批后具体发生了什么？（对应演示第 5、6 步）**
A：`LostFoundAdminService.approveClaim`（`LostFoundAdminService.java:313`）：
1. 该 claim：`SUBMITTED → APPROVED`；
2. 同一报告其余待审 claim：**自动 `REJECTED`**（固定文案 "Another claim was approved by admin"）；
3. 报告：`OPEN → CLAIMED`（`report.markClaimed()`）→ 于是 Browse 公开列表里它**不再出现**（公开搜索强制 `adminHidden=false` 且默认 `status=OPEN`）；
4. 写**审计日志**（`CLAIM_APPROVED_BY_ADMIN`，含 before→after）并**发站内通知**给申请人（`notificationService.claimApproved`）。
拒绝则只有 claim→REJECTED，报告状态不变，且**拒绝原因必填**（`LostFoundAdminService.java:354`）。

**Q24：为什么认领审核收归管理员？**
A：普通用户的 approve/reject 端点保留但一律返回 403 `CLAIM_REVIEW_ADMIN_ONLY`（`LostFoundClaimService.java:91`），审核逻辑移到带 `@PreAuthorize('ADMIN','SUPER_ADMIN')` 的 `LostFoundAdminService`。原因是把"裁决权"收敛到受信任角色，配合审计日志保证可追溯。

### F. 架构与部署

**Q25：整个 L&F 由哪些服务组成？**
A：前端 Web（React）+ **lost-found-agent**（Python/FastAPI，8083，intent/抽取/打分）+ **lost-found-mcp**（8085，走 MCP 给聊天用，同镜像）+ 后端（Java Spring Boot，8080，数据/图片/认领/管理）+ **Embedding 服务**（8091，预训练向量）+ MinIO（图片对象存储）+ MySQL（数据）。面板聊天走 `/api/lost-found/agent/invoke → lost-found-agent`；通用聊天走 orchestration → `lost-found-mcp`。

**Q26：数据怎么存？**
A：报告/图片/认领/通知/审计都在 MySQL（`lost_found_*` 表）；图片字节在 MinIO（暂存图前缀 `lost-found-staging/`，报告图正式键），DB 只存 objectKey/指纹/向量。

**Q27：为什么有两个 agent 容器？**
A：同一个镜像（`agent/lost_found_agent/Dockerfile`），只是端口和协议不同：REST 面板（8083，profile:agent）和 MCP 服务（8085）。所以改代码要 `docker compose --profile agent up -d --build` 一起重建，否则会跑旧代码（比如追问文案不对）。

**Q28：怎么保证 Java/Python 两端颜色表、指纹一致？**
A：双份实现靠**注释互相引用 + 同步修改约定 + 测试锁定**：颜色 canonical 表（`matching.py COLOUR_GROUPS` ↔ `ColourNormalizer.buildGroups`）、指纹规格（`embeddings.py` ↔ `VisualFingerprintExtractor`）各自有测试（`test_matching.py` / `ColourNormalizerTest`）。规则是"纯 ASCII 颜色用词边界正则、含中文用子串；单字中文色（白/黑/蓝）故意不收，防误命中 明白/黑板"。

### G. 局限与改进方向

**Q29：目前有什么已知局限？**
A：挑 2~3 条说就够：
1. **baseline 直方图只感知颜色分布**，不感知形状/语义——同色不同物会高相似（所以降级时把 visual 权重压到 0.10）。
2. **预训练向量的可解释性**：打分可解释（多因子可拆解），但向量本身是黑盒。
3. 短物品名（2 字中文如"耳机"）与后端 `@Size(min=3)` 曾有边界不一致的 WIP 问题。
4. 认领确认是**服务内存态**（600s TTL），服务重启即失效（可接受，因为只是写操作前的二次确认）。

**Q30：后续可改进方向？**
A：把颜色直方图升级为带形状/语义的深度特征；候选召回从 SQL 预筛升级为向量数据库（ANN）；确认状态持久化支持跨请求；为分类/打分加更多回归测试与真实数据评估。

---

## 三、演示小抄（每步如果要被追问）

- **步骤 1 追问"为什么先要日期？"** → 报失/拾获必填 5 字段（名称/类别/描述/地点/日期），缺一就要补齐；未来日期会被校验器拦下转追问。
- **步骤 1 追问"图片有什么用？"** → 图在上传时就算好 VF1 指纹 + 预训练向量，报告创建后自动拿它们去做一次反向匹配（找可能对应的报失记录）。
- **步骤 3 追问"昨天"怎么算的？** → LLM/规则基于服务器注入的权威今天（Asia/Singapore）往前推一天，不是本机时区。
- **步骤 4 追问"证明为什么要 10 字以上？"** → `proof_description` 最小长度校验，避免"我有证据"这种空泛输入。
- **步骤 5 追问"审批动了哪些数据？"** → claim→APPROVED、报告→CLAIMED、其余待审自动 REJECTED、审计日志 + 通知，四条一起在事务里完成。
- **步骤 9 追问"和文字搜索什么区别？"** → 文字搜索走 SQL LIKE；以图搜物走"SQL 预筛 + 向量/指纹打分 + 多因子加权重排"，能搜到**没写对关键词但长得像**的物品。
