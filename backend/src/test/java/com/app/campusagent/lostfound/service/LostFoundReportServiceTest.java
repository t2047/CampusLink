package com.app.campusagent.lostfound.service;

import com.app.campusagent.domain.Role;
import com.app.campusagent.domain.User;
import com.app.campusagent.lostfound.domain.ItemCategory;
import com.app.campusagent.lostfound.domain.EmbeddingStatus;
import com.app.campusagent.lostfound.domain.LostFoundReport;
import com.app.campusagent.lostfound.domain.ReportStatus;
import com.app.campusagent.lostfound.domain.ReportType;
import com.app.campusagent.lostfound.dto.CreateLostFoundReportRequest;
import com.app.campusagent.lostfound.dto.UpdateLostFoundReportRequest;
import com.app.campusagent.lostfound.exception.LostFoundApiException;
import com.app.campusagent.lostfound.embedding.LostFoundEmbeddingClient;
import com.app.campusagent.lostfound.embedding.StoredEmbedding;
import com.app.campusagent.lostfound.embedding.TextEmbeddingBundle;
import com.app.campusagent.lostfound.repository.LostFoundClaimRepository;
import com.app.campusagent.lostfound.repository.LostFoundNotificationRepository;
import com.app.campusagent.lostfound.repository.LostFoundReportRepository;
import com.app.campusagent.lostfound.storage.ObjectStorageService;
import com.app.campusagent.lostfound.storage.StoredObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.zip.CRC32;

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

    @Mock
    private LostFoundClaimRepository claimRepository;

    @Mock
    private LostFoundNotificationRepository notificationRepository;

    @Mock
    private LostFoundAuditService auditService;

    @Mock
    private LostFoundImageStagingService stagingService;

    @Mock
    private LostFoundEmbeddingClient embeddingClient;

    private LostFoundReportService service;
    private User user;

    @BeforeEach
    void setUp() throws Exception {
        service = new LostFoundReportService(
                reportRepository, storageService, claimRepository,
                notificationRepository, auditService, stagingService, embeddingClient);
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
    void storesPretrainedTextVectorsAndModelRevision() throws Exception {
        StoredEmbedding semantic = new StoredEmbedding(
                new byte[]{0, 0, (byte) 128, 63}, "intfloat/multilingual-e5-small", "text-rev", 1);
        StoredEmbedding cross = new StoredEmbedding(
                new byte[]{0, 0, (byte) 128, 63}, "multilingual-clip", "cross-rev", 1);
        when(embeddingClient.embedDocument(any())).thenReturn(Optional.of(
                new TextEmbeddingBundle(semantic, cross, true)));
        java.util.concurrent.atomic.AtomicReference<LostFoundReport> captured =
                new java.util.concurrent.atomic.AtomicReference<>();
        when(reportRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            LostFoundReport report = invocation.getArgument(0);
            captured.set(report);
            setField(report, "id", 21L);
            setField(report, "createdAt", java.time.Instant.now());
            setField(report, "updatedAt", java.time.Instant.now());
            return report;
        });

        service.create(request(ReportType.LOST), List.of(), user);

        assertThat(captured.get().getSemanticTextEmbedding()).containsExactly(semantic.vector());
        assertThat(captured.get().getSemanticTextRevision()).isEqualTo("text-rev");
        assertThat(captured.get().getCrossModalTextRevision()).isEqualTo("cross-rev");
        assertThat(captured.get().getEmbeddingStatus()).isEqualTo(EmbeddingStatus.READY);
    }

    @Test
    void embeddingFailureDoesNotBlockCreationAndLeavesPendingStatus() throws Exception {
        when(embeddingClient.embedDocument(any())).thenReturn(Optional.empty());
        java.util.concurrent.atomic.AtomicReference<LostFoundReport> captured =
                new java.util.concurrent.atomic.AtomicReference<>();
        when(reportRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            LostFoundReport report = invocation.getArgument(0);
            captured.set(report);
            setField(report, "id", 22L);
            setField(report, "createdAt", java.time.Instant.now());
            setField(report, "updatedAt", java.time.Instant.now());
            return report;
        });

        service.create(request(ReportType.FOUND), List.of(), user);

        assertThat(captured.get().getEmbeddingStatus()).isEqualTo(EmbeddingStatus.PENDING);
        assertThat(captured.get().getSemanticTextEmbedding()).isNull();
    }

    @Test
    void createsReportFromStagedImagesReusingObjectKeyAndFingerprint() throws Exception {
        byte[] png = pngBytes(2, 2);
        LostFoundImageStagingService.StagedImage staged = new LostFoundImageStagingService.StagedImage(
                "lost-found-staging/abc.png", png, "image/png", "item.png", png.length);
        when(stagingService.retrieveOwned("lost-found-staging/abc.png", user)).thenReturn(staged);
        java.util.concurrent.atomic.AtomicReference<LostFoundReport> captured =
                new java.util.concurrent.atomic.AtomicReference<>();
        when(reportRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            LostFoundReport report = invocation.getArgument(0);
            captured.set(report);
            setField(report, "id", 12L);
            setField(report.getImages().getFirst(), "id", 9L);
            setField(report, "createdAt", java.time.Instant.now());
            setField(report, "updatedAt", java.time.Instant.now());
            return report;
        });

        var response = service.createFromStaged(
                request(ReportType.LOST), List.of("lost-found-staging/abc.png"), user);

        assertThat(response.images()).hasSize(1);
        assertThat(response.images().getFirst().url()).isEqualTo("/api/lost-found/images/9");
        assertThat(captured.get().getImages().getFirst().getObjectKey())
                .isEqualTo("lost-found-staging/abc.png");
        // 指纹由暂存字节确定性重算（与上传时同算法），非空即生效
        assertThat(captured.get().getImages().getFirst().getVisualFingerprint()).isNotBlank();
        verify(stagingService).retrieveOwned("lost-found-staging/abc.png", user);
    }

    @Test
    void stagedCreateRollsBackWhenAStagedObjectIsMissing() {
        when(stagingService.retrieveOwned("lost-found-staging/missing.png", user))
                .thenThrow(new LostFoundApiException(
                        HttpStatus.NOT_FOUND, "STAGED_IMAGE_NOT_FOUND", "missing"));

        assertThatThrownBy(() -> service.createFromStaged(
                request(ReportType.LOST), List.of("lost-found-staging/missing.png"), user))
                .isInstanceOf(LostFoundApiException.class)
                .extracting("code")
                .isEqualTo("STAGED_IMAGE_NOT_FOUND");
        verify(reportRepository, never()).saveAndFlush(any());
    }

    @Test
    void rejectsMoreThanFiveStagedImages() {
        List<String> keys = List.of("k1", "k2", "k3", "k4", "k5", "k6");

        assertThatThrownBy(() -> service.createFromStaged(request(ReportType.LOST), keys, user))
                .isInstanceOf(LostFoundApiException.class)
                .extracting("code")
                .isEqualTo("TOO_MANY_IMAGES");
        verify(stagingService, never()).retrieve(any());
    }

    @Test
    void rejectsImageWithOversizedDimensions() throws Exception {
        MockMultipartFile image = new MockMultipartFile(
                "images", "huge.png", MediaType.IMAGE_PNG_VALUE, pngWithDimensions(9000, 9000));

        assertThatThrownBy(() -> service.create(request(ReportType.FOUND), List.of(image), user))
                .isInstanceOf(LostFoundApiException.class)
                .extracting("code")
                .isEqualTo("IMAGE_DIMENSION_TOO_LARGE");
        verify(storageService, never()).upload(any());
    }

    @Test
    void uploadsValidPng() throws Exception {
        MockMultipartFile image = png("item.png");
        when(storageService.upload(image))
                .thenReturn(new StoredObject("lost-found/key.png", "item.png", "image/png", image.getSize()));
        when(reportRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            LostFoundReport report = invocation.getArgument(0);
            setField(report, "id", 11L);
            setField(report.getImages().getFirst(), "id", 7L);
            setField(report, "createdAt", java.time.Instant.now());
            setField(report, "updatedAt", java.time.Instant.now());
            return report;
        });

        var response = service.create(request(ReportType.FOUND), List.of(image), user);

        assertThat(response.images()).hasSize(1);
        assertThat(response.images().getFirst().url()).isEqualTo("/api/lost-found/images/7");
    }

    @Test
    void rejectsMoreThanFiveImages() throws Exception {
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
    void cleansUpAlreadyUploadedObjectsWhenLaterUploadFails() throws Exception {
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

    @Test
    void updateAllowsOwnerToEditTextFields() throws Exception {
        LostFoundReport report = report(ReportStatus.OPEN);
        when(reportRepository.findById(10L)).thenReturn(Optional.of(report));
        when(reportRepository.save(any())).thenAnswer(invocation -> {
            LostFoundReport saved = invocation.getArgument(0);
            setField(saved, "updatedAt", java.time.Instant.now());
            return saved;
        });

        var response = service.update(10L, updateRequest(), null, user);

        assertThat(response.itemName()).isEqualTo("White Earphones");
        assertThat(response.category()).isEqualTo(ItemCategory.ELECTRONICS);
        assertThat(response.location()).isEqualTo("Yale-NUS Library");
        assertThat(report.getItemName()).isEqualTo("White Earphones");
    }

    @Test
    void updateRejectsNonOwner() throws Exception {
        LostFoundReport report = report(ReportStatus.OPEN);
        when(reportRepository.findById(10L)).thenReturn(Optional.of(report));
        User other = new User("other@u.nus.edu", "encoded");
        setField(other, "id", 99L);

        assertThatThrownBy(() -> service.update(10L, updateRequest(), null, other))
                .isInstanceOf(LostFoundApiException.class)
                .extracting("code")
                .isEqualTo("REPORT_EDIT_FORBIDDEN");
    }

    @Test
    void updateRejectsNonOpenReport() throws Exception {
        LostFoundReport report = report(ReportStatus.CLAIMED);
        when(reportRepository.findById(10L)).thenReturn(Optional.of(report));

        assertThatThrownBy(() -> service.update(10L, updateRequest(), null, user))
                .isInstanceOf(LostFoundApiException.class)
                .extracting("code")
                .isEqualTo("REPORT_NOT_EDITABLE");
    }

    @Test
    void updateReplacesImagesAndDeletesOldObjects() throws Exception {
        LostFoundReport report = report(ReportStatus.OPEN);
        setField(report, "images", new java.util.ArrayList<>());
        when(reportRepository.findById(10L)).thenReturn(Optional.of(report));
        MockMultipartFile oldImage = png("old.png");
        MockMultipartFile newImage = png("new.png");
        when(storageService.upload(newImage))
                .thenReturn(new StoredObject("lost-found/new.png", "new.png", "image/png", newImage.getSize()));
        when(reportRepository.save(any())).thenAnswer(invocation -> {
            LostFoundReport saved = invocation.getArgument(0);
            setField(saved.getImages().getFirst(), "id", 8L);
            setField(saved, "updatedAt", java.time.Instant.now());
            return saved;
        });

        var response = service.update(10L, updateRequest(), List.of(newImage), user);

        assertThat(response.images()).hasSize(1);
        assertThat(response.images().getFirst().url()).isEqualTo("/api/lost-found/images/8");
    }

    @Test
    void closeMarksOpenReportClosed() throws Exception {
        LostFoundReport report = report(ReportStatus.OPEN);
        when(reportRepository.findById(10L)).thenReturn(Optional.of(report));
        when(reportRepository.save(any())).thenAnswer(invocation -> {
            LostFoundReport saved = invocation.getArgument(0);
            setField(saved, "updatedAt", java.time.Instant.now());
            return saved;
        });

        var response = service.close(10L, user);

        assertThat(response.status()).isEqualTo(ReportStatus.CLOSED);
        assertThat(report.getStatus()).isEqualTo(ReportStatus.CLOSED);
    }

    @Test
    void closeRejectsNonOwner() throws Exception {
        LostFoundReport report = report(ReportStatus.OPEN);
        when(reportRepository.findById(10L)).thenReturn(Optional.of(report));
        User other = new User("other@u.nus.edu", "encoded");
        setField(other, "id", 99L);

        assertThatThrownBy(() -> service.close(10L, other))
                .isInstanceOf(LostFoundApiException.class)
                .extracting("code")
                .isEqualTo("REPORT_CLOSE_FORBIDDEN");
    }

    @Test
    void closeRejectsNonOpenReport() throws Exception {
        LostFoundReport report = report(ReportStatus.CLAIMED);
        when(reportRepository.findById(10L)).thenReturn(Optional.of(report));

        assertThatThrownBy(() -> service.close(10L, user))
                .isInstanceOf(LostFoundApiException.class)
                .extracting("code")
                .isEqualTo("REPORT_NOT_OPEN");
    }

    @Test
    void deleteCleansClaimsNotificationsAndObjects() throws Exception {
        LostFoundReport report = report(ReportStatus.OPEN);
        report.addImage(new com.app.campusagent.lostfound.domain.LostFoundImage(
                "lost-found/pic.png", "pic.png", "image/png", 1024L, 0));
        when(reportRepository.findById(10L)).thenReturn(Optional.of(report));

        service.delete(10L, user);

        verify(notificationRepository).deleteByReportId(10L);
        verify(claimRepository).deleteByReportId(10L);
        verify(reportRepository).delete(report);
        verify(storageService).delete("lost-found/pic.png");
    }

    @Test
    void deleteRejectsNonOwner() throws Exception {
        LostFoundReport report = report(ReportStatus.OPEN);
        when(reportRepository.findById(10L)).thenReturn(Optional.of(report));
        User other = new User("other@u.nus.edu", "encoded");
        setField(other, "id", 99L);

        assertThatThrownBy(() -> service.delete(10L, other))
                .isInstanceOf(LostFoundApiException.class)
                .extracting("code")
                .isEqualTo("REPORT_DELETE_FORBIDDEN");
    }

    @Test
    void deleteRejectsNonOpenReport() throws Exception {
        LostFoundReport report = report(ReportStatus.CLOSED);
        when(reportRepository.findById(10L)).thenReturn(Optional.of(report));

        assertThatThrownBy(() -> service.delete(10L, user))
                .isInstanceOf(LostFoundApiException.class)
                .extracting("code")
                .isEqualTo("REPORT_NOT_DELETABLE");
    }

    @Test
    void getByIdHidesDelistedReportFromNonOwner() throws Exception {
        LostFoundReport report = report(ReportStatus.OPEN);
        report.hide();
        when(reportRepository.findById(10L)).thenReturn(Optional.of(report));
        User other = new User("other@u.nus.edu", "encoded");
        setField(other, "id", 99L);

        assertThatThrownBy(() -> service.getById(10L, other))
                .isInstanceOf(LostFoundApiException.class)
                .extracting("code")
                .isEqualTo("LOST_FOUND_REPORT_NOT_FOUND");
    }

    @Test
    void getByIdAllowsOwnerToViewDelistedReport() throws Exception {
        LostFoundReport report = report(ReportStatus.OPEN);
        report.hide();
        when(reportRepository.findById(10L)).thenReturn(Optional.of(report));

        var response = service.getById(10L, user);

        assertThat(response.id()).isEqualTo(10L);
        assertThat(response.itemName()).isEqualTo("Black AirPods");
    }

    @Test
    void getByIdAllowsAdminToViewDelistedReport() throws Exception {
        LostFoundReport report = report(ReportStatus.OPEN);
        report.hide();
        when(reportRepository.findById(10L)).thenReturn(Optional.of(report));
        User admin = new User("admin@campuslink.com", "encoded");
        admin.setRole(Role.ADMIN);
        setField(admin, "id", 88L);

        var response = service.getById(10L, admin);

        assertThat(response.id()).isEqualTo(10L);
    }

    private LostFoundReport report(ReportStatus status) throws Exception {
        LostFoundReport report = new LostFoundReport(
                ReportType.FOUND,
                "Black AirPods",
                ItemCategory.ELECTRONICS,
                "Black AirPods with a small scratch on the case.",
                "Black",
                "Central Library",
                LocalDate.now().minusDays(1),
                "Afternoon",
                user);
        setField(report, "id", 10L);
        setField(report, "status", status);
        setField(report, "createdAt", java.time.Instant.now());
        setField(report, "updatedAt", java.time.Instant.now());
        return report;
    }

    private UpdateLostFoundReportRequest updateRequest() {
        return new UpdateLostFoundReportRequest(
                "White Earphones",
                ItemCategory.ELECTRONICS,
                "White wireless earphones in a charging case.",
                "White",
                "Yale-NUS Library",
                LocalDate.now().minusDays(2),
                "Morning");
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

    private MockMultipartFile png(String name) throws Exception {
        return new MockMultipartFile("images", name, MediaType.IMAGE_PNG_VALUE, pngBytes(1, 1));
    }

    /** 生成真实 PNG；按给定宽高改写 IHDR 头部并重算 CRC，用于尺寸校验测试。 */
    private static byte[] pngWithDimensions(int width, int height) throws Exception {
        byte[] bytes = pngBytes(1, 1);
        bytes[16] = (byte) (width >>> 24);
        bytes[17] = (byte) (width >>> 16);
        bytes[18] = (byte) (width >>> 8);
        bytes[19] = (byte) width;
        bytes[20] = (byte) (height >>> 24);
        bytes[21] = (byte) (height >>> 16);
        bytes[22] = (byte) (height >>> 8);
        bytes[23] = (byte) height;
        CRC32 crc = new CRC32();
        crc.update(bytes, 12, 17); // "IHDR" + 13 字节数据
        long value = crc.getValue();
        bytes[29] = (byte) (value >>> 24);
        bytes[30] = (byte) (value >>> 16);
        bytes[31] = (byte) (value >>> 8);
        bytes[32] = (byte) value;
        return bytes;
    }

    private static byte[] pngBytes(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
