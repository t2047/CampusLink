package com.app.campusagent.facilities.mcp;

import com.app.campusagent.domain.User;
import com.app.campusagent.facilities.dto.McpToolResponse;
import com.app.campusagent.facilities.service.FacilitiesService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
class FacilitiesMcpToolsTest {

    @Mock
    private FacilitiesService facilitiesService;

    private FacilitiesMcpTools tools;

    @BeforeEach
    void setUp() {
        User user = mock(User.class);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
        tools = new FacilitiesMcpTools(facilitiesService);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void searchSpacesToolCanBeCalled() {
        assertSuccess(tools.searchSpaces(null, "COM2", "STUDY_ROOM", 4,
                List.of("projector"), null, null));
    }

    @Test
    void getSpaceDetailsToolCanBeCalled() {
        assertSuccess(tools.getSpaceDetails(1L));
    }

    @Test
    void checkAvailabilityToolCanBeCalled() {
        assertSuccess(tools.checkAvailability(1L,
                "2026-08-10T14:00:00", "2026-08-10T16:00:00"));
    }

    @Test
    void createBookingToolCanBeCalled() {
        assertSuccess(tools.createBooking(1L,
                "2026-08-10T14:00:00", "2026-08-10T16:00:00"));
    }

    @Test
    void listUserBookingsToolCanBeCalled() {
        assertSuccess(tools.listUserBookings());
    }

    @Test
    void getBookingStatusToolCanBeCalled() {
        assertSuccess(tools.getBookingStatus(1L));
    }

    @Test
    void cancelBookingToolCanBeCalled() {
        assertSuccess(tools.cancelBooking(1L));
    }

    @Test
    void submitMaintenanceRequestToolCanBeCalled() {
        assertSuccess(tools.submitMaintenanceRequest(1L, null, null,
                "projector", "Projector cannot turn on", "HIGH"));
    }

    @Test
    void getMaintenanceStatusToolCanBeCalled() {
        assertSuccess(tools.getMaintenanceStatus(1L));
    }

    @Test
    void listUserMaintenanceRequestsToolCanBeCalled() {
        assertSuccess(tools.listUserMaintenanceRequests());
    }

    @Test
    void malformedDateTimeReturnsStructuredError() {
        McpToolResponse<?> response = tools.checkAvailability(1L, "tomorrow", "later");

        assertThat(response.success()).isFalse();
        assertThat(response.error().code()).isEqualTo("INVALID_TIME");
    }

    private void assertSuccess(McpToolResponse<?> response) {
        assertThat(response.success()).isTrue();
        assertThat(response.error()).isNull();
    }
}
