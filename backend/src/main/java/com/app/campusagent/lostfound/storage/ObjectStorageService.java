package com.app.campusagent.lostfound.storage;

import org.springframework.web.multipart.MultipartFile;

public interface ObjectStorageService {

    StoredObject upload(MultipartFile file);

    /**
     * 以调用方指定的 objectKey 上传（调用方负责命名空间/URL 安全性），
     * 默认实现使用 {@code lost-found/} 前缀。
     */
    StoredObject upload(MultipartFile file, String objectKey);

    String createPresignedGetUrl(String objectKey);

    byte[] download(String objectKey);

    void delete(String objectKey);
}
