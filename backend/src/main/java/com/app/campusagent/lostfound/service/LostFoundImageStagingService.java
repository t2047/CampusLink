/**
 * Agent 图片暂存服务：负责失物招领模块中"用户先上传图片、随后再确认创建报告"的
 * 两步流程中间态。
 *
 * <p><b>职责</b>：把用户上传的图片存入 MinIO {@code lost-found-staging/} 前缀，并在
 * 上传时同步计算视觉指纹（SHA-256 指纹，可选视觉向量）与 TTL 过期时间，写入
 * {@code LostFoundStagedImage} 元数据表；当报告创建确认时，由
 * {@code LostFoundReportService} 通过 {@link #retrieveOwned} 取回对象并关联为正式报告图片。
 *
 * <p><b>被谁调用</b>：Web 控制器（上传/读取暂存图）、{@code LostFoundReportService}
 * （创建报告时关联）、{@code LostFoundAgentGateway}（向 Agent 下发可信图片元数据）、
 * {@code LostFoundImageStagingCleanupJob}（TTL 清理孤儿图）。
 *
 * <p><b>依赖</b>：MinIO 对象存储、{@code LostFoundStagedImageRepository} 元数据表、
 * {@code LostFoundEmbeddingClient}（视觉向量）、{@code VisualFingerprintExtractor}（指纹）。
 *
 * <p><b>清理策略</b>：未确认即放弃的孤儿图由 {@code LostFoundImageStagingCleanupJob}
 * 按 TTL 清理；已被 DB 正式报告引用的键会被跳过，只删暂存元数据、保留 MinIO 对象。</p>
 */
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

    /** 暂存对象统一前缀，用于命名空间隔离与 TTL 清理扫描。 */
    public static final String PREFIX = "lost-found-staging/";

    /** 写入 MinIO 对象 user metadata 的原始文件名键名。 */
    private static final String ORIGINAL_NAME_KEY = "original-name";

    /**
     * 暂存对象的内容与元数据，供创建报告时关联落库。
     *
     * <p>记录类型（record），content 与 visualEmbedding 为可变数组，因此
     * 在紧凑构造器中做了防御性拷贝；accessor 方法返回前再次克隆，防止外部篡改。</p>
     */
    public record StagedImage(
            String objectKey,
            byte[] content,
            String contentType,
            String originalName,
            long fileSize,
            byte[] visualEmbedding,
            String visualEmbeddingModel,
            String visualEmbeddingRevision) {

        // 紧凑构造器：对字节数组做防御性拷贝，避免调用方直接持有内部数组引用
        public StagedImage {
            content = content == null ? null : content.clone();
            visualEmbedding = visualEmbedding == null ? null : visualEmbedding.clone();
        }

        /** 无向量/模型信息的便捷构造器（向量为 null 的场景）。 */
        public StagedImage(
                String objectKey,
                byte[] content,
                String contentType,
                String originalName,
                long fileSize) {
            this(objectKey, content, contentType, originalName, fileSize, null, null, null);
        }

        /** 覆写 accessor：返回前克隆，防止外部修改内部字节数组。 */
        @Override
        public byte[] content() {
            return content == null ? null : content.clone();
        }

        /** 覆写 accessor：返回前克隆，防止外部修改内部字节数组。 */
        @Override
        public byte[] visualEmbedding() {
            return visualEmbedding == null ? null : visualEmbedding.clone();
        }
    }

    /** TTL 清理用对象摘要：仅记录 objectKey 与最后修改时间。 */
    public record StagedObjectSummary(String objectKey, Instant lastModified) {
    }

    private final MinioClient minioClient;
    private final String bucket;
    private final LostFoundStagedImageRepository stagedRepository;
    private final LostFoundEmbeddingClient embeddingClient;
    private final Duration ttl;

    /**
     * 构造器：注入 MinIO 客户端、存储桶名、暂存元数据仓库、向量客户端与 TTL。
     *
     * @param ttlMillis 暂存对象有效时长（毫秒），默认 86400000 = 24 小时
     */
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

    /**
     * 上传单个图片到暂存区：写入 MinIO + 计算指纹/向量 + 保存元数据。
     *
     * @param file       用户上传的图片（需通过 {@link LostFoundImageRules#validateSingle} 校验）
     * @param currentUser 当前登录用户（成为暂存记录的拥有者）
     * @return 暂存结果：objectKey、指纹、可访问的 URL、Content-Type、原始文件名、大小与向量状态
     * @throws LostFoundApiException 图片读取失败（422）或对象存储不可用（503）时抛出
     */
    public StagedImageResponse upload(MultipartFile file, User currentUser) {
        // 复用报告图片的共享校验规则，保证 Web 与 Agent 上传行为一致
        LostFoundImageRules.validateSingle(file);
        String contentType = file.getContentType();
        // 用 UUID 生成唯一对象名，并按 Content-Type 追加扩展名，避免重名覆盖与缓存混淆
        String objectName = UUID.randomUUID() + extensionFor(contentType);
        String objectKey = PREFIX + objectName;
        // 提取安全化的原始文件名（去除路径、截断超长）
        String originalName = safeOriginalName(file.getOriginalFilename());
        try {
            // 确保目标存储桶已存在（幂等）
            ensureBucketExists();
            // 把文件字节流传给 MinIO，同时带上 Content-Type 与原始文件名元数据
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
            // 计算图片的 SHA-256 视觉指纹（以图搜物与去重依据）
            String fingerprint = VisualFingerprintExtractor.extract(bytes, contentType);
            // 请求向量服务生成视觉向量（仅支持可识别的格式，WebP 等走 SHA-256 回退）
            List<StoredEmbedding> pretrained = embeddingClient.embedImages(List.of(
                    new LostFoundEmbeddingClient.ImageInput(bytes, contentType, originalName)));
            StoredEmbedding embedding = pretrained.isEmpty() ? null : pretrained.getFirst();
            // 落库暂存元数据：记录拥有者、指纹、向量（可能为 null）与过期时间（当前时间 + TTL）
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
                    // 供前端后续读取暂存图的可访问 URL（仅对象名部分，前缀由控制器拼接）
                    "/api/lost-found/images/staging/" + objectName,
                    contentType,
                    originalName,
                    file.getSize(),
                    // 向量就绪状态：有向量为 READY，否则 PENDING（仅指纹，可降级匹配）
                    embedding == null ? "PENDING" : "READY");
        } catch (IOException ex) {
            // 读取失败：清理已写入的对象，避免留下孤儿对象
            delete(objectKey);
            throw new LostFoundApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "IMAGE_READ_FAILED",
                    "The uploaded image could not be read",
                    ex);
        } catch (Exception ex) {
            // 其他任何异常（MinIO 不可用等）：同样回滚清理并抛 503
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
     *
     * @param objectKey 暂存对象键（必须以 {@link #PREFIX} 开头）
     * @return 从 MinIO 读回的完整 {@link StagedImage}（含内容与元数据）
     */
    public StagedImage retrieve(String objectKey) {
        // 安全校验：拒绝非暂存前缀的键，防止任意对象名读取
        if (objectKey == null || !objectKey.startsWith(PREFIX)) {
            throw notFound();
        }
        try {
            // 读取对象元数据（stat）：拿到 Content-Type 与原始文件名
            StatObjectResponse stat = minioClient.statObject(StatObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .build());
            String contentType = stat.contentType();
            // 元数据缺省时回退为 objectKey 本身
            String originalName = stat.userMetadata().getOrDefault(ORIGINAL_NAME_KEY, objectKey);
            byte[] content = download(objectKey);
            return new StagedImage(objectKey, content, contentType, originalName, stat.size());
        } catch (Exception ex) {
            // 对象不存在 / 读取失败统一视为 404
            throw new LostFoundApiException(
                    HttpStatus.NOT_FOUND,
                    "STAGED_IMAGE_NOT_FOUND",
                    "The staged image does not exist",
                    ex);
        }
    }

    /**
     * 报告创建只接受当前用户上传且未过期的暂存图片。
     * 先用 DB 元数据校验归属与有效期，再读取 MinIO 内容并合并向量/模型信息。
     *
     * @param objectKey   暂存对象键
     * @param currentUser 当前用户
     * @return 完整 {@link StagedImage}，向量字段取自 DB 元数据（可能为 null）
     * @throws LostFoundApiException 非本人/已过期/不存在时抛 404
     */
    public StagedImage retrieveOwned(String objectKey, User currentUser) {
        // 查询本人上传的暂存记录，并要求未过期；否则视为不存在
        LostFoundStagedImage metadata = stagedRepository
                .findByObjectKeyAndCreatedById(objectKey, currentUser.getId())
                .filter(value -> value.getExpiresAt().isAfter(Instant.now()))
                .orElseThrow(this::notFound);
        StagedImage stored = retrieve(objectKey);
        // 把 DB 里存的向量/模型/版本合并进 StagedImage，供报告落库使用
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

    /**
     * 把暂存图片转换成"可信"元数据列表，供 {@code LostFoundAgentGateway} 下发给 Agent。
     *
     * <p>只透传 DB 中校验过的字段（object_key、视觉指纹、向量 base64），URL 来自请求参数；
     * 这样 Agent 拿到的指纹/向量一定是服务端计算过的，而不是前端可伪造的内容。</p>
     *
     * @param images     请求携带的图片列表（含 objectKey 与原始 URL）
     * @param currentUser 当前用户（用于归属校验）
     * @return 可信元数据 Map 列表；空列表时返回空集合
     * @throws LostFoundApiException 任一图片非本人/过期/不存在时抛 404
     */
    public List<Map<String, Object>> trustedAgentImages(
            List<com.app.campusagent.lostfound.dto.agent.AgentWebInvokeRequest.AgentImage> images,
            User currentUser) {
        if (images == null || images.isEmpty()) {
            return List.of();
        }
        // 对每张图：校验归属与有效期后组装可信元数据
        return images.stream().map(image -> {
            LostFoundStagedImage metadata = stagedRepository
                    .findByObjectKeyAndCreatedById(image.objectKey(), currentUser.getId())
                    .filter(value -> value.getExpiresAt().isAfter(Instant.now()))
                    .orElseThrow(this::notFound);
            Map<String, Object> trusted = new LinkedHashMap<>();
            trusted.put("object_key", metadata.getObjectKey());
            trusted.put("visual_fingerprint", metadata.getVisualFingerprint());
            trusted.put("url", image.url());
            // 仅当向量存在时下发向量信息，且必须做 base64 编码（DB 存的是原始 float32）
            if (metadata.getVisualEmbedding() != null) {
                trusted.put("visual_embedding", Base64.getEncoder()
                        .encodeToString(metadata.getVisualEmbedding()));
                trusted.put("visual_embedding_model", metadata.getVisualEmbeddingModel());
                trusted.put("visual_embedding_revision", metadata.getVisualEmbeddingRevision());
            }
            return trusted;
        }).toList();
    }

    /**
     * 图片已经关联正式报告后只删除暂存元数据，保留 MinIO 对象。
     * 因为此时对象已被报告 {@code LostFoundImage} 行引用，继续按 TTL 删除会损坏报告。
     */
    public void consume(List<String> objectKeys) {
        if (objectKeys != null && !objectKeys.isEmpty()) {
            // 仅删元数据表记录，MinIO 对象交由报告生命周期管理
            stagedRepository.deleteAllById(objectKeys);
        }
    }

    /**
     * 删除暂存对象（元数据 + MinIO 对象），用于上传失败回滚或主动放弃。
     * 非暂存前缀的键直接忽略；异常一律吞掉，保证 best-effort 清理不阻塞主流程。
     */
    public void delete(String objectKey) {
        // 安全校验：只允许删除暂存前缀下的对象
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

    /**
     * 列出暂存区全部对象摘要，供 TTL 清理任务扫描。
     *
     * @return 每个对象的 objectKey 与最后修改时间（无时间时用 EPOCH 占位）
     * @throws LostFoundApiException 对象存储不可用时抛 503
     */
    public List<StagedObjectSummary> list() {
        List<StagedObjectSummary> result = new ArrayList<>();
        try {
            // 遍历 MinIO 中 PREFIX 前缀下的所有对象
            Iterable<Result<Item>> objects = minioClient.listObjects(ListObjectsArgs.builder()
                    .bucket(bucket)
                    .prefix(PREFIX)
                    .build());
            for (Result<Item> object : objects) {
                Item item = object.get();
                // lastModified 可能为 null，回退到 EPOCH 以便比较
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

    /**
     * 从 MinIO 下载对象完整字节内容。
     *
     * @throws LostFoundApiException 下载失败时抛 503
     */
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

    /** 确保存储桶存在：不存在则创建（幂等操作）。 */
    private void ensureBucketExists() throws Exception {
        boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
        if (!exists) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
    }

    /**
     * 根据 Content-Type 返回对象扩展名；未知类型返回空串（由浏览器嗅探）。
     * 目的：让暂存对象在直接访问时能正确被识别为图片。
     */
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

    /**
     * 安全化原始文件名：只取文件名部分（去掉路径），非法路径回退为 "image"，
     * 超长文件名保留末尾 255 字符，防止写入 MinIO 元数据超限或路径穿越。
     */
    private String safeOriginalName(String name) {
        String safe;
        try {
            // getFileName 去除任何目录部分，避免路径穿越
            safe = Path.of(name).getFileName().toString();
        } catch (RuntimeException ex) {
            safe = "image";
        }
        return safe.length() <= 255 ? safe : safe.substring(safe.length() - 255);
    }

    /** 统一的"暂存图不存在"异常（404）。 */
    private LostFoundApiException notFound() {
        return new LostFoundApiException(
                HttpStatus.NOT_FOUND,
                "STAGED_IMAGE_NOT_FOUND",
                "The staged image does not exist");
    }
}
