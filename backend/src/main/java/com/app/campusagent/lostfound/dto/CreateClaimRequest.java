package com.app.campusagent.lostfound.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateClaimRequest(
        @NotBlank @Size(min = 10, max = 1000) String proofDescription) {

    public CreateClaimRequest {
        proofDescription = proofDescription == null ? null : proofDescription.trim();
    }
}
