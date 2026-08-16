package com.app.campusagent.lostfound.colour;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ColourNormalizerTest {

    @Test
    void canonicalCodesMapCrossLanguageAndSynonyms() {
        assertThat(ColourNormalizer.canonicalCodes("white")).containsExactly("WHITE");
        assertThat(ColourNormalizer.canonicalCodes("White")).containsExactly("WHITE");
        assertThat(ColourNormalizer.canonicalCodes("白色")).containsExactly("WHITE");
        assertThat(ColourNormalizer.canonicalCodes("ivory")).containsExactly("WHITE");
        assertThat(ColourNormalizer.canonicalCodes("cream")).containsExactly("WHITE");
        assertThat(ColourNormalizer.canonicalCodes("黑色")).containsExactly("BLACK");
        assertThat(ColourNormalizer.canonicalCodes("gray")).containsExactly("GREY");
        assertThat(ColourNormalizer.canonicalCodes("navy")).containsExactly("BLUE");
        assertThat(ColourNormalizer.canonicalCodes("golden")).containsExactly("GOLD");
    }

    @Test
    void compoundColourYieldsMultipleCodes() {
        assertThat(ColourNormalizer.canonicalCodes("blue lid black bottle"))
                .containsExactlyInAnyOrder("BLUE", "BLACK");
    }

    @Test
    void wordBoundaryAvoidsFalsePositives() {
        assertThat(ColourNormalizer.canonicalCodes("backpack")).isEmpty();
        assertThat(ColourNormalizer.canonicalCodes("redemption")).isEmpty();
        assertThat(ColourNormalizer.canonicalCodes("")).isEmpty();
        assertThat(ColourNormalizer.canonicalCodes(null)).isEmpty();
    }

    @Test
    void expandReturnsAllSynonymsForCanonicalGroup() {
        assertThat(ColourNormalizer.expand("white"))
                .contains("白色", "ivory", "cream", "white");
        assertThat(ColourNormalizer.expand("白色"))
                .contains("white", "ivory", "cream", "白色");
        assertThat(ColourNormalizer.expand("black")).contains("黑色", "charcoal", "black");
        // 未知颜色/空值 → 空列表，调用方回退原始 LIKE
        assertThat(ColourNormalizer.expand("zzz")).isEmpty();
        assertThat(ColourNormalizer.expand("")).isEmpty();
        assertThat(ColourNormalizer.expand(null)).isEmpty();
    }
}
