package com.app.campusagent.lostfound.controller;

import com.app.campusagent.domain.User;
import com.app.campusagent.lostfound.dto.agent.AgentClassifyResponse;
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
class LostFoundAgentClassifyControllerTest {

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
    void returnsCategorySuggestion() throws Exception {
        when(agentGateway.classify(ArgumentMatchers.any(), ArgumentMatchers.any(User.class)))
                .thenReturn(new AgentClassifyResponse("ELECTRONICS"));

        mockMvc.perform(post("/api/lost-found/agent/classify")
                        .contentType(APPLICATION_JSON)
                        .content("{\"itemName\":\"黑色耳机\"}")
                        .with(authentication(authFor(new User("student@example.com", "unused")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.category").value("ELECTRONICS"));
    }

    @Test
    void returnsNullCategoryWhenAgentIsUnsure() throws Exception {
        when(agentGateway.classify(ArgumentMatchers.any(), ArgumentMatchers.any(User.class)))
                .thenReturn(new AgentClassifyResponse(null));

        mockMvc.perform(post("/api/lost-found/agent/classify")
                        .contentType(APPLICATION_JSON)
                        .content("{\"itemName\":\"mystery gadget\"}")
                        .with(authentication(authFor(new User("student@example.com", "unused")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.category").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void rejectsBlankItemNameWithoutCallingGateway() throws Exception {
        mockMvc.perform(post("/api/lost-found/agent/classify")
                        .contentType(APPLICATION_JSON)
                        .content("{\"itemName\":\"   \"}")
                        .with(authentication(authFor(new User("student@example.com", "unused")))))
                .andExpect(status().isUnprocessableEntity());

        verify(agentGateway, never())
                .classify(ArgumentMatchers.any(), ArgumentMatchers.any(User.class));
    }
}
