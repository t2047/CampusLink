package com.app.campusagent.lostfound.visual;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class VisualFingerprintExtractorTest {

    // Canonical image shared with the agent's tests/test_embeddings.py:
    // a 16x16 RGB PNG, left half blue (0,0,255), right half red (255,0,0),
    // with no gAMA/iCCP/alpha so both decoders see the same pixels.
    private static final String GOLDEN_PNG_B64 = "iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAIAAACQkWg2AAAAKElEQVR4nGNkYPjPgA38Z2DEKs7EQCJgGtVABGAiRhEyGNVADCA5lAA1WwIfpDdLxAAAAABJRU5ErkJggg==";

    // Must match embeddings.py's visual_fingerprint(embed_image(png)) exactly.
    private static final String GOLDEN_FINGERPRINT = "VF1:AAAAAAAAAAAAAAAAAAAAPwAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
            + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
            + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAPwAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
            + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA==";

    @Test
    void matchesPythonGoldenVector() {
        byte[] png = Base64.getDecoder().decode(GOLDEN_PNG_B64);

        assertThat(VisualFingerprintExtractor.extract(png, "image/png"))
                .isEqualTo(GOLDEN_FINGERPRINT);
    }

    @Test
    void solidColoursProduceDeterministicDistinctFingerprints() throws IOException {
        String blue = fingerprint(png(0, 0, 255));
        String red = fingerprint(png(255, 0, 0));

        assertThat(blue).isNotEqualTo(red);
        assertThat(fingerprint(png(0, 0, 255))).isEqualTo(blue);
    }

    @Test
    void undecodableBytesFallBackToDeterministicHash() {
        byte[] garbage = "this is not an image".getBytes();

        String first = VisualFingerprintExtractor.extract(garbage, "image/png");
        String second = VisualFingerprintExtractor.extract(garbage, "image/png");

        assertThat(first).isEqualTo(second).startsWith("VF1:");
    }

    @Test
    void webpContentTypeRoutesToTheSameFallback() {
        byte[] garbage = "this is not an image".getBytes();

        assertThat(VisualFingerprintExtractor.extract(garbage, "image/webp"))
                .isEqualTo(VisualFingerprintExtractor.extract(garbage, "image/png"));
    }

    private static String fingerprint(byte[] png) {
        return VisualFingerprintExtractor.extract(png, "image/png");
    }

    private static byte[] png(int red, int green, int blue) throws IOException {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                image.setRGB(x, y, (red << 16) | (green << 8) | blue);
            }
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }
}
