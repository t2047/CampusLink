package com.app.campusagent.controller;

import com.app.campusagent.domain.Role;
import com.app.campusagent.domain.User;
import com.app.campusagent.dto.UserProfileResponse;
import com.app.campusagent.exception.GlobalExceptionHandler;
import com.app.campusagent.lostfound.exception.LostFoundExceptionHandler;
import com.app.campusagent.lostfound.storage.ObjectStorageService;
import com.app.campusagent.service.UserProfileService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("UserProfileController")
class UserProfileControllerTest {

    private MockMvc mockMvc;
    private UserProfileService profileService;
    private ObjectStorageService storageService;

    @BeforeEach
    void setUp() {
        profileService = mock(UserProfileService.class);
        storageService = mock(ObjectStorageService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new UserProfileController(profileService, storageService))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler(), new LostFoundExceptionHandler())
                .build();

        User principal = new User("student@example.edu", "encoded");
        principal.setRole(Role.STUDENT);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        principal, null, List.of(new SimpleGrantedAuthority("ROLE_STUDENT"))));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void getProfileReturnsAuthenticatedUserProfile() throws Exception {
        when(profileService.getProfile(any(User.class))).thenReturn(
                new UserProfileResponse("student@example.edu", "STUDENT", "Alex", null));

        mockMvc.perform(get("/api/users/me/profile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("student@example.edu"))
                .andExpect(jsonPath("$.role").value("STUDENT"))
                .andExpect(jsonPath("$.nickname").value("Alex"));
    }

    @Test
    void updateNicknameParsesBodyAndReturnsProfile() throws Exception {
        when(profileService.updateNickname(any(User.class), any())).thenReturn(
                new UserProfileResponse("student@example.edu", "STUDENT", "Alex", null));

        mockMvc.perform(put("/api/users/me/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"Alex\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nickname").value("Alex"));
    }

    @Test
    void uploadAvatarAcceptsMultipartAndReturnsProfile() throws Exception {
        byte[] png = {0x01, 0x02};
        when(profileService.uploadAvatar(any(User.class), any())).thenReturn(
                new UserProfileResponse("student@example.edu", "STUDENT", "Alex",
                        "/api/users/avatar/avatar-new.png"));

        mockMvc.perform(multipart("/api/users/me/avatar")
                        .file(new MockMultipartFile("file", "avatar.png", "image/png", png)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.avatarUrl").value("/api/users/avatar/avatar-new.png"));
    }

    @Test
    void downloadAvatarServesStoredBytes() throws Exception {
        when(storageService.download("avatar-abc.png")).thenReturn(new byte[]{1, 2, 3});

        mockMvc.perform(get("/api/users/avatar/avatar-abc.png"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(content().bytes(new byte[]{1, 2, 3}));
    }

    @Test
    void downloadAvatarRejectsNonAvatarKeys() throws Exception {
        mockMvc.perform(get("/api/users/avatar/not-an-avatar"))
                .andExpect(status().isNotFound());
    }
}
