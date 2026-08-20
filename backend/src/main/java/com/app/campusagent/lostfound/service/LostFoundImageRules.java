/**
 * 失物招领模块图片上传的共享校验规则工具类（纯静态方法，无状态）。
 *
 * <p><b>职责</b>：对报告图片与用户头像做统一的前置校验：
 * <ul>
 *     <li>类型白名单：仅接受 JPEG / PNG / WebP（既校验 Content-Type，也校验文件头魔数）；</li>
 *     <li>大小限制：报告图片 ≤10MB，头像 ≤2MB；</li>
 *     <li>数量限制：单份报告最多 5 张；</li>
 *     <li>尺寸限制：单边 ≤8192 像素（报告）/ ≤512 像素（头像），防御解压炸弹。</li>
 * </ul>
 *
 * <p><b>被谁调用</b>：{@code LostFoundReportService}（创建/编辑报告）与
 * {@code LostFoundImageStagingService}（Agent 暂存）共用同一套规则，
 * 保证 Agent 上传与 Web 上传行为一致。
 *
 * <p><b>设计</b>：{@code final} 类 + 私有构造器，禁止实例化与继承，仅暴露静态校验方法。
 * 校验失败统一抛出携带错误码的 {@link LostFoundApiException}。</p>
 */
package com.app.campusagent.lostfound.service;

import com.app.campusagent.lostfound.exception.LostFoundApiException;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;

/**
 * 报告/暂存图片的共享校验规则：类型白名单（JPEG/PNG/WebP）、大小（≤10MB）、
 * 数量（≤5）与尺寸（单边 ≤8192，防解压炸弹）。
 *
 * <p>{@code LostFoundReportService}（创建/编辑）与 {@code LostFoundImageStagingService}
 * （Agent 暂存）共用同一套规则，保证 Agent 上传与 Web 上传行为一致。</p>
 */
public final class LostFoundImageRules {

    /** 单份报告允许的最大图片数量：5 张。 */
    public static final int MAX_IMAGES = 5;

    /** 单张报告图片的最大体积：10 MB（10 * 1024 * 1024 字节）。 */
    public static final long MAX_IMAGE_SIZE = 10L * 1024L * 1024L;

    /** 报告图片单边最大像素：8192。用于拦截超大图，防御解压炸弹攻击。 */
    public static final int MAX_IMAGE_DIMENSION = 8192;

    /** 头像：≤2MB、单边 ≤512px（个人中心需求 §11.2）。 */
    public static final long MAX_AVATAR_SIZE = 2L * 1024L * 1024L;

    /** 头像单边最大像素：512（个人中心需求 §11.2）。 */
    public static final int MAX_AVATAR_DIMENSION = 512;

    /** 允许的图片 MIME 类型白名单：仅 JPEG / PNG / WebP。 */
    private static final List<String> ALLOWED_TYPES = List.of(
            "image/jpeg", "image/png", "image/webp");

    /** 私有构造器：工具类禁止实例化。 */
    private LostFoundImageRules() {
    }

    /**
     * 批量校验一组图片：先校验总数量（≤5），再逐张校验单张规则。
     *
     * @param images 图片列表
     * @throws LostFoundApiException 数量超限或任一图片不合法时抛出
     */
    public static void validateAll(List<MultipartFile> images) {
        validateCount(images.size());
        for (MultipartFile image : images) {
            validateSingle(image);
        }
    }

