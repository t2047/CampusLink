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

@Service
public class MinioObjectStorageService implements ObjectStorageService {

    private final MinioClient minioClient;
    private final String bucket;
    private final int presignedUrlMinutes;

    public MinioObjectStorageService(
            MinioClient minioClient,
            @Value("${app.storage.bucket:campuslink}") String bucket,
            @Value("${app.storage.presigned-url-minutes:15}") int presignedUrlMinutes) {
        this.minioClient = minioClient;
        this.bucket = bucket;
        this.presignedUrlMinutes = presignedUrlMinutes;
    }

    @Override
    public StoredObject upload(MultipartFile file) {
        String extension = extensionFor(file.getContentType());
        return upload(file, "lost-found/" + UUID.randomUUID() + extension);
    }

    @Override
    public StoredObject upload(MultipartFile file, String objectKey) {
        String contentType = file.getContentType();
        String extension = extensionFor(contentType);

        try {
            ensureBucketExists();
            try (InputStream input = file.getInputStream()) {
                minioClient.putObject(PutObjectArgs.builder()
                        .bucket(bucket)
                        .object(objectKey)
                        .stream(input, file.getSize(), -1)
                        .contentType(contentType)
                        .build());
            }
            String originalName = file.getOriginalFilename() == null
                    ? "image" + extension
                    : file.getOriginalFilename();
            return new StoredObject(objectKey, originalName, contentType, file.getSize());
        } catch (Exception ex) {
            throw new LostFoundApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "OBJECT_STORAGE_UNAVAILABLE",
                    "Image storage is temporarily unavailable",
                    ex);
        }
    }

    @Override
    public String createPresignedGetUrl(String objectKey) {
        try {
            return minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(bucket)
                    .object(objectKey)
                    .expiry(presignedUrlMinutes, TimeUnit.MINUTES)
                    .build());
        } catch (Exception ex) {
            throw new LostFoundApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "OBJECT_STORAGE_UNAVAILABLE",
                    "Image storage is temporarily unavailable",
                    ex);
        }
    }

    @Override
    public byte[] download(String objectKey) {
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

    @Override
    public void delete(String objectKey) {
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .build());
        } catch (Exception ignored) {
            // Best-effort cleanup; the failed key can be removed by a later maintenance job.
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
}
