package com.app.campusagent.facilities.service;

import com.app.campusagent.domain.Role;
import com.app.campusagent.domain.User;
import com.app.campusagent.repository.UserRepository;
import com.app.campusagent.facilities.domain.*;
import com.app.campusagent.facilities.dto.*;
import com.app.campusagent.facilities.exception.FacilityErrorCode;
import com.app.campusagent.facilities.exception.FacilityException;
import com.app.campusagent.facilities.repository.BookingRepository;
import com.app.campusagent.facilities.repository.MaintenanceTicketRepository;
import com.app.campusagent.facilities.repository.SpaceRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class FacilitiesService {

    private static final Duration MAX_BOOKING_DURATION = Duration.ofHours(4);
    private static final long MAX_BOOKING_DAYS_IN_ADVANCE = 14;
    private static final Set<BookingStatus> BLOCKING_STATUSES = Set.of(BookingStatus.CONFIRMED);
    private static final Map<MaintenanceStatus, Set<MaintenanceStatus>> MAINTENANCE_TRANSITIONS = Map.of(
            MaintenanceStatus.SUBMITTED, Set.of(MaintenanceStatus.IN_PROGRESS, MaintenanceStatus.CANCELLED),
            MaintenanceStatus.IN_PROGRESS, Set.of(MaintenanceStatus.RESOLVED, MaintenanceStatus.CANCELLED),
            MaintenanceStatus.RESOLVED, Set.of(),
            MaintenanceStatus.CANCELLED, Set.of());

    private final SpaceRepository spaceRepository;
    private final BookingRepository bookingRepository;
    private final MaintenanceTicketRepository maintenanceTicketRepository;
    private final UserRepository userRepository;

    public FacilitiesService(SpaceRepository spaceRepository,
                             BookingRepository bookingRepository,
                             MaintenanceTicketRepository maintenanceTicketRepository,
                             UserRepository userRepository) {
        this.spaceRepository = spaceRepository;
        this.bookingRepository = bookingRepository;
        this.maintenanceTicketRepository = maintenanceTicketRepository;
        this.userRepository = userRepository;
    }

    /** Searches spaces and optionally removes spaces unavailable in the supplied time window. */
    @Transactional(readOnly = true)
    public List<SpaceResponse> searchSpaces(String query, String building, String spaceType,
                                            Integer minimumCapacity, Collection<String> equipment,
                                            LocalDateTime startDateTime, LocalDateTime endDateTime) {
        if (minimumCapacity != null && minimumCapacity < 1) {
            throw error(FacilityErrorCode.INVALID_CAPACITY, "Minimum capacity must be at least 1");
        }
        validatePairedTimes(startDateTime, endDateTime);
        if (startDateTime != null) {
            validateBasicTimeRange(startDateTime, endDateTime, false);
        }

        SpaceType requiredType = parseSpaceType(spaceType);
        Set<String> requiredEquipment = normalizeEquipment(equipment);
        String normalizedQuery = normalize(query);
        String normalizedBuilding = normalize(building);

        return spaceRepository.findAll().stream()
                .filter(space -> normalizedQuery == null
                        || normalize(space.getName()).contains(normalizedQuery)
                        || normalize(space.getRoomNumber()).contains(normalizedQuery))
                .filter(space -> normalizedBuilding == null
                        || normalize(space.getBuilding()).equals(normalizedBuilding))
                .filter(space -> requiredType == null || space.getSpaceType() == requiredType)
                .filter(space -> minimumCapacity == null || space.getCapacity() >= minimumCapacity)
                .filter(space -> normalizeEquipment(space.getEquipment()).containsAll(requiredEquipment))
                .filter(space -> startDateTime == null || isAvailable(space, startDateTime, endDateTime))
                .map(this::toSpaceResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public SpaceResponse getSpaceDetails(Long spaceId) {
        return toSpaceResponse(requireSpace(spaceId));
    }

    /** Checks status, opening hours and booking overlap for one space. */
    @Transactional(readOnly = true)
    public AvailabilityResponse checkAvailability(Long spaceId, LocalDateTime startDateTime,
                                                  LocalDateTime endDateTime) {
        Space space = requireSpace(spaceId);
        validateBasicTimeRange(startDateTime, endDateTime, false);
        String reason = availabilityReason(space, startDateTime, endDateTime);
        return new AvailabilityResponse(reason == null, reason, toSpaceResponse(space),
                startDateTime, endDateTime);
    }

    /** Creates a confirmed booking while holding a per-space database lock. */
    @Transactional
    public BookingResponse createBooking(User user, CreateBookingRequest request) {
        requireUser(user);
        if (request == null) {
            throw error(FacilityErrorCode.INVALID_TIME, "Booking request is required");
        }
        if (request.spaceId() == null) {
            throw error(FacilityErrorCode.SPACE_NOT_FOUND, "Space ID is required");
        }
        validateBasicTimeRange(request.startDateTime(), request.endDateTime(), true);

        Space space = spaceRepository.findByIdForUpdate(request.spaceId())
                .orElseThrow(() -> new FacilityException(FacilityErrorCode.SPACE_NOT_FOUND,
                        "Space not found: " + request.spaceId(), HttpStatus.NOT_FOUND));

        String reason = availabilityReason(space, request.startDateTime(), request.endDateTime());
        if (FacilityErrorCode.SPACE_UNAVAILABLE.name().equals(reason)) {
            throw new FacilityException(FacilityErrorCode.SPACE_UNAVAILABLE,
                    "Space is not available for booking", HttpStatus.CONFLICT);
        }
        if (FacilityErrorCode.INVALID_TIME.name().equals(reason)) {
            throw error(FacilityErrorCode.INVALID_TIME, "Booking is outside the space opening hours");
        }
        if (FacilityErrorCode.BOOKING_CONFLICT.name().equals(reason)) {
            throw new FacilityException(FacilityErrorCode.BOOKING_CONFLICT,
                    "The requested time overlaps an existing booking", HttpStatus.CONFLICT);
        }

        Booking booking = bookingRepository.save(new Booking(user.getId(), space,
                request.startDateTime(), request.endDateTime()));
        return toBookingResponse(booking);
    }

    @Transactional(readOnly = true)
    public List<BookingResponse> listUserBookings(User user) {
        requireUser(user);
        return bookingRepository.findAllByUserIdOrderByStartDateTimeDesc(user.getId()).stream()
                .map(this::toBookingResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AdminBookingResponse> listAllBookings() {
        return bookingRepository.findAllByOrderByStartDateTimeDesc().stream()
                .map(this::toAdminBookingResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public AdminBookingResponse getAnyBooking(Long bookingId) {
        return bookingRepository.findById(bookingId)
                .map(this::toAdminBookingResponse)
                .orElseThrow(() -> new FacilityException(FacilityErrorCode.BOOKING_NOT_FOUND,
                        "Booking not found", HttpStatus.NOT_FOUND));
    }

    @Transactional(readOnly = true)
    public BookingResponse getBookingStatus(User user, Long bookingId) {
        requireUser(user);
        if (bookingId == null) {
            throw new FacilityException(FacilityErrorCode.BOOKING_NOT_FOUND,
                    "Booking not found", HttpStatus.NOT_FOUND);
        }
        Booking booking = bookingRepository.findByIdAndUserId(bookingId, user.getId())
                .orElseThrow(() -> new FacilityException(FacilityErrorCode.BOOKING_NOT_FOUND,
                        "Booking not found", HttpStatus.NOT_FOUND));
        return toBookingResponse(booking);
    }

    /** Cancels an owned future booking without deleting its audit record. */
    @Transactional
    public BookingResponse cancelBooking(User user, Long bookingId) {
        requireUser(user);
        if (bookingId == null) {
            throw new FacilityException(FacilityErrorCode.BOOKING_NOT_FOUND,
                    "Booking not found", HttpStatus.NOT_FOUND);
        }
        Booking booking = bookingRepository.findByIdAndUserId(bookingId, user.getId())
                .orElseThrow(() -> new FacilityException(FacilityErrorCode.BOOKING_NOT_FOUND,
                        "Booking not found", HttpStatus.NOT_FOUND));

        if (booking.getStatus() == BookingStatus.CANCELLED) {
            return toBookingResponse(booking);
        }
        if (booking.getStatus() == BookingStatus.COMPLETED) {
            throw new FacilityException(FacilityErrorCode.BOOKING_CANCELLATION_NOT_ALLOWED,
                    "A completed booking cannot be cancelled", HttpStatus.CONFLICT);
        }
        if (!booking.getStartDateTime().isAfter(LocalDateTime.now())) {
            throw new FacilityException(FacilityErrorCode.BOOKING_CANCELLATION_NOT_ALLOWED,
                    "A booking cannot be cancelled after its start time", HttpStatus.CONFLICT);
        }

        booking.cancel();
        return toBookingResponse(bookingRepository.save(booking));
    }

    /** Creates a maintenance ticket using a known space or an explicit building and room. */
    @Transactional
    public MaintenanceResponse submitMaintenanceRequest(User user, SubmitMaintenanceRequest request) {
        requireUser(user);
        if (request == null || !StringUtils.hasText(request.facilityType())
                || !StringUtils.hasText(request.description())) {
            throw error(FacilityErrorCode.INVALID_MAINTENANCE_REQUEST,
                    "facilityType and description are required");
        }
        if (request.facilityType().length() > 255 || request.description().length() > 2000) {
            throw error(FacilityErrorCode.INVALID_MAINTENANCE_REQUEST,
                    "facilityType or description exceeds the allowed length");
        }
        Space space = null;
        String building;
        String roomNumber;

        if (request.spaceId() != null) {
            space = requireSpace(request.spaceId());
            building = space.getBuilding();
            roomNumber = space.getRoomNumber();
        } else {
            building = trimToNull(request.building());
            roomNumber = trimToNull(request.roomNumber());
            if (building == null || roomNumber == null) {
                throw error(FacilityErrorCode.INVALID_LOCATION,
                        "Provide either spaceId or both building and roomNumber");
            }
        }

        MaintenancePriority priority = parsePriority(request.priority());
        MaintenanceTicket ticket = maintenanceTicketRepository.save(new MaintenanceTicket(
                user.getId(), space, building, roomNumber, request.facilityType().trim(),
                request.description().trim(), priority));
        return toMaintenanceResponse(ticket);
    }

    @Transactional(readOnly = true)
    public List<MaintenanceResponse> listUserMaintenanceRequests(User user) {
        requireUser(user);
        return maintenanceTicketRepository.findAllByUserIdOrderByCreatedAtDesc(user.getId()).stream()
                .map(this::toMaintenanceResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<MaintenanceResponse> listAllMaintenanceRequests() {
        return maintenanceTicketRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(this::toMaintenanceResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public MaintenanceResponse getAnyMaintenanceRequest(Long ticketId) {
        return maintenanceTicketRepository.findById(ticketId)
                .map(this::toMaintenanceResponse)
                .orElseThrow(() -> new FacilityException(FacilityErrorCode.TICKET_NOT_FOUND,
                        "Maintenance ticket not found", HttpStatus.NOT_FOUND));
    }

    @Transactional(readOnly = true)
    public MaintenanceResponse getMaintenanceStatus(User user, Long ticketId) {
        requireUser(user);
        if (ticketId == null) {
            throw new FacilityException(FacilityErrorCode.TICKET_NOT_FOUND,
                    "Maintenance ticket not found", HttpStatus.NOT_FOUND);
        }
        MaintenanceTicket ticket = maintenanceTicketRepository.findByIdAndUserId(ticketId, user.getId())
                .orElseThrow(() -> new FacilityException(FacilityErrorCode.TICKET_NOT_FOUND,
                        "Maintenance ticket not found", HttpStatus.NOT_FOUND));
        return toMaintenanceResponse(ticket);
    }

    /** Updates ticket workflow state for an existing CampusLink administrator. */
    @Transactional
    public MaintenanceResponse updateMaintenanceStatus(User requester, Long ticketId,
                                                        UpdateMaintenanceStatusRequest request) {
        requireMaintenanceManager(requester);
        if (ticketId == null) {
            throw new FacilityException(FacilityErrorCode.TICKET_NOT_FOUND,
                    "Maintenance ticket not found", HttpStatus.NOT_FOUND);
        }
        if (request == null || !StringUtils.hasText(request.status())) {
            throw error(FacilityErrorCode.INVALID_MAINTENANCE_STATUS,
                    "Maintenance status is required");
        }

        MaintenanceStatus targetStatus = parseMaintenanceStatus(request.status());
        MaintenanceTicket ticket = maintenanceTicketRepository.findById(ticketId)
                .orElseThrow(() -> new FacilityException(FacilityErrorCode.TICKET_NOT_FOUND,
                        "Maintenance ticket not found", HttpStatus.NOT_FOUND));
        MaintenanceStatus currentStatus = ticket.getStatus();
        if (currentStatus == targetStatus) {
            return toMaintenanceResponse(ticket);
        }
        if (!MAINTENANCE_TRANSITIONS.get(currentStatus).contains(targetStatus)) {
            throw new FacilityException(FacilityErrorCode.INVALID_MAINTENANCE_TRANSITION,
                    "Maintenance status cannot change from " + currentStatus + " to " + targetStatus,
                    HttpStatus.CONFLICT);
        }

        ticket.updateStatus(targetStatus);
        return toMaintenanceResponse(maintenanceTicketRepository.save(ticket));
    }

    private boolean isAvailable(Space space, LocalDateTime startDateTime, LocalDateTime endDateTime) {
        try {
            return availabilityReason(space, startDateTime, endDateTime) == null;
        } catch (FacilityException ex) {
            return false;
        }
    }

    private String availabilityReason(Space space, LocalDateTime startDateTime, LocalDateTime endDateTime) {
        if (space.getStatus() != SpaceStatus.AVAILABLE) {
            return FacilityErrorCode.SPACE_UNAVAILABLE.name();
        }
        if (startDateTime.toLocalTime().isBefore(space.getOpeningTime())
                || endDateTime.toLocalTime().isAfter(space.getClosingTime())) {
            return FacilityErrorCode.INVALID_TIME.name();
        }
        boolean conflict = bookingRepository.existsConflict(space.getId(), BLOCKING_STATUSES,
                startDateTime, endDateTime);
        return conflict ? FacilityErrorCode.BOOKING_CONFLICT.name() : null;
    }

    private void validatePairedTimes(LocalDateTime startDateTime, LocalDateTime endDateTime) {
        if ((startDateTime == null) != (endDateTime == null)) {
            throw error(FacilityErrorCode.INVALID_TIME,
                    "startDateTime and endDateTime must be provided together");
        }
    }

    private void validateBasicTimeRange(LocalDateTime startDateTime, LocalDateTime endDateTime,
                                        boolean enforceBookingWindow) {
        if (startDateTime == null || endDateTime == null || !startDateTime.isBefore(endDateTime)) {
            throw error(FacilityErrorCode.INVALID_TIME, "Start time must be before end time");
        }
        if (!startDateTime.toLocalDate().equals(endDateTime.toLocalDate())) {
            throw error(FacilityErrorCode.INVALID_TIME, "A booking must start and end on the same date");
        }
        if (Duration.between(startDateTime, endDateTime).compareTo(MAX_BOOKING_DURATION) > 0) {
            throw error(FacilityErrorCode.INVALID_TIME, "Booking duration cannot exceed 4 hours");
        }
        if (enforceBookingWindow) {
            LocalDateTime now = LocalDateTime.now();
            if (startDateTime.isBefore(now)) {
                throw error(FacilityErrorCode.INVALID_TIME, "Booking time must be in the future");
            }
            if (startDateTime.isAfter(now.plusDays(MAX_BOOKING_DAYS_IN_ADVANCE))) {
                throw error(FacilityErrorCode.INVALID_TIME, "Bookings can be made at most 14 days in advance");
            }
        }
    }

    private Space requireSpace(Long spaceId) {
        if (spaceId == null) {
            throw error(FacilityErrorCode.SPACE_NOT_FOUND, "Space ID is required");
        }
        return spaceRepository.findById(spaceId)
                .orElseThrow(() -> new FacilityException(FacilityErrorCode.SPACE_NOT_FOUND,
                        "Space not found: " + spaceId, HttpStatus.NOT_FOUND));
    }

    private void requireUser(User user) {
        if (user == null || user.getId() == null) {
            throw new FacilityException(FacilityErrorCode.AUTHENTICATION_REQUIRED,
                    "An authenticated user is required", HttpStatus.UNAUTHORIZED);
        }
    }

    private void requireMaintenanceManager(User user) {
        requireUser(user);
        if (user.getRole() != Role.ADMIN && user.getRole() != Role.SUPER_ADMIN) {
            throw new AccessDeniedException("ADMIN or SUPER_ADMIN role is required");
        }
    }

    private SpaceType parseSpaceType(String value) {
        if (!StringUtils.hasText(value) || "ANY".equalsIgnoreCase(value.trim())) {
            return null;
        }
        try {
            return SpaceType.valueOf(value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_'));
        } catch (IllegalArgumentException ex) {
            throw error(FacilityErrorCode.INVALID_SPACE_TYPE, "Unsupported space type: " + value);
        }
    }

    private MaintenancePriority parsePriority(String value) {
        if (!StringUtils.hasText(value)) {
            return MaintenancePriority.MEDIUM;
        }
        try {
            return MaintenancePriority.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw error(FacilityErrorCode.INVALID_PRIORITY, "Priority must be LOW, MEDIUM, or HIGH");
        }
    }

    private MaintenanceStatus parseMaintenanceStatus(String value) {
        try {
            return MaintenanceStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw error(FacilityErrorCode.INVALID_MAINTENANCE_STATUS,
                    "Status must be SUBMITTED, IN_PROGRESS, RESOLVED, or CANCELLED");
        }
    }

    private Set<String> normalizeEquipment(Collection<String> equipment) {
        if (equipment == null) {
            return Set.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        equipment.stream().filter(StringUtils::hasText)
                .map(this::normalize)
                .forEach(normalized::add);
        return normalized;
    }

    private String normalize(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private FacilityException error(FacilityErrorCode code, String message) {
        return new FacilityException(code, message, HttpStatus.BAD_REQUEST);
    }

    private SpaceResponse toSpaceResponse(Space space) {
        return new SpaceResponse(space.getId(), space.getName(), space.getBuilding(), space.getFloor(),
                space.getRoomNumber(), space.getSpaceType().name(), space.getCapacity(),
                Collections.unmodifiableSet(new LinkedHashSet<>(space.getEquipment())),
                space.getOpeningTime(), space.getClosingTime(), space.getStatus().name());
    }

    private BookingResponse toBookingResponse(Booking booking) {
        return new BookingResponse(true, booking.getId(), toSpaceResponse(booking.getSpace()),
                booking.getStartDateTime(), booking.getEndDateTime(), booking.getStatus().name(),
                booking.getCreatedAt(), booking.getUpdatedAt());
    }

    private AdminBookingResponse toAdminBookingResponse(Booking booking) {
        User owner = userRepository.findById(booking.getUserId()).orElse(null);
        return new AdminBookingResponse(true, booking.getId(), booking.getUserId(),
                owner == null ? "Unknown account" : owner.getEmail(), toSpaceResponse(booking.getSpace()),
                booking.getStartDateTime(), booking.getEndDateTime(), booking.getStatus().name(),
                booking.getCreatedAt(), booking.getUpdatedAt());
    }

    private MaintenanceResponse toMaintenanceResponse(MaintenanceTicket ticket) {
        Space space = ticket.getSpace();
        return new MaintenanceResponse(true, ticket.getId(), space == null ? null : space.getId(),
                space == null ? null : space.getName(), ticket.getBuilding(), ticket.getRoomNumber(),
                ticket.getFacilityType(), ticket.getDescription(), ticket.getPriority().name(),
                ticket.getStatus().name(), ticket.getCreatedAt(), ticket.getUpdatedAt());
    }
}
