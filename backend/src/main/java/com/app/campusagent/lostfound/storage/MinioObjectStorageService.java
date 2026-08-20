/**
 * 基于 MinIO 的对象存储服务实现。
 * <p>承担失物招领模块图片文件的存取：上传（自动生成对象键或按调用方指定键）、
 * 生成带时效的预签名 GET 地址、下载原始字节、删除对象。被失物招领的
 * Service 层（如报告图片上传、图片 URL 生成）通过 {@link ObjectStorageService}
 * 接口注入调用。
 * <p>依赖外部系统：MinIO（S3 兼容对象存储）；所有对外操作失败时统一包装为
 * {@link LostFoundApiException}（HTTP 503 OBJECT_STORAGE_UNAVAILABLE），
 * 交由全局异常处理器转为统一的错误响应。
 */
package com.app.campusagent.lostfound.storage;

import com.app.campusagent.lostfound.exception.LostFoundApiException;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.http.Method;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Spring 服务 Bean，实现 {@link ObjectStorageService}，对失物招领模块屏蔽 MinIO 底层细节。 */
@Service
public class MinioObjectStorageService implements ObjectStorageService {

    /** 底层 MinIO 客户端，由 {@link MinioConfiguration} 注入。 */
    private final MinioClient minioClient;

    /** 使用的存储桶名称，配置项 {@code app.storage.bucket}，缺省 campuslink。 */
    private final String bucket;

    /** 预签名 GET URL 的有效时长（分钟），配置项 {@code app.storage.presigned-url-minutes}，缺省 15。 */
    private final int presignedUrlMinutes;

    /**
     * 构造器注入配置。
     *
     * @param minioClient         MinIO 客户端
     * @param bucket              桶名
     * @param presignedUrlMinutes 预签名 URL 有效分钟数
     */
    public MinioObjectStorageService(
            MinioClient minioClient,
            @Value("${app.storage.bucket:campuslink}") String bucket,
            @Value("${app.storage.presigned-url-minutes:15}") int presignedUrlMinutes) {
        this.minioClient = minioClient;
        this.bucket = bucket;
        this.presignedUrlMinutes = presignedUrlMinutes;
    }

    /**
     * 上传图片：由本实现自动生成对象键（{@code lost-found/<UUID><ext>}），
     * 调用方无需关心对象命名。
     *
     * @param file 前端上传的图片文件（MultipartFile）
     * @return 上传结果 {@link StoredObject}，含对象键、原始文件名、内容类型与大小
     */
    @Override
    public StoredObject upload(MultipartFile file) {
        // 依据文件 Content-Type 推导扩展名（.jpg / .png / .webp），未知类型时为空字符串
        String extension = extensionFor(file.getContentType());
        // 对象键使用 UUID 保证全局唯一，配合 "lost-found/" 前缀进行命名空间隔离，
        // 避免不同报告 / 不同用户之间的图片互相覆盖
        return upload(file, "lost-found/" + UUID.randomUUID() + extension);
    }

