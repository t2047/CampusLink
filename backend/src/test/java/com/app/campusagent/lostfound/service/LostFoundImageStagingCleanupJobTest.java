package com.app.campusagent.lostfound.service;

import com.app.campusagent.lostfound.service.LostFoundImageStagingService.StagedObjectSummary;
import com.app.campusagent.lostfound.repository.LostFoundImageRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LostFoundImageStagingCleanupJobTest {

    @Mock
    private LostFoundImageStagingService stagingService;

    @Mock
    private LostFoundImageRepository imageRepository;

    @Test
    void deletesExpiredUnreferencedObjectsOnly() throws Exception {
        LostFoundImageStagingCleanupJob job =
                new LostFoundImageStagingCleanupJob(stagingService, imageRepository);
        setField(job, "ttlHours", 24L);
        Instant now = Instant.now();
        StagedObjectSummary oldUnreferenced =
                new StagedObjectSummary("k1.png", now.minus(25, ChronoUnit.HOURS));
        StagedObjectSummary oldReferenced =
                new StagedObjectSummary("k2.png", now.minus(25, ChronoUnit.HOURS));
        StagedObjectSummary recent = new StagedObjectSummary("k3.png", now.minus(1, ChronoUnit.HOURS));
        when(stagingService.list()).thenReturn(List.of(oldUnreferenced, oldReferenced, recent));
        when(imageRepository.existsByObjectKey("k1.png")).thenReturn(false);
        when(imageRepository.existsByObjectKey("k2.png")).thenReturn(true);

        job.cleanupExpired();

        verify(stagingService).delete("k1.png");
        verify(stagingService, never()).delete("k2.png");
        verify(stagingService, never()).delete("k3.png");
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
