package com.app.campusagent.lostfound.controller;

import com.app.campusagent.lostfound.domain.LostFoundImage;
import com.app.campusagent.lostfound.repository.LostFoundImageRepository;
import com.app.campusagent.lostfound.storage.ObjectStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class LostFoundImageControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LostFoundImageRepository imageRepository;

    @MockitoBean
    private ObjectStorageService storageService;

    @Test
    void servesImageBytesWithoutAuthentication() throws Exception {
        LostFoundImage image = new LostFoundImage(
                "lost-found/key.png", "item.png", "image/png", 1024L, 0, null);
        when(imageRepository.findById(1L)).thenReturn(Optional.of(image));
        when(storageService.download("lost-found/key.png"))
                .thenReturn(new byte[]{1, 2, 3, 4});

        // 不带 authentication：验证 /api/lost-found/images/** 走 permitAll（<img> 无法携带 JWT）
        mockMvc.perform(get("/api/lost-found/images/1"))
                .andExpect(status().isOk())
                .andExpect(content().bytes(new byte[]{1, 2, 3, 4}))
                .andExpect(header().string("Content-Type", "image/png"));

        verify(storageService).download("lost-found/key.png");
    }

    @Test
    void returns404WhenImageNotFound() throws Exception {
        when(imageRepository.findById(404L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/lost-found/images/404"))
                .andExpect(status().isNotFound());
    }
}
