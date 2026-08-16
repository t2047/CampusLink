package com.app.campusagent.facilities.controller;

import com.app.campusagent.facilities.dto.AdminBookingResponse;
import com.app.campusagent.facilities.dto.MaintenanceResponse;
import com.app.campusagent.facilities.dto.UtilizationAnalyticsResponse;
import com.app.campusagent.facilities.service.FacilitiesService;
import com.app.campusagent.facilities.service.UtilizationAnalyticsService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/admin/facilities")
public class AdminFacilitiesAnalyticsController {

    private final UtilizationAnalyticsService analyticsService;
    private final FacilitiesService facilitiesService;

    public AdminFacilitiesAnalyticsController(UtilizationAnalyticsService analyticsService,
                                              FacilitiesService facilitiesService) {
        this.analyticsService = analyticsService;
        this.facilitiesService = facilitiesService;
    }

    @GetMapping("/analytics")
    public UtilizationAnalyticsResponse analytics(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate) {
        return analyticsService.analyze(fromDate, toDate);
    }

    @GetMapping("/bookings")
    public List<AdminBookingResponse> allBookings() {
        return facilitiesService.listAllBookings();
    }

    @GetMapping("/bookings/{bookingId}")
    public AdminBookingResponse booking(@PathVariable Long bookingId) {
        return facilitiesService.getAnyBooking(bookingId);
    }

    @GetMapping("/maintenance")
    public List<MaintenanceResponse> allMaintenanceRequests() {
        return facilitiesService.listAllMaintenanceRequests();
    }

    @GetMapping("/maintenance/{ticketId}")
    public MaintenanceResponse maintenanceRequest(@PathVariable Long ticketId) {
        return facilitiesService.getAnyMaintenanceRequest(ticketId);
    }
}
