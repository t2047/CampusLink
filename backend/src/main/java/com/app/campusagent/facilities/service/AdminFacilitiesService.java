package com.app.campusagent.facilities.service;

import com.app.campusagent.domain.User;
import com.app.campusagent.facilities.domain.Booking;
import com.app.campusagent.facilities.domain.BookingStatus;
import com.app.campusagent.facilities.domain.MaintenancePriority;
import com.app.campusagent.facilities.domain.MaintenanceStatus;
import com.app.campusagent.facilities.domain.MaintenanceTicket;
import com.app.campusagent.facilities.domain.Space;
import com.app.campusagent.facilities.domain.SpaceStatus;
import com.app.campusagent.facilities.dto.admin.AdminFacilitiesOverviewResponse;
import com.app.campusagent.facilities.dto.admin.AdminFacilitiesOverviewResponse.StatusCount;
import com.app.campusagent.facilities.dto.admin.AdminFacilitiesOverviewResponse.Summary;
import com.app.campusagent.facilities.dto.admin.AdminFacilitiesPageResponse;
import com.app.campusagent.facilities.dto.admin.AdminFacilityBookingResponse;
import com.app.campusagent.facilities.dto.admin.AdminFacilityMaintenanceResponse;
import com.app.campusagent.facilities.repository.BookingRepository;
import com.app.campusagent.facilities.repository.MaintenanceTicketRepository;
import com.app.campusagent.facilities.repository.SpaceRepository;
import com.app.campusagent.repository.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.function.ToLongFunction;
import java.util.stream.Collectors;

@Service
public class AdminFacilitiesService {

    private final SpaceRepository spaceRepository;
    private final BookingRepository bookingRepository;
    private final MaintenanceTicketRepository maintenanceTicketRepository;
    private final UserRepository userRepository;

