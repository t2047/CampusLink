/**
 * 视觉指纹回填的启动触发器。
 *
 * <p><b>职责</b>：实现 {@link CommandLineRunner}，在 Spring Boot 应用启动完成后、
 * 进入服务前执行一次回填判断：若配置项 {@code app.lost-found.backfill-fingerprints}
 * 为 true，则调用 {@link LostFoundImageBackfillService#backfill} 回填历史图片指纹，
 * 并记录汇总日志；默认关闭，跳过执行。</p>
 *
 * <p><b>被谁调用</b>：由 Spring Boot 启动过程自动调用（{@code CommandLineRunner}）。
 *
 * <p><b>依赖</b>：{@code LostFoundImageBackfillService} 回填服务。</p>
 */
package com.app.campusagent.lostfound.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时按配置触发旧图片视觉指纹回填（默认关闭）。
 * 置 {@code app.lost-found.backfill-fingerprints=true} 才执行。
 */
@Component
public class LostFoundImageBackfillRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(LostFoundImageBackfillRunner.class);

    /** 回填服务：真正执行分页回填逻辑。 */
    private final LostFoundImageBackfillService backfillService;

    public LostFoundImageBackfillRunner(LostFoundImageBackfillService backfillService) {
        this.backfillService = backfillService;
    }

    /** 是否启用回填：默认 false（关闭）。 */
    @Value("${app.lost-found.backfill-fingerprints:false}")
    private boolean enabled;

    /** 分页大小：默认每页 100 条。 */
    @Value("${app.lost-found.backfill-page-size:100}")
    private int pageSize;

    /**
     * Spring Boot 启动完成后执行：开关关闭则跳过，开启则执行回填并打印汇总。
     *
     * @param args 启动命令行参数（本实现不使用）
     */
    @Override
    public void run(String... args) {
        // 默认关闭：未开启直接跳过，避免每次启动都做全量回填
        if (!enabled) {
            log.debug("visual fingerprint backfill skipped (app.lost-found.backfill-fingerprints=false)");
            return;
        }
        // 执行回填并输出处理/更新/失败统计
        LostFoundImageBackfillService.BackfillResult result = backfillService.backfill(pageSize);
        log.info("visual fingerprint backfill done: processed={}, updated={}, failed={}",
                result.processed(), result.updated(), result.failed());
    }
}
