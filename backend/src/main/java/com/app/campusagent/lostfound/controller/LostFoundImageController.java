/**
 * 失物招领图片回显代理控制器（REST 控制器）。
 *
 * 主要作用与职责：
 *  1. 充当浏览器与对象存储（MinIO）之间的"读图代理"，提供两类只读端点：
 *      - GET /{imageId}       按已关联图片的自增 id 回显正式图片
 *      - GET /staging/{objectName} 按 objectKey 回显 Agent 面板暂存（未确认）的图片
 *  2. 之所以用后端代理回显而不是直接把 MinIO 内网地址或预签名 URL 返回给前端，是因为：
 *      前端浏览器无法访问 Docker 内网地址；而预签名 URL 又存在过期时间与签名泄露风险。
 *     统一走本控制器后，前端只需固定请求 /api/lost-found/images/{id} 即可。
 *  3. 图片内容由 ObjectStorageService 从对象存储读出字节流返回；
 *     暂存图由 LostFoundImageStagingService 管理；图片元数据从 LostFoundImageRepository 读取。
 */
package com.app.campusagent.lostfound.controller;

// —— 领域模型、异常、仓储与存储服务 ——
import com.app.campusagent.lostfound.domain.LostFoundImage;
import com.app.campusagent.lostfound.exception.LostFoundApiException;
import com.app.campusagent.lostfound.repository.LostFoundImageRepository;
import com.app.campusagent.lostfound.service.LostFoundImageStagingService;
import com.app.campusagent.lostfound.service.LostFoundImageStagingService.StagedImage;
import com.app.campusagent.lostfound.storage.ObjectStorageService;
// —— HTTP 相关：缓存控制、状态码、媒体类型、响应实体 ——
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
// —— Web MVC 绑定注解 ——
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
// —— JDK 工具类 ——
import java.util.concurrent.TimeUnit;

/**
 * 图片回显代理端点：浏览器统一通过 /api/lost-found/images/{id} 取图，
 * 后端从 MinIO 读取字节返回，避免下发 Docker 内网地址或会过期的预签名 URL。
 */
@RestController
@RequestMapping("/api/lost-found/images")
public class LostFoundImageController {

    // 图片元数据仓储：按 id 读取图片记录（objectKey、contentType 等）
    private final LostFoundImageRepository imageRepository;
    // 对象存储服务：按 objectKey 从 MinIO 下载图片字节
    private final ObjectStorageService storageService;
    // 图片暂存服务：管理未确认的暂存图（上传、按 objectName 检索）
    private final LostFoundImageStagingService stagingService;

    // 构造器注入三个依赖（Spring 构造器依赖注入）
    public LostFoundImageController(
            LostFoundImageRepository imageRepository,
            ObjectStorageService storageService,
            LostFoundImageStagingService stagingService) {
        this.imageRepository = imageRepository;
        this.storageService = storageService;
        this.stagingService = stagingService;
    }

    /**
     * GET /api/lost-found/images/{imageId}
     * 按已关联图片的自增 id 回显正式图片内容。
     * 入参：imageId 图片记录 id（路径参数）。
     * 返回：图片字节流，Content-Type 使用图片存储时的真实类型；带 1 天公共缓存。
     * 异常：图片不存在时抛 LostFoundApiException（404 IMAGE_NOT_FOUND）。
     * 调用方：前端 <img> / 预览组件统一加载正式图片。
     */
    @GetMapping("/{imageId}")
    public ResponseEntity<byte[]> download(@PathVariable Long imageId) {
        // 先按 id 查图片元数据；查不到则抛出 404 异常（含业务错误码 IMAGE_NOT_FOUND）
        LostFoundImage image = imageRepository.findById(imageId)
                .orElseThrow(() -> new LostFoundApiException(
                        HttpStatus.NOT_FOUND,
                        "IMAGE_NOT_FOUND",
                        "The requested image does not exist"));
        // 用图片记录的 objectKey 从对象存储下载真实字节内容
        byte[] content = storageService.download(image.getObjectKey());
        // 确定响应 Content-Type：元数据里有则用真实类型，否则退回通用的 application/octet-stream
        String contentType = image.getContentType() == null
                ? MediaType.APPLICATION_OCTET_STREAM_VALUE
                : image.getContentType();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                // objectKey 是随机 UUID、图片内容上传后不变，可安全缓存；删除记录后 404 兜底
                // 图片一旦上传内容即不可变且 objectKey 随机，因此可放心设置 1 天公共缓存；
                // 即使后端删除了图片记录，前端缓存过期后请求会回到这里并命中 404，形成安全兜底
                .cacheControl(CacheControl.maxAge(1, TimeUnit.DAYS).cachePublic())
                .body(content);
    }

    /**
     * 暂存图回显：Agent 面板选中图片后由该端点预览。objectName 是随机 UUID 文件名，
     * 不可枚举（与已关联图片的自增 id 不同）；未确认的暂存图超时后由 TTL 清理。
     *
     * 说明：
     *  - 入参 objectName 是暂存图的随机文件名（不含目录路径），来自上传接口返回的 objectKey 中缀。
     *  - 由于 objectName 为随机 UUID 且不含路径分隔符，不可被枚举/遍历，具备一定的防探测性。
     *  - 未在报告创建时确认的暂存图，超过 TTL 后由清理任务删除（见 LostFoundImageStagingService）。
     */
    @GetMapping("/staging/{objectName}")
    public ResponseEntity<byte[]> downloadStaged(@PathVariable String objectName) {
        // 防御：拒绝含路径分隔符的 objectName，防止路径穿越读取对象存储中任意对象
        if (objectName.contains("/") || objectName.contains("\\")) {
            throw new LostFoundApiException(
                    HttpStatus.NOT_FOUND,
                    "STAGED_IMAGE_NOT_FOUND",
                    "The staged image does not exist");
        }
        // 在 objectName 前拼接暂存前缀（PREFIX）得到完整 objectKey，并从暂存服务检索内容
        StagedImage staged = stagingService.retrieve(LostFoundImageStagingService.PREFIX + objectName);
        // 确定响应 Content-Type：暂存记录里有则用真实类型，否则退回通用二进制类型
        String contentType = staged.contentType() == null
                ? MediaType.APPLICATION_OCTET_STREAM_VALUE
                : staged.contentType();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                // 暂存图同样内容不可变，设置 1 天公共缓存；删除/TTL 清理后由 404 兜底
                .cacheControl(CacheControl.maxAge(1, TimeUnit.DAYS).cachePublic())
                .body(staged.content());
    }
}
