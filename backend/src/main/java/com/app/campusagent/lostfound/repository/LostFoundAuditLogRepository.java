package com.app.campusagent.lostfound.repository;

import com.app.campusagent.lostfound.domain.LostFoundAuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.domain.Specification;

public interface LostFoundAuditLogRepository extends
        JpaRepository<LostFoundAuditLog, Long>,
        JpaSpecificationExecutor<LostFoundAuditLog> {

    @Override
    @EntityGraph(attributePaths = "actor")
    Page<LostFoundAuditLog> findAll(Specification<LostFoundAuditLog> specification, Pageable pageable);
}
