package com.app.campusagent.facilities.domain;

import com.app.campusagent.facilities.config.FacilitiesDatabase;
import jakarta.persistence.*;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Entity
@Table(name = "maintenance_tickets", catalog = FacilitiesDatabase.CATALOG, indexes = {
        @Index(name = "idx_maintenance_user", columnList = "user_id"),
        @Index(name = "idx_maintenance_status", columnList = "status")
})
public class MaintenanceTicket {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "space_id")
    private Space space;

    @Column(nullable = false)
    private String building;

    @Column(name = "room_number", nullable = false)
    private String roomNumber;

    @Column(name = "facility_type", nullable = false)
    private String facilityType;

    @Column(nullable = false, length = 2000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MaintenancePriority priority;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MaintenanceStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected MaintenanceTicket() {
    }

    public MaintenanceTicket(Long userId, Space space, String building, String roomNumber,
                             String facilityType, String description, MaintenancePriority priority) {
        this.userId = userId;
        this.space = space;
        this.building = building;
        this.roomNumber = roomNumber;
        this.facilityType = facilityType;
        this.description = description;
        this.priority = priority;
        this.status = MaintenanceStatus.SUBMITTED;
    }

    public void updateStatus(MaintenanceStatus newStatus) {
        status = newStatus;
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
