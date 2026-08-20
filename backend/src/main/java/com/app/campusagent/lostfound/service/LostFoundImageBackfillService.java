/**
 * 历史图片视觉指纹回填服务。
 *
 * <p><b>背景</b>：{@code visual_fingerprint} 字段是后加的，只对新增上传生效；
 * 历史已入库的图片该字段为空，导致以图搜物/去重时无法命中。本服务逐张补算。</p>
 *
 * <p><b>流程</b>：分页查询指纹为空的图片 → 逐张下载旧对象 → 计算 SHA-256 视觉指纹 →
 * 回写实体并保存；单张失败记录并继续处理其余图片，不会中断整个任务。</p>
 *
 * <p><b>幂等性</b>：只处理 {@code visual_fingerprint} 为空的记录，重复运行安全。</p>
 *
 * <p><b>被谁调用</b>：由 {@link LostFoundImageBackfillRunner} 在启动时按配置触发；
 * 配置项 {@code app.lost-found.backfill-fingerprints=true} 时执行。</p>
 *
 * <p><b>依赖</b>：{@code LostFoundImageRepository}（分页查询/保存）、
 * {@code ObjectStorageService}（下载对象字节）、{@code VisualFingerprintExtractor}（计算指纹）。</p>
 */
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

    /** 图片仓库：用于分页查询指纹为空的图片并保存回写结果。 */
    private final LostFoundImageRepository imageRepository;

    /** 对象存储服务：下载图片原始字节。 */
    private final ObjectStorageService storageService;

    public LostFoundImageBackfillService(
            LostFoundImageRepository imageRepository,
            ObjectStorageService storageService) {
        this.imageRepository = imageRepository;
        this.storageService = storageService;
    }

    /** 回填结果汇总：处理总数、成功更新数、失败数。 */
    public record BackfillResult(int processed, int updated, int failed) {
    }

    /**
     * 执行一次全量回填：分页遍历指纹为空的图片并逐张补算指纹。
     *
     * <p>事务特性：{@code @Transactional}；单张失败会被捕获而不影响整体提交，
     * 但注意单张失败会使其所在分页事务继续执行（保存其他成功行）。</p>
     *
     * @param pageSize 每页条数（会被钳制为至少 1）
     * @return 处理/更新/失败数量的汇总
     */
    @Transactional
    public BackfillResult backfill(int pageSize) {
        int processed = 0;
        int updated = 0;
        int failed = 0;
        // 从第一页开始，仅查询指纹为空的图片；pageSize 至少为 1 防止除零/空页
        Page<LostFoundImage> page = imageRepository.findByVisualFingerprintIsNull(
                PageRequest.of(0, Math.max(1, pageSize)));
        // 分页遍历直到最后一页
        while (page.hasContent()) {
            for (LostFoundImage image : page.getContent()) {
                processed++;
                try {
                    // 下载旧对象字节
                    byte[] bytes = storageService.download(image.getObjectKey());
                    // 依据字节与 Content-Type 计算视觉指纹（WebP 等不可解码格式走 SHA-256 回退）
                    String fingerprint = VisualFingerprintExtractor.extract(bytes, image.getContentType());
                    // 回写实体并保存
                    image.assignVisualFingerprint(fingerprint);
                    imageRepository.save(image);
                    updated++;
                } catch (RuntimeException ex) {
                    // 单张失败：累计计数并记录日志，继续处理下一张（保证任务不中断）
                    failed++;
                    log.warn("visual fingerprint backfill failed for image id={}, key={}: {}",
                            image.getId(), image.getObjectKey(), ex.toString());
                }
            }
            if (page.isLast()) {
                break;
            }
            // 继续取下一页（基于上一页的 Pageable 偏移）
            page = imageRepository.findByVisualFingerprintIsNull(page.nextPageable());
        }
        return new BackfillResult(processed, updated, failed);
    }
}
