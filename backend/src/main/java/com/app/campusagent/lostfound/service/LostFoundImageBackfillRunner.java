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

    private final LostFoundImageBackfillService backfillService;

    public LostFoundImageBackfillRunner(LostFoundImageBackfillService backfillService) {
        this.backfillService = backfillService;
    }

    @Value("${app.lost-found.backfill-fingerprints:false}")
    private boolean enabled;

    @Value("${app.lost-found.backfill-page-size:100}")
    private int pageSize;

    @Override
    public void run(String... args) {
        if (!enabled) {
            log.debug("visual fingerprint backfill skipped (app.lost-found.backfill-fingerprints=false)");
            return;
        }
        LostFoundImageBackfillService.BackfillResult result = backfillService.backfill(pageSize);
        log.info("visual fingerprint backfill done: processed={}, updated={}, failed={}",
                result.processed(), result.updated(), result.failed());
    }
}