    public AdminFacilitiesService(SpaceRepository spaceRepository,
                                  BookingRepository bookingRepository,
                                  MaintenanceTicketRepository maintenanceTicketRepository,
                                  UserRepository userRepository) {
        this.spaceRepository = spaceRepository;
        this.bookingRepository = bookingRepository;
        this.maintenanceTicketRepository = maintenanceTicketRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public AdminFacilitiesOverviewResponse overview() {
        Map<SpaceStatus, Long> spaceCounts = counts(SpaceStatus.class, spaceRepository::countByStatus);
        Map<BookingStatus, Long> bookingCounts = counts(BookingStatus.class, bookingRepository::countByStatus);
        Map<MaintenanceStatus, Long> maintenanceCounts = counts(
                MaintenanceStatus.class,
                maintenanceTicketRepository::countByStatus);

        long submittedMaintenanceRequests = maintenanceCounts.get(MaintenanceStatus.SUBMITTED);
        long inProgressMaintenanceRequests = maintenanceCounts.get(MaintenanceStatus.IN_PROGRESS);

        Summary summary = new Summary(
                spaceRepository.count(),
                spaceCounts.get(SpaceStatus.AVAILABLE),
                spaceCounts.get(SpaceStatus.OUT_OF_SERVICE),
                spaceCounts.get(SpaceStatus.INACTIVE),
                bookingRepository.count(),
                bookingCounts.get(BookingStatus.CONFIRMED),
                bookingCounts.get(BookingStatus.CANCELLED),
                bookingCounts.get(BookingStatus.COMPLETED),
                maintenanceTicketRepository.count(),
                submittedMaintenanceRequests,
                inProgressMaintenanceRequests,
                maintenanceCounts.get(MaintenanceStatus.RESOLVED),
                maintenanceCounts.get(MaintenanceStatus.CANCELLED),
                submittedMaintenanceRequests + inProgressMaintenanceRequests);

        return new AdminFacilitiesOverviewResponse(
                summary,
                breakdown(SpaceStatus.values(), spaceCounts),
                breakdown(BookingStatus.values(), bookingCounts),
                breakdown(MaintenanceStatus.values(), maintenanceCounts));
    }

    @Transactional(readOnly = true)
    public AdminFacilitiesPageResponse<AdminFacilityBookingResponse> searchBookings(
            BookingStatus status,
            Long spaceId,
            Long userId,
            String userEmail,
            LocalDateTime startFrom,
            LocalDateTime startTo,
            Pageable pageable) {
        if (startFrom != null && startTo != null && startFrom.isAfter(startTo)) {
            throw new IllegalArgumentException("startFrom must be before or equal to startTo");
        }

        String normalizedEmail = StringUtils.hasText(userEmail) ? userEmail.trim() : null;
        Long emailUserId = null;
        if (normalizedEmail != null) {
            emailUserId = userRepository.findByEmail(normalizedEmail)
                    .map(User::getId)
                    .orElse(null);
            if (emailUserId == null) {
                return AdminFacilitiesPageResponse.from(Page.empty(pageable));
            }
        }

        Specification<Booking> specification = (root, query, criteriaBuilder) -> criteriaBuilder.conjunction();
        if (status != null) {
            specification = specification.and((root, query, criteriaBuilder) ->
                    criteriaBuilder.equal(root.get("status"), status));
        }
        if (spaceId != null) {
            specification = specification.and((root, query, criteriaBuilder) ->
                    criteriaBuilder.equal(root.get("space").get("id"), spaceId));
        }
        if (userId != null) {
            specification = specification.and((root, query, criteriaBuilder) ->
                    criteriaBuilder.equal(root.get("userId"), userId));
        }
        if (emailUserId != null) {
            Long resolvedEmailUserId = emailUserId;
            specification = specification.and((root, query, criteriaBuilder) ->
                    criteriaBuilder.equal(root.get("userId"), resolvedEmailUserId));
        }
        if (startFrom != null) {
            specification = specification.and((root, query, criteriaBuilder) ->
                    criteriaBuilder.greaterThanOrEqualTo(root.get("startDateTime"), startFrom));
        }
        if (startTo != null) {
            specification = specification.and((root, query, criteriaBuilder) ->
                    criteriaBuilder.lessThanOrEqualTo(root.get("startDateTime"), startTo));
        }

        Page<Booking> bookings = bookingRepository.findAll(specification, pageable);
        Map<Long, String> emailsByUserId = userRepository
                .findAllById(bookings.getContent().stream().map(Booking::getUserId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(User::getId, User::getEmail));

        Page<AdminFacilityBookingResponse> responses = bookings.map(booking ->
                toAdminBookingResponse(booking, emailsByUserId.get(booking.getUserId())));
        return AdminFacilitiesPageResponse.from(responses);
    }

    @Transactional(readOnly = true)
    public AdminFacilitiesPageResponse<AdminFacilityMaintenanceResponse> searchMaintenance(
            List<MaintenanceStatus> statuses,
            MaintenancePriority priority,
            Long spaceId,
            Long userId,
            String userEmail,
            String building,
            LocalDateTime createdFrom,
            LocalDateTime createdTo,
            Pageable pageable) {
        if (createdFrom != null && createdTo != null && createdFrom.isAfter(createdTo)) {
            throw new IllegalArgumentException("createdFrom must be before or equal to createdTo");
        }

        String normalizedEmail = StringUtils.hasText(userEmail) ? userEmail.trim() : null;
        Long emailUserId = null;
        if (normalizedEmail != null) {
            emailUserId = userRepository.findByEmail(normalizedEmail)
                    .map(User::getId)
                    .orElse(null);
            if (emailUserId == null) {
                return AdminFacilitiesPageResponse.from(Page.empty(pageable));
            }
        }

        Specification<MaintenanceTicket> specification =
                (root, query, criteriaBuilder) -> criteriaBuilder.conjunction();
        if (statuses != null && !statuses.isEmpty()) {
            specification = specification.and((root, query, criteriaBuilder) ->
                    root.get("status").in(statuses));
        }
        if (priority != null) {
            specification = specification.and((root, query, criteriaBuilder) ->
                    criteriaBuilder.equal(root.get("priority"), priority));
        }
        if (spaceId != null) {
            specification = specification.and((root, query, criteriaBuilder) ->
                    criteriaBuilder.equal(root.get("space").get("id"), spaceId));
        }
        if (userId != null) {
            specification = specification.and((root, query, criteriaBuilder) ->
                    criteriaBuilder.equal(root.get("userId"), userId));
        }
        if (emailUserId != null) {
            Long resolvedEmailUserId = emailUserId;
            specification = specification.and((root, query, criteriaBuilder) ->
                    criteriaBuilder.equal(root.get("userId"), resolvedEmailUserId));
        }
        if (StringUtils.hasText(building)) {
            String buildingPattern = "%" + building.trim().toLowerCase(Locale.ROOT) + "%";
            specification = specification.and((root, query, criteriaBuilder) ->
                    criteriaBuilder.like(criteriaBuilder.lower(root.get("building")), buildingPattern));
        }
        if (createdFrom != null) {
            specification = specification.and((root, query, criteriaBuilder) ->
                    criteriaBuilder.greaterThanOrEqualTo(root.get("createdAt"), createdFrom));
        }
        if (createdTo != null) {
            specification = specification.and((root, query, criteriaBuilder) ->
                    criteriaBuilder.lessThanOrEqualTo(root.get("createdAt"), createdTo));
        }

        Page<MaintenanceTicket> tickets = maintenanceTicketRepository.findAll(specification, pageable);
        Map<Long, String> emailsByUserId = userRepository
                .findAllById(tickets.getContent().stream().map(MaintenanceTicket::getUserId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(User::getId, User::getEmail));
        Page<AdminFacilityMaintenanceResponse> responses = tickets.map(ticket ->
                toAdminMaintenanceResponse(ticket, emailsByUserId.get(ticket.getUserId())));
        return AdminFacilitiesPageResponse.from(responses);
    }

    @Transactional(readOnly = true)
    public AdminFacilityMaintenanceResponse getMaintenance(Long ticketId) {
        MaintenanceTicket ticket = maintenanceTicketRepository.findById(ticketId)
                .orElseThrow(() -> new com.app.campusagent.facilities.exception.FacilityException(
                        com.app.campusagent.facilities.exception.FacilityErrorCode.TICKET_NOT_FOUND,
                        "Maintenance ticket not found",
                        org.springframework.http.HttpStatus.NOT_FOUND));
        String userEmail = userRepository.findById(ticket.getUserId()).map(User::getEmail).orElse(null);
        return toAdminMaintenanceResponse(ticket, userEmail);
    }

    private AdminFacilityMaintenanceResponse toAdminMaintenanceResponse(
            MaintenanceTicket ticket,
            String userEmail) {
        Space space = ticket.getSpace();
        return new AdminFacilityMaintenanceResponse(
                ticket.getId(),
                ticket.getUserId(),
                userEmail,
                space == null ? null : space.getId(),
                space == null ? null : space.getName(),
                space == null || space.getSpaceType() == null ? null : space.getSpaceType().name(),
                ticket.getBuilding(),
                space == null ? null : space.getFloor(),
                ticket.getRoomNumber(),
                ticket.getFacilityType(),
                ticket.getDescription(),
                ticket.getPriority().name(),
                ticket.getStatus().name(),
                ticket.getCreatedAt(),
                ticket.getUpdatedAt());
    }

    private AdminFacilityBookingResponse toAdminBookingResponse(Booking booking, String userEmail) {
        Space space = booking.getSpace();
        return new AdminFacilityBookingResponse(
                booking.getId(),
                booking.getUserId(),
                userEmail,
                space.getId(),
                space.getName(),
                space.getBuilding(),
                space.getFloor(),
                space.getRoomNumber(),
                space.getSpaceType().name(),
                booking.getStartDateTime(),
                booking.getEndDateTime(),
                booking.getStatus().name(),
                booking.getCreatedAt(),
                booking.getUpdatedAt());
    }

    private <E extends Enum<E>> Map<E, Long> counts(Class<E> enumType, ToLongFunction<E> counter) {
        Map<E, Long> counts = new EnumMap<>(enumType);
        Arrays.stream(enumType.getEnumConstants())
                .forEach(status -> counts.put(status, counter.applyAsLong(status)));
        return counts;
    }

    private <E extends Enum<E>> List<StatusCount> breakdown(E[] statuses, Map<E, Long> counts) {
        return Arrays.stream(statuses)
                .map(status -> new StatusCount(status.name(), counts.get(status)))
                .toList();
    }
}
