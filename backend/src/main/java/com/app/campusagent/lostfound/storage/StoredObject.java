/**
 * 对象存储上传结果返回值（record）。
 * <p>记录一次成功上传后对象的定位信息与元数据：objectKey 用于后续的
 * 下载 / 预签名 URL / 删除；originalName、contentType、size 用于持久化
 * 与前端展示（如报告详情里的原文件名与大小）。
 */
package com.app.campusagent.lostfound.storage;

/**
 * @param objectKey    对象在存储中的唯一键（数据库持久化该值，后续访问均依赖它）
 * @param originalName 上传时的原始文件名（用于展示，可能为中文）
 * @param contentType  对象的 MIME 类型，如 image/jpeg
 * @param size         对象字节大小
 */
public record StoredObject(
        String objectKey,
        String originalName,
        String contentType,
        long size) {
}
