package com.app.campusagent.facilities.controller;

import com.app.campusagent.domain.Role;
import com.app.campusagent.domain.User;
import com.app.campusagent.facilities.domain.SpaceStatus;
import com.app.campusagent.facilities.dto.CreateBookingRequest;
import com.app.campusagent.facilities.dto.MaintenanceResponse;
import com.app.campusagent.facilities.dto.SubmitMaintenanceRequest;
import com.app.campusagent.facilities.service.FacilitiesService;
import com.app.campusagent.facilities.repository.SpaceRepository;
import com.app.campusagent.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.containsString;

@SpringBootTest
@Transactional
class FacilitiesControllerAuthorizationIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private FacilitiesService facilitiesService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SpaceRepository spaceRepository;

    private MockMvc mockMvc;
    private User student;
    private User admin;
    private Long spaceId;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(
                org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
        student = userRepository.save(new User("controller-student-" + UUID.randomUUID() + "@test.edu", "encoded"));
        admin = new User("controller-admin-" + UUID.randomUUID() + "@test.edu", "encoded");
        admin.setRole(Role.ADMIN);
        admin = userRepository.save(admin);
        spaceId = spaceRepository.findAll().stream()
                .filter(space -> space.getStatus() == SpaceStatus.AVAILABLE)
                .findFirst().orElseThrow().getId();
    }

    @Test
    void bookingOwnerCanCancelThroughRest() throws Exception {
        LocalDateTime start = LocalDateTime.of(java.time.LocalDate.now().plusDays(1), LocalTime.of(14, 0));
        long bookingId = facilitiesService.createBooking(student,
                new CreateBookingRequest(spaceId, start, start.plusHours(1))).bookingId();

        mockMvc.perform(patch("/api/facilities/bookings/{bookingId}/cancel", bookingId)
                        .with(authentication(authFor(student)))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void studentReceivesForbiddenWhenUpdatingMaintenanceStatus() throws Exception {
        MaintenanceResponse ticket = createTicket();

        mockMvc.perform(patch("/api/facilities/maintenance/{ticketId}/status", ticket.ticketId())
                        .with(authentication(authFor(student)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"IN_PROGRESS\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanUpdateMaintenanceStatusThroughRest() throws Exception {
        MaintenanceResponse ticket = createTicket();

        mockMvc.perform(patch("/api/facilities/maintenance/{ticketId}/status", ticket.ticketId())
                        .with(authentication(authFor(admin)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"IN_PROGRESS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
    }

    @Test
    void invalidMaintenanceTransitionReturnsStructuredConflict() throws Exception {
        MaintenanceResponse ticket = createTicket();

        mockMvc.perform(patch("/api/facilities/maintenance/{ticketId}/status", ticket.ticketId())
                        .with(authentication(authFor(admin)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"RESOLVED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_MAINTENANCE_TRANSITION"))
                .andExpect(jsonPath("$.error", containsString("SUBMITTED")));
    }

    private MaintenanceResponse createTicket() {
        return facilitiesService.submitMaintenanceRequest(student,
                new SubmitMaintenanceRequest(spaceId, null, null,
                        "projector", "Projector cannot turn on", "MEDIUM"));
    }

    private UsernamePasswordAuthenticationToken authFor(User user) {
        return new UsernamePasswordAuthenticationToken(user, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name())));
    }
}
