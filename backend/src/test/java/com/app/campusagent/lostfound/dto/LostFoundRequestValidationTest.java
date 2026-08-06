package com.app.campusagent.lostfound.dto;

import com.app.campusagent.lostfound.domain.ItemCategory;
import com.app.campusagent.lostfound.domain.ReportType;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class LostFoundRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    @Test
    void reportValidationUsesTrimmedRequiredText() {
        CreateLostFoundReportRequest request = new CreateLostFoundReportRequest(
                ReportType.FOUND,
                "  ab  ",
                ItemCategory.ELECTRONICS,
                "  short   ",
                " black ",
                " Central Library ",
                LocalDate.now(),
                " morning ");

        var violations = validator.validate(request);

        assertThat(request.itemName()).isEqualTo("ab");
        assertThat(request.description()).isEqualTo("short");
        assertThat(request.colour()).isEqualTo("black");
        assertThat(request.location()).isEqualTo("Central Library");
        assertThat(request.timeDescription()).isEqualTo("morning");
        assertThat(violations)
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactlyInAnyOrder("itemName", "description");
    }

    @Test
    void claimValidationUsesTrimmedProofDescription() {
        CreateClaimRequest request = new CreateClaimRequest("  too short  ");

        var violations = validator.validate(request);

        assertThat(request.proofDescription()).isEqualTo("too short");
        assertThat(violations)
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactly("proofDescription");
    }

    @Test
    void blankDecisionNoteIsNormalizedToNull() {
        ClaimDecisionRequest request = new ClaimDecisionRequest("   ");

        assertThat(request.decisionNote()).isNull();
        assertThat(validator.validate(request)).isEmpty();
    }
}
