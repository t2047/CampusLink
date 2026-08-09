package com.app.campusagent.lostfound.repository;

import com.app.campusagent.lostfound.domain.ClaimStatus;
import com.app.campusagent.lostfound.domain.LostFoundClaim;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface LostFoundClaimRepository extends JpaRepository<LostFoundClaim, Long> {

    long countByStatus(ClaimStatus status);

    boolean existsByReportIdAndClaimantIdAndStatusIn(
            Long reportId,
            Long claimantId,
            Collection<ClaimStatus> statuses);

    List<LostFoundClaim> findByClaimantIdOrderByCreatedAtDesc(Long claimantId);

    List<LostFoundClaim> findByReportCreatedByIdOrderByCreatedAtDesc(Long ownerId);

    List<LostFoundClaim> findByReportIdAndStatus(Long reportId, ClaimStatus status);
}
