package com.app.campusagent.lostfound.repository;

import com.app.campusagent.lostfound.domain.LostFoundImage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LostFoundImageRepository extends JpaRepository<LostFoundImage, Long> {

    Page<LostFoundImage> findByVisualFingerprintIsNull(Pageable pageable);

    @Query("""
            select i from LostFoundImage i
            where i.visualEmbedding is null
               or i.visualEmbeddingRevision is null
               or i.visualEmbeddingRevision <> :revision
            order by i.id
            """)
    Page<LostFoundImage> findNeedingVisualEmbedding(
            @Param("revision") String revision, Pageable pageable);

    /** 暂存 TTL 清理时判断 objectKey 是否已被报告引用（引用的键需跳过）。 */
    boolean existsByObjectKey(String objectKey);
}
