package com.app.campusagent.lostfound.service;

import com.app.campusagent.lostfound.domain.LostFoundImage;
import com.app.campusagent.lostfound.repository.LostFoundImageRepository;
import com.app.campusagent.lostfound.storage.ObjectStorageService;
import com.app.campusagent.lostfound.visual.VisualFingerprintExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 为历史图片回填 {@code visual_fingerprint}（新增字段只覆盖新上传）。
 *
 * <p>逐张下载旧对象 → 计算指纹 → 回写；单张失败记录并继续，幂等
 * （只处理指纹为空的图片，重跑安全）。可通过
 * {@code app.lost-found.backfill-fingerprints=true} 在启动时触发。</p>
 */
@Service
public class LostFoundImageBackfillService {

    private static final Logger log = LoggerFactory.getLogger(LostFoundImageBackfillService.class);

    private final LostFoundImageRepository imageRepository;
    private final ObjectStorageService storageService;

    public LostFoundImageBackfillService(
            LostFoundImageRepository imageRepository,
            ObjectStorageService storageService) {
        this.imageRepository = imageRepository;
        this.storageService = storageService;
    }

    public record BackfillResult(int processed, int updated, int failed) {
    }

    @Transactional
    public BackfillResult backfill(int pageSize) {
        int processed = 0;
        int updated = 0;
        int failed = 0;
        Page<LostFoundImage> page = imageRepository.findByVisualFingerprintIsNull(
                PageRequest.of(0, Math.max(1, pageSize)));
        while (page.hasContent()) {
            for (LostFoundImage image : page.getContent()) {
                processed++;
                try {
                    byte[] bytes = storageService.download(image.getObjectKey());
                    String fingerprint = VisualFingerprintExtractor.extract(bytes, image.getContentType());
                    image.assignVisualFingerprint(fingerprint);
                    imageRepository.save(image);
                    updated++;
                } catch (RuntimeException ex) {
                    failed++;
                    log.warn("visual fingerprint backfill failed for image id={}, key={}: {}",
                            image.getId(), image.getObjectKey(), ex.toString());
                }
            }
            if (page.isLast()) {
                break;
            }
            page = imageRepository.findByVisualFingerprintIsNull(page.nextPageable());
        }
        return new BackfillResult(processed, updated, failed);
    }
}
