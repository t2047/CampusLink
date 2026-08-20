/**
 * 失物招领模块的"预训练向量回填定时任务"（Embedding Backfill Job）。
 *
 * <p>职责：为失物招领的历史报告（report）与图片（image）补齐预训练向量（embedding），
 * 供"以文搜物 / 以图搜物"等语义检索使用。整轮分两段处理：
 * 1) 文本段：对报告的名称 + 描述拼成文本，调用嵌入模型生成语义向量（semantic）与
 *    可选的跨模态向量（cross-modal）；
 * 2) 图片段：从对象存储下载图片字节，生成视觉向量。
 * 两段都只处理"当前缺少对应版本向量"的记录（由仓库 findNeeding* 查询过滤），
 * 因此具备幂等性 —— 重复运行不会覆盖已是最新版本的记录。</p>
 *
 * <p>定时配置：@Scheduled 使用 initialDelayString + fixedDelayString，首次延迟
 * {@code app.lost-found.embedding.backfill-initial-delay-ms}（默认 30000ms），
 * 此后每隔 {@code app.lost-found.embedding.backfill-interval-ms}（默认 300000ms = 5 分钟）
 * 执行一次。总开关为 {@code app.lost-found.embedding.backfill-enabled}（默认 false，
 * 需在配置中显式开启，参见开发文档"L&F 向量回填"一节）。</p>
 *
 * <p>降级策略：嵌入模型不可用（embeddingClient.enabled() == false）时整轮直接返回跳过；
 * 某条记录取不到向量（模型返回空结果或语义向量为空）时 break 提前结束本轮，
 * 等下一轮再尝试，避免在模型异常时空转浪费资源。</p>
 */
package com.app.campusagent.lostfound.service;

// LostFoundImage：图片实体，记录图片对象键、内容类型、原始文件名与视觉向量
import com.app.campusagent.lostfound.domain.LostFoundImage;
// LostFoundReport：报告实体，持有文本向量与跨模态向量（assignTextEmbeddings 写入）
import com.app.campusagent.lostfound.domain.LostFoundReport;
// LostFoundEmbeddingClient：嵌入模型客户端，封装模型调用与可用性判断
import com.app.campusagent.lostfound.embedding.LostFoundEmbeddingClient;
// StoredEmbedding：单个已生成的向量 + 其模型名 + revision 的封装
import com.app.campusagent.lostfound.embedding.StoredEmbedding;
// TextEmbeddingBundle：一条文本生成的"语义向量 + 可选跨模态向量"的整体
import com.app.campusagent.lostfound.embedding.TextEmbeddingBundle;
// LostFoundImageRepository：图片仓库，findNeedingVisualEmbedding 找出缺向量的图片
import com.app.campusagent.lostfound.repository.LostFoundImageRepository;
// LostFoundReportRepository：报告仓库，findNeedingTextEmbedding 找出缺向量的报告
import com.app.campusagent.lostfound.repository.LostFoundReportRepository;
// ObjectStorageService：对象存储服务，负责下载图片字节
import com.app.campusagent.lostfound.storage.ObjectStorageService;
// Logger / LoggerFactory：SLF4J 日志，记录回填进度与图片下载失败告警
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
// Value：读取 Spring 配置项并注入字段（带默认值）
import org.springframework.beans.factory.annotation.Value;
// PageRequest：构造分页请求（第 0 页、batchSize 条）
import org.springframework.data.domain.PageRequest;
// Scheduled：声明定时任务调度
import org.springframework.scheduling.annotation.Scheduled;
// Component：Spring 组件注解，让定时任务 bean 被扫描并生效
import org.springframework.stereotype.Component;
// Transactional：声明方法事务边界
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/** 小批量、幂等回填历史报告的预训练向量；模型不可用时等待下一轮。 */
@Component
public class LostFoundEmbeddingBackfillJob {

    // 日志对象：记录回填结果与图片下载失败告警
    private static final Logger log = LoggerFactory.getLogger(LostFoundEmbeddingBackfillJob.class);
    // 报告仓库：找出缺少文本向量的历史报告
    private final LostFoundReportRepository reportRepository;
    // 图片仓库：找出缺少视觉向量的历史图片
    private final LostFoundImageRepository imageRepository;
    // 对象存储服务：按 objectKey 下载图片字节用于向量生成
    private final ObjectStorageService storageService;
    // 嵌入模型客户端：判断模型可用性、生成文本/图片向量
    private final LostFoundEmbeddingClient embeddingClient;

    // 回填总开关：false 时定时任务每轮直接返回，用于灰度开启或紧急关闭
    @Value("${app.lost-found.embedding.backfill-enabled:false}")
    private boolean enabled;

    // 每批最多处理条数（在 backfill 内被收敛到 1~100，防御配置异常）
    @Value("${app.lost-found.embedding.backfill-batch-size:20}")
    private int batchSize;

    // 文本向量模型版本指纹：仅对文本 revision 不等于此值的报告回填，保证幂等
    @Value("${app.lost-found.embedding.text-revision:614241f622f53c4eeff9890bdc4f31cfecc418b3}")
    private String textRevision;

    // 图片视觉向量模型版本指纹：仅对视觉 revision 不等于此值的图片回填
    @Value("${app.lost-found.embedding.image-revision:327ab6726d33c0e22f920c83f2ff9e4bd38ca37f}")
    private String imageRevision;

    // 跨模态向量模型版本指纹：决定是否生成跨模态向量并参与版本比对
    @Value("${app.lost-found.embedding.cross-modal-revision:58edf8cada9e398793dca955574a48cbb7f18be2}")
    private String crossModalRevision;

