package com.app.campusagent.facilities.service;

import com.app.campusagent.facilities.domain.Booking;
import com.app.campusagent.facilities.domain.BookingStatus;
import com.app.campusagent.facilities.domain.Space;
import com.app.campusagent.facilities.domain.SpaceStatus;
import com.app.campusagent.facilities.dto.UtilizationAnalyticsResponse;
import com.app.campusagent.facilities.repository.BookingRepository;
import com.app.campusagent.facilities.repository.SpaceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class UtilizationAnalyticsService {

    private static final Set<BookingStatus> COUNTED_STATUSES = Set.of(BookingStatus.CONFIRMED, BookingStatus.COMPLETED);
    private static final double UNDERUTILIZED_THRESHOLD = 20.0;

    private final BookingRepository bookingRepository;
    private final SpaceRepository spaceRepository;

    public UtilizationAnalyticsService(BookingRepository bookingRepository, SpaceRepository spaceRepository) {
        this.bookingRepository = bookingRepository;
        this.spaceRepository = spaceRepository;
    }

    @Transactional(readOnly = true)
    public UtilizationAnalyticsResponse analyze(LocalDate requestedFrom, LocalDate requestedTo) {
        LocalDate to = requestedTo == null ? LocalDate.now() : requestedTo;
        LocalDate from = requestedFrom == null ? to.minusDays(29) : requestedFrom;
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("fromDate must not be after toDate");
        }

        LocalDateTime rangeStart = from.atStartOfDay();
        LocalDateTime rangeEnd = to.plusDays(1).atStartOfDay();
        List<Booking> bookings = bookingRepository.findAll().stream()
                .filter(booking -> COUNTED_STATUSES.contains(booking.getStatus()))
                .filter(booking -> booking.getStartDateTime().isBefore(rangeEnd)
                        && booking.getEndDateTime().isAfter(rangeStart))
                .toList();
        List<Space> spaces = spaceRepository.findAll();

        long days = Duration.between(from.atStartOfDay(), rangeEnd).toDays();
        Map<Long, List<Booking>> bookingsBySpace = bookings.stream()
                .filter(booking -> booking.getSpace() != null)
                .collect(Collectors.groupingBy(booking -> booking.getSpace().getId()));
        List<UtilizationAnalyticsResponse.SpaceUtilization> spaceRows = spaces.stream()
                .map(space -> utilizationFor(space, bookingsBySpace.getOrDefault(space.getId(), List.of()), from, rangeEnd, days))
                .sorted(Comparator.comparingDouble(UtilizationAnalyticsResponse.SpaceUtilization::utilizationRate).reversed())
                .toList();

        Map<LocalDate, Integer> dailyCounts = new HashMap<>();
        Map<DayOfWeek, Map<Integer, Integer>> heatmapCounts = new EnumMap<>(DayOfWeek.class);
        Map<Integer, Integer> hourCounts = new HashMap<>();
        for (Booking booking : bookings) {
            LocalDate date = booking.getStartDateTime().toLocalDate();
            dailyCounts.merge(date, 1, Integer::sum);
            int hour = booking.getStartDateTime().getHour();
            hourCounts.merge(hour, 1, Integer::sum);
            heatmapCounts.computeIfAbsent(date.getDayOfWeek(), ignored -> new HashMap<>()).merge(hour, 1, Integer::sum);
        }

        List<UtilizationAnalyticsResponse.HeatmapCell> heatmap = new ArrayList<>();
        for (DayOfWeek day : DayOfWeek.values()) {
            for (int hour = 8; hour <= 21; hour++) {
                heatmap.add(new UtilizationAnalyticsResponse.HeatmapCell(
                        day.getDisplayName(TextStyle.SHORT, Locale.ENGLISH), hour,
                        heatmapCounts.getOrDefault(day, Map.of()).getOrDefault(hour, 0)));
            }
        }

        List<UtilizationAnalyticsResponse.ForecastPoint> forecast = forecast(to, dailyCounts, from, to);
        int predictedBookings = (int) Math.round(forecast.stream()
                .mapToDouble(UtilizationAnalyticsResponse.ForecastPoint::predictedBookings).sum());
        UtilizationAnalyticsResponse.SpaceUtilization topSpace = spaceRows.stream().findFirst().orElse(null);
        double averageUtilization = spaceRows.stream().mapToDouble(UtilizationAnalyticsResponse.SpaceUtilization::utilizationRate)
                .average().orElse(0.0);
        String peakHour = hourCounts.entrySet().stream().max(Map.Entry.comparingByValue())
                .map(entry -> String.format(Locale.ROOT, "%02d:00-%02d:00", entry.getKey(), entry.getKey() + 1))
                .orElse("No activity");
        List<UtilizationAnalyticsResponse.Insight> insights = buildInsights(spaceRows, peakHour, predictedBookings);

        return new UtilizationAnalyticsResponse(
                from, to, "Linear Regression (daily demand forecast)",
                new UtilizationAnalyticsResponse.Summary(
                        round(averageUtilization), peakHour,
                        topSpace == null ? "No activity" : topSpace.name(),
                        (int) spaceRows.stream().filter(row -> row.classification().equals("UNDERUTILIZED")).count(),
                        bookings.size(), predictedBookings),
                heatmap, spaceRows, forecast, insights);
    }

    private UtilizationAnalyticsResponse.SpaceUtilization utilizationFor(
            Space space, List<Booking> bookings, LocalDate from, LocalDateTime rangeEnd, long days) {
        double dailyHours = Math.max(0, Duration.between(space.getOpeningTime(), space.getClosingTime()).toMinutes() / 60.0);
        double availableHours = space.getStatus() == SpaceStatus.AVAILABLE ? dailyHours * days : 0.0;
        double bookedHours = bookings.stream().mapToDouble(booking -> overlapHours(
                booking.getStartDateTime(), booking.getEndDateTime(), from.atStartOfDay(), rangeEnd)).sum();
        double rate = availableHours == 0 ? 0 : Math.min(100, bookedHours / availableHours * 100);
        String classification = rate < UNDERUTILIZED_THRESHOLD ? "UNDERUTILIZED" : rate >= 70 ? "HIGH_DEMAND" : "STABLE";
        return new UtilizationAnalyticsResponse.SpaceUtilization(
                space.getId(), space.getName(), space.getBuilding(), space.getSpaceType().name(),
                bookings.size(), round(bookedHours), round(availableHours), round(rate), classification);
    }

    private List<UtilizationAnalyticsResponse.ForecastPoint> forecast(
            LocalDate to, Map<LocalDate, Integer> dailyCounts, LocalDate from, LocalDate actualTo) {
        long n = Duration.between(from.atStartOfDay(), actualTo.plusDays(1).atStartOfDay()).toDays();
        double sumX = 0, sumY = 0, sumXY = 0, sumXX = 0;
        for (int i = 0; i < n; i++) {
            double y = dailyCounts.getOrDefault(from.plusDays(i), 0);
            sumX += i; sumY += y; sumXY += i * y; sumXX += i * i;
        }
        double denominator = n * sumXX - sumX * sumX;
        double slope = denominator == 0 ? 0 : (n * sumXY - sumX * sumY) / denominator;
        double intercept = n == 0 ? 0 : (sumY - slope * sumX) / n;
        double confidence = Math.min(0.95, 0.5 + n / 60.0);
        List<UtilizationAnalyticsResponse.ForecastPoint> result = new ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            double prediction = Math.max(0, intercept + slope * (n - 1 + i));
            result.add(new UtilizationAnalyticsResponse.ForecastPoint(to.plusDays(i), round(prediction), round(confidence * 100) / 100));
        }
        return result;
    }

    private List<UtilizationAnalyticsResponse.Insight> buildInsights(
            List<UtilizationAnalyticsResponse.SpaceUtilization> spaces, String peakHour, int predictedBookings) {
        List<UtilizationAnalyticsResponse.Insight> result = new ArrayList<>();
        spaces.stream().filter(row -> row.classification().equals("UNDERUTILIZED")).findFirst().ifPresent(row ->
                result.add(new UtilizationAnalyticsResponse.Insight("OPPORTUNITY", "Underutilized space",
                        row.name() + " has only " + row.utilizationRate() + "% utilization. Consider promotion or reassignment.")));
        if (!peakHour.equals("No activity")) {
            result.add(new UtilizationAnalyticsResponse.Insight("PEAK", "Peak demand window",
                    "The highest booking activity starts around " + peakHour + "."));
        }
        result.add(new UtilizationAnalyticsResponse.Insight("FORECAST", "Seven-day demand forecast",
                "The model predicts approximately " + predictedBookings + " bookings over the next seven days."));
        return result;
    }

    private double overlapHours(LocalDateTime start, LocalDateTime end, LocalDateTime rangeStart, LocalDateTime rangeEnd) {
        LocalDateTime overlapStart = start.isAfter(rangeStart) ? start : rangeStart;
        LocalDateTime overlapEnd = end.isBefore(rangeEnd) ? end : rangeEnd;
        return overlapEnd.isAfter(overlapStart) ? Duration.between(overlapStart, overlapEnd).toMinutes() / 60.0 : 0;
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
