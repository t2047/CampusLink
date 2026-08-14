package com.app.campusagent.lostfound.repository;

import com.app.campusagent.lostfound.domain.LostFoundNotification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LostFoundNotificationRepository extends JpaRepository<LostFoundNotification, Long> {

    Page<LostFoundNotification> findByRecipientId(Long recipientId, Pageable pageable);

    Page<LostFoundNotification> findByRecipientIdAndReadAtIsNull(Long recipientId, Pageable pageable);

    long countByRecipientIdAndReadAtIsNull(Long recipientId);

    Optional<LostFoundNotification> findByIdAndRecipientId(Long id, Long recipientId);

    void deleteByReportId(Long reportId);
}