    // 跨模态开关：'auto' 跟随模型能力，'off' 强制关闭，其余值一律按开启处理
    @Value("${app.lost-found.embedding.cross-modal-enabled:auto}")
    private String crossModalEnabled;

    /**
     * 构造器注入四个依赖（Spring 自动装配单例 bean）。
     *
     * @param reportRepository 报告仓库
     * @param imageRepository  图片仓库
     * @param storageService   对象存储服务
     * @param embeddingClient  嵌入模型客户端
     */
    public LostFoundEmbeddingBackfillJob(
            LostFoundReportRepository reportRepository,
            LostFoundImageRepository imageRepository,
            ObjectStorageService storageService,
            LostFoundEmbeddingClient embeddingClient) {
        this.reportRepository = reportRepository;
        this.imageRepository = imageRepository;
        this.storageService = storageService;
        this.embeddingClient = embeddingClient;
    }

    /**
     * 定时回填入口：小批量、幂等地补齐文本向量与图片向量。
     *
     * 定时配置：首次延迟 30 秒，之后每 5 分钟执行一次（均可通过配置调整）。
     * 事务特性：@Transactional —— 每个报告/图片的向量写入与版本刷新在同一事务内提交，
     * 失败自动回滚，避免出现"向量写了但版本号没刷"的中间状态。
     *
     * 流程说明：
     * 1. 开关未开启或模型不可用时直接返回（降级，等下一轮）；
     * 2. 将 batchSize 收敛到 1~100 之间，防止配置异常导致单批过大；
     * 3. 文本段：查缺文本向量的报告，逐条生成向量并 assignTextEmbeddings；
     * 4. 图片段：查缺视觉向量的图片，下载字节生成视觉向量并 assignVisualEmbedding；
     * 5. 有实际更新时输出一条汇总日志，便于观测回填进度。
     */
    @Scheduled(
            initialDelayString = "${app.lost-found.embedding.backfill-initial-delay-ms:30000}",
            fixedDelayString = "${app.lost-found.embedding.backfill-interval-ms:300000}")
    @Transactional
    public void backfill() {
        // 总开关关闭，或嵌入模型当前不可用：整轮跳过，等下一轮再尝试
        if (!enabled || !embeddingClient.enabled()) {
            return;
        }
        // 收敛每批条数到 [1,100]，防御配置值异常（如 0、负数或超大值）
        int size = Math.max(1, Math.min(batchSize, 100));
        // 统计本轮实际更新的报告数
        int updatedReports = 0;
        // ---- 文本段：遍历缺少文本向量的报告，逐条生成并写入 ----
        for (LostFoundReport report : reportRepository
                .findNeedingTextEmbedding(
                        textRevision,
                        crossModalRevision,
                        !"off".equalsIgnoreCase(crossModalEnabled),
                        PageRequest.of(0, size))
                .getContent()) {
            // 把"名称 + 描述"拼成一条文本交给嵌入模型
            Optional<TextEmbeddingBundle> bundle = embeddingClient.embedDocument(
                    report.getItemName() + "\n" + report.getDescription());
            // 模型未返回结果，或连语义向量都是空：说明模型暂时异常，break 提前结束本轮
            if (bundle.isEmpty() || bundle.get().semantic() == null) {
                break;
            }
            StoredEmbedding semantic = bundle.get().semantic();
            // 跨模态向量可空（跨模态关闭或模型不支持时为 null）
            StoredEmbedding cross = bundle.get().crossModal();
            // 把语义向量（及可选的跨模态向量）连同模型名 / revision 一并写入报告
            report.assignTextEmbeddings(
                    semantic.vector(), semantic.model(), semantic.revision(),
                    cross == null ? null : cross.vector(),
                    cross == null ? null : cross.model(),
                    cross == null ? null : cross.revision());
            // 刷新报告嵌入状态：向量齐全后标记为已回填，避免下一轮重复处理
            report.refreshEmbeddingStatus();
            updatedReports++;
        }

        // ---- 图片段：遍历缺少视觉向量的图片，下载字节生成并写入 ----
        int updatedImages = 0;
        for (LostFoundImage image : imageRepository
                .findNeedingVisualEmbedding(imageRevision, PageRequest.of(0, size)).getContent()) {
            byte[] bytes;
            try {
                // 从对象存储按 objectKey 下载图片字节
                bytes = storageService.download(image.getObjectKey());
            } catch (RuntimeException exception) {
                // 单张图片下载失败：只记告警并跳过该图，不中断整轮任务
                log.warn("图片向量回填下载失败 imageId={}: {}", image.getId(), exception.getMessage());
                continue;
            }
            // 调用嵌入模型生成视觉向量（这里固定为单图列表）
            List<StoredEmbedding> embeddings = embeddingClient.embedImages(List.of(
                    new LostFoundEmbeddingClient.ImageInput(
                            bytes, image.getContentType(), image.getOriginalName())));
            // 模型未返回任何向量：模型异常，break 提前结束本轮
            if (embeddings.isEmpty()) {
                break;
            }
            StoredEmbedding embedding = embeddings.getFirst();
            // 把视觉向量连同模型名 / revision 写入图片实体
            image.assignVisualEmbedding(embedding.vector(), embedding.model(), embedding.revision());
            // 刷新所属报告的嵌入状态（图片向量齐全后报告的嵌入状态才算完整）
            image.getReport().refreshEmbeddingStatus();
            updatedImages++;
        }
        // 只要有实际更新，就输出一条汇总日志便于观测回填进度
        if (updatedReports + updatedImages > 0) {
            log.info("预训练向量回填完成 reports={}, images={}", updatedReports, updatedImages);
        }
    }
}
