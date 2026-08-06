package com.app.campusagent.lostfound.service;

import com.app.campusagent.domain.User;
import com.app.campusagent.lostfound.domain.ItemCategory;
import com.app.campusagent.lostfound.domain.LostFoundImage;
import com.app.campusagent.lostfound.domain.LostFoundReport;
import com.app.campusagent.lostfound.domain.ReportStatus;
import com.app.campusagent.lostfound.domain.ReportType;
import com.app.campusagent.lostfound.dto.CreateLostFoundReportRequest;
import com.app.campusagent.lostfound.dto.LostFoundImageResponse;
import com.app.campusagent.lostfound.dto.LostFoundReportResponse;
import com.app.campusagent.lostfound.dto.PageResponse;
import com.app.campusagent.lostfound.exception.LostFoundApiException;
import com.app.campusagent.lostfound.repository.LostFoundReportRepository;
import com.app.campusagent.lostfound.storage.ObjectStorageService;
import com.app.campusagent.lostfound.storage.StoredObject;
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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class LostFoundReportService {

    private static final int MAX_IMAGES = 5;
    private static final long MAX_IMAGE_SIZE = 10L * 1024L * 1024L;
    private static final List<String> ALLOWED_TYPES = List.of(
            "image/jpeg", "image/png", "image/webp");

    private final LostFoundReportRepository reportRepository;
    private final ObjectStorageService storageService;

    public LostFoundReportService(
            LostFoundReportRepository reportRepository,
            ObjectStorageService storageService) {
        this.reportRepository = reportRepository;
        this.storageService = storageService;
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
                StoredObject stored = storageService.upload(safeImages.get(index));
                uploaded.add(stored);
                report.addImage(new LostFoundImage(
                        stored.objectKey(),
                        safeOriginalName(stored.originalName()),
                        stored.contentType(),
                        stored.size(),
                        index));
            }
            LostFoundReport saved = reportRepository.saveAndFlush(report);
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
        if (dateFrom != null && dateTo != null && dateFrom.isAfter(dateTo)) {
            throw new LostFoundApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "INVALID_DATE_RANGE",
                    "dateFrom must be on or before dateTo");
        }

        Specification<LostFoundReport> specification = (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
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
                predicates.add(builder.like(
                        builder.lower(root.get("colour")), likePattern(colour)));
            }
            if (StringUtils.hasText(location)) {
                predicates.add(builder.like(
                        builder.lower(root.get("location")), likePattern(location)));
            }
            if (dateFrom != null) {
                predicates.add(builder.greaterThanOrEqualTo(root.get("eventDate"), dateFrom));
            }
            if (dateTo != null) {
                predicates.add(builder.lessThanOrEqualTo(root.get("eventDate"), dateTo));
            }
            return builder.and(predicates.toArray(Predicate[]::new));
        };

        Page<LostFoundReportResponse> result = reportRepository.findAll(specification, pageable)
                .map(report -> toResponse(report, currentUser));
        return PageResponse.from(result);
    }

    @Transactional(readOnly = true)
    public LostFoundReportResponse getById(Long reportId, User currentUser) {
        return toResponse(requireReport(reportId), currentUser);
    }

    @Transactional(readOnly = true)
    public LostFoundReport requireReport(Long reportId) {
        return reportRepository.findById(reportId)
                .orElseThrow(() -> new LostFoundApiException(
                        HttpStatus.NOT_FOUND,
                        "LOST_FOUND_REPORT_NOT_FOUND",
                        "The requested report does not exist"));
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
