package com.app.campusagent.facilities.repository;

import com.app.campusagent.facilities.domain.Booking;
import com.app.campusagent.facilities.domain.BookingStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface BookingRepository extends JpaRepository<Booking, Long>, JpaSpecificationExecutor<Booking> {

    List<Booking> findAllByUserIdOrderByStartDateTimeDesc(Long userId);

    List<Booking> findAllByOrderByStartDateTimeDesc();

    Optional<Booking> findByIdAndUserId(Long bookingId, Long userId);

    long countByStatus(BookingStatus status);

    @Override
    @EntityGraph(attributePaths = "space")
    Page<Booking> findAll(Specification<Booking> specification, Pageable pageable);

    @Query("""
            select (count(b) > 0) from Booking b
            where b.space.id = :spaceId
              and b.status in :statuses
              and b.startDateTime < :endDateTime
              and b.endDateTime > :startDateTime
            """)
    boolean existsConflict(@Param("spaceId") Long spaceId,
                           @Param("statuses") Collection<BookingStatus> statuses,
                           @Param("startDateTime") LocalDateTime startDateTime,
                           @Param("endDateTime") LocalDateTime endDateTime);
}
