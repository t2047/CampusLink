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

    private static final int GRID_SIZE = 8;
    private static final int BUCKETS = 64;
    private static final String PREFIX = "VF1:";
    private static final int FALLBACK_SAMPLE_BYTES = 1024;

    private VisualFingerprintExtractor() {
    }

    public static String extract(byte[] imageBytes, String contentType) {
        if (isWebp(contentType)) {
            return fingerprint(fallbackCounts(imageBytes));
        }
        int[] counts = pixelHistogram(imageBytes);
        return fingerprint(counts != null ? counts : fallbackCounts(imageBytes));
    }

    private static int[] pixelHistogram(byte[] imageBytes) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageBytes));
            if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
                return null;
            }
            int width = image.getWidth();
            int height = image.getHeight();
            int[] counts = new int[BUCKETS];
            for (int row = 0; row < GRID_SIZE; row++) {
                int sampleY = (row * height) / GRID_SIZE;
                for (int column = 0; column < GRID_SIZE; column++) {
                    int sampleX = (column * width) / GRID_SIZE;
                    int rgb = image.getRGB(sampleX, sampleY);
                    int red = (rgb >> 16) & 0xff;
                    int green = (rgb >> 8) & 0xff;
                    int blue = rgb & 0xff;
                    int bucket = ((red >> 6) & 3) << 4
                            | ((green >> 6) & 3) << 2
                            | ((blue >> 6) & 3);
                    counts[bucket]++;
                }
            }
            return counts;
        } catch (Exception ex) {
            return null;
        }
    }

    private static int[] fallbackCounts(byte[] imageBytes) {
        byte[] sample = imageBytes.length > FALLBACK_SAMPLE_BYTES
                ? Arrays.copyOfRange(imageBytes, 0, FALLBACK_SAMPLE_BYTES)
                : imageBytes;
        byte[] digest = sha256(sample);
        int[] counts = new int[BUCKETS];
        for (int index = 0; index < BUCKETS; index++) {
            counts[index] = digest[index % digest.length] & 0xff;
        }
        return counts;
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }

    private static String fingerprint(int[] counts) {
        long total = 0;
        for (int count : counts) {
            total += count;
        }
        ByteBuffer buffer = ByteBuffer.allocate(BUCKETS * Float.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        if (total == 0) {
            for (int index = 0; index < BUCKETS; index++) {
                buffer.putFloat(0.0f);
            }
        } else {
            for (int count : counts) {
                buffer.putFloat((float) (count / (double) total));
            }
        }
        return PREFIX + Base64.getEncoder().encodeToString(buffer.array());
    }

    private static boolean isWebp(String contentType) {
        return contentType != null && "image/webp".equalsIgnoreCase(contentType.trim());
    }
}
