package com.app.campusagent.lostfound.service;

import com.app.campusagent.lostfound.domain.LostFoundImage;
import com.app.campusagent.lostfound.repository.LostFoundImageRepository;
import com.app.campusagent.lostfound.storage.ObjectStorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LostFoundImageBackfillServiceTest {

    @Mock
    private LostFoundImageRepository imageRepository;

    @Mock
    private ObjectStorageService storageService;

    @Test
    void backfillsImageWithoutFingerprint() throws Exception {
        LostFoundImage image = image("lost-found/a.png", "image/png");
        var pageable = PageRequest.of(0, 10);
        when(imageRepository.findByVisualFingerprintIsNull(pageable))
                .thenReturn(new PageImpl<>(List.of(image), pageable, 1));
        when(storageService.download("lost-found/a.png")).thenReturn(pngBytes());

        var result = new LostFoundImageBackfillService(imageRepository, storageService).backfill(10);

        assertThat(result.processed()).isEqualTo(1);
        assertThat(result.updated()).isEqualTo(1);
        assertThat(result.failed()).isZero();
        assertThat(image.getVisualFingerprint()).startsWith("VF1:");
        verify(imageRepository).save(image);
    }

    @Test
    void countsFailedDownloadsAndContinues() throws Exception {
        LostFoundImage good = image("lost-found/good.png", "image/png");
        LostFoundImage bad = image("lost-found/bad.png", "image/png");
        var pageable = PageRequest.of(0, 10);
        when(imageRepository.findByVisualFingerprintIsNull(pageable))
                .thenReturn(new PageImpl<>(List.of(good, bad), pageable, 2));
        when(storageService.download("lost-found/good.png")).thenReturn(pngBytes());
        when(storageService.download("lost-found/bad.png")).thenThrow(new RuntimeException("boom"));

        var result = new LostFoundImageBackfillService(imageRepository, storageService).backfill(10);

        assertThat(result.processed()).isEqualTo(2);
        assertThat(result.updated()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(good.getVisualFingerprint()).startsWith("VF1:");
        assertThat(bad.getVisualFingerprint()).isNull();
        verify(imageRepository, never()).save(bad);
    }

    private LostFoundImage image(String key, String contentType) {
        return new LostFoundImage(key, "a.png", contentType, 10L, 0);
    }

    private static byte[] pngBytes() throws Exception {
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
