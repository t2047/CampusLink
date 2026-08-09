package com.app.campusagent.facilities.domain;

import com.app.campusagent.facilities.config.FacilitiesDatabase;
import jakarta.persistence.*;
import lombok.Getter;

import java.time.LocalTime;
import java.util.LinkedHashSet;
import java.util.Set;

@Getter
@Entity
@Table(name = "spaces", catalog = FacilitiesDatabase.CATALOG, indexes = {
        @Index(name = "idx_space_building_type", columnList = "building, space_type")
})
public class Space {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(nullable = false)
    private String building;

    @Column(nullable = false)
    private String floor;

    @Column(name = "room_number", nullable = false)
    private String roomNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "space_type", nullable = false)
    private SpaceType spaceType;

    @Column(nullable = false)
    private int capacity;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "space_equipment", catalog = FacilitiesDatabase.CATALOG,
            joinColumns = @JoinColumn(name = "space_id"))
    @Column(name = "equipment", nullable = false)
    private Set<String> equipment = new LinkedHashSet<>();

    @Column(name = "opening_time", nullable = false)
    private LocalTime openingTime;

    @Column(name = "closing_time", nullable = false)
    private LocalTime closingTime;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SpaceStatus status;

    protected Space() {
    }

    public Space(String name, String building, String floor, String roomNumber,
                 SpaceType spaceType, int capacity, Set<String> equipment,
                 LocalTime openingTime, LocalTime closingTime, SpaceStatus status) {
        this.name = name;
        this.building = building;
        this.floor = floor;
        this.roomNumber = roomNumber;
        this.spaceType = spaceType;
        this.capacity = capacity;
        this.equipment = new LinkedHashSet<>(equipment);
        this.openingTime = openingTime;
        this.closingTime = closingTime;
        this.status = status;
    }
}
