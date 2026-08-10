package com.app.campusagent.lostfound.repository;

import com.app.campusagent.lostfound.domain.LostFoundReport;
import com.app.campusagent.lostfound.domain.ReportStatus;
import com.app.campusagent.lostfound.domain.ReportType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.domain.Specification;

public interface LostFoundReportRepository extends
        JpaRepository<LostFoundReport, Long>,
        JpaSpecificationExecutor<LostFoundReport> {

    long countByStatus(ReportStatus status);

    long countByReportType(ReportType reportType);

    @Override
    @EntityGraph(attributePaths = "createdBy")
    Page<LostFoundReport> findAll(Specification<LostFoundReport> specification, Pageable pageable);
}
