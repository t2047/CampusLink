package com.app.campusagent.lostfound.service;

import com.app.campusagent.domain.User;
import com.app.campusagent.lostfound.domain.LostFoundClaim;
import com.app.campusagent.lostfound.domain.LostFoundNotification;
import com.app.campusagent.lostfound.domain.NotificationType;
import com.app.campusagent.lostfound.dto.LostFoundNotificationResponse;
import com.app.campusagent.lostfound.dto.PageResponse;
import com.app.campusagent.lostfound.exception.LostFoundApiException;
import com.app.campusagent.lostfound.repository.LostFoundNotificationRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LostFoundNotificationService {

    private final LostFoundNotificationRepository repository;

    public LostFoundNotificationService(LostFoundNotificationRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void claimSubmitted(LostFoundClaim claim) {
        save(
                claim.getReport().getCreatedBy(),
                NotificationType.CLAIM_SUBMITTED,
                claim,
                "New claim submitted",
                "A user submitted an ownership claim for " + claim.getReport().getItemName() + ".");
    }

    @Transactional
    public void claimApproved(LostFoundClaim claim) {
        save(
                claim.getClaimant(),
                NotificationType.CLAIM_APPROVED,
                claim,
                "Claim approved",
                "Your claim for " + claim.getReport().getItemName() + " was approved.");
        save(
                claim.getReport().getCreatedBy(),
                NotificationType.REPORT_CLAIMED,
                claim,
                "Report marked claimed",
                claim.getReport().getItemName() + " has been marked as claimed.");
    }

    @Transactional
    public void claimRejected(LostFoundClaim claim) {
        save(
                claim.getClaimant(),
                NotificationType.CLAIM_REJECTED,
                claim,
                "Claim rejected",
                "Your claim for " + claim.getReport().getItemName() + " was rejected.");
    }

    @Transactional(readOnly = true)
    public PageResponse<LostFoundNotificationResponse> mine(
            User currentUser,
            Pageable pageable,
            boolean unreadOnly) {
        Long recipientId = currentUser.getId();
        Page<LostFoundNotification> page = unreadOnly
                ? repository.findByRecipientIdAndReadAtIsNull(recipientId, pageable)
                : repository.findByRecipientId(recipientId, pageable);
        return PageResponse.from(page.map(this::toResponse));
    }

    @Transactional(readOnly = true)
    public long unreadCount(User currentUser) {
        return repository.countByRecipientIdAndReadAtIsNull(currentUser.getId());
    }

    @Transactional
    public LostFoundNotificationResponse markRead(Long id, User currentUser) {
        LostFoundNotification notification = repository.findByIdAndRecipientId(id, currentUser.getId())
                .orElseThrow(() -> new LostFoundApiException(
                        HttpStatus.NOT_FOUND,
                        "NOTIFICATION_NOT_FOUND",
                        "The requested notification does not exist"));
        notification.markRead();
        return toResponse(repository.save(notification));
    }

    private void save(
            User recipient,
            NotificationType type,
            LostFoundClaim claim,
            String title,
            String message) {
        repository.save(new LostFoundNotification(
                recipient,
                type,
                claim.getReport(),
                claim,
                title,
                message));
    }

    private LostFoundNotificationResponse toResponse(LostFoundNotification notification) {
        return new LostFoundNotificationResponse(
                notification.getId(),
                notification.getType(),
                notification.getReport() == null ? null : notification.getReport().getId(),
                notification.getClaim() == null ? null : notification.getClaim().getId(),
                notification.getTitle(),
                notification.getMessage(),
                notification.getReadAt() != null,
                notification.getCreatedAt(),
                notification.getReadAt());
    }
}
