package com.app.campusagent.facilities.mcp;

import com.app.campusagent.domain.User;
import com.app.campusagent.facilities.dto.CreateBookingRequest;
import com.app.campusagent.facilities.dto.McpToolResponse;
import com.app.campusagent.facilities.dto.SubmitMaintenanceRequest;
import com.app.campusagent.facilities.exception.FacilityErrorCode;
import com.app.campusagent.facilities.exception.FacilityException;
import com.app.campusagent.facilities.service.FacilitiesService;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;

@Component
public class FacilitiesMcpTools {

    private final FacilitiesService facilitiesService;

    public FacilitiesMcpTools(FacilitiesService facilitiesService) {
        this.facilitiesService = facilitiesService;
    }

    @McpTool(name = "search_spaces", description = "Search campus spaces by name, building, type, minimum capacity, equipment, and optional availability window. Use ISO local date-times such as 2026-08-10T14:00:00.")
    public McpToolResponse<?> searchSpaces(
            @McpToolParam(description = "Optional room name or room number text", required = false) String query,
            @McpToolParam(description = "Optional building code, for example COM2", required = false) String building,
            @McpToolParam(description = "Optional type: STUDY_ROOM, SEMINAR_ROOM, SPORTS_VENUE, LAB, LECTURE_ROOM, or ANY", required = false) String spaceType,
            @McpToolParam(description = "Optional minimum number of people, at least 1", required = false) Integer minimumCapacity,
            @McpToolParam(description = "Required equipment names such as projector, whiteboard, monitor, or power_socket", required = false) List<String> equipment,
            @McpToolParam(description = "Optional ISO start date-time; must be supplied with endDateTime", required = false) String startDateTime,
            @McpToolParam(description = "Optional ISO end date-time; must be supplied with startDateTime", required = false) String endDateTime) {
        return execute(() -> facilitiesService.searchSpaces(query, building, spaceType, minimumCapacity,
                equipment, parseOptionalDateTime(startDateTime), parseOptionalDateTime(endDateTime)));
    }

    @McpTool(name = "get_space_details", description = "Get full details for one campus space by its numeric ID.")
    public McpToolResponse<?> getSpaceDetails(
            @McpToolParam(description = "Numeric space ID returned by search_spaces", required = true) Long spaceId) {
        return execute(() -> facilitiesService.getSpaceDetails(spaceId));
    }

    @McpTool(name = "check_availability", description = "Check whether one campus space can be booked for a specific ISO date-time window.")
    public McpToolResponse<?> checkAvailability(
            @McpToolParam(description = "Numeric space ID", required = true) Long spaceId,
            @McpToolParam(description = "ISO start date-time, for example 2026-08-10T14:00:00", required = true) String startDateTime,
            @McpToolParam(description = "ISO end date-time, for example 2026-08-10T16:00:00", required = true) String endDateTime) {
        return execute(() -> facilitiesService.checkAvailability(spaceId,
                parseRequiredDateTime(startDateTime), parseRequiredDateTime(endDateTime)));
    }

    @McpTool(name = "create_booking", description = "Create a confirmed booking for the authenticated user after validating hours and conflicts. Call check_availability first when practical.")
    public McpToolResponse<?> createBooking(
            @McpToolParam(description = "Numeric space ID", required = true) Long spaceId,
            @McpToolParam(description = "ISO start date-time", required = true) String startDateTime,
            @McpToolParam(description = "ISO end date-time", required = true) String endDateTime) {
        return execute(() -> facilitiesService.createBooking(currentUser(), new CreateBookingRequest(
                spaceId, parseRequiredDateTime(startDateTime), parseRequiredDateTime(endDateTime))));
    }

    @McpTool(name = "list_user_bookings", description = "List all bookings owned by the authenticated user.")
    public McpToolResponse<?> listUserBookings() {
        return execute(() -> facilitiesService.listUserBookings(currentUser()));
    }

    @McpTool(name = "get_booking_status", description = "Get one booking owned by the authenticated user, including its current status.")
    public McpToolResponse<?> getBookingStatus(
            @McpToolParam(description = "Numeric booking ID", required = true) Long bookingId) {
        return execute(() -> facilitiesService.getBookingStatus(currentUser(), bookingId));
    }

    @McpTool(name = "cancel_booking", description = "Cancel one future booking owned by the authenticated user. The booking remains stored with CANCELLED status for auditability.")
    public McpToolResponse<?> cancelBooking(
            @McpToolParam(description = "Numeric booking ID owned by the authenticated user", required = true)
            Long bookingId) {
        return execute(() -> facilitiesService.cancelBooking(currentUser(), bookingId));
    }

    @McpTool(name = "submit_maintenance_request", description = "Submit a maintenance ticket for the authenticated user. Supply a known spaceId, or supply both building and roomNumber.")
    public McpToolResponse<?> submitMaintenanceRequest(
            @McpToolParam(description = "Optional numeric space ID", required = false) Long spaceId,
            @McpToolParam(description = "Building code when spaceId is not known", required = false) String building,
            @McpToolParam(description = "Room number when spaceId is not known", required = false) String roomNumber,
            @McpToolParam(description = "Broken facility type, for example projector or air_conditioning", required = true) String facilityType,
            @McpToolParam(description = "Clear description of the problem", required = true) String description,
            @McpToolParam(description = "Optional LOW, MEDIUM, or HIGH; defaults to MEDIUM", required = false) String priority) {
        return execute(() -> facilitiesService.submitMaintenanceRequest(currentUser(),
                new SubmitMaintenanceRequest(spaceId, building, roomNumber, facilityType, description, priority)));
    }

    @McpTool(name = "get_maintenance_status", description = "Get one maintenance ticket owned by the authenticated user, including its current status.")
    public McpToolResponse<?> getMaintenanceStatus(
            @McpToolParam(description = "Numeric maintenance ticket ID", required = true) Long ticketId) {
        return execute(() -> facilitiesService.getMaintenanceStatus(currentUser(), ticketId));
    }

    @McpTool(name = "list_user_maintenance_requests", description = "List all maintenance tickets owned by the authenticated user. Use this when the user asks for status without giving a ticket ID.")
    public McpToolResponse<?> listUserMaintenanceRequests() {
        return execute(() -> facilitiesService.listUserMaintenanceRequests(currentUser()));
    }

    private User currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof User user)) {
            throw new FacilityException(FacilityErrorCode.AUTHENTICATION_REQUIRED,
                    "A valid CampusLink JWT is required", org.springframework.http.HttpStatus.UNAUTHORIZED);
        }
        return user;
    }

    private LocalDateTime parseOptionalDateTime(String value) {
        return value == null || value.isBlank() ? null : parseRequiredDateTime(value);
    }

    private LocalDateTime parseRequiredDateTime(String value) {
        try {
            return LocalDateTime.parse(value);
        } catch (DateTimeParseException | NullPointerException ex) {
            throw new FacilityException(FacilityErrorCode.INVALID_TIME,
                    "Date-times must use ISO format, for example 2026-08-10T14:00:00",
                    org.springframework.http.HttpStatus.BAD_REQUEST);
        }
    }

    private McpToolResponse<?> execute(ToolCall call) {
        try {
            return McpToolResponse.success(call.run());
        } catch (FacilityException ex) {
            return McpToolResponse.failure(ex.getCode().name(), ex.getMessage());
        } catch (RuntimeException ex) {
            return McpToolResponse.failure("INTERNAL_ERROR", "Facilities operation failed");
        }
    }

    @FunctionalInterface
    private interface ToolCall {
        Object run();
    }
}
