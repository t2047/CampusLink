/**
 * 失物招领模块的"图片暂存区清理定时任务"（Image Staging Cleanup Job）。
 *
 * <p>职责：周期性扫描暂存空间（lost-found-staging/），删除超过 TTL 时长、且未被任何
 * 报告正式引用的暂存图片 —— 即"用户已上传但最终没有确认创建报告"的孤儿图，
 * 防止对象存储被废弃图片占满、造成存储浪费。</p>
 *
 * <p>定时配置：@Scheduled 使用 fixedDelayString，间隔由
 * {@code app.lost-found.staging-cleanup-interval-ms} 配置（默认 3600000ms = 1 小时）；
 * TTL 阈值由 {@code app.lost-found.staging-ttl-hours} 配置（默认 24 小时，<=0 表示禁用
 * 本任务）。为单机后台任务，横向扩容前无需分布式锁。</p>
 *
 * <p>与上传流程的配合：图片先写入暂存区并关联 Nonce，用户"确认创建报告"时才会把
 * 暂存键写入 lost_found_images 表；本任务通过 existsByObjectKey 查询数据库，
 * 跳过已被报告引用的键，避免误删正式图片（详见类内 javadoc 与开发文档 §7）。</p>
 */
package com.app.campusagent.lostfound.service;

// 图片仓库：existsByObjectKey 判断暂存键是否已被报告正式引用
import com.app.campusagent.lostfound.repository.LostFoundImageRepository;
// Logger / LoggerFactory：SLF4J 日志，记录每次删除的暂存图片键
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
// Value：读取 Spring 配置项并注入字段（带默认值）
import org.springframework.beans.factory.annotation.Value;
// Scheduled：声明定时任务调度
import org.springframework.scheduling.annotation.Scheduled;
// Component：Spring 组件注解，让定时任务 bean 被扫描并生效
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

    // 日志对象：记录被删除的过期暂存图片键
    private static final Logger log = LoggerFactory.getLogger(LostFoundImageStagingCleanupJob.class);

    // 暂存服务：负责列出全部暂存对象摘要、按 objectKey 删除（同包注入）
    private final LostFoundImageStagingService stagingService;
    // 图片仓库：用于确认暂存键是否已被报告引用
    private final LostFoundImageRepository imageRepository;

    /**
     * 构造器注入两个依赖（Spring 自动装配单例 bean）。
     *
     * @param stagingService  暂存服务（同包）
     * @param imageRepository 图片仓库
     */
    public LostFoundImageStagingCleanupJob(
            LostFoundImageStagingService stagingService,
            LostFoundImageRepository imageRepository) {
        this.stagingService = stagingService;
        this.imageRepository = imageRepository;
    }

    // 暂存图片 TTL（小时）：超过该时长的孤儿图才删除；<=0 表示禁用清理
    @Value("${app.lost-found.staging-ttl-hours:24}")
    private long ttlHours;

    /**
     * 定时清理入口：删除过期的、未被引用的暂存图片。
     *
     * 定时配置：fixedDelay —— 上一次执行结束后再隔
     * {@code app.lost-found.staging-cleanup-interval-ms}（默认 3600000ms = 1 小时）执行
     * 下一次，避免任务重叠。无 @Transactional：单对象删除无需事务，逐条独立提交。
     *
     * 判定逻辑：暂存对象的 lastModified 早于 cutoff（now - ttlHours），
     * 且数据库中不存在该 objectKey（未被报告引用），则删除该对象。
     */
    @Scheduled(fixedDelayString = "${app.lost-found.staging-cleanup-interval-ms:3600000}")
    public void cleanupExpired() {
        // TTL 配置非法（<=0）：视为禁用清理，直接返回
        if (ttlHours <= 0) {
            return;
        }
        // 计算过期时间点 cutoff = 当前时间 - ttlHours 小时
        Instant cutoff = Instant.now().minus(ttlHours, ChronoUnit.HOURS);
        // 遍历全部暂存对象摘要
        for (LostFoundImageStagingService.StagedObjectSummary staged : stagingService.list()) {
            // 仅当"上传时间早于 cutoff"且"未被任何报告引用"时才删除（孤儿图）
            if (staged.lastModified().isBefore(cutoff)
                    && !imageRepository.existsByObjectKey(staged.objectKey())) {
                stagingService.delete(staged.objectKey());
                // 记录一次删除动作，便于排查与观测清理进度
                log.info("deleted expired staged image {}", staged.objectKey());
            }
        }
    }
}
