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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class LostFoundReportService {

    private final LostFoundReportRepository reportRepository;
    private final ObjectStorageService storageService;
    private final LostFoundClaimRepository claimRepository;
    private final LostFoundNotificationRepository notificationRepository;
    private final LostFoundAuditService auditService;
    private final LostFoundImageStagingService stagingService;

    public LostFoundReportService(
            LostFoundReportRepository reportRepository,
            ObjectStorageService storageService,
            LostFoundClaimRepository claimRepository,
            LostFoundNotificationRepository notificationRepository,
            LostFoundAuditService auditService,
            LostFoundImageStagingService stagingService) {
        this.reportRepository = reportRepository;
        this.storageService = storageService;
        this.claimRepository = claimRepository;
        this.notificationRepository = notificationRepository;
        this.auditService = auditService;
        this.stagingService = stagingService;
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

    /**
     * Agent 确认创建：把已暂存的 objectKey 关联为新报告的图片。
     *
     * <p>暂存图已存在于 MinIO（{@code lost-found-staging/} 前缀），此处只下载字节
     * 计算指纹并建立 {@link LostFoundImage} 行，objectKey 复用暂存键。行创建后该键
     * 被 DB 引用，TTL 清理任务会自动跳过；若任一暂存对象缺失（如已被 TTL 清理），
     * 整个创建回滚，不产生"有记录无图"或"有图无记录"的半态。</p>
     */
    @Transactional
    public LostFoundReportResponse createFromStaged(
            CreateLostFoundReportRequest request,
            List<String> stagedImageKeys,
            User currentUser) {
        List<String> keys = stagedImageKeys == null ? List.of() : stagedImageKeys;
        LostFoundImageRules.validateCount(keys.size());

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

        for (int index = 0; index < keys.size(); index++) {
            LostFoundImageStagingService.StagedImage staged = stagingService.retrieve(keys.get(index));
            String fingerprint = VisualFingerprintExtractor.extract(
                    staged.content(), staged.contentType());
            report.addImage(new LostFoundImage(
                    staged.objectKey(),
                    safeOriginalName(staged.originalName()),
                    staged.contentType(),
                    staged.fileSize(),
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
                "images=" + keys.size() + ", staged=true");
        return toResponse(saved, currentUser);
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
            ReportType reportType,
            String keyword,
            ItemCategory category,
            String colour,
            String location,
            LocalDate dateFrom,
            LocalDate dateTo,
            Pageable pageable) {
        Specification<LostFoundReport> specification = specification(
            reportType,
            keyword,
            category,
            colour,
            location,
            dateFrom,
            dateTo,
            ReportStatus.OPEN);

        return PageResponse.from(reportRepository.findAll(specification, pageable)
                .map(report -> {
                    List<LostFoundImage> images = report.getImages().stream()
                            .sorted(Comparator.comparingInt(LostFoundImage::getSortOrder))
                            .toList();
                    return new AgentCandidateResponse(
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
                            images.stream()
                                    .map(image -> LostFoundImageResponse.of(image).url())
                                    .toList(),
                            // 与 imageUrls 同序：无指纹的图片位置为 null，Agent 端跳过
                            images.stream()
                                    .map(LostFoundImage::getVisualFingerprint)
                                    .toList());
                }));
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
                .map(LostFoundImageResponse::of)
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
        LostFoundImageRules.validateAll(images);
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
