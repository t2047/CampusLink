package com.app.campusagent.facilities.controller;

import com.app.campusagent.facilities.domain.BookingStatus;
import com.app.campusagent.facilities.domain.MaintenancePriority;
import com.app.campusagent.facilities.domain.MaintenanceStatus;
import com.app.campusagent.facilities.dto.admin.AdminFacilitiesOverviewResponse;
import com.app.campusagent.facilities.dto.admin.AdminFacilitiesPageResponse;
import com.app.campusagent.facilities.dto.admin.AdminFacilityBookingResponse;
import com.app.campusagent.facilities.dto.admin.AdminFacilityMaintenanceResponse;
import com.app.campusagent.facilities.service.AdminFacilitiesService;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/admin/facilities")
@PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
public class AdminFacilitiesController {

    private static final Set<String> BOOKING_SORT_FIELDS = Set.of(
            "id", "startDateTime", "endDateTime", "createdAt", "updatedAt", "status");
    private static final Set<String> MAINTENANCE_SORT_FIELDS = Set.of(
            "id", "createdAt", "updatedAt", "status", "priority", "building", "roomNumber");

    private final AdminFacilitiesService adminFacilitiesService;

    public AdminFacilitiesController(AdminFacilitiesService adminFacilitiesService) {
        this.adminFacilitiesService = adminFacilitiesService;
    }

    @GetMapping("/overview")
    public AdminFacilitiesOverviewResponse overview() {
        return adminFacilitiesService.overview();
    }

    @GetMapping("/bookings")
    public AdminFacilitiesPageResponse<AdminFacilityBookingResponse> bookings(
            @RequestParam(required = false) BookingStatus status,
            @RequestParam(required = false) Long spaceId,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String userEmail,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startFrom,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "startDateTime,asc") String sort) {
        return adminFacilitiesService.searchBookings(
                status,
                spaceId,
                userId,
                userEmail,
                startFrom,
                startTo,
                bookingPageable(page, size, sort));
    }

    @GetMapping("/maintenance")
    public AdminFacilitiesPageResponse<AdminFacilityMaintenanceResponse> maintenance(
            @RequestParam(name = "status", required = false) List<MaintenanceStatus> statuses,
            @RequestParam(required = false) MaintenancePriority priority,
            @RequestParam(required = false) Long spaceId,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String userEmail,
            @RequestParam(required = false) String building,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime createdFrom,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime createdTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "createdAt,desc") String sort) {
        return adminFacilitiesService.searchMaintenance(
                statuses,
                priority,
                spaceId,
                userId,
                userEmail,
                building,
                createdFrom,
                createdTo,
                maintenancePageable(page, size, sort));
    }

    @GetMapping("/maintenance/{ticketId}")
    public AdminFacilityMaintenanceResponse maintenanceDetail(@PathVariable Long ticketId) {
        return adminFacilitiesService.getMaintenance(ticketId);
    }

    private Pageable bookingPageable(int page, int size, String sortValue) {
        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("page must be at least 0 and size must be between 1 and 100");
        }

        String[] parts = sortValue.trim().split(",", -1);
        if (parts.length > 2) {
            throw new IllegalArgumentException("sort must use the format field,direction");
        }

        String field = parts[0].trim();
        if (!BOOKING_SORT_FIELDS.contains(field)) {
            throw new IllegalArgumentException(
                    "sort field must be id, startDateTime, endDateTime, createdAt, updatedAt or status");
        }

        Sort.Direction direction = parts.length == 1
                ? Sort.Direction.ASC
                : Sort.Direction.fromOptionalString(parts[1].trim()).orElseThrow(() ->
                        new IllegalArgumentException("sort direction must be asc or desc"));

        Sort bookingSort = Sort.by(direction, field);
        if (!"id".equals(field)) {
            bookingSort = bookingSort.and(Sort.by(Sort.Direction.ASC, "id"));
        }
        return PageRequest.of(page, size, bookingSort);
    }
    private Pageable maintenancePageable(int page, int size, String sortValue) {
        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("page must be at least 0 and size must be between 1 and 100");
        }

        String[] parts = sortValue.trim().split(",", -1);
        if (parts.length > 2) {
            throw new IllegalArgumentException("sort must use the format field,direction");
        }

        String field = parts[0].trim();
        if (!MAINTENANCE_SORT_FIELDS.contains(field)) {
            throw new IllegalArgumentException(
                    "sort field must be id, createdAt, updatedAt, status, priority, building or roomNumber");
        }

        Sort.Direction direction = parts.length == 1
                ? Sort.Direction.ASC
                : Sort.Direction.fromOptionalString(parts[1].trim()).orElseThrow(() ->
                        new IllegalArgumentException("sort direction must be asc or desc"));
        Sort maintenanceSort = Sort.by(direction, field);
        if (!"id".equals(field)) {
            maintenanceSort = maintenanceSort.and(Sort.by(Sort.Direction.ASC, "id"));
        }
        return PageRequest.of(page, size, maintenanceSort);
    }

}
