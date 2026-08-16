package com.app.campusagent.facilities.controller;

import com.app.campusagent.domain.Role;
import com.app.campusagent.domain.User;
import com.app.campusagent.facilities.domain.MaintenancePriority;
import com.app.campusagent.facilities.domain.MaintenanceStatus;
import com.app.campusagent.facilities.domain.MaintenanceTicket;
import com.app.campusagent.facilities.domain.Space;
import com.app.campusagent.facilities.repository.MaintenanceTicketRepository;
import com.app.campusagent.facilities.repository.SpaceRepository;
import com.app.campusagent.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
@Transactional
class AdminFacilitiesMaintenanceIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MaintenanceTicketRepository maintenanceTicketRepository;

    @Autowired
    private SpaceRepository spaceRepository;

    @Autowired
    private UserRepository userRepository;

    private User firstStudent;
    private User secondStudent;
    private User admin;
    private User superAdmin;
    private Space firstSpace;
    private Space secondSpace;
    private MaintenanceTicket firstTicket;
    private MaintenanceTicket secondTicket;
    private MaintenanceTicket noSpaceTicket;

    @BeforeEach
    void setUp() {
        firstStudent = saveUser("maintenance-first", Role.STUDENT);
        secondStudent = saveUser("maintenance-second", Role.STUDENT);
        admin = saveUser("maintenance-admin", Role.ADMIN);
        superAdmin = saveUser("maintenance-super", Role.SUPER_ADMIN);

        List<Space> spaces = spaceRepository.findAll().stream()
                .sorted(Comparator.comparing(Space::getId))
                .toList();
        firstSpace = spaces.get(0);
        secondSpace = spaces.get(1);

        firstTicket = saveTicket(new MaintenanceTicket(
                firstStudent.getId(), firstSpace, firstSpace.getBuilding(), firstSpace.getRoomNumber(),
                "projector", "Projector cannot turn on", MaintenancePriority.HIGH));
        secondTicket = saveTicket(new MaintenanceTicket(
                secondStudent.getId(), secondSpace, secondSpace.getBuilding(), secondSpace.getRoomNumber(),
                "aircon", "Air conditioning is too warm", MaintenancePriority.MEDIUM));
        secondTicket.updateStatus(MaintenanceStatus.IN_PROGRESS);
        secondTicket = maintenanceTicketRepository.saveAndFlush(secondTicket);
        noSpaceTicket = saveTicket(new MaintenanceTicket(
                secondStudent.getId(), null, "COM9", "99-01", "elevator",
                "Elevator button is broken", MaintenancePriority.LOW));


    }

    @Test
    void adminCanQueryMaintenanceAcrossUsersAndNullableSpaceFields() throws Exception {
        mockMvc.perform(get("/api/admin/facilities/maintenance/search")
                        .with(authentication(authFor(admin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(3))
                .andExpect(jsonPath("$.size").value(25))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content[0].ticketId").value(noSpaceTicket.getId()))
                .andExpect(jsonPath("$.content[0].userEmail").value(secondStudent.getEmail()))
                .andExpect(jsonPath("$.content[0].spaceId").value(nullValue()))
                .andExpect(jsonPath("$.content[0].spaceName").value(nullValue()))
                .andExpect(jsonPath("$.content[0].spaceType").value(nullValue()))
                .andExpect(jsonPath("$.content[0].floor").value(nullValue()))
                .andExpect(jsonPath("$.content[0].building").value("COM9"))
                .andExpect(jsonPath("$.content[0].roomNumber").value("99-01"))
                .andExpect(jsonPath("$.content[0].description").value("Elevator button is broken"));
    }

    @Test
    void superAdminCanAccessMaintenance() throws Exception {
        mockMvc.perform(get("/api/admin/facilities/maintenance/search")
                        .with(authentication(authFor(superAdmin))))
                .andExpect(status().isOk());
    }

    @Test
    void studentReceivesForbidden() throws Exception {
        mockMvc.perform(get("/api/admin/facilities/maintenance/search")
                        .with(authentication(authFor(firstStudent))))
                .andExpect(status().isForbidden());
    }

    @Test
    void anonymousUserReceivesUnauthorized() throws Exception {
        mockMvc.perform(get("/api/admin/facilities/maintenance/search"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void supportsMultipleStatusValues() throws Exception {
        mockMvc.perform(get("/api/admin/facilities/maintenance/search")
                        .param("status", "SUBMITTED")
                        .param("status", "IN_PROGRESS")
                        .with(authentication(authFor(admin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(3));
    }

    @Test
    void filtersByPriority() throws Exception {
        mockMvc.perform(get("/api/admin/facilities/maintenance/search")
                        .param("priority", "HIGH")
                        .with(authentication(authFor(admin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].ticketId").value(firstTicket.getId()));
    }

    @Test
    void filtersBySpaceAndUserId() throws Exception {
        mockMvc.perform(get("/api/admin/facilities/maintenance/search")
                        .param("spaceId", firstSpace.getId().toString())
                        .with(authentication(authFor(admin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].ticketId").value(firstTicket.getId()));

        mockMvc.perform(get("/api/admin/facilities/maintenance/search")
                        .param("userId", secondStudent.getId().toString())
                        .with(authentication(authFor(admin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2));
    }

    @Test
    void filtersByTrimmedUserEmail() throws Exception {
        mockMvc.perform(get("/api/admin/facilities/maintenance/search")
                        .param("userEmail", "  " + firstStudent.getEmail() + "  ")
                        .with(authentication(authFor(admin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].userEmail").value(firstStudent.getEmail()));
    }

    @Test
    void missingUserEmailReturnsEmptyPage() throws Exception {
        mockMvc.perform(get("/api/admin/facilities/maintenance/search")
                        .param("userEmail", "missing-maintenance@nus.edu.sg")
                        .with(authentication(authFor(admin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void buildingFilterIsCaseInsensitiveContainsMatch() throws Exception {
        mockMvc.perform(get("/api/admin/facilities/maintenance/search")
                        .param("building", "  com9  ")
                        .with(authentication(authFor(admin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].ticketId").value(noSpaceTicket.getId()));
    }

    @Test
    void filtersByCreatedDateRange() throws Exception {
        mockMvc.perform(get("/api/admin/facilities/maintenance/search")
                        .param("createdFrom", LocalDateTime.now().minusMinutes(1).toString())
                        .param("createdTo", LocalDateTime.now().plusMinutes(1).toString())
                        .with(authentication(authFor(admin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(3));
    }

    @Test
    void rejectsReversedCreatedDateRange() throws Exception {
        mockMvc.perform(get("/api/admin/facilities/maintenance/search")
                        .param("createdFrom", "2026-08-12T00:00:00")
                        .param("createdTo", "2026-08-11T00:00:00")
                        .with(authentication(authFor(admin))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void validatesPaginationAndSortWhitelist() throws Exception {
        mockMvc.perform(get("/api/admin/facilities/maintenance/search")
                        .param("page", "-1")
                        .with(authentication(authFor(admin))))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/admin/facilities/maintenance/search")
                        .param("size", "101")
                        .with(authentication(authFor(admin))))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/admin/facilities/maintenance/search")
                        .param("sort", "space.name,asc")
                        .with(authentication(authFor(admin))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void defaultsToCreatedAtDescendingAndSupportsPageSize() throws Exception {
        mockMvc.perform(get("/api/admin/facilities/maintenance/search")
                        .with(authentication(authFor(admin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].ticketId").value(noSpaceTicket.getId()));

        mockMvc.perform(get("/api/admin/facilities/maintenance/search")
                        .param("page", "0")
                        .param("size", "1")
                        .param("sort", "id,asc")
                        .with(authentication(authFor(admin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.content.length()").value(1));
    }

    @Test
    void adminCanReadAnotherUsersDetail() throws Exception {
        mockMvc.perform(get("/api/admin/facilities/maintenance/search/{ticketId}", secondTicket.getId())
                        .with(authentication(authFor(admin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticketId").value(secondTicket.getId()))
                .andExpect(jsonPath("$.userId").value(secondStudent.getId()))
                .andExpect(jsonPath("$.userEmail").value(secondStudent.getEmail()))
                .andExpect(jsonPath("$.spaceName").value(secondSpace.getName()))
                .andExpect(jsonPath("$.facilityType").value("aircon"))
                .andExpect(jsonPath("$.description").value("Air conditioning is too warm"))
                .andExpect(jsonPath("$.priority").value("MEDIUM"))
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.createdAt").isString())
                .andExpect(jsonPath("$.updatedAt").isString());
    }

    @Test
    void detailMissingTicketReturnsNotFound() throws Exception {
        mockMvc.perform(get("/api/admin/facilities/maintenance/search/{ticketId}", Long.MAX_VALUE)
                        .with(authentication(authFor(admin))))
                .andExpect(status().isNotFound());
    }

    @Test
    void listAndDetailDoNotModifyData() throws Exception {
        long before = maintenanceTicketRepository.count();

        mockMvc.perform(get("/api/admin/facilities/maintenance/search")
                        .with(authentication(authFor(admin))))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/admin/facilities/maintenance/search/{ticketId}", firstTicket.getId())
                        .with(authentication(authFor(admin))))
                .andExpect(status().isOk());

        assertThat(maintenanceTicketRepository.count()).isEqualTo(before);
    }

    @Test
    void ordinaryUserMaintenanceListStillReturnsOnlyCurrentUsersTickets() throws Exception {
        mockMvc.perform(get("/api/facilities/maintenance")
                        .with(authentication(authFor(firstStudent))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].ticketId").value(firstTicket.getId()));
    }

    @Test
    void existingAdminStatusUpdateStillWorks() throws Exception {
        mockMvc.perform(patch("/api/facilities/maintenance/{ticketId}/status", firstTicket.getId())
                        .with(authentication(authFor(admin)))
                        .contentType("application/json")
                        .content("{\"status\":\"IN_PROGRESS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));

        mockMvc.perform(get("/api/admin/facilities/maintenance/{ticketId}", firstTicket.getId())
                        .with(authentication(authFor(admin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
    }

    @Test
    void unknownUserEmailIsNullable() throws Exception {
        MaintenanceTicket orphaned = saveTicket(new MaintenanceTicket(
                Long.MAX_VALUE, firstSpace, firstSpace.getBuilding(), firstSpace.getRoomNumber(),
                "monitor", "Monitor has no signal", MaintenancePriority.LOW));

        mockMvc.perform(get("/api/admin/facilities/maintenance/search/{ticketId}", orphaned.getId())
                        .with(authentication(authFor(admin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userEmail").value(nullValue()));
    }

    private MaintenanceTicket saveTicket(MaintenanceTicket ticket) {
        return maintenanceTicketRepository.saveAndFlush(ticket);
    }

    private User saveUser(String prefix, Role role) {
        User user = new User(prefix + "-" + UUID.randomUUID() + "@test.edu", "encoded");
        user.setRole(role);
        return userRepository.save(user);
    }

    private UsernamePasswordAuthenticationToken authFor(User user) {
        return new UsernamePasswordAuthenticationToken(
                user,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name())));
    }
}
