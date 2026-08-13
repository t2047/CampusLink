package com.app.campusagent.lostfound.controller;

import com.app.campusagent.domain.User;
import com.app.campusagent.lostfound.service.LostFoundAgentGateway;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class LostFoundAgentSearchControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LostFoundAgentGateway agentGateway;

    private UsernamePasswordAuthenticationToken authFor(User user) {
        return new UsernamePasswordAuthenticationToken(
                user,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name())));
    }

    @Test
    void returnsSearchResults() throws Exception {
        when(agentGateway.search(ArgumentMatchers.any(), ArgumentMatchers.any(User.class)))
                .thenReturn(Map.of(
                        "status", "match_found",
                        "request_id", "trace-search",
                        "match_results", List.of()));

        mockMvc.perform(post("/api/lost-found/agent/search")
                        .contentType(APPLICATION_JSON)
                        .content("{\"reportType\":\"FOUND\","
                                + "\"keyword\":\"耳机\","
                                + "\"images\":[{\"objectKey\":\"lost-found-staging/k.png\","
                                + "\"visualFingerprint\":\"VF1:fp\","
                                + "\"url\":\"/api/lost-found/images/staging/k.png\"}]}")
                        .with(authentication(authFor(new User("student@example.com", "unused")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("match_found"));
    }

    @Test
    void rejectsInvalidRequestWithoutCallingGateway() throws Exception {
        mockMvc.perform(post("/api/lost-found/agent/search")
                        .contentType(APPLICATION_JSON)
                        .content("{\"images\":[{\"objectKey\":\"lost-found-staging/k.png\"}]}")
                        .with(authentication(authFor(new User("student@example.com", "unused")))))
                .andExpect(status().isUnprocessableEntity());

        verify(agentGateway, never())
                .search(ArgumentMatchers.any(), ArgumentMatchers.any(User.class));
    }
}
