package com.app.campusagent.lostfound.repository;

import com.app.campusagent.lostfound.domain.LostFoundReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface LostFoundReportRepository extends
        JpaRepository<LostFoundReport, Long>,
        JpaSpecificationExecutor<LostFoundReport> {
}
