package com.app.campusagent.lostfound.service;

import com.app.campusagent.lostfound.repository.LostFoundImageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 暂存图片 TTL 清理：删除超过 {@code app.lost-found.staging-ttl-hours} 小时、且未被
 * 任何报告引用的 {@code lost-found-staging/} 对象（即"已上传但未确认创建"的孤儿图）。
 *
 * <p>确认创建后暂存键会写入 {@code lost_found_images}，此处通过 existsByObjectKey 跳过，
 * 避免误删已关联的报告图片。横向扩容前与 Nonce/SSE 存储同属单机债（见开发文档 §7）。</p>
 */
@Component
public class LostFoundImageStagingCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(LostFoundImageStagingCleanupJob.class);

    private final LostFoundImageStagingService stagingService;
    private final LostFoundImageRepository imageRepository;

    public LostFoundImageStagingCleanupJob(
            LostFoundImageStagingService stagingService,
            LostFoundImageRepository imageRepository) {
        this.stagingService = stagingService;
        this.imageRepository = imageRepository;
    }

    @Value("${app.lost-found.staging-ttl-hours:24}")
    private long ttlHours;

    @Scheduled(fixedDelayString = "${app.lost-found.staging-cleanup-interval-ms:3600000}")
    public void cleanupExpired() {
        if (ttlHours <= 0) {
            return;
        }
        Instant cutoff = Instant.now().minus(ttlHours, ChronoUnit.HOURS);
        for (LostFoundImageStagingService.StagedObjectSummary staged : stagingService.list()) {
            if (staged.lastModified().isBefore(cutoff)
                    && !imageRepository.existsByObjectKey(staged.objectKey())) {
                stagingService.delete(staged.objectKey());
                log.info("deleted expired staged image {}", staged.objectKey());
            }
        }
    }
}
