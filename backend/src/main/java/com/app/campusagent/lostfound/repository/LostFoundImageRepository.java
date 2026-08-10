package com.app.campusagent.lostfound.repository;

import com.app.campusagent.lostfound.domain.LostFoundImage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LostFoundImageRepository extends JpaRepository<LostFoundImage, Long> {

    Page<LostFoundImage> findByVisualFingerprintIsNull(Pageable pageable);
}
