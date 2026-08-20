/**
 * 对象存储抽象接口。
 * <p>屏蔽底层对象存储实现（当前唯一实现为 {@code MinioObjectStorageService}），
 * 为失物招领模块的 Service 层提供统一的上传 / 下载 / 临时 URL / 删除能力，
 * 便于未来替换存储后端（如本地盘、AWS S3）而不改动上层代码。
 */
package com.app.campusagent.lostfound.storage;

import org.springframework.web.multipart.MultipartFile;

/** 对象存储抽象：失物招领图片的读、写、临时访问与删除均通过此接口完成。 */
public interface ObjectStorageService {

    /** 自动生成对象键上传：适合调用方不关心对象键的常规图片上传。 */
    StoredObject upload(MultipartFile file);

    /**
     * 以调用方指定的 objectKey 上传（调用方负责命名空间/URL 安全性），
     * 默认实现使用 {@code lost-found/} 前缀。
     */
    StoredObject upload(MultipartFile file, String objectKey);

    /** 生成对象的临时 GET 预签名 URL，用于把存储对象转换为前端可访问的链接。 */
    String createPresignedGetUrl(String objectKey);

    /** 按对象键下载完整原始字节。 */
    byte[] download(String objectKey);

    /** 删除指定对象（实现方应为尽力而为，失败不阻断主流程）。 */
    void delete(String objectKey);
}
