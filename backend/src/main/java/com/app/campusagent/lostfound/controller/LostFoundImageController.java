package com.app.campusagent.lostfound.controller;

import com.app.campusagent.lostfound.domain.LostFoundImage;
import com.app.campusagent.lostfound.exception.LostFoundApiException;
import com.app.campusagent.lostfound.repository.LostFoundImageRepository;
import com.app.campusagent.lostfound.storage.ObjectStorageService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.TimeUnit;

/**
 * 图片回显代理端点：浏览器统一通过 /api/lost-found/images/{id} 取图，
 * 后端从 MinIO 读取字节返回，避免下发 Docker 内网地址或会过期的预签名 URL。
 */
@RestController
@RequestMapping("/api/lost-found/images")
public class LostFoundImageController {

    private final LostFoundImageRepository imageRepository;
    private final ObjectStorageService storageService;

    public LostFoundImageController(
            LostFoundImageRepository imageRepository,
            ObjectStorageService storageService) {
        this.imageRepository = imageRepository;
        this.storageService = storageService;
    }

    @GetMapping("/{imageId}")
    public ResponseEntity<byte[]> download(@PathVariable Long imageId) {
        LostFoundImage image = imageRepository.findById(imageId)
                .orElseThrow(() -> new LostFoundApiException(
                        HttpStatus.NOT_FOUND,
                        "IMAGE_NOT_FOUND",
                        "The requested image does not exist"));
        byte[] content = storageService.download(image.getObjectKey());
        String contentType = image.getContentType() == null
                ? MediaType.APPLICATION_OCTET_STREAM_VALUE
                : image.getContentType();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                // objectKey 是随机 UUID、图片内容上传后不变，可安全缓存；删除记录后 404 兜底
                .cacheControl(CacheControl.maxAge(1, TimeUnit.DAYS).cachePublic())
                .body(content);
    }
}
