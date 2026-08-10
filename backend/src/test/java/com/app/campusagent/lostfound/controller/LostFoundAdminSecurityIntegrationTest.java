package com.app.campusagent.lostfound.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class LostFoundAdminSecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void rejectsAnonymousUsers() throws Exception {
        mockMvc.perform(get("/api/admin/lost-found/overview"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "STUDENT")
    void rejectsStudents() throws Exception {
        mockMvc.perform(get("/api/admin/lost-found/overview"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void allowsAdministratorsToViewOverview() throws Exception {
        mockMvc.perform(get("/api/admin/lost-found/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalReports").isNumber())
                .andExpect(jsonPath("$.submittedClaims").isNumber());
    }

    @Test
    @WithMockUser(roles = "SUPER_ADMIN")
    void allowsSuperAdministratorsToViewReports() throws Exception {
        mockMvc.perform(get("/api/admin/lost-found/reports")
                        .param("page", "0")
                        .param("size", "25"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }
}
