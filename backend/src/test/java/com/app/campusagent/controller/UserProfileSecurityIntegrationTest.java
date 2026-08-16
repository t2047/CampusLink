package com.app.campusagent.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class UserProfileSecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void rejectsUnauthenticatedProfileRequests() throws Exception {
        mockMvc.perform(get("/api/users/me/profile"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(put("/api/users/me/profile")
                        .contentType(APPLICATION_JSON)
                        .content("{\"nickname\":\"Alex\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void avatarProxyIsPublicButRejectsNonAvatarKeys() throws Exception {
        // 公开回显端点：无需登录即可访问；非 avatar- 前缀的键被控制器守卫拒绝（404 而非 401），
        // 也不触发 MinIO 读取。
        mockMvc.perform(get("/api/users/avatar/not-an-avatar"))
                .andExpect(status().isNotFound());
    }
}
