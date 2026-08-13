package com.app.campusagent.lostfound.service;

import com.app.campusagent.domain.User;
import com.app.campusagent.lostfound.dto.StagedImageResponse;
import com.app.campusagent.lostfound.domain.LostFoundStagedImage;
import com.app.campusagent.lostfound.embedding.LostFoundEmbeddingClient;
import com.app.campusagent.lostfound.embedding.StoredEmbedding;
import com.app.campusagent.lostfound.exception.LostFoundApiException;
import com.app.campusagent.lostfound.repository.LostFoundStagedImageRepository;
import com.app.campusagent.lostfound.visual.VisualFingerprintExtractor;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.messages.Item;
import io.minio.Result;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Base64;
import java.util.LinkedHashMap;

/**
 * Agent 图片暂存服务：上传到 MinIO {@code lost-found-staging/} 前缀并在上传时算好
 * 视觉指纹，供 Agent 确认创建时把 objectKey 关联为报告图片。
 *
 * <p>暂存对象在上传时把原始文件名写入 user metadata，并携带 Content-Type，
 * 以便 {@link #retrieve(String)} 重建 {@code LostFoundImage} 行的元数据。未确认即放弃
 * 的孤儿图由 {@link LostFoundImageStagingCleanupJob} 按 TTL 清理；已被 DB 引用的键会被跳过。</p>
 */
@Service
public class LostFoundImageStagingService {

    public static final String PREFIX = "lost-found-staging/";
    private static final String ORIGINAL_NAME_KEY = "original-name";

    /** 暂存对象内容与元数据，供创建报告时关联落库。 */
    public record StagedImage(
            String objectKey,
            byte[] content,
            String contentType,
            String originalName,
            long fileSize,
            byte[] visualEmbedding,
            String visualEmbeddingModel,
            String visualEmbeddingRevision) {

        public StagedImage {
            content = content == null ? null : content.clone();
            visualEmbedding = visualEmbedding == null ? null : visualEmbedding.clone();
        }

        public StagedImage(
                String objectKey,
                byte[] content,
                String contentType,
                String originalName,
                long fileSize) {
            this(objectKey, content, contentType, originalName, fileSize, null, null, null);
        }

        @Override
        public byte[] content() {
            return content == null ? null : content.clone();
        }

        @Override
        public byte[] visualEmbedding() {
            return visualEmbedding == null ? null : visualEmbedding.clone();
        }
    }

    /** TTL 清理用对象摘要。 */
    public record StagedObjectSummary(String objectKey, Instant lastModified) {
    }

    private final MinioClient minioClient;
    private final String bucket;
    private final LostFoundStagedImageRepository stagedRepository;
    private final LostFoundEmbeddingClient embeddingClient;
    private final Duration ttl;

    public LostFoundImageStagingService(
            MinioClient minioClient,
            @Value("${app.storage.bucket:campuslink}") String bucket,
            LostFoundStagedImageRepository stagedRepository,
            LostFoundEmbeddingClient embeddingClient,
            @Value("${app.lost-found.staging-ttl-ms:86400000}") long ttlMillis) {
        this.minioClient = minioClient;
        this.bucket = bucket;
        this.stagedRepository = stagedRepository;
        this.embeddingClient = embeddingClient;
        this.ttl = Duration.ofMillis(ttlMillis);
    }

