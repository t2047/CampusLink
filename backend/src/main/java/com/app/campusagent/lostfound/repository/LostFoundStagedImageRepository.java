package com.app.campusagent.lostfound.repository;

import com.app.campusagent.lostfound.domain.LostFoundStagedImage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface LostFoundStagedImageRepository
        extends JpaRepository<LostFoundStagedImage, String> {

    Optional<LostFoundStagedImage> findByObjectKeyAndCreatedById(String objectKey, Long userId);

    List<LostFoundStagedImage> findTop100ByExpiresAtBefore(Instant now);
}
