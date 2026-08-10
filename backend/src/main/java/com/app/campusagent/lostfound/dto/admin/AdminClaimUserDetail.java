package com.app.campusagent.lostfound.dto.admin;

import com.app.campusagent.domain.Role;

public record AdminClaimUserDetail(
        Long id,
        String email,
        Role role) {
}
