package com.app.campusagent.facilities.controller;

import com.app.campusagent.domain.User;
import com.app.campusagent.facilities.dto.*;
import com.app.campusagent.facilities.service.FacilitiesService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/facilities")
public class FacilitiesController {

    private final FacilitiesService facilitiesService;

    public FacilitiesController(FacilitiesService facilitiesService) {
        this.facilitiesService = facilitiesService;
    }

    @GetMapping("/spaces")
    public List<SpaceResponse> searchSpaces(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String building,
            @RequestParam(required = false) String spaceType,
            @RequestParam(required = false) Integer minimumCapacity,
            @RequestParam(required = false) List<String> equipment,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            LocalDateTime startDateTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            LocalDateTime endDateTime) {
        return facilitiesService.searchSpaces(query, building, spaceType, minimumCapacity, equipment,
                startDateTime, endDateTime);
    }

    @GetMapping("/spaces/{spaceId}")
    public SpaceResponse getSpaceDetails(@PathVariable Long spaceId) {
        return facilitiesService.getSpaceDetails(spaceId);
    }

    @GetMapping("/spaces/{spaceId}/availability")
    public AvailabilityResponse checkAvailability(
            @PathVariable Long spaceId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDateTime,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDateTime) {
        return facilitiesService.checkAvailability(spaceId, startDateTime, endDateTime);
    }

    @PostMapping("/bookings")
    public ResponseEntity<BookingResponse> createBooking(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody CreateBookingRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(facilitiesService.createBooking(user, request));
    }

    @GetMapping("/bookings")
    public List<BookingResponse> listUserBookings(@AuthenticationPrincipal User user) {
        return facilitiesService.listUserBookings(user);
    }

    @GetMapping("/bookings/{bookingId}")
    public BookingResponse getBookingStatus(@AuthenticationPrincipal User user,
                                            @PathVariable Long bookingId) {
        return facilitiesService.getBookingStatus(user, bookingId);
    }

    @PatchMapping("/bookings/{bookingId}/cancel")
    public BookingResponse cancelBooking(@AuthenticationPrincipal User user,
                                         @PathVariable Long bookingId) {
        return facilitiesService.cancelBooking(user, bookingId);
    }

    @PostMapping("/maintenance")
    public ResponseEntity<MaintenanceResponse> submitMaintenanceRequest(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody SubmitMaintenanceRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(facilitiesService.submitMaintenanceRequest(user, request));
    }

    @GetMapping("/maintenance")
    public List<MaintenanceResponse> listUserMaintenanceRequests(@AuthenticationPrincipal User user) {
        return facilitiesService.listUserMaintenanceRequests(user);
    }

    @GetMapping("/maintenance/{ticketId}")
    public MaintenanceResponse getMaintenanceStatus(@AuthenticationPrincipal User user,
                                                    @PathVariable Long ticketId) {
        return facilitiesService.getMaintenanceStatus(user, ticketId);
    }

    @PatchMapping("/maintenance/{ticketId}/status")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public MaintenanceResponse updateMaintenanceStatus(
            @AuthenticationPrincipal User user,
            @PathVariable Long ticketId,
            @Valid @RequestBody UpdateMaintenanceStatusRequest request) {
        return facilitiesService.updateMaintenanceStatus(user, ticketId, request);
    }
}
