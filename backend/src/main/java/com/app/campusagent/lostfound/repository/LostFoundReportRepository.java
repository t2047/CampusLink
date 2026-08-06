package com.app.campusagent.lostfound.repository;

import com.app.campusagent.lostfound.domain.LostFoundReport;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;

import java.util.Optional;

public interface LostFoundReportRepository extends
        JpaRepository<LostFoundReport, Long>,
        JpaSpecificationExecutor<LostFoundReport> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<LostFoundReport> findLockedById(Long id);
}
