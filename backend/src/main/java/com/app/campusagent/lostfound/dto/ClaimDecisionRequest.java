package com.app.campusagent.lostfound.dto;

import jakarta.validation.constraints.Size;

public record ClaimDecisionRequest(@Size(max = 500) String decisionNote) {

    public ClaimDecisionRequest {
        if (decisionNote != null) {
            decisionNote = decisionNote.trim();
            if (decisionNote.isEmpty()) {
                decisionNote = null;
            }
        }
    }
}
