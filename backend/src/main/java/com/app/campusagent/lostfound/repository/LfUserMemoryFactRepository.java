package com.app.campusagent.lostfound.repository;

import com.app.campusagent.lostfound.domain.LfFactType;
import com.app.campusagent.lostfound.domain.LfUserMemoryFact;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface LfUserMemoryFactRepository extends JpaRepository<LfUserMemoryFact, Long> {

    List<LfUserMemoryFact> findByUserIdOrderByUpdatedAtDesc(Long userId);

    List<LfUserMemoryFact> findByUserIdAndFactTypeOrderByUpdatedAtDesc(Long userId, LfFactType factType);

    @Modifying
    @Query("delete from LfUserMemoryFact f where f.user.id = :userId")
    long deleteByUserId(@Param("userId") Long userId);
}
