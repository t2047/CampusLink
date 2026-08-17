package com.app.campusagent.controller;

import com.app.campusagent.config.JwtTokenProvider;
import com.app.campusagent.domain.User;
import com.app.campusagent.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class UserProfileSecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private User createdUser;

    @AfterEach
    void cleanUp() {
        if (createdUser != null) {
            userRepository.delete(createdUser);
            createdUser = null;
        }
    }

    @Test
    void rejectsUnauthenticatedProfileRequests() throws Exception {
        mockMvc.perform(get("/api/users/me/profile"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(put("/api/users/me/profile")
                        .contentType(APPLICATION_JSON)
                        .content("{\"nickname\":\"Alex\"}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(put("/api/users/me/password")
                        .contentType(APPLICATION_JSON)
                        .content("{\"currentPassword\":\"old-pass\",\"newPassword\":\"new-pass-123\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void avatarProxyIsPublicButRejectsNonAvatarKeys() throws Exception {
        // 公开回显端点：无需登录即可访问；非 avatar- 前缀的键被控制器守卫拒绝（404 而非 401），
        // 也不触发 MinIO 读取。
        mockMvc.perform(get("/api/users/avatar/not-an-avatar"))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsTokenIssuedBeforePasswordChange() throws Exception {
        // ChangePassWord.md：改密后旧 JWT 最长还能用满有效期，此处验证改密前签发的 token 立即失效。
        createdUser = userRepository.save(new User("pwd-change@campuslink.test", "encoded"));

        // 改密前签发的 token：iat 早于 passwordChangedAt → 拒绝
        String oldToken = jwtTokenProvider.generateToken(createdUser.getEmail(), createdUser.getRole().name());

        // JWT 的 iat 是秒级精度，且过滤器把改密时间对齐到秒比较。
        // 等 1.1s 确保 oldToken 落在改密时间所在秒之前，避免落入同秒的 ≤1s 残留窗口。
        Thread.sleep(1100);
        createdUser.setPasswordChangedAt(LocalDateTime.now());
        createdUser = userRepository.save(createdUser);

        mockMvc.perform(get("/api/users/me/profile")
                        .header("Authorization", "Bearer " + oldToken))
                .andExpect(status().isUnauthorized());

        // 改密后签发的新 token：iat 与改密时间同秒或更晚 → 放行
        String newToken = jwtTokenProvider.generateToken(createdUser.getEmail(), createdUser.getRole().name());

        mockMvc.perform(get("/api/users/me/profile")
                        .header("Authorization", "Bearer " + newToken))
                .andExpect(status().isOk());
    }
}
