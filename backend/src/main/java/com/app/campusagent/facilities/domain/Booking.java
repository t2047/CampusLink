package com.app.campusagent.facilities.domain;

import com.app.campusagent.facilities.config.FacilitiesDatabase;
import jakarta.persistence.*;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Entity
@Table(name = "bookings", catalog = FacilitiesDatabase.CATALOG, indexes = {
        @Index(name = "idx_booking_space_time", columnList = "space_id, start_datetime, end_datetime"),
        @Index(name = "idx_booking_user", columnList = "user_id")
})
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "space_id", nullable = false)
    private Space space;

    @Column(name = "start_datetime", nullable = false)
    private LocalDateTime startDateTime;

    @Column(name = "end_datetime", nullable = false)
    private LocalDateTime endDateTime;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BookingStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected Booking() {
    }

    public Booking(Long userId, Space space, LocalDateTime startDateTime, LocalDateTime endDateTime) {
        this.userId = userId;
        this.space = space;
        this.startDateTime = startDateTime;
        this.endDateTime = endDateTime;
        this.status = BookingStatus.CONFIRMED;
    }

    public void cancel() {
        status = BookingStatus.CANCELLED;
        updatedAt = LocalDateTime.now();
    }

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
