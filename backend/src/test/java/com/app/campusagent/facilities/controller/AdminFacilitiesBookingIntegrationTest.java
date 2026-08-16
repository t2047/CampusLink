package com.app.campusagent.facilities.controller;

import com.app.campusagent.domain.Role;
import com.app.campusagent.domain.User;
import com.app.campusagent.facilities.domain.Booking;
import com.app.campusagent.facilities.domain.Space;
import com.app.campusagent.facilities.repository.BookingRepository;
import com.app.campusagent.facilities.repository.SpaceRepository;
import com.app.campusagent.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminFacilitiesBookingIntegrationTest {

    private static final LocalDateTime BASE_TIME = LocalDateTime.of(2026, 8, 16, 10, 0);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private BookingRepository bookingRepository;

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
    private Booking earlierBooking;
    private Booking laterBooking;

    @BeforeEach
    void setUp() {
        firstStudent = saveUser("first", Role.STUDENT);
        secondStudent = saveUser("second", Role.STUDENT);
        admin = saveUser("admin", Role.ADMIN);
        superAdmin = saveUser("super-admin", Role.SUPER_ADMIN);

        List<Space> spaces = spaceRepository.findAll().stream()
                .sorted(Comparator.comparing(Space::getId))
                .toList();
        firstSpace = spaces.get(0);
        secondSpace = spaces.get(1);

        earlierBooking = bookingRepository.saveAndFlush(
                new Booking(secondStudent.getId(), secondSpace, BASE_TIME, BASE_TIME.plusHours(1)));
        laterBooking = bookingRepository.saveAndFlush(
                new Booking(firstStudent.getId(), firstSpace, BASE_TIME.plusHours(2), BASE_TIME.plusHours(3)));
        laterBooking.cancel();
        bookingRepository.saveAndFlush(laterBooking);
    }

    @Test
    void adminCanQueryBookingsAcrossUsersInDefaultAscendingOrder() throws Exception {
        mockMvc.perform(get("/api/admin/facilities/bookings/search")
                        .with(authentication(authFor(admin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].bookingId").value(earlierBooking.getId()))
                .andExpect(jsonPath("$.content[0].userId").value(secondStudent.getId()))
                .andExpect(jsonPath("$.content[0].userEmail").value(secondStudent.getEmail()))
                .andExpect(jsonPath("$.content[0].spaceId").value(secondSpace.getId()))
                .andExpect(jsonPath("$.content[0].spaceName").value(secondSpace.getName()))
                .andExpect(jsonPath("$.content[0].building").value(secondSpace.getBuilding()))
                .andExpect(jsonPath("$.content[0].floor").value(secondSpace.getFloor()))
                .andExpect(jsonPath("$.content[0].roomNumber").value(secondSpace.getRoomNumber()))
                .andExpect(jsonPath("$.content[0].spaceType").value(secondSpace.getSpaceType().name()))
                .andExpect(jsonPath("$.content[0].status").value("CONFIRMED"))
                .andExpect(jsonPath("$.content[0].createdAt").isString())
                .andExpect(jsonPath("$.content[0].updatedAt").isString())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(25))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.first").value(true))
                .andExpect(jsonPath("$.last").value(true));
    }

    @Test
    void superAdminCanAccessBookings() throws Exception {
        mockMvc.perform(get("/api/admin/facilities/bookings/search")
                        .with(authentication(authFor(superAdmin))))
                .andExpect(status().isOk());
    }

    @Test
    void studentReceivesForbidden() throws Exception {
        mockMvc.perform(get("/api/admin/facilities/bookings/search")
                        .with(authentication(authFor(firstStudent))))
                .andExpect(status().isForbidden());
    }

    @Test
    void anonymousUserReceivesUnauthorized() throws Exception {
        mockMvc.perform(get("/api/admin/facilities/bookings/search"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void filtersByStatus() throws Exception {
        mockMvc.perform(get("/api/admin/facilities/bookings/search")
                        .param("status", "CANCELLED")
                        .with(authentication(authFor(admin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].bookingId").value(laterBooking.getId()));
    }

    @Test
    void filtersBySpaceId() throws Exception {
        mockMvc.perform(get("/api/admin/facilities/bookings/search")
                        .param("spaceId", firstSpace.getId().toString())
                        .with(authentication(authFor(admin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].bookingId").value(laterBooking.getId()));
    }

    @Test
    void filtersByUserId() throws Exception {
        mockMvc.perform(get("/api/admin/facilities/bookings/search")
                        .param("userId", secondStudent.getId().toString())
                        .with(authentication(authFor(admin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].bookingId").value(earlierBooking.getId()));
    }

    @Test
    void filtersByTrimmedExactUserEmail() throws Exception {
        mockMvc.perform(get("/api/admin/facilities/bookings/search")
                        .param("userEmail", "  " + firstStudent.getEmail() + "  ")
                        .with(authentication(authFor(admin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].bookingId").value(laterBooking.getId()));
    }

    @Test
    void missingUserEmailReturnsEmptyPage() throws Exception {
        mockMvc.perform(get("/api/admin/facilities/bookings/search")
                        .param("userEmail", "missing@nus.edu.sg")
                        .with(authentication(authFor(admin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(25));
    }

    @Test
    void filtersByInclusiveStartDateRange() throws Exception {
        mockMvc.perform(get("/api/admin/facilities/bookings/search")
                        .param("startFrom", BASE_TIME.plusHours(1).toString())
                        .param("startTo", BASE_TIME.plusHours(2).toString())
                        .with(authentication(authFor(admin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].bookingId").value(laterBooking.getId()));
    }

    @Test
    void acceptsIsoDateTimeWithUtcOffsetFromTheFrontend() throws Exception {
        mockMvc.perform(get("/api/admin/facilities/bookings/search")
                        .param("startFrom", "2026-08-15T00:00:00.000Z")
                        .with(authentication(authFor(admin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2));
    }

    @Test
    void rejectsReversedStartDateRange() throws Exception {
        mockMvc.perform(get("/api/admin/facilities/bookings/search")
                        .param("startFrom", BASE_TIME.plusDays(1).toString())
                        .param("startTo", BASE_TIME.toString())
                        .with(authentication(authFor(admin))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void validatesPageAndSize() throws Exception {
        mockMvc.perform(get("/api/admin/facilities/bookings/search")
                        .param("page", "-1")
                        .with(authentication(authFor(admin))))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/admin/facilities/bookings/search")
                        .param("size", "101")
                        .with(authentication(authFor(admin))))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/admin/facilities/bookings/search")
                        .param("page", "1")
                        .param("size", "1")
                        .with(authentication(authFor(admin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.content.length()").value(1));
    }

    @Test
    void enforcesSortWhitelistAndAcceptsSafeSort() throws Exception {
        mockMvc.perform(get("/api/admin/facilities/bookings/search")
                        .param("sort", "space.name,asc")
                        .with(authentication(authFor(admin))))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/admin/facilities/bookings/search")
                        .param("sort", "id,desc")
                        .with(authentication(authFor(admin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].bookingId").value(laterBooking.getId()));
    }

    @Test
    void returnsNullEmailWhenBookingUserNoLongerExists() throws Exception {
        Booking orphanedBooking = bookingRepository.saveAndFlush(
                new Booking(Long.MAX_VALUE, firstSpace, BASE_TIME.plusHours(4), BASE_TIME.plusHours(5)));

        mockMvc.perform(get("/api/admin/facilities/bookings/search")
                        .param("userId", Long.toString(Long.MAX_VALUE))
                        .with(authentication(authFor(admin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].bookingId").value(orphanedBooking.getId()))
                .andExpect(jsonPath("$.content[0].userEmail").value(nullValue()));
    }

    @Test
    void endpointDoesNotModifyBookings() throws Exception {
        long before = bookingRepository.count();

        mockMvc.perform(get("/api/admin/facilities/bookings/search")
                        .with(authentication(authFor(admin))))
                .andExpect(status().isOk());

        assertThat(bookingRepository.count()).isEqualTo(before);
    }

    @Test
    void ordinaryStudentBookingEndpointStillReturnsOnlyCurrentUsersBookings() throws Exception {
        mockMvc.perform(get("/api/facilities/bookings")
                        .with(authentication(authFor(firstStudent))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].bookingId").value(laterBooking.getId()));
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
