package com.app.campusagent.facilities.repository;

import com.app.campusagent.facilities.domain.MaintenanceStatus;
import com.app.campusagent.facilities.domain.MaintenanceTicket;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;

public interface MaintenanceTicketRepository extends JpaRepository<MaintenanceTicket, Long>, JpaSpecificationExecutor<MaintenanceTicket> {

    List<MaintenanceTicket> findAllByUserIdOrderByCreatedAtDesc(Long userId);

    List<MaintenanceTicket> findAllByOrderByCreatedAtDesc();

    Optional<MaintenanceTicket> findByIdAndUserId(Long ticketId, Long userId);

    long countByStatus(MaintenanceStatus status);

    @Override
    @EntityGraph(attributePaths = "space")
    Page<MaintenanceTicket> findAll(Specification<MaintenanceTicket> specification, Pageable pageable);
}
