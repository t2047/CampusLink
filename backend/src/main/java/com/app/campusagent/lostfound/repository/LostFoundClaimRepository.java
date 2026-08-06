package com.app.campusagent.lostfound.repository;

import com.app.campusagent.lostfound.domain.ClaimStatus;
import com.app.campusagent.lostfound.domain.LostFoundClaim;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface LostFoundClaimRepository extends JpaRepository<LostFoundClaim, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<LostFoundClaim> findLockedById(Long id);

    boolean existsByReportIdAndClaimantIdAndStatusIn(
            Long reportId,
            Long claimantId,
            Collection<ClaimStatus> statuses);

    List<LostFoundClaim> findByClaimantIdOrderByCreatedAtDesc(Long claimantId);

    List<LostFoundClaim> findByReportCreatedByIdOrderByCreatedAtDesc(Long ownerId);

    List<LostFoundClaim> findByReportIdAndStatus(Long reportId, ClaimStatus status);
}
