package com.app.campusagent.facilities.repository;

import com.app.campusagent.facilities.domain.Space;
import com.app.campusagent.facilities.domain.SpaceStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface SpaceRepository extends JpaRepository<Space, Long> {

    boolean existsByName(String name);

    long countByStatus(SpaceStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Space s where s.id = :id")
    Optional<Space> findByIdForUpdate(@Param("id") Long id);
}
