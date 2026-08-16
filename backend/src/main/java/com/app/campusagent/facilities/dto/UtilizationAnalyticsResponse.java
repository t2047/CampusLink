package com.app.campusagent.facilities.dto;

import java.time.LocalDate;
import java.util.List;

public record UtilizationAnalyticsResponse(
        LocalDate fromDate,
        LocalDate toDate,
        String model,
        Summary summary,
        List<HeatmapCell> heatmap,
        List<SpaceUtilization> spaces,
        List<ForecastPoint> forecast,
        List<Insight> insights) {

    public record Summary(
            double averageUtilization,
            String peakHour,
            String mostUtilizedSpace,
            int underutilizedSpaces,
            int totalBookings,
            int predictedBookings) { }

    public record HeatmapCell(String day, int hour, int bookings) { }

    public record SpaceUtilization(
            Long spaceId,
            String name,
            String building,
            String spaceType,
            int reservationCount,
            double bookedHours,
            double availableHours,
            double utilizationRate,
            String classification) { }

    public record ForecastPoint(LocalDate date, double predictedBookings, double confidence) { }

    public record Insight(String type, String title, String message) { }
}
