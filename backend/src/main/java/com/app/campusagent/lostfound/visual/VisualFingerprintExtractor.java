/**
 * 视觉指纹提取工具：确定性颜色直方图指纹。
 * <p>把一张图片压缩成一个 64 维 L1 归一化颜色直方图，再序列化为
 * little-endian float32 的 Base64 字符串（带 {@code VF1:} 前缀）。
 * 该指纹是确定性算法，同一图片在 Java 后端与 Python agent 两端产出
 * 逐字节一致的结果，因此可同时用于以图搜物的基础匹配与跨端校验。
 * <p>被失物招领的图片上传 / 检索链路调用；WebP（JDK ImageIO 无法解码）
 * 或解码失败的字节退化为对文件头 1 KiB 的 SHA-256 直方图，保证两端仍对齐。
 */
package com.app.campusagent.lostfound.visual;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;

/**
 * Deterministic colour-histogram fingerprint shared byte-for-byte with the
 * agent's {@code embeddings.py}.
 *
 * <p>Spec (both sides must agree): sample an 8x8 grid with integer scaling
 * {@code sx = (dx * width) / 8}, quantize each RGB pixel into a 64-bucket
 * histogram ({@code bin = (r>>6&3)<<4 | (g>>6&3)<<2 | (b>>6&3)}), then
 * L1-normalize in double precision and serialize the 64 floats as
 * little-endian float32 Base64 with a {@code VF1:} prefix.
 *
 * <p>WebP (which the JDK ImageIO cannot decode) and undecodable bytes fall
 * back to a SHA-256 histogram of the first 1 KiB so both sides stay aligned.
 */
public final class VisualFingerprintExtractor {

    /** 采样网格边长：8x8 网格，共采样 64 个像素点。 */
    private static final int GRID_SIZE = 8;

    /** 直方图桶数：RGB 各取高 2 位 => 4*4*4 = 64 桶，正好对应一个 64 维向量。 */
    private static final int BUCKETS = 64;

    /** 指纹字符串前缀 "VF1:"（版本标记，两端据此识别指纹格式）。 */
    private static final String PREFIX = "VF1:";

    /** 降级采样字节数：取文件头 1 KiB 用于 SHA-256 直方图。 */
    private static final int FALLBACK_SAMPLE_BYTES = 1024;

    /** 工具类，禁止实例化（仅提供静态方法）。 */
    private VisualFingerprintExtractor() {
    }

    /**
     * 从图片字节提取视觉指纹（公开入口）。
     *
     * @param imageBytes  图片文件的原始字节
     * @param contentType 图片 MIME 类型（用于识别 WebP）
     * @return 指纹字符串（"VF1:" + Base64），Java 与 Python 两端一致
     */
    public static String extract(byte[] imageBytes, String contentType) {
        // WebP 走 SHA-256 降级路径（JDK ImageIO 不支持解码该格式）
        if (isWebp(contentType)) {
            return fingerprint(fallbackCounts(imageBytes));
        }
        // 其余格式先尝试像素直方图；解码失败（返回 null）时同样降级到 SHA-256
        int[] counts = pixelHistogram(imageBytes);
        return fingerprint(counts != null ? counts : fallbackCounts(imageBytes));
    }