    /**
     * 按调用方指定的对象键上传图片（调用方负责命名空间与 URL 安全性，
     * 接口约定见 {@link ObjectStorageService#upload(MultipartFile, String)}）。
     *
     * @param file      图片文件
     * @param objectKey 对象键，例如由上层传入的带前缀键
     * @return 上传结果 StoredObject
     * @throws LostFoundApiException 存储不可用等异常时抛出（HTTP 503）
     */
    @Override
    public StoredObject upload(MultipartFile file, String objectKey) {
        // 从 MultipartFile 读取 MIME 类型，并据此推导文件扩展名
        String contentType = file.getContentType();
        String extension = extensionFor(contentType);

        try {
            // 确保目标桶已存在（不存在则自动创建），避免首次上传因缺桶而失败
            ensureBucketExists();
            // try-with-resources：上传完成后自动关闭输入流，防止文件句柄泄漏
            try (InputStream input = file.getInputStream()) {
                // 调用 MinIO SDK 以流式方式写入对象；
                // stream(input, size, -1)：-1 表示不分片阈值，由 SDK 按对象大小自动选择分片策略
                minioClient.putObject(PutObjectArgs.builder()
                        .bucket(bucket)               // 指定桶
                        .object(objectKey)            // 指定对象键
                        .stream(input, file.getSize(), -1)
                        .contentType(contentType)     // 记录 Content-Type，便于浏览器预览/下载
                        .build());
            }
            // 记录原始文件名；若前端未传则用 "image<ext>" 兜底，保证展示字段有值
            String originalName = file.getOriginalFilename() == null
                    ? "image" + extension
                    : file.getOriginalFilename();
            // 组装上传结果元数据返回给调用方
            return new StoredObject(objectKey, originalName, contentType, file.getSize());
        } catch (Exception ex) {
            // 任何存储层异常统一转换为业务异常（HTTP 503），
            // 避免把 MinIO SDK 的底层异常直接抛给上层
            throw new LostFoundApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "OBJECT_STORAGE_UNAVAILABLE",
                    "Image storage is temporarily unavailable",
                    ex);
        }
    }

    /**
     * 生成指定对象的临时 GET 预签名 URL。
     * <p>预签名 URL 使客户端（浏览器）在有效期内无需携带任何凭据即可直接下载对象，
     * 常用于把数据库中的 object_key 转换为前端可访问的图片链接返回。
     *
     * @param objectKey 对象键
     * @return 带时效的完整 GET URL 字符串
     * @throws LostFoundApiException 存储不可用时抛出（HTTP 503）
     */
    @Override
    public String createPresignedGetUrl(String objectKey) {
        try {
            return minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)                     // 只允许 GET（下载/预览），收紧签名权限
                    .bucket(bucket)
                    .object(objectKey)
                    .expiry(presignedUrlMinutes, TimeUnit.MINUTES) // URL 有效期，缺省 15 分钟
                    .build());
        } catch (Exception ex) {
            throw new LostFoundApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "OBJECT_STORAGE_UNAVAILABLE",
                    "Image storage is temporarily unavailable",
                    ex);
        }
    }

    /**
     * 按对象键下载完整原始字节（一次性读入内存）。
     *
     * @param objectKey 对象键
     * @return 对象的完整字节数组
     * @throws LostFoundApiException 下载失败时抛出（HTTP 503）
     */
    @Override
    public byte[] download(String objectKey) {
        // try-with-resources：读完对象后自动关闭输入流，释放连接
        try (InputStream input = minioClient.getObject(GetObjectArgs.builder()
                .bucket(bucket)
                .object(objectKey)
                .build())) {
            return input.readAllBytes(); // 将对象内容一次性读为字节数组
        } catch (Exception ex) {
            throw new LostFoundApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "OBJECT_STORAGE_UNAVAILABLE",
                    "Image download is temporarily unavailable",
                    ex);
        }
    }

    /**
     * 删除对象（尽力而为）：删除失败仅记录并忽略，不阻断主流程，
     * 失败的键可由后续维护任务清理。
     *
     * @param objectKey 对象键
     */
    @Override
    public void delete(String objectKey) {
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .build());
        } catch (Exception ignored) {
            // Best-effort cleanup; the failed key can be removed by a later maintenance job.
            // 尽力而为的清理：删除失败的键可由后续维护任务移除（保留原英文注释）。
        }
    }

    /**
     * 确保目标桶存在：不存在则自动创建（幂等操作）。
     * MinIO SDK 接口统一声明抛 {@code Exception}，此处向上抛出由调用方统一处理。
     */
    private void ensureBucketExists() throws Exception {
        // 先查询桶是否已存在
        boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
        if (!exists) {
            // 桶不存在时创建，避免后续 putObject 因缺桶直接失败
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
    }

    /**
     * 依据 Content-Type 推导文件扩展名。
     * <p>仅识别失物招领场景最常用的三种图片格式；其余格式返回空串
     * （对象键不带扩展名，仍可正常存取，仅影响 URL 后缀观感）。
     *
     * @param contentType MIME 类型，例如 image/jpeg
     * @return 对应扩展名（含点号），未知类型返回空字符串
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
}
