package com.app.campusagent.lostfound.service;

import com.app.campusagent.domain.Role;
import com.app.campusagent.domain.User;
import com.app.campusagent.lostfound.domain.ItemCategory;
import com.app.campusagent.lostfound.domain.LostFoundAuditAction;
import com.app.campusagent.lostfound.domain.LostFoundImage;
import com.app.campusagent.lostfound.domain.LostFoundReport;
import com.app.campusagent.lostfound.domain.ReportStatus;
import com.app.campusagent.lostfound.domain.ReportType;
import com.app.campusagent.lostfound.dto.CreateLostFoundReportRequest;
import com.app.campusagent.lostfound.dto.LostFoundImageResponse;
import com.app.campusagent.lostfound.dto.LostFoundReportResponse;
import com.app.campusagent.lostfound.dto.PageResponse;
import com.app.campusagent.lostfound.dto.UpdateLostFoundReportRequest;
import com.app.campusagent.lostfound.dto.agent.AgentCandidateResponse;
import com.app.campusagent.lostfound.exception.LostFoundApiException;
import com.app.campusagent.lostfound.repository.LostFoundClaimRepository;
import com.app.campusagent.lostfound.repository.LostFoundNotificationRepository;
import com.app.campusagent.lostfound.repository.LostFoundReportRepository;
import com.app.campusagent.lostfound.storage.ObjectStorageService;
import com.app.campusagent.lostfound.storage.StoredObject;
import com.app.campusagent.lostfound.visual.VisualFingerprintExtractor;
import jakarta.persistence.criteria.Predicate;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class LostFoundReportService {

    private static final int MAX_IMAGES = 5;
    private static final long MAX_IMAGE_SIZE = 10L * 1024L * 1024L;
    private static final int MAX_IMAGE_DIMENSION = 8192;
    private static final List<String> ALLOWED_TYPES = List.of(
            "image/jpeg", "image/png", "image/webp");

    private final LostFoundReportRepository reportRepository;
    private final ObjectStorageService storageService;
    private final LostFoundClaimRepository claimRepository;
    private final LostFoundNotificationRepository notificationRepository;
    private final LostFoundAuditService auditService;

    public LostFoundReportService(
            LostFoundReportRepository reportRepository,
            ObjectStorageService storageService,
            LostFoundClaimRepository claimRepository,
            LostFoundNotificationRepository notificationRepository,
            LostFoundAuditService auditService) {
        this.reportRepository = reportRepository;
        this.storageService = storageService;
        this.claimRepository = claimRepository;
        this.notificationRepository = notificationRepository;
        this.auditService = auditService;
    }

    @Transactional
    public LostFoundReportResponse create(
            CreateLostFoundReportRequest request,
            List<MultipartFile> images,
            User currentUser) {
        List<MultipartFile> safeImages = images == null ? List.of() : images;
        validateImages(safeImages);

        LostFoundReport report = new LostFoundReport(
                request.reportType(),
                request.itemName().trim(),
                request.category(),
                request.description().trim(),
                trimToNull(request.colour()),
                request.location().trim(),
                request.eventDate(),
                trimToNull(request.timeDescription()),
                currentUser);

        List<StoredObject> uploaded = new ArrayList<>();
        registerRollbackCleanup(uploaded);
        try {
            for (int index = 0; index < safeImages.size(); index++) {
                MultipartFile image = safeImages.get(index);
                String fingerprint = visualFingerprint(image);
                StoredObject stored = storageService.upload(image);
                uploaded.add(stored);
                report.addImage(new LostFoundImage(
                        stored.objectKey(),
                        safeOriginalName(stored.originalName()),
                        stored.contentType(),
                        stored.size(),
                        index,
                        fingerprint));
            }
            LostFoundReport saved = reportRepository.saveAndFlush(report);
            auditService.record(
                    LostFoundAuditAction.REPORT_CREATED,
                    saved.getId(),
                    saved.getItemName(),
                    currentUser,
                    null,
                    "images=" + safeImages.size());
            return toResponse(saved, currentUser);
        } catch (RuntimeException ex) {
            uploaded.forEach(stored -> storageService.delete(stored.objectKey()));
            uploaded.clear();
            throw ex;
        }
    }

    @Transactional(readOnly = true)
    public PageResponse<LostFoundReportResponse> search(
            ReportType reportType,
            String keyword,
            ItemCategory category,
            String colour,
            String location,
            LocalDate dateFrom,
            LocalDate dateTo,
            ReportStatus status,
            Pageable pageable,
            User currentUser) {
        Specification<LostFoundReport> specification = specification(
                reportType, keyword, category, colour, location, dateFrom, dateTo, status);

        Page<LostFoundReportResponse> result = reportRepository.findAll(specification, pageable)
                .map(report -> toResponse(report, currentUser));
        return PageResponse.from(result);
    }

    @Transactional(readOnly = true)
    public PageResponse<AgentCandidateResponse> searchCandidates(
            String keyword,
            ItemCategory category,
            String colour,
            String location,
            LocalDate dateFrom,
            LocalDate dateTo,
            Pageable pageable) {
        Specification<LostFoundReport> specification = specification(
                ReportType.FOUND,
                keyword,
                category,
                colour,
                location,
                dateFrom,
                dateTo,
                ReportStatus.OPEN);
        return PageResponse.from(reportRepository.findAll(specification, pageable)
                .map(report -> new AgentCandidateResponse(
                        report.getId(),
                        report.getItemName(),
                        report.getCategory(),
                        report.getDescription(),
                        report.getColour(),
                        report.getLocation(),
                        report.getEventDate(),
                        report.getTimeDescription(),
                        report.getStatus(),
                        visualFingerprints(report))));
    }

    @Transactional(readOnly = true)
    public LostFoundReportResponse getById(Long reportId, User currentUser) {
        LostFoundReport report = requireReport(reportId);
        if (report.isAdminHidden()) {
            boolean owner = report.getCreatedBy().getId().equals(currentUser.getId());
            boolean admin = currentUser.getRole() == Role.ADMIN
                    || currentUser.getRole() == Role.SUPER_ADMIN;
            if (!owner && !admin) {
                throw new LostFoundApiException(
                        HttpStatus.NOT_FOUND,
                        "LOST_FOUND_REPORT_NOT_FOUND",
                        "The requested report does not exist");
            }
        }
        return toResponse(report, currentUser);
    }

    @Transactional(readOnly = true)
    public LostFoundReport requireReport(Long reportId) {
        return reportRepository.findById(reportId)
                .orElseThrow(() -> new LostFoundApiException(
                        HttpStatus.NOT_FOUND,
                        "LOST_FOUND_REPORT_NOT_FOUND",
                        "The requested report does not exist"));
    }

    @Transactional
    public LostFoundReportResponse update(
            Long reportId,
            UpdateLostFoundReportRequest request,
            List<MultipartFile> images,
            User currentUser) {
        LostFoundReport report = requireReport(reportId);
        assertOwner(report, currentUser, "REPORT_EDIT_FORBIDDEN",
                "Only the report creator can edit this report");
        if (report.getStatus() != ReportStatus.OPEN) {
            throw conflict("REPORT_NOT_EDITABLE", "Only open reports can be edited");
        }

        boolean imagesReplaced = images != null && !images.isEmpty();
        if (imagesReplaced) {
            replaceImages(report, images);
        }
        report.updateDetails(
                request.itemName().trim(),
                request.category(),
                request.description().trim(),
                trimToNull(request.colour()),
                request.location().trim(),
                request.eventDate(),
                trimToNull(request.timeDescription()));
        LostFoundReport saved = reportRepository.save(report);
        auditService.record(
                LostFoundAuditAction.REPORT_UPDATED,
                reportId,
                saved.getItemName(),
                currentUser,
                null,
                "imagesReplaced=" + imagesReplaced);
        return toResponse(saved, currentUser);
    }

    @Transactional
    public LostFoundReportResponse close(Long reportId, User currentUser) {
        LostFoundReport report = requireReport(reportId);
        assertOwner(report, currentUser, "REPORT_CLOSE_FORBIDDEN",
                "Only the report creator can close this report");
        if (report.getStatus() != ReportStatus.OPEN) {
            throw conflict("REPORT_NOT_OPEN", "This report is no longer open");
        }
        report.markClosed();
        LostFoundReport saved = reportRepository.save(report);
        auditService.record(
                LostFoundAuditAction.REPORT_CLOSED,
                reportId,
                saved.getItemName(),
                currentUser,
                null,
                "status=OPEN→CLOSED");
        return toResponse(saved, currentUser);
    }

    @Transactional
    public void delete(Long reportId, User currentUser) {
        LostFoundReport report = requireReport(reportId);
        assertOwner(report, currentUser, "REPORT_DELETE_FORBIDDEN",
                "Only the report creator can delete this report");
        if (report.getStatus() != ReportStatus.OPEN) {
            throw conflict("REPORT_NOT_DELETABLE", "Only open reports can be deleted");
        }
        String itemName = report.getItemName();
        int imageCount = report.imageObjectKeys().size();
        deleteReportAndCleanup(report);
        auditService.record(
                LostFoundAuditAction.REPORT_DELETED,
                reportId,
                itemName,
                currentUser,
                null,
                "status=OPEN→DELETED, images=" + imageCount);
    }

    /**
     * 管理员删除：不校验 owner 与状态（由管理接口的 ADMIN/SUPER_ADMIN 权限兜底），
     * 复用 owner 删除的级联清理。审计行在 {@code deleteReportAndCleanup} 之后
     * 由调用方写入，reportId 为无外键普通列，报告删除后仍保留。
     */
    @Transactional
    public void deleteAsAdmin(Long reportId) {
        deleteReportAndCleanup(requireReport(reportId));
    }

    /** 硬删除报告并级联清理通知、认领与 MinIO 对象；审计日志不入级联。 */
    private void deleteReportAndCleanup(LostFoundReport report) {
        List<String> objectKeys = report.imageObjectKeys();
        notificationRepository.deleteByReportId(report.getId());
        claimRepository.deleteByReportId(report.getId());
        reportRepository.delete(report);
        reportRepository.flush();
        objectKeys.forEach(storageService::delete);
    }

    private void replaceImages(LostFoundReport report, List<MultipartFile> images) {
        validateImages(images);
        List<String> oldKeys = report.imageObjectKeys();
        List<LostFoundImage> newImages = new ArrayList<>();
        List<StoredObject> uploaded = new ArrayList<>();
        registerRollbackCleanup(uploaded);
        try {
            for (int index = 0; index < images.size(); index++) {
                MultipartFile image = images.get(index);
                String fingerprint = visualFingerprint(image);
                StoredObject stored = storageService.upload(image);
                uploaded.add(stored);
                newImages.add(new LostFoundImage(
                        stored.objectKey(),
                        safeOriginalName(stored.originalName()),
                        stored.contentType(),
                        stored.size(),
                        index,
                        fingerprint));
            }
            report.replaceImages(newImages);
            oldKeys.forEach(storageService::delete);
        } catch (RuntimeException ex) {
            uploaded.forEach(stored -> storageService.delete(stored.objectKey()));
            uploaded.clear();
            throw ex;
        }
    }

    private void assertOwner(LostFoundReport report, User currentUser,
                             String code, String message) {
        if (!report.getCreatedBy().getId().equals(currentUser.getId())) {
            throw new LostFoundApiException(HttpStatus.FORBIDDEN, code, message);
        }
    }

    private LostFoundApiException conflict(String code, String message) {
        return new LostFoundApiException(HttpStatus.CONFLICT, code, message);
    }

    private Specification<LostFoundReport> specification(
            ReportType reportType,
            String keyword,
            ItemCategory category,
            String colour,
            String location,
            LocalDate dateFrom,
            LocalDate dateTo,
            ReportStatus status) {
        if (dateFrom != null && dateTo != null && dateFrom.isAfter(dateTo)) {
            throw new LostFoundApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "INVALID_DATE_RANGE",
                    "dateFrom must be on or before dateTo");
        }
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(builder.isFalse(root.get("adminHidden")));
            if (reportType != null) {
                predicates.add(builder.equal(root.get("reportType"), reportType));
            }
            if (category != null) {
                predicates.add(builder.equal(root.get("category"), category));
            }
            if (status != null) {
                predicates.add(builder.equal(root.get("status"), status));
            }
            if (StringUtils.hasText(keyword)) {
                String pattern = likePattern(keyword);
                predicates.add(builder.or(
                        builder.like(builder.lower(root.get("itemName")), pattern),
                        builder.like(builder.lower(root.get("description")), pattern)));
            }
            if (StringUtils.hasText(colour)) {
                predicates.add(builder.like(builder.lower(root.get("colour")), likePattern(colour)));
            }
            if (StringUtils.hasText(location)) {
                predicates.add(builder.like(builder.lower(root.get("location")), likePattern(location)));
            }
            if (dateFrom != null) {
                predicates.add(builder.greaterThanOrEqualTo(root.get("eventDate"), dateFrom));
            }
            if (dateTo != null) {
                predicates.add(builder.lessThanOrEqualTo(root.get("eventDate"), dateTo));
            }
            return builder.and(predicates.toArray(Predicate[]::new));
        };
    }

    private LostFoundReportResponse toResponse(LostFoundReport report, User currentUser) {
        List<LostFoundImageResponse> images = report.getImages().stream()
                .sorted(Comparator.comparingInt(LostFoundImage::getSortOrder))
                .map(image -> new LostFoundImageResponse(
                        image.getId(),
                        storageService.createPresignedGetUrl(image.getObjectKey()),
                        image.getContentType(),
                        image.getFileSize(),
                        image.getSortOrder()))
                .toList();

        return new LostFoundReportResponse(
                report.getId(),
                report.getReportType(),
                report.getItemName(),
                report.getCategory(),
                report.getDescription(),
                report.getColour(),
                report.getLocation(),
                report.getEventDate(),
                report.getTimeDescription(),
                report.getStatus(),
                images,
                report.getCreatedBy().getId().equals(currentUser.getId()),
                report.getCreatedAt(),
                report.getUpdatedAt());
    }

    private void validateImages(List<MultipartFile> images) {
        if (images.size() > MAX_IMAGES) {
            throw new LostFoundApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "TOO_MANY_IMAGES",
                    "A report can contain at most 5 images");
        }
        for (MultipartFile image : images) {
            if (image == null || image.isEmpty()) {
                throw new LostFoundApiException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "EMPTY_IMAGE",
                        "Uploaded images cannot be empty");
            }
            if (image.getSize() > MAX_IMAGE_SIZE) {
                throw new LostFoundApiException(
                        HttpStatus.PAYLOAD_TOO_LARGE,
                        "IMAGE_TOO_LARGE",
                        "Each image must be 10 MB or smaller");
            }
            String contentType = image.getContentType();
            if (!ALLOWED_TYPES.contains(contentType) || !matchesMagicBytes(image, contentType)) {
                throw new LostFoundApiException(
                        HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                        "UNSUPPORTED_IMAGE_TYPE",
                        "Only valid JPEG, PNG and WebP images are accepted");
            }
            validateImageDimensions(image);
        }
    }

    /**
     * 读取图片头部尺寸（不整图解码），拒绝超大尺寸以防御解压炸弹。
     * 仅当 ImageIO 能识别格式时才检查；WebP 等无法识别的格式在指纹
     * 提取时走 SHA-256 回退、不触发解码，无解压炸弹风险，直接跳过。
     */
    private void validateImageDimensions(MultipartFile image) {
        try (InputStream input = image.getInputStream()) {
            try (ImageInputStream imageInput = ImageIO.createImageInputStream(input)) {
                if (imageInput == null) {
                    return;
                }
                Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInput);
                if (!readers.hasNext()) {
                    return;
                }
                ImageReader reader = readers.next();
                try {
                    reader.setInput(imageInput, true, true);
                    int width = reader.getWidth(0);
                    int height = reader.getHeight(0);
                    if (width > MAX_IMAGE_DIMENSION || height > MAX_IMAGE_DIMENSION) {
                        throw new LostFoundApiException(
                                HttpStatus.UNPROCESSABLE_ENTITY,
                                "IMAGE_DIMENSION_TOO_LARGE",
                                "Each image must be at most " + MAX_IMAGE_DIMENSION
                                        + " pixels per side");
                    }
                } finally {
                    reader.dispose();
                }
            }
        } catch (IOException ex) {
            throw new LostFoundApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "IMAGE_READ_FAILED",
                    "The uploaded image could not be read",
                    ex);
        }
    }

    private boolean matchesMagicBytes(MultipartFile file, String contentType) {
        try (InputStream input = file.getInputStream()) {
            byte[] header = input.readNBytes(12);
            if ("image/jpeg".equals(contentType)) {
                return header.length >= 3
                        && (header[0] & 0xff) == 0xff
                        && (header[1] & 0xff) == 0xd8
                        && (header[2] & 0xff) == 0xff;
            }
            if ("image/png".equals(contentType)) {
                byte[] png = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
                if (header.length < png.length) {
                    return false;
                }
                for (int i = 0; i < png.length; i++) {
                    if (header[i] != png[i]) {
                        return false;
                    }
                }
                return true;
            }
            return "image/webp".equals(contentType)
                    && header.length >= 12
                    && new String(header, 0, 4, StandardCharsets.US_ASCII).equals("RIFF")
                    && new String(header, 8, 4, StandardCharsets.US_ASCII).equals("WEBP");
        } catch (IOException ex) {
            throw new LostFoundApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "IMAGE_READ_FAILED",
                    "The uploaded image could not be read",
                    ex);
        }
    }

    private List<String> visualFingerprints(LostFoundReport report) {
        return report.getImages().stream()
                .sorted(Comparator.comparingInt(LostFoundImage::getSortOrder))
                .map(LostFoundImage::getVisualFingerprint)
                .filter(Objects::nonNull)
                .toList();
    }

    private String visualFingerprint(MultipartFile image) {
        try {
            return VisualFingerprintExtractor.extract(image.getBytes(), image.getContentType());
        } catch (IOException ex) {
            throw new LostFoundApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "IMAGE_READ_FAILED",
                    "The uploaded image could not be read",
                    ex);
        }
    }

    private String safeOriginalName(String name) {
        String safe;
        try {
            safe = Path.of(name).getFileName().toString();
        } catch (RuntimeException ex) {
            safe = "image";
        }
        return safe.length() <= 255 ? safe : safe.substring(safe.length() - 255);
    }

    private void registerRollbackCleanup(List<StoredObject> uploaded) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != TransactionSynchronization.STATUS_COMMITTED) {
                    uploaded.forEach(stored -> storageService.delete(stored.objectKey()));
                }
            }
        });
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String likePattern(String value) {
        return "%" + value.trim().toLowerCase(Locale.ROOT) + "%";
    }
}
