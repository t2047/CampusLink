package com.app.campusagent.facilities.service;

import com.app.campusagent.domain.Role;
import com.app.campusagent.domain.User;
import com.app.campusagent.facilities.domain.Booking;
import com.app.campusagent.facilities.domain.BookingStatus;
import com.app.campusagent.facilities.domain.Space;
import com.app.campusagent.facilities.domain.SpaceStatus;
import com.app.campusagent.facilities.dto.*;
import com.app.campusagent.facilities.exception.FacilityErrorCode;
import com.app.campusagent.facilities.exception.FacilityException;
import com.app.campusagent.facilities.repository.BookingRepository;
import com.app.campusagent.facilities.repository.MaintenanceTicketRepository;
import com.app.campusagent.facilities.repository.SpaceRepository;
import com.app.campusagent.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class FacilitiesServiceIntegrationTest {

    @Autowired
    private FacilitiesService facilitiesService;

    @Autowired
    private SpaceRepository spaceRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private MaintenanceTicketRepository maintenanceTicketRepository;

    private User user;
    private User otherUser;
    private User admin;
    private Long availableSpaceId;
    private Long unavailableSpaceId;
    private LocalDate bookingDate;

    @BeforeEach
    void setUp() {
        bookingRepository.deleteAll();
        maintenanceTicketRepository.deleteAll();
        user = userRepository.save(new User("facilities-" + UUID.randomUUID() + "@test.edu", "encoded"));
        otherUser = userRepository.save(new User("other-" + UUID.randomUUID() + "@test.edu", "encoded"));
        admin = new User("admin-" + UUID.randomUUID() + "@test.edu", "encoded");
        admin.setRole(Role.ADMIN);
        admin = userRepository.save(admin);
        availableSpaceId = spaceRepository.findAll().stream()
                .filter(space -> space.getStatus() == SpaceStatus.AVAILABLE)
                .filter(space -> space.getName().equals("COM2-03-12 Study Room"))
                .findFirst().orElseThrow().getId();
        unavailableSpaceId = spaceRepository.findAll().stream()
                .filter(space -> space.getStatus() == SpaceStatus.OUT_OF_SERVICE)
                .findFirst().orElseThrow().getId();
        bookingDate = LocalDate.now().plusDays(1);
    }

    @Test
    void searchWithoutFiltersReturnsSeedData() {
        List<SpaceResponse> results = facilitiesService.searchSpaces(null, null, null,
                null, null, null, null);

        assertThat(results).hasSizeGreaterThanOrEqualTo(15);
    }

    @Test
    void searchFiltersByCapacity() {
        List<SpaceResponse> results = facilitiesService.searchSpaces(null, null, null,
                100, null, null, null);

        assertThat(results).isNotEmpty().allMatch(space -> space.capacity() >= 100);
    }

    @Test
    void searchFiltersByBuilding() {
        List<SpaceResponse> results = facilitiesService.searchSpaces(null, "COM2", null,
                null, null, null, null);

        assertThat(results).isNotEmpty().allMatch(space -> space.building().equals("COM2"));
    }

    @Test
    void searchFiltersByEquipment() {
        List<SpaceResponse> results = facilitiesService.searchSpaces(null, null, null,
                null, List.of("projector", "whiteboard"), null, null);

        assertThat(results).isNotEmpty().allMatch(space ->
                space.equipment().containsAll(List.of("projector", "whiteboard")));
    }

    @Test
    void searchReturnsEmptyListWhenNothingMatches() {
        List<SpaceResponse> results = facilitiesService.searchSpaces(null, "DOES_NOT_EXIST", null,
                null, null, null, null);

        assertThat(results).isEmpty();
    }

    @Test
    void searchRejectsInvalidCapacity() {
        assertErrorCode(FacilityErrorCode.INVALID_CAPACITY, () -> facilitiesService.searchSpaces(
                null, null, null, 0, null, null, null));
    }

    @Test
    void searchAvailabilityWindowExcludesConflictingSpace() {
        facilitiesService.createBooking(user,
                new CreateBookingRequest(availableSpaceId, at(14, 0), at(16, 0)));

        List<SpaceResponse> results = facilitiesService.searchSpaces("COM2-03-12", null, null,
                null, null, at(15, 0), at(16, 0));

        assertThat(results).isEmpty();
    }

    @Test
    void availabilityIsTrueWhenThereIsNoBooking() {
        AvailabilityResponse result = facilitiesService.checkAvailability(availableSpaceId,
                at(10, 0), at(11, 0));

        assertThat(result.available()).isTrue();
        assertThat(result.reasonCode()).isNull();
    }

    @Test
    void unavailableSpaceCannotBeBooked() {
        AvailabilityResponse availability = facilitiesService.checkAvailability(unavailableSpaceId,
                at(10, 0), at(11, 0));

        assertThat(availability.available()).isFalse();
        assertThat(availability.reasonCode()).isEqualTo(FacilityErrorCode.SPACE_UNAVAILABLE.name());
        assertErrorCode(FacilityErrorCode.SPACE_UNAVAILABLE, () -> facilitiesService.createBooking(user,
                new CreateBookingRequest(unavailableSpaceId, at(10, 0), at(11, 0))));
    }

    @ParameterizedTest(name = "existing 14:00-16:00 conflicts with {0}:00-{1}:00")
    @CsvSource({"13,15", "14,16", "15,17", "13,17"})
    void detectsEveryOverlapShape(int requestedStartHour, int requestedEndHour) {
        facilitiesService.createBooking(user,
                new CreateBookingRequest(availableSpaceId, at(14, 0), at(16, 0)));

        AvailabilityResponse result = facilitiesService.checkAvailability(availableSpaceId,
                at(requestedStartHour, 0), at(requestedEndHour, 0));

        assertThat(result.available()).isFalse();
        assertThat(result.reasonCode()).isEqualTo(FacilityErrorCode.BOOKING_CONFLICT.name());
    }

    @ParameterizedTest(name = "edge-touching interval {0}:00-{1}:00 is allowed")
    @CsvSource({"12,14", "16,18"})
    void edgeTouchingIntervalsDoNotConflict(int requestedStartHour, int requestedEndHour) {
        facilitiesService.createBooking(user,
                new CreateBookingRequest(availableSpaceId, at(14, 0), at(16, 0)));

        AvailabilityResponse result = facilitiesService.checkAvailability(availableSpaceId,
                at(requestedStartHour, 0), at(requestedEndHour, 0));

        assertThat(result.available()).isTrue();
    }

    @Test
    void createBookingReturnsConfirmedBooking() {
        BookingResponse result = facilitiesService.createBooking(user,
                new CreateBookingRequest(availableSpaceId, at(9, 0), at(11, 0)));

        assertThat(result.success()).isTrue();
        assertThat(result.bookingId()).isNotNull();
        assertThat(result.status()).isEqualTo("CONFIRMED");
        assertThat(result.space().spaceId()).isEqualTo(availableSpaceId);
    }

    @Test
    void createBookingRejectsMissingSpace() {
        assertErrorCode(FacilityErrorCode.SPACE_NOT_FOUND, () -> facilitiesService.createBooking(user,
                new CreateBookingRequest(Long.MAX_VALUE, at(9, 0), at(10, 0))));
    }

    @Test
    void createBookingRejectsInvalidTime() {
        assertErrorCode(FacilityErrorCode.INVALID_TIME, () -> facilitiesService.createBooking(user,
                new CreateBookingRequest(availableSpaceId, at(11, 0), at(10, 0))));
    }

    @Test
    void createBookingRejectsConflict() {
        facilitiesService.createBooking(user,
                new CreateBookingRequest(availableSpaceId, at(14, 0), at(16, 0)));

        assertErrorCode(FacilityErrorCode.BOOKING_CONFLICT, () -> facilitiesService.createBooking(otherUser,
                new CreateBookingRequest(availableSpaceId, at(15, 0), at(17, 0))));
    }

    @Test
    void bookingTrackingIsRestrictedToOwner() {
        BookingResponse booking = facilitiesService.createBooking(user,
                new CreateBookingRequest(availableSpaceId, at(9, 0), at(10, 0)));

        assertThat(facilitiesService.listUserBookings(user)).hasSize(1);
        assertThat(facilitiesService.listUserBookings(otherUser)).isEmpty();
        assertErrorCode(FacilityErrorCode.BOOKING_NOT_FOUND,
                () -> facilitiesService.getBookingStatus(otherUser, booking.bookingId()));
    }

    @Test
    void cancellingBookingReleasesItsTimeAndKeepsAuditRecord() {
        BookingResponse booking = facilitiesService.createBooking(user,
                new CreateBookingRequest(availableSpaceId, at(14, 0), at(16, 0)));
        assertThat(facilitiesService.checkAvailability(availableSpaceId, at(15, 0), at(17, 0)).available())
                .isFalse();

        BookingResponse cancelled = facilitiesService.cancelBooking(user, booking.bookingId());

        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(cancelled.updatedAt()).isAfterOrEqualTo(booking.updatedAt());
        assertThat(bookingRepository.count()).isEqualTo(1);
        assertThat(facilitiesService.checkAvailability(availableSpaceId, at(15, 0), at(17, 0)).available())
                .isTrue();
        assertThat(facilitiesService.cancelBooking(user, booking.bookingId()).status())
                .isEqualTo("CANCELLED");
    }

    @Test
    void cancellingBookingIsRestrictedToOwner() {
        BookingResponse booking = facilitiesService.createBooking(user,
                new CreateBookingRequest(availableSpaceId, at(14, 0), at(16, 0)));

        assertErrorCode(FacilityErrorCode.BOOKING_NOT_FOUND,
                () -> facilitiesService.cancelBooking(otherUser, booking.bookingId()));
    }

    @Test
    void completedBookingCannotBeCancelled() {
        BookingResponse created = facilitiesService.createBooking(user,
                new CreateBookingRequest(availableSpaceId, at(14, 0), at(16, 0)));
        Booking booking = bookingRepository.findById(created.bookingId()).orElseThrow();
        ReflectionTestUtils.setField(booking, "status", BookingStatus.COMPLETED);
        bookingRepository.saveAndFlush(booking);

        assertErrorCode(FacilityErrorCode.BOOKING_CANCELLATION_NOT_ALLOWED,
                () -> facilitiesService.cancelBooking(user, created.bookingId()));
    }

    @Test
    void startedBookingCannotBeCancelled() {
        Space space = spaceRepository.findById(availableSpaceId).orElseThrow();
        Booking booking = bookingRepository.saveAndFlush(new Booking(user.getId(), space,
                LocalDateTime.now().minusHours(2), LocalDateTime.now().minusHours(1)));

        assertErrorCode(FacilityErrorCode.BOOKING_CANCELLATION_NOT_ALLOWED,
                () -> facilitiesService.cancelBooking(user, booking.getId()));
    }

    @Test
    void submitMaintenanceRequestCreatesSubmittedTicket() {
        MaintenanceResponse result = facilitiesService.submitMaintenanceRequest(user,
                new SubmitMaintenanceRequest(availableSpaceId, null, null,
                        "projector", "Projector cannot turn on", "HIGH"));

        assertThat(result.success()).isTrue();
        assertThat(result.ticketId()).isNotNull();
        assertThat(result.status()).isEqualTo("SUBMITTED");
        assertThat(result.building()).isEqualTo("COM2");
        assertThat(result.roomNumber()).isEqualTo("03-12");
    }

    @Test
    void maintenanceStatusCanBeReadByOwner() {
        MaintenanceResponse ticket = facilitiesService.submitMaintenanceRequest(user,
                new SubmitMaintenanceRequest(null, "COM2", "03-12",
                        "projector", "Projector cannot turn on", null));

        MaintenanceResponse result = facilitiesService.getMaintenanceStatus(user, ticket.ticketId());

        assertThat(result.status()).isEqualTo("SUBMITTED");
        assertThat(facilitiesService.listUserMaintenanceRequests(user)).hasSize(1);
    }

    @Test
    void maintenanceStatusRejectsUnknownTicket() {
        assertErrorCode(FacilityErrorCode.TICKET_NOT_FOUND,
                () -> facilitiesService.getMaintenanceStatus(user, Long.MAX_VALUE));
    }

    @Test
    void maintenanceTrackingIsRestrictedToOwner() {
        MaintenanceResponse ticket = facilitiesService.submitMaintenanceRequest(user,
                new SubmitMaintenanceRequest(availableSpaceId, null, null,
                        "monitor", "No power", "LOW"));

        assertThat(facilitiesService.listUserMaintenanceRequests(otherUser)).isEmpty();
        assertErrorCode(FacilityErrorCode.TICKET_NOT_FOUND,
                () -> facilitiesService.getMaintenanceStatus(otherUser, ticket.ticketId()));
    }

    @Test
    void adminCanAdvanceMaintenanceThroughAllowedStatuses() {
        MaintenanceResponse ticket = facilitiesService.submitMaintenanceRequest(user,
                new SubmitMaintenanceRequest(availableSpaceId, null, null,
                        "projector", "Projector cannot turn on", "HIGH"));

        MaintenanceResponse inProgress = facilitiesService.updateMaintenanceStatus(admin, ticket.ticketId(),
                new UpdateMaintenanceStatusRequest("IN_PROGRESS"));
        MaintenanceResponse resolved = facilitiesService.updateMaintenanceStatus(admin, ticket.ticketId(),
                new UpdateMaintenanceStatusRequest("RESOLVED"));

        assertThat(inProgress.status()).isEqualTo("IN_PROGRESS");
        assertThat(resolved.status()).isEqualTo("RESOLVED");
    }

    @Test
    void studentCannotUpdateMaintenanceStatus() {
        MaintenanceResponse ticket = facilitiesService.submitMaintenanceRequest(user,
                new SubmitMaintenanceRequest(availableSpaceId, null, null,
                        "monitor", "No power", "LOW"));

        assertThatThrownBy(() -> facilitiesService.updateMaintenanceStatus(user, ticket.ticketId(),
                new UpdateMaintenanceStatusRequest("IN_PROGRESS")))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void invalidMaintenanceTransitionIsRejected() {
        MaintenanceResponse ticket = facilitiesService.submitMaintenanceRequest(user,
                new SubmitMaintenanceRequest(availableSpaceId, null, null,
                        "monitor", "No power", "LOW"));

        assertErrorCode(FacilityErrorCode.INVALID_MAINTENANCE_TRANSITION,
                () -> facilitiesService.updateMaintenanceStatus(admin, ticket.ticketId(),
                        new UpdateMaintenanceStatusRequest("RESOLVED")));
    }

    private LocalDateTime at(int hour, int minute) {
        return LocalDateTime.of(bookingDate, LocalTime.of(hour, minute));
    }

    private void assertErrorCode(FacilityErrorCode code, Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(FacilityException.class)
                .extracting(ex -> ((FacilityException) ex).getCode())
                .isEqualTo(code);
    }
}
