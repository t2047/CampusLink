package com.app.campusagent.facilities.dto;

import java.time.LocalTime;
import java.util.Set;

public record SpaceResponse(
        Long spaceId,
        String name,
        String building,
        String floor,
        String roomNumber,
        String spaceType,
        int capacity,
        Set<String> equipment,
        LocalTime openingTime,
        LocalTime closingTime,
        String status
) {
}