    public StagedImageResponse upload(MultipartFile file, User currentUser) {
        LostFoundImageRules.validateSingle(file);
        String contentType = file.getContentType();
        String objectName = UUID.randomUUID() + extensionFor(contentType);
        String objectKey = PREFIX + objectName;
        String originalName = safeOriginalName(file.getOriginalFilename());
        try {
            ensureBucketExists();
            try (InputStream input = file.getInputStream()) {
                minioClient.putObject(PutObjectArgs.builder()
                        .bucket(bucket)
                        .object(objectKey)
                        .stream(input, file.getSize(), -1)
                        .contentType(contentType)
                        .userMetadata(Map.of(ORIGINAL_NAME_KEY, originalName))
                        .build());
            }
            byte[] bytes = file.getBytes();
            String fingerprint = VisualFingerprintExtractor.extract(bytes, contentType);
            List<StoredEmbedding> pretrained = embeddingClient.embedImages(List.of(
                    new LostFoundEmbeddingClient.ImageInput(bytes, contentType, originalName)));
            StoredEmbedding embedding = pretrained.isEmpty() ? null : pretrained.getFirst();
            stagedRepository.save(new LostFoundStagedImage(
                    objectKey,
                    currentUser,
                    fingerprint,
                    embedding == null ? null : embedding.vector(),
                    embedding == null ? null : embedding.model(),
                    embedding == null ? null : embedding.revision(),
                    Instant.now().plus(ttl)));
            return new StagedImageResponse(
                    objectKey,
                    fingerprint,
                    "/api/lost-found/images/staging/" + objectName,
                    contentType,
                    originalName,
                    file.getSize(),
                    embedding == null ? "PENDING" : "READY");
        } catch (IOException ex) {
            delete(objectKey);
            throw new LostFoundApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "IMAGE_READ_FAILED",
                    "The uploaded image could not be read",
                    ex);
        } catch (Exception ex) {
            delete(objectKey);
            throw new LostFoundApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "OBJECT_STORAGE_UNAVAILABLE",
                    "Image storage is temporarily unavailable",
                    ex);
        }
    }

    /**
     * 读取暂存对象及其元数据。对象不存在（如已被 TTL 清理）时抛 NOT_FOUND，
     * 使报告创建整体回滚，避免产生"有记录无图"或"有图无记录"的半态。
     */
    public StagedImage retrieve(String objectKey) {
        if (objectKey == null || !objectKey.startsWith(PREFIX)) {
            throw notFound();
        }
        try {
            StatObjectResponse stat = minioClient.statObject(StatObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .build());
            String contentType = stat.contentType();
            String originalName = stat.userMetadata().getOrDefault(ORIGINAL_NAME_KEY, objectKey);
            byte[] content = download(objectKey);
            return new StagedImage(objectKey, content, contentType, originalName, stat.size());
        } catch (Exception ex) {
            throw new LostFoundApiException(
                    HttpStatus.NOT_FOUND,
                    "STAGED_IMAGE_NOT_FOUND",
                    "The staged image does not exist",
                    ex);
        }
    }

    /** 报告创建只接受当前用户上传且未过期的暂存图片。 */
    public StagedImage retrieveOwned(String objectKey, User currentUser) {
        LostFoundStagedImage metadata = stagedRepository
                .findByObjectKeyAndCreatedById(objectKey, currentUser.getId())
                .filter(value -> value.getExpiresAt().isAfter(Instant.now()))
                .orElseThrow(this::notFound);
        StagedImage stored = retrieve(objectKey);
        return new StagedImage(
                stored.objectKey(),
                stored.content(),
                stored.contentType(),
                stored.originalName(),
                stored.fileSize(),
                metadata.getVisualEmbedding(),
                metadata.getVisualEmbeddingModel(),
                metadata.getVisualEmbeddingRevision());
    }

    public List<Map<String, Object>> trustedAgentImages(
            List<com.app.campusagent.lostfound.dto.agent.AgentWebInvokeRequest.AgentImage> images,
            User currentUser) {
        if (images == null || images.isEmpty()) {
            return List.of();
        }
        return images.stream().map(image -> {
            LostFoundStagedImage metadata = stagedRepository
                    .findByObjectKeyAndCreatedById(image.objectKey(), currentUser.getId())
                    .filter(value -> value.getExpiresAt().isAfter(Instant.now()))
                    .orElseThrow(this::notFound);
            Map<String, Object> trusted = new LinkedHashMap<>();
            trusted.put("object_key", metadata.getObjectKey());
            trusted.put("visual_fingerprint", metadata.getVisualFingerprint());
            trusted.put("url", image.url());
            if (metadata.getVisualEmbedding() != null) {
                trusted.put("visual_embedding", Base64.getEncoder()
                        .encodeToString(metadata.getVisualEmbedding()));
                trusted.put("visual_embedding_model", metadata.getVisualEmbeddingModel());
                trusted.put("visual_embedding_revision", metadata.getVisualEmbeddingRevision());
            }
            return trusted;
        }).toList();
    }

    /** 图片已经关联正式报告后只删除暂存元数据，保留 MinIO 对象。 */
    public void consume(List<String> objectKeys) {
        if (objectKeys != null && !objectKeys.isEmpty()) {
            stagedRepository.deleteAllById(objectKeys);
        }
    }

    public void delete(String objectKey) {
        if (objectKey == null || !objectKey.startsWith(PREFIX)) {
            return;
        }
        try {
            stagedRepository.deleteById(objectKey);
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .build());
        } catch (Exception ignored) {
            // Best-effort cleanup; orphan objects can be removed by a later TTL run.
        }
    }

    public List<StagedObjectSummary> list() {
        List<StagedObjectSummary> result = new ArrayList<>();
        try {
            Iterable<Result<Item>> objects = minioClient.listObjects(ListObjectsArgs.builder()
                    .bucket(bucket)
                    .prefix(PREFIX)
                    .build());
            for (Result<Item> object : objects) {
                Item item = object.get();
                result.add(new StagedObjectSummary(
                        item.objectName(),
                        item.lastModified() == null ? Instant.EPOCH : item.lastModified().toInstant()));
            }
        } catch (Exception ex) {
            throw new LostFoundApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "OBJECT_STORAGE_UNAVAILABLE",
                    "Image storage is temporarily unavailable",
                    ex);
        }
        return result;
    }

    private byte[] download(String objectKey) {
        try (InputStream input = minioClient.getObject(GetObjectArgs.builder()
                .bucket(bucket)
                .object(objectKey)
                .build())) {
            return input.readAllBytes();
        } catch (Exception ex) {
            throw new LostFoundApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "OBJECT_STORAGE_UNAVAILABLE",
                    "Image download is temporarily unavailable",
                    ex);
        }
    }

    private void ensureBucketExists() throws Exception {
        boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
        if (!exists) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
    }

    private String extensionFor(String contentType) {
        if ("image/jpeg".equals(contentType)) {
            return ".jpg";
        }
        if ("image/png".equals(contentType)) {
            return ".png";
        }
        if ("image/webp".equals(contentType)) {
            return ".webp";
        }
        return "";
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

    private LostFoundApiException notFound() {
        return new LostFoundApiException(
                HttpStatus.NOT_FOUND,
                "STAGED_IMAGE_NOT_FOUND",
                "The staged image does not exist");
    }
}
