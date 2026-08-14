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
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LostFoundReportRepository extends
        JpaRepository<LostFoundReport, Long>,
        JpaSpecificationExecutor<LostFoundReport> {

    long countByStatus(ReportStatus status);

    long countByReportType(ReportType reportType);

    long countByAdminHiddenTrue();

    @Query("""
            select r from LostFoundReport r
            where r.semanticTextEmbedding is null
               or r.semanticTextRevision is null
               or r.semanticTextRevision <> :revision
               or (:requireCrossModal = true and (
                    r.crossModalTextRevision is null
                    or r.crossModalTextRevision <> :crossModalRevision))
            order by r.id
            """)
    Page<LostFoundReport> findNeedingTextEmbedding(
            @Param("revision") String revision,
            @Param("crossModalRevision") String crossModalRevision,
            @Param("requireCrossModal") boolean requireCrossModal,
            Pageable pageable);

    @Override
    @EntityGraph(attributePaths = "createdBy")
    Page<LostFoundReport> findAll(Specification<LostFoundReport> specification, Pageable pageable);
}
