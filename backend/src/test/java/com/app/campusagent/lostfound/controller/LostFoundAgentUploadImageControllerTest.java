package com.app.campusagent.lostfound.controller;

import com.app.campusagent.domain.User;
import com.app.campusagent.lostfound.dto.StagedImageResponse;
import com.app.campusagent.lostfound.service.LostFoundImageStagingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class LostFoundAgentUploadImageControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LostFoundImageStagingService stagingService;

    @Test
    void stagesValidImageForAuthenticatedUser() throws Exception {
        MockMultipartFile image = new MockMultipartFile(
                "image", "item.png", MediaType.IMAGE_PNG_VALUE, new byte[]{1, 2, 3, 4});
        when(stagingService.upload(any(MultipartFile.class), any(User.class)))
                .thenReturn(new StagedImageResponse(
                        "lost-found-staging/k.png",
                        "VF1:fp",
                        "/api/lost-found/images/staging/k.png",
                        "image/png",
                        "item.png",
                        image.getSize()));

        mockMvc.perform(multipart("/api/lost-found/agent/upload-image")
                        .file(image)
                        .with(authentication(authFor(new User("student@example.com", "unused")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.objectKey").value("lost-found-staging/k.png"))
                .andExpect(jsonPath("$.visualFingerprint").value("VF1:fp"))
                .andExpect(jsonPath("$.url").value("/api/lost-found/images/staging/k.png"))
                .andExpect(jsonPath("$.contentType").value("image/png"));
    }

    @Test
    void rejectsStagingWithoutAuthentication() throws Exception {
        mockMvc.perform(multipart("/api/lost-found/agent/upload-image")
                        .file(new MockMultipartFile(
                                "image", "item.png", MediaType.IMAGE_PNG_VALUE, new byte[]{1, 2, 3, 4})))
                .andExpect(status().isUnauthorized());
    }

    private UsernamePasswordAuthenticationToken authFor(User user) {
        return new UsernamePasswordAuthenticationToken(
                user,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name())));
    }
}
