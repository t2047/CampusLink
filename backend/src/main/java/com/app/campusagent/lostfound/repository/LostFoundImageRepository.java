package com.app.campusagent.lostfound.repository;

import com.app.campusagent.lostfound.domain.LostFoundImage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LostFoundImageRepository extends JpaRepository<LostFoundImage, Long> {

    Page<LostFoundImage> findByVisualFingerprintIsNull(Pageable pageable);

    /** 暂存 TTL 清理时判断 objectKey 是否已被报告引用（引用的键需跳过）。 */
    boolean existsByObjectKey(String objectKey);
}