    /**
     * 像素直方图：按 8x8 网格采样 64 个像素，RGB 各取高 2 位合并成 64 桶。
     * <p>采用「整数缩放」采样 {@code sx = (column * width) / 8}（而非浮点比例），
     * 保证 Java 与 Python 两侧取到的像素坐标完全一致，是「两端逐字节一致」的关键。
     *
     * @param imageBytes 图片字节
     * @return 长度为 64 的桶计数数组；解码失败或尺寸非法时返回 null
     */
    private static int[] pixelHistogram(byte[] imageBytes) {
        try {
            // 用 JDK ImageIO 把字节流解码为 BufferedImage
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageBytes));
            if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
                return null;
            }
            int width = image.getWidth();
            int height = image.getHeight();
            // 64 个直方图桶，初始计数为 0
            int[] counts = new int[BUCKETS];
            // 双层循环遍历 8x8 网格的每个采样点
            for (int row = 0; row < GRID_SIZE; row++) {
                int sampleY = (row * height) / GRID_SIZE;   // 行号 -> 采样 y 坐标（整数缩放）
                for (int column = 0; column < GRID_SIZE; column++) {
                    int sampleX = (column * width) / GRID_SIZE; // 列号 -> 采样 x 坐标（整数缩放）
                    // 读取该像素的 ARGB 值，然后移位取出 R / G / B 各 8 位分量
                    int rgb = image.getRGB(sampleX, sampleY);
                    int red = (rgb >> 16) & 0xff;
                    int green = (rgb >> 8) & 0xff;
                    int blue = rgb & 0xff;
                    // 桶号 = R高2位(0-3)<<4 | G高2位<<2 | B高2位；
                    // 右移 6 位并 &3 把 0-255 压缩到 0-3，三通道组合即 4*4*4=64 桶
                    int bucket = ((red >> 6) & 3) << 4
                            | ((green >> 6) & 3) << 2
                            | ((blue >> 6) & 3);
                    counts[bucket]++;
                }
            }
            return counts;
        } catch (Exception ex) {
            // 解码异常（非法图片等）返回 null，由调用方走降级路径
            return null;
        }
    }

    /**
     * 降级直方图：取文件头 1 KiB 的 SHA-256 摘要（32 字节），
     * 用摘要字节（对 32 取模循环使用）逐桶填充 64 个桶计数。
     * <p>这样 WebP / 无法解码的图片也能产出确定性的 64 维指纹，且两端算法一致。
     */
    private static int[] fallbackCounts(byte[] imageBytes) {
        // 截取前 1024 字节作为样本（不足则取全量）
        byte[] sample = imageBytes.length > FALLBACK_SAMPLE_BYTES
                ? Arrays.copyOfRange(imageBytes, 0, FALLBACK_SAMPLE_BYTES)
                : imageBytes;
        byte[] digest = sha256(sample); // 32 字节 SHA-256 摘要
        int[] counts = new int[BUCKETS];
        for (int index = 0; index < BUCKETS; index++) {
            // 依次取摘要字节（index % 32 循环复用），&0xff 转为无符号 0-255 作为桶计数
            counts[index] = digest[index % digest.length] & 0xff;
        }
        return counts;
    }

    /** 计算 SHA-256 摘要；算法不可用时抛出 IllegalStateException（正常运行不会发生）。 */
    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }

    /**
     * 把 64 维桶计数序列化为指纹字符串：L1 归一化后按小端 float32 编码为 Base64。
     * <p>归一化用「双精度」计算（count / total），再收窄为 float32 写入，
     * 与 Python 端完全对齐，是两端逐字节一致的另一关键点。
     */
    private static String fingerprint(int[] counts) {
        // 先累加总计数，供后续 L1 归一化（每个桶占总数的比例）使用
        long total = 0;
        for (int count : counts) {
            total += count;
        }
        // 分配 64*4 = 256 字节，字节序固定为 LITTLE_ENDIAN（小端，与 Python struct 一致）
        ByteBuffer buffer = ByteBuffer.allocate(BUCKETS * Float.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        if (total == 0) {
            // 全零计数（如图片全黑或降级摘要为 0）时写入 64 个 0.0f，保证长度与格式恒定
            for (int index = 0; index < BUCKETS; index++) {
                buffer.putFloat(0.0f);
            }
        } else {
            // 逐桶写入归一化比例：先用 double 做除法再转 float32，避免精度累积偏差
            for (int count : counts) {
                buffer.putFloat((float) (count / (double) total));
            }
        }
        // 加上 VF1: 前缀并做标准 Base64 编码，得到最终指纹字符串
        return PREFIX + Base64.getEncoder().encodeToString(buffer.array());
    }

    /** 判断 MIME 类型是否为 WebP（不区分大小写，并容忍首尾空白）。 */
    private static boolean isWebp(String contentType) {
        return contentType != null && "image/webp".equalsIgnoreCase(contentType.trim());
    }
}