    /**
     * 校验图片数量：超过 {@link #MAX_IMAGES}（5 张）时拒绝。
     *
     * @param count 图片数量
     * @throws LostFoundApiException 数量超限时抛 422（TOO_MANY_IMAGES）
     */
    public static void validateCount(int count) {
        if (count > MAX_IMAGES) {
            throw new LostFoundApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "TOO_MANY_IMAGES",
                    "A report can contain at most 5 images");
        }
    }

    /**
     * 校验单张报告图片：空文件、超 10MB、类型不在白名单（或魔数不符）、单边超 8192px。
     *
     * @param image 待校验的图片
     * @throws LostFoundApiException 任一规则不满足时抛出（对应 422 / 413 / 415 状态码）
     */
    public static void validateSingle(MultipartFile image) {
        // 空文件校验：null 或大小为 0 都视为空
        if (image == null || image.isEmpty()) {
            throw new LostFoundApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "EMPTY_IMAGE",
                    "Uploaded images cannot be empty");
        }
        // 大小校验：超过 10MB 拒绝（413 PAYLOAD_TOO_LARGE）
        if (image.getSize() > MAX_IMAGE_SIZE) {
            throw new LostFoundApiException(
                    HttpStatus.PAYLOAD_TOO_LARGE,
                    "IMAGE_TOO_LARGE",
                    "Each image must be 10 MB or smaller");
        }
        String contentType = image.getContentType();
        // 双保险：Content-Type 必须在白名单内，且文件头魔数必须与声称的类型一致，
        // 防止改后缀/伪装 Content-Type 上传非法文件
        if (!ALLOWED_TYPES.contains(contentType) || !matchesMagicBytes(image, contentType)) {
            throw new LostFoundApiException(
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "UNSUPPORTED_IMAGE_TYPE",
                    "Only valid JPEG, PNG and WebP images are accepted");
        }
        validateImageDimensions(image, MAX_IMAGE_DIMENSION, "IMAGE_DIMENSION_TOO_LARGE");
    }

    /**
     * 头像校验：类型白名单同报告图片，但大小 ≤2MB、单边 ≤512px（个人中心需求 §11.2）。
     * 逻辑与 {@link #validateSingle} 一致，仅阈值与错误码不同。
     */
    public static void validateAvatar(MultipartFile image) {
        if (image == null || image.isEmpty()) {
            throw new LostFoundApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "EMPTY_IMAGE",
                    "Uploaded images cannot be empty");
        }
        if (image.getSize() > MAX_AVATAR_SIZE) {
            throw new LostFoundApiException(
                    HttpStatus.PAYLOAD_TOO_LARGE,
                    "AVATAR_TOO_LARGE",
                    "Avatar must be 2 MB or smaller");
        }
        String contentType = image.getContentType();
        if (!ALLOWED_TYPES.contains(contentType) || !matchesMagicBytes(image, contentType)) {
            throw new LostFoundApiException(
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "UNSUPPORTED_IMAGE_TYPE",
                    "Only valid JPEG, PNG and WebP images are accepted");
        }
        validateImageDimensions(image, MAX_AVATAR_DIMENSION, "AVATAR_DIMENSION_TOO_LARGE");
    }

    /**
     * 读取图片头部尺寸（不整图解码），拒绝超大尺寸以防御解压炸弹。
     * 仅当 ImageIO 能识别格式时才检查；WebP 等无法识别的格式在指纹
     * 提取时走 SHA-256 回退、不触发解码，无解压炸弹风险，直接跳过。
     *
     * <p>实现要点：只读取图片头信息（getWidth/getHeight），并不解码整张位图，
     * 因此开销小、可拦截恶意构造的超大尺寸图片。</p>
     *
     * @param image        待校验图片
     * @param maxDimension 单边像素上限
     * @param errorCode    超限时使用的错误码（区分报告/头像）
     */
    private static void validateImageDimensions(MultipartFile image, int maxDimension, String errorCode) {
        try (InputStream input = image.getInputStream()) {
            // 创建 ImageInputStream；对不支持的格式返回 null 时直接跳过（交给指纹回退）
            try (ImageInputStream imageInput = ImageIO.createImageInputStream(input)) {
                if (imageInput == null) {
                    return;
                }
                // 查找能解析该格式的 ImageReader
                Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInput);
                if (!readers.hasNext()) {
                    return;
                }
                ImageReader reader = readers.next();
                try {
                    // 以"仅搜索头部、忽略颜色转换"模式读取尺寸
                    reader.setInput(imageInput, true, true);
                    int width = reader.getWidth(0);
                    int height = reader.getHeight(0);
                    // 任一边超过上限即拒绝
                    if (width > maxDimension || height > maxDimension) {
                        throw new LostFoundApiException(
                                HttpStatus.UNPROCESSABLE_ENTITY,
                                errorCode,
                                "Each image must be at most " + maxDimension
                                        + " pixels per side");
                    }
                } finally {
                    // 释放 reader 占用的资源
                    reader.dispose();
                }
            }
        } catch (IOException ex) {
            throw new LostFoundApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "IMAGE_READ_FAILED",
                    "The uploaded image could not be read",
                    ex);
        }
    }

    /**
     * 校验文件头魔数与声称的 Content-Type 一致（读取前 12 字节）。
     *
     * <p>魔数规则：JPEG 以 {@code FF D8 FF} 开头；PNG 为 8 字节固定签名
     * {@code 89 50 4E 47 0D 0A 1A 0A}；WebP 为 "RIFF" + "WEBP" 组合。</p>
     *
     * @param file        图片文件
     * @param contentType 文件声称的 MIME 类型
     * @return true 表示魔数与类型匹配
     */
    private static boolean matchesMagicBytes(MultipartFile file, String contentType) {
        try (InputStream input = file.getInputStream()) {
            // 只读前 12 字节就足以判别三种格式
            byte[] header = input.readNBytes(12);
            // JPEG：十六进制 FF D8 FF（SOI 标记开头）
            if ("image/jpeg".equals(contentType)) {
                return header.length >= 3
                        && (header[0] & 0xff) == 0xff
                        && (header[1] & 0xff) == 0xd8
                        && (header[2] & 0xff) == 0xff;
            }
            // PNG：固定 8 字节签名
            if ("image/png".equals(contentType)) {
                byte[] png = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
                if (header.length < png.length) {
                    return false;
                }
                // 逐字节比对签名
                for (int i = 0; i < png.length; i++) {
                    if (header[i] != png[i]) {
                        return false;
                    }
                }
                return true;
            }
            // WebP：前 4 字节 "RIFF"，第 8-11 字节 "WEBP"
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
}
