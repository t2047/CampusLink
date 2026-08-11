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

    public static final int MAX_IMAGES = 5;
    public static final long MAX_IMAGE_SIZE = 10L * 1024L * 1024L;
    public static final int MAX_IMAGE_DIMENSION = 8192;
    private static final List<String> ALLOWED_TYPES = List.of(
            "image/jpeg", "image/png", "image/webp");

    private LostFoundImageRules() {
    }

    public static void validateAll(List<MultipartFile> images) {
        validateCount(images.size());
        for (MultipartFile image : images) {
            validateSingle(image);
        }
    }

    public static void validateCount(int count) {
        if (count > MAX_IMAGES) {
            throw new LostFoundApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "TOO_MANY_IMAGES",
                    "A report can contain at most 5 images");
        }
    }

    public static void validateSingle(MultipartFile image) {
        if (image == null || image.isEmpty()) {
            throw new LostFoundApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "EMPTY_IMAGE",
                    "Uploaded images cannot be empty");
        }
        if (image.getSize() > MAX_IMAGE_SIZE) {
            throw new LostFoundApiException(
                    HttpStatus.PAYLOAD_TOO_LARGE,
                    "IMAGE_TOO_LARGE",
                    "Each image must be 10 MB or smaller");
        }
        String contentType = image.getContentType();
        if (!ALLOWED_TYPES.contains(contentType) || !matchesMagicBytes(image, contentType)) {
            throw new LostFoundApiException(
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "UNSUPPORTED_IMAGE_TYPE",
                    "Only valid JPEG, PNG and WebP images are accepted");
        }
        validateImageDimensions(image);
    }

    /**
     * 读取图片头部尺寸（不整图解码），拒绝超大尺寸以防御解压炸弹。
     * 仅当 ImageIO 能识别格式时才检查；WebP 等无法识别的格式在指纹
     * 提取时走 SHA-256 回退、不触发解码，无解压炸弹风险，直接跳过。
     */
    private static void validateImageDimensions(MultipartFile image) {
        try (InputStream input = image.getInputStream()) {
            try (ImageInputStream imageInput = ImageIO.createImageInputStream(input)) {
                if (imageInput == null) {
                    return;
                }
                Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInput);
                if (!readers.hasNext()) {
                    return;
                }
                ImageReader reader = readers.next();
                try {
                    reader.setInput(imageInput, true, true);
                    int width = reader.getWidth(0);
                    int height = reader.getHeight(0);
                    if (width > MAX_IMAGE_DIMENSION || height > MAX_IMAGE_DIMENSION) {
                        throw new LostFoundApiException(
                                HttpStatus.UNPROCESSABLE_ENTITY,
                                "IMAGE_DIMENSION_TOO_LARGE",
                                "Each image must be at most " + MAX_IMAGE_DIMENSION
                                        + " pixels per side");
                    }
                } finally {
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

    private static boolean matchesMagicBytes(MultipartFile file, String contentType) {
        try (InputStream input = file.getInputStream()) {
            byte[] header = input.readNBytes(12);
            if ("image/jpeg".equals(contentType)) {
                return header.length >= 3
                        && (header[0] & 0xff) == 0xff
                        && (header[1] & 0xff) == 0xd8
                        && (header[2] & 0xff) == 0xff;
            }
            if ("image/png".equals(contentType)) {
                byte[] png = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
                if (header.length < png.length) {
                    return false;
                }
                for (int i = 0; i < png.length; i++) {
                    if (header[i] != png[i]) {
                        return false;
                    }
                }
                return true;
            }
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
