package com.app.campusagent.lostfound.dto;

import java.util.List;

public record LostFoundMetadataResponse(
        List<String> reportTypes,
        List<String> categories,
        List<String> reportStatuses,
        List<String> claimStatuses) {
}
