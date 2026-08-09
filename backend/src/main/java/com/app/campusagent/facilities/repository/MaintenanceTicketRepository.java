package com.app.campusagent.facilities.repository;

import com.app.campusagent.facilities.domain.MaintenanceTicket;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MaintenanceTicketRepository extends JpaRepository<MaintenanceTicket, Long> {

    List<MaintenanceTicket> findAllByUserIdOrderByCreatedAtDesc(Long userId);

    Optional<MaintenanceTicket> findByIdAndUserId(Long ticketId, Long userId);
}
