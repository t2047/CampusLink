package com.app.campusagent.lostfound.service;

import com.app.campusagent.lostfound.domain.LostFoundImage;
import com.app.campusagent.lostfound.domain.LostFoundReport;
import com.app.campusagent.lostfound.embedding.LostFoundEmbeddingClient;
import com.app.campusagent.lostfound.embedding.StoredEmbedding;
import com.app.campusagent.lostfound.embedding.TextEmbeddingBundle;
import com.app.campusagent.lostfound.repository.LostFoundImageRepository;
import com.app.campusagent.lostfound.repository.LostFoundReportRepository;
import com.app.campusagent.lostfound.storage.ObjectStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/** 小批量、幂等回填历史报告的预训练向量；模型不可用时等待下一轮。 */
@Component
public class LostFoundEmbeddingBackfillJob {

    private static final Logger log = LoggerFactory.getLogger(LostFoundEmbeddingBackfillJob.class);
    private final LostFoundReportRepository reportRepository;
    private final LostFoundImageRepository imageRepository;
    private final ObjectStorageService storageService;
    private final LostFoundEmbeddingClient embeddingClient;

    @Value("${app.lost-found.embedding.backfill-enabled:false}")
    private boolean enabled;

    @Value("${app.lost-found.embedding.backfill-batch-size:20}")
    private int batchSize;

    @Value("${app.lost-found.embedding.text-revision:614241f622f53c4eeff9890bdc4f31cfecc418b3}")
    private String textRevision;

    @Value("${app.lost-found.embedding.image-revision:327ab6726d33c0e22f920c83f2ff9e4bd38ca37f}")
    private String imageRevision;

    @Value("${app.lost-found.embedding.cross-modal-revision:58edf8cada9e398793dca955574a48cbb7f18be2}")
    private String crossModalRevision;

    @Value("${app.lost-found.embedding.cross-modal-enabled:auto}")
    private String crossModalEnabled;

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

    @Scheduled(
            initialDelayString = "${app.lost-found.embedding.backfill-initial-delay-ms:30000}",
            fixedDelayString = "${app.lost-found.embedding.backfill-interval-ms:300000}")
    @Transactional
    public void backfill() {
        if (!enabled || !embeddingClient.enabled()) {
            return;
        }
        int size = Math.max(1, Math.min(batchSize, 100));
        int updatedReports = 0;
        for (LostFoundReport report : reportRepository
                .findNeedingTextEmbedding(
                        textRevision,
                        crossModalRevision,
                        !"off".equalsIgnoreCase(crossModalEnabled),
                        PageRequest.of(0, size))
                .getContent()) {
            Optional<TextEmbeddingBundle> bundle = embeddingClient.embedDocument(
                    report.getItemName() + "\n" + report.getDescription());
            if (bundle.isEmpty() || bundle.get().semantic() == null) {
                break;
            }
            StoredEmbedding semantic = bundle.get().semantic();
            StoredEmbedding cross = bundle.get().crossModal();
            report.assignTextEmbeddings(
                    semantic.vector(), semantic.model(), semantic.revision(),
                    cross == null ? null : cross.vector(),
                    cross == null ? null : cross.model(),
                    cross == null ? null : cross.revision());
            report.refreshEmbeddingStatus();
            updatedReports++;
        }

        int updatedImages = 0;
        for (LostFoundImage image : imageRepository
                .findNeedingVisualEmbedding(imageRevision, PageRequest.of(0, size)).getContent()) {
            byte[] bytes;
            try {
                bytes = storageService.download(image.getObjectKey());
            } catch (RuntimeException exception) {
                log.warn("图片向量回填下载失败 imageId={}: {}", image.getId(), exception.getMessage());
                continue;
            }
            List<StoredEmbedding> embeddings = embeddingClient.embedImages(List.of(
                    new LostFoundEmbeddingClient.ImageInput(
                            bytes, image.getContentType(), image.getOriginalName())));
            if (embeddings.isEmpty()) {
                break;
            }
            StoredEmbedding embedding = embeddings.getFirst();
            image.assignVisualEmbedding(embedding.vector(), embedding.model(), embedding.revision());
            image.getReport().refreshEmbeddingStatus();
            updatedImages++;
        }
        if (updatedReports + updatedImages > 0) {
            log.info("预训练向量回填完成 reports={}, images={}", updatedReports, updatedImages);
        }
    }
}
