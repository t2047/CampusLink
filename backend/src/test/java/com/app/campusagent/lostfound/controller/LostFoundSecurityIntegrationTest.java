package com.app.campusagent.lostfound.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class LostFoundSecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void rejectsUnauthenticatedLostFoundRequests() throws Exception {
        mockMvc.perform(get("/api/lost-found/metadata"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/lost-found/agent/invoke")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "message": "帮我找蓝色雨伞",
                                  "conversationContext": {
                                    "sessionId": "anonymous-session",
                                    "sharedData": {}
                                  },
                                  "confirmed": false
                                }
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsUnauthenticatedClassifyRequests() throws Exception {
        mockMvc.perform(post("/api/lost-found/agent/classify")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "itemName": "黑色耳机"
                                }
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser
    void allowsAuthenticatedLostFoundRequests() throws Exception {
        mockMvc.perform(get("/api/lost-found/metadata"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories").isArray())
                .andExpect(jsonPath("$.reportTypes[0]").value("LOST"));
    }
}
