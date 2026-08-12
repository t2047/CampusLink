package com.app.campusagent.lostfound.controller;

import com.app.campusagent.config.JwtTokenProvider;
import com.app.campusagent.domain.User;
import com.app.campusagent.lostfound.domain.LostFoundNotification;
import com.app.campusagent.lostfound.domain.NotificationType;
import com.app.campusagent.lostfound.repository.LostFoundNotificationRepository;
import com.app.campusagent.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 通知接口安全契约：匿名拒绝，且用户只能查询 / 标记自己的通知。
 */
@SpringBootTest
@AutoConfigureMockMvc
class LostFoundNotificationSecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private LostFoundNotificationRepository notificationRepository;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private User userA;
    private User userB;
    private LostFoundNotification userBNotification;

    @BeforeEach
    void setUp() {
        userA = user("alice@campuslink.test");
        userB = user("bob@campuslink.test");
        userBNotification = notificationRepository.save(new LostFoundNotification(
                userB,
                NotificationType.CLAIM_APPROVED,
                null,
                null,
                "Claim approved",
                "Your claim was approved."));
    }

    @AfterEach
    void cleanUp() {
        notificationRepository.deleteById(userBNotification.getId());
        userRepository.deleteAll(List.of(userA, userB));
    }

    @Test
    void rejectsUnauthenticatedNotificationRequests() throws Exception {
        mockMvc.perform(get("/api/lost-found/notifications"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/lost-found/notifications/unread-count"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/lost-found/notifications/1/read"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void userCannotSeeOthersNotifications() throws Exception {
        mockMvc.perform(get("/api/lost-found/notifications")
                        .header("Authorization", bearer(userA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    @Test
    void userCannotMarkOthersNotificationAsRead() throws Exception {
        mockMvc.perform(post("/api/lost-found/notifications/{id}/read", userBNotification.getId())
                        .header("Authorization", bearer(userA)))
                .andExpect(status().isNotFound());
    }

    @Test
    void userSeesOwnNotificationAndUnreadCount() throws Exception {
        mockMvc.perform(get("/api/lost-found/notifications")
                        .header("Authorization", bearer(userB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(userBNotification.getId()))
                .andExpect(jsonPath("$.content[0].read").value(false));

        mockMvc.perform(get("/api/lost-found/notifications/unread-count")
                        .header("Authorization", bearer(userB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unread").value(1));
    }

    private User user(String email) {
        return userRepository.findByEmail(email)
                .orElseGet(() -> userRepository.save(new User(email, "encoded")));
    }

    private String bearer(User user) {
        return "Bearer " + jwtTokenProvider.generateToken(user.getEmail(), user.getRole().name());
    }
}
