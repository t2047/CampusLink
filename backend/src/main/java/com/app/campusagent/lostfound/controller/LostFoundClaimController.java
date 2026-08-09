package com.app.campusagent.lostfound.controller;

import com.app.campusagent.domain.User;
import com.app.campusagent.lostfound.dto.ClaimDecisionRequest;
import com.app.campusagent.lostfound.dto.CreateClaimRequest;
import com.app.campusagent.lostfound.dto.LostFoundClaimResponse;
import com.app.campusagent.lostfound.service.LostFoundClaimService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/lost-found")
public class LostFoundClaimController {

    private final LostFoundClaimService claimService;

    public LostFoundClaimController(LostFoundClaimService claimService) {
        this.claimService = claimService;
    }

    @PostMapping("/reports/{reportId}/claims")
    public ResponseEntity<LostFoundClaimResponse> create(
            @PathVariable Long reportId,
            @Valid @RequestBody CreateClaimRequest request,
            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(claimService.create(reportId, request, currentUser));
    }

    @GetMapping("/claims/mine")
    public List<LostFoundClaimResponse> mine(@AuthenticationPrincipal User currentUser) {
        return claimService.mine(currentUser);
    }

    @GetMapping("/claims/received")
    public List<LostFoundClaimResponse> received(@AuthenticationPrincipal User currentUser) {
        return claimService.received(currentUser);
    }

    @PostMapping("/claims/{claimId}/approve")
    public LostFoundClaimResponse approve(
            @PathVariable Long claimId,
            @Valid @RequestBody ClaimDecisionRequest request,
            @AuthenticationPrincipal User currentUser) {
        return claimService.approve(claimId, request, currentUser);
    }

    @PostMapping("/claims/{claimId}/reject")
    public LostFoundClaimResponse reject(
            @PathVariable Long claimId,
            @Valid @RequestBody ClaimDecisionRequest request,
            @AuthenticationPrincipal User currentUser) {
        return claimService.reject(claimId, request, currentUser);
    }
}
