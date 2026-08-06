package com.app.campusagent.lostfound.service;

import com.app.campusagent.domain.User;
import com.app.campusagent.lostfound.domain.ItemCategory;
import com.app.campusagent.lostfound.domain.LostFoundReport;
import com.app.campusagent.lostfound.domain.ReportType;
import com.app.campusagent.lostfound.dto.CreateLostFoundReportRequest;
import com.app.campusagent.lostfound.exception.LostFoundApiException;
import com.app.campusagent.lostfound.repository.LostFoundReportRepository;
import com.app.campusagent.lostfound.storage.ObjectStorageService;
import com.app.campusagent.lostfound.storage.StoredObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LostFoundReportServiceTest {

    @Mock
    private LostFoundReportRepository reportRepository;

    @Mock
    private ObjectStorageService storageService;

    private LostFoundReportService service;
    private User user;

    @BeforeEach
    void setUp() throws Exception {
        service = new LostFoundReportService(reportRepository, storageService);
        user = new User("student@u.nus.edu", "encoded");
        setField(user, "id", 7L);
    }

    @Test
    void createsReportWithoutImages() {
        when(reportRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            LostFoundReport report = invocation.getArgument(0);
            setField(report, "id", 10L);
            setField(report, "createdAt", java.time.Instant.now());
            setField(report, "updatedAt", java.time.Instant.now());
            return report;
        });

        var response = service.create(request(ReportType.LOST), List.of(), user);

        assertThat(response.id()).isEqualTo(10L);
        assertThat(response.reportType()).isEqualTo(ReportType.LOST);
        assertThat(response.createdByMe()).isTrue();
        assertThat(response.images()).isEmpty();
    }

    @Test
    void uploadsValidPng() {
        MockMultipartFile image = png("item.png");
        when(storageService.upload(image))
                .thenReturn(new StoredObject("lost-found/key.png", "item.png", "image/png", image.getSize()));
        when(storageService.createPresignedGetUrl("lost-found/key.png"))
                .thenReturn("http://minio/item.png");
        when(reportRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            LostFoundReport report = invocation.getArgument(0);
            setField(report, "id", 11L);
            setField(report, "createdAt", java.time.Instant.now());
            setField(report, "updatedAt", java.time.Instant.now());
            return report;
        });

        var response = service.create(request(ReportType.FOUND), List.of(image), user);

        assertThat(response.images()).hasSize(1);
        assertThat(response.images().getFirst().url()).isEqualTo("http://minio/item.png");
    }

    @Test
    void rejectsMoreThanFiveImages() {
        List<MultipartFile> images = List.of(
                png("1.png"), png("2.png"), png("3.png"),
                png("4.png"), png("5.png"), png("6.png"));

        assertThatThrownBy(() -> service.create(request(ReportType.FOUND), images, user))
                .isInstanceOf(LostFoundApiException.class)
                .extracting("code")
                .isEqualTo("TOO_MANY_IMAGES");
        verify(storageService, never()).upload(any());
    }

    @Test
    void rejectsContentTypeThatDoesNotMatchBytes() {
        MockMultipartFile fake = new MockMultipartFile(
                "images", "fake.png", MediaType.IMAGE_PNG_VALUE, "not-an-image".getBytes());

        assertThatThrownBy(() -> service.create(request(ReportType.FOUND), List.of(fake), user))
                .isInstanceOf(LostFoundApiException.class)
                .extracting("code")
                .isEqualTo("UNSUPPORTED_IMAGE_TYPE");
    }

    @Test
    void rejectsImageLargerThanTenMegabytes() {
        byte[] bytes = new byte[10 * 1024 * 1024 + 1];
        bytes[0] = (byte) 0x89;
        bytes[1] = 0x50;
        bytes[2] = 0x4e;
        bytes[3] = 0x47;
        MockMultipartFile image = new MockMultipartFile(
                "images", "large.png", MediaType.IMAGE_PNG_VALUE, bytes);

        assertThatThrownBy(() -> service.create(request(ReportType.FOUND), List.of(image), user))
                .isInstanceOf(LostFoundApiException.class)
                .extracting("code")
                .isEqualTo("IMAGE_TOO_LARGE");
    }

    @Test
    void rejectsUnsupportedMimeType() {
        MockMultipartFile image = new MockMultipartFile(
                "images", "item.gif", MediaType.IMAGE_GIF_VALUE, "GIF89a".getBytes());

        assertThatThrownBy(() -> service.create(request(ReportType.FOUND), List.of(image), user))
                .isInstanceOf(LostFoundApiException.class)
                .extracting("code")
                .isEqualTo("UNSUPPORTED_IMAGE_TYPE");
    }

    @Test
    void cleansUpAlreadyUploadedObjectsWhenLaterUploadFails() {
        MockMultipartFile first = png("first.png");
        MockMultipartFile second = png("second.png");
        when(storageService.upload(first))
                .thenReturn(new StoredObject("lost-found/first.png", "first.png", "image/png", first.getSize()));
        when(storageService.upload(second)).thenThrow(new LostFoundApiException(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                "OBJECT_STORAGE_UNAVAILABLE",
                "failed"));

        assertThatThrownBy(() -> service.create(
                request(ReportType.FOUND), List.of(first, second), user))
                .isInstanceOf(LostFoundApiException.class);

        verify(storageService).delete("lost-found/first.png");
        verify(reportRepository, never()).saveAndFlush(any());
    }

    private CreateLostFoundReportRequest request(ReportType type) {
        return new CreateLostFoundReportRequest(
                type,
                "Black AirPods",
                ItemCategory.ELECTRONICS,
                "Black AirPods with a small scratch on the case.",
                "Black",
                "Central Library",
                LocalDate.now().minusDays(1),
                "Afternoon");
    }

    private MockMultipartFile png(String name) {
        byte[] bytes = {
                (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
                0x00, 0x00, 0x00, 0x00
        };
        return new MockMultipartFile("images", name, MediaType.IMAGE_PNG_VALUE, bytes);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
