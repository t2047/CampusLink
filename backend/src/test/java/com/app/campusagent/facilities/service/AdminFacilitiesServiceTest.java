package com.app.campusagent.facilities.service;

import com.app.campusagent.domain.User;
import com.app.campusagent.facilities.domain.Booking;
import com.app.campusagent.facilities.domain.BookingStatus;
import com.app.campusagent.facilities.domain.MaintenanceStatus;
import com.app.campusagent.facilities.domain.Space;
import com.app.campusagent.facilities.domain.SpaceStatus;
import com.app.campusagent.facilities.domain.SpaceType;
import com.app.campusagent.facilities.dto.admin.AdminFacilitiesOverviewResponse;
import com.app.campusagent.facilities.dto.admin.AdminFacilitiesPageResponse;
import com.app.campusagent.facilities.dto.admin.AdminFacilityBookingResponse;
import com.app.campusagent.facilities.repository.BookingRepository;
import com.app.campusagent.facilities.repository.MaintenanceTicketRepository;
import com.app.campusagent.facilities.repository.SpaceRepository;
import com.app.campusagent.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminFacilitiesServiceTest {

    @Mock
    private SpaceRepository spaceRepository;

    @Mock
    private BookingRepository bookingRepository;

    @Mock
    private MaintenanceTicketRepository maintenanceTicketRepository;

    @Mock
    private UserRepository userRepository;

    @Test
    void combinesRepositoryCountsIntoOverview() {
        when(spaceRepository.count()).thenReturn(12L);
        when(spaceRepository.countByStatus(SpaceStatus.AVAILABLE)).thenReturn(8L);
        when(spaceRepository.countByStatus(SpaceStatus.OUT_OF_SERVICE)).thenReturn(3L);
        when(spaceRepository.countByStatus(SpaceStatus.INACTIVE)).thenReturn(1L);

        when(bookingRepository.count()).thenReturn(20L);
        when(bookingRepository.countByStatus(BookingStatus.CONFIRMED)).thenReturn(9L);
        when(bookingRepository.countByStatus(BookingStatus.CANCELLED)).thenReturn(4L);
        when(bookingRepository.countByStatus(BookingStatus.COMPLETED)).thenReturn(7L);

        when(maintenanceTicketRepository.count()).thenReturn(14L);
        when(maintenanceTicketRepository.countByStatus(MaintenanceStatus.SUBMITTED)).thenReturn(5L);
        when(maintenanceTicketRepository.countByStatus(MaintenanceStatus.IN_PROGRESS)).thenReturn(3L);
        when(maintenanceTicketRepository.countByStatus(MaintenanceStatus.RESOLVED)).thenReturn(4L);
        when(maintenanceTicketRepository.countByStatus(MaintenanceStatus.CANCELLED)).thenReturn(2L);

        AdminFacilitiesOverviewResponse result = service().overview();

        assertThat(result.summary()).isEqualTo(new AdminFacilitiesOverviewResponse.Summary(
                12L, 8L, 3L, 1L,
                20L, 9L, 4L, 7L,
                14L, 5L, 3L, 4L, 2L, 8L));
        assertThat(result.spaceStatusBreakdown())
                .extracting(AdminFacilitiesOverviewResponse.StatusCount::status)
                .containsExactly("AVAILABLE", "OUT_OF_SERVICE", "INACTIVE");
        assertThat(result.bookingStatusBreakdown())
                .extracting(AdminFacilitiesOverviewResponse.StatusCount::status)
                .containsExactly("CONFIRMED", "CANCELLED", "COMPLETED");
        assertThat(result.maintenanceStatusBreakdown())
                .extracting(AdminFacilitiesOverviewResponse.StatusCount::status)
                .containsExactly("SUBMITTED", "IN_PROGRESS", "RESOLVED", "CANCELLED");
    }

    @Test
    void returnsEveryEnumStatusWhenAllCountsAreZero() {
        AdminFacilitiesOverviewResponse result = service().overview();

        assertThat(result.summary()).isEqualTo(new AdminFacilitiesOverviewResponse.Summary(
                0L, 0L, 0L, 0L,
                0L, 0L, 0L, 0L,
                0L, 0L, 0L, 0L, 0L, 0L));
        assertThat(result.spaceStatusBreakdown())
                .extracting(AdminFacilitiesOverviewResponse.StatusCount::status)
                .containsExactlyElementsOf(Arrays.stream(SpaceStatus.values()).map(Enum::name).toList());
        assertThat(result.bookingStatusBreakdown())
                .extracting(AdminFacilitiesOverviewResponse.StatusCount::status)
                .containsExactlyElementsOf(Arrays.stream(BookingStatus.values()).map(Enum::name).toList());
        assertThat(result.maintenanceStatusBreakdown())
                .extracting(AdminFacilitiesOverviewResponse.StatusCount::status)
                .containsExactlyElementsOf(Arrays.stream(MaintenanceStatus.values()).map(Enum::name).toList());
        assertThat(result.spaceStatusBreakdown()).allMatch(item -> item.count() == 0L);
        assertThat(result.bookingStatusBreakdown()).allMatch(item -> item.count() == 0L);
        assertThat(result.maintenanceStatusBreakdown()).allMatch(item -> item.count() == 0L);
    }

    @Test
    void usesOnlyReadOnlyCountQueriesForOverview() {
        AdminFacilitiesService service = service();

        service.overview();

        verify(spaceRepository).count();
        for (SpaceStatus status : SpaceStatus.values()) {
            verify(spaceRepository).countByStatus(status);
        }
        verify(bookingRepository).count();
        for (BookingStatus status : BookingStatus.values()) {
            verify(bookingRepository).countByStatus(status);
        }
        verify(maintenanceTicketRepository).count();
        for (MaintenanceStatus status : MaintenanceStatus.values()) {
            verify(maintenanceTicketRepository).countByStatus(status);
        }
        verifyNoMoreInteractions(spaceRepository, bookingRepository, maintenanceTicketRepository);
        verifyNoInteractions(userRepository);
    }

    @Test
    void mapsCurrentPageUserEmailsWithOneBatchLookup() {
        PageRequest pageable = PageRequest.of(0, 25);
        Space space = space(5L);
        Booking first = booking(1L, 10L, space, LocalDateTime.of(2026, 8, 16, 10, 0));
        Booking second = booking(2L, 11L, space, LocalDateTime.of(2026, 8, 16, 12, 0));
        User firstUser = user(10L, "first@nus.edu.sg");
        User secondUser = user(11L, "second@nus.edu.sg");

        when(bookingRepository.findAll(
                ArgumentMatchers.<Specification<Booking>>any(),
                eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(first, second), pageable, 2));
        when(userRepository.findAllById(ArgumentMatchers.<Iterable<Long>>any()))
                .thenReturn(List.of(firstUser, secondUser));

        AdminFacilitiesPageResponse<AdminFacilityBookingResponse> result = service().searchBookings(
                null, null, null, null, null, null, pageable);

        assertThat(result.content())
                .extracting(AdminFacilityBookingResponse::userEmail)
                .containsExactly("first@nus.edu.sg", "second@nus.edu.sg");
        verify(userRepository).findAllById(argThat(ids ->
                StreamSupport.stream(ids.spliterator(), false).collect(java.util.stream.Collectors.toSet())
                        .equals(Set.of(10L, 11L))));
        verifyNoMoreInteractions(userRepository);
    }

    @Test
    void returnsNullEmailWhenCurrentPageUserNoLongerExists() {
        PageRequest pageable = PageRequest.of(0, 25);
        Booking booking = booking(1L, 999L, space(5L), LocalDateTime.of(2026, 8, 16, 10, 0));
        when(bookingRepository.findAll(
                ArgumentMatchers.<Specification<Booking>>any(),
                eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(booking), pageable, 1));
        when(userRepository.findAllById(ArgumentMatchers.<Iterable<Long>>any())).thenReturn(List.of());

        AdminFacilitiesPageResponse<AdminFacilityBookingResponse> result = service().searchBookings(
                null, null, null, null, null, null, pageable);

        assertThat(result.content()).singleElement()
                .extracting(AdminFacilityBookingResponse::userEmail)
                .isNull();
    }

    private AdminFacilitiesService service() {
        return new AdminFacilitiesService(
                spaceRepository,
                bookingRepository,
                maintenanceTicketRepository,
                userRepository);
    }

    private Space space(Long id) {
        Space space = new Space(
                "Seminar Room 2", "COM3", "2", "02-10", SpaceType.SEMINAR_ROOM, 20,
                Set.of("Projector"), LocalTime.of(8, 0), LocalTime.of(22, 0), SpaceStatus.AVAILABLE);
        ReflectionTestUtils.setField(space, "id", id);
        return space;
    }

    private Booking booking(Long id, Long userId, Space space, LocalDateTime start) {
        Booking booking = new Booking(userId, space, start, start.plusHours(1));
        ReflectionTestUtils.setField(booking, "id", id);
        ReflectionTestUtils.setField(booking, "createdAt", start.minusDays(1));
        ReflectionTestUtils.setField(booking, "updatedAt", start.minusDays(1));
        return booking;
    }

    private User user(Long id, String email) {
        User user = new User(email, "encoded");
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
