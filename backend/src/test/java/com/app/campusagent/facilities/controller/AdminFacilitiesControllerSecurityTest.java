package com.app.campusagent.facilities.controller;

import com.app.campusagent.domain.Role;
import com.app.campusagent.domain.User;
import com.app.campusagent.facilities.repository.BookingRepository;
import com.app.campusagent.facilities.repository.MaintenanceTicketRepository;
import com.app.campusagent.facilities.repository.SpaceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AdminFacilitiesControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SpaceRepository spaceRepository;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private MaintenanceTicketRepository maintenanceTicketRepository;

    @Test
    void allowsAdminToReadStableOverviewContract() throws Exception {
        mockMvc.perform(get("/api/admin/facilities/overview")
                        .with(authentication(authFor(Role.ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.totalSpaces").isNumber())
                .andExpect(jsonPath("$.summary.openMaintenanceRequests").isNumber())
                .andExpect(jsonPath("$.spaceStatusBreakdown.length()").value(3))
                .andExpect(jsonPath("$.spaceStatusBreakdown[0].status").value("AVAILABLE"))
                .andExpect(jsonPath("$.spaceStatusBreakdown[1].status").value("OUT_OF_SERVICE"))
                .andExpect(jsonPath("$.spaceStatusBreakdown[2].status").value("INACTIVE"))
                .andExpect(jsonPath("$.bookingStatusBreakdown.length()").value(3))
                .andExpect(jsonPath("$.maintenanceStatusBreakdown.length()").value(4));
    }

    @Test
    void allowsSuperAdmin() throws Exception {
        mockMvc.perform(get("/api/admin/facilities/overview")
                        .with(authentication(authFor(Role.SUPER_ADMIN))))
                .andExpect(status().isOk());
    }

    @Test
    void rejectsStudent() throws Exception {
        mockMvc.perform(get("/api/admin/facilities/overview")
                        .with(authentication(authFor(Role.STUDENT))))
                .andExpect(status().isForbidden());
    }

    @Test
    void rejectsAnonymousUser() throws Exception {
        mockMvc.perform(get("/api/admin/facilities/overview"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void overviewEndpointDoesNotModifyFacilitiesData() throws Exception {
        long spacesBefore = spaceRepository.count();
        long bookingsBefore = bookingRepository.count();
        long maintenanceBefore = maintenanceTicketRepository.count();

        mockMvc.perform(get("/api/admin/facilities/overview")
                        .with(authentication(authFor(Role.ADMIN))))
                .andExpect(status().isOk());

        assertThat(spaceRepository.count()).isEqualTo(spacesBefore);
        assertThat(bookingRepository.count()).isEqualTo(bookingsBefore);
        assertThat(maintenanceTicketRepository.count()).isEqualTo(maintenanceBefore);
    }

    private UsernamePasswordAuthenticationToken authFor(Role role) {
        User user = new User(role.name().toLowerCase() + "@test.edu", "unused");
        user.setRole(role);
        return new UsernamePasswordAuthenticationToken(
                user,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role.name())));
    }
}