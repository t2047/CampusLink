package com.app.campusagent.lostfound.service;

import com.app.campusagent.domain.User;
import com.app.campusagent.lostfound.domain.EmbeddingStatus;
import com.app.campusagent.lostfound.domain.ItemCategory;
import com.app.campusagent.lostfound.domain.LostFoundReport;
import com.app.campusagent.lostfound.domain.ReportType;
import com.app.campusagent.lostfound.embedding.LostFoundEmbeddingClient;
import com.app.campusagent.lostfound.embedding.StoredEmbedding;
import com.app.campusagent.lostfound.embedding.TextEmbeddingBundle;
import com.app.campusagent.lostfound.repository.LostFoundImageRepository;
import com.app.campusagent.lostfound.repository.LostFoundReportRepository;
import com.app.campusagent.lostfound.storage.ObjectStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LostFoundEmbeddingBackfillJobTest {

    @Mock
    private LostFoundReportRepository reportRepository;
    @Mock
    private LostFoundImageRepository imageRepository;
    @Mock
    private ObjectStorageService storageService;
    @Mock
    private LostFoundEmbeddingClient embeddingClient;

    private LostFoundEmbeddingBackfillJob job;

    @BeforeEach
    void setUp() {
        job = new LostFoundEmbeddingBackfillJob(
                reportRepository, imageRepository, storageService, embeddingClient);
        ReflectionTestUtils.setField(job, "enabled", true);
        ReflectionTestUtils.setField(job, "batchSize", 20);
        ReflectionTestUtils.setField(job, "textRevision", "text-new");
        ReflectionTestUtils.setField(job, "imageRevision", "image-new");
        ReflectionTestUtils.setField(job, "crossModalRevision", "cross-new");
        ReflectionTestUtils.setField(job, "crossModalEnabled", "auto");
    }

    @Test
    void recalculatesMissingOrOldRevisionTextVectors() {
        LostFoundReport report = report();
        when(embeddingClient.enabled()).thenReturn(true);
        when(reportRepository.findNeedingTextEmbedding(
                anyString(), anyString(), anyBoolean(), any()))
                .thenReturn(new PageImpl<>(List.of(report)));
        when(imageRepository.findNeedingVisualEmbedding(anyString(), any()))
                .thenReturn(new PageImpl<>(List.of()));
        when(embeddingClient.embedDocument(anyString())).thenReturn(Optional.of(
                new TextEmbeddingBundle(
                        embedding("e5", "text-new"),
                        embedding("clip-text", "cross-new"),
                        true)));

        job.backfill();

        assertThat(report.getSemanticTextRevision()).isEqualTo("text-new");
        assertThat(report.getCrossModalTextRevision()).isEqualTo("cross-new");
        assertThat(report.getEmbeddingStatus()).isEqualTo(EmbeddingStatus.READY);
    }

    @Test
    void skipsAllWorkWhenBackfillIsDisabled() {
        ReflectionTestUtils.setField(job, "enabled", false);

        job.backfill();

        verify(embeddingClient, never()).embedDocument(anyString());
        verify(reportRepository, never()).findNeedingTextEmbedding(
                anyString(), anyString(), anyBoolean(), any());
    }

    private static StoredEmbedding embedding(String model, String revision) {
        return new StoredEmbedding(new byte[]{0, 0, (byte) 128, 63}, model, revision, 1);
    }

    private static LostFoundReport report() {
        return new LostFoundReport(
                ReportType.FOUND,
                "Black headphones",
                ItemCategory.ELECTRONICS,
                "Black wireless headphones in a charging case",
                "Black",
                "Central Library",
                LocalDate.of(2026, 8, 9),
                "15:00",
                new User("owner@example.com", "encoded"));
    }
}
