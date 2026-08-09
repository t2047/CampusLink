package com.app.campusagent.facilities.mcp;

import com.app.campusagent.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.mock;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
class McpEndpointIntegrationTest {

    private static final String INITIALIZE = """
            {
              "jsonrpc":"2.0",
              "id":1,
              "method":"initialize",
              "params":{
                "protocolVersion":"2025-11-25",
                "capabilities":{},
                "clientInfo":{"name":"facilities-test","version":"1.0.0"}
              }
            }
            """;

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;
    private UsernamePasswordAuthenticationToken authentication;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        User user = mock(User.class);
        authentication = new UsernamePasswordAuthenticationToken(user, null, List.of());
    }

    @Test
    void mcpEndpointRequiresCampusLinkAuthentication() throws Exception {
        mockMvc.perform(mcpPost(INITIALIZE))
                .andExpect(status().isForbidden());
    }

    @Test
    void authenticatedClientCanInitializeDiscoverAndCallTools() throws Exception {
        MvcResult initialized = mockMvc.perform(mcpPost(INITIALIZE).with(authentication(authentication)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("campuslink-facilities-test")))
                .andReturn();

        String sessionId = initialized.getResponse().getHeader("Mcp-Session-Id");

        mockMvc.perform(mcpPost("""
                        {"jsonrpc":"2.0","method":"notifications/initialized"}
                        """).with(authentication(authentication)).header("Mcp-Session-Id", sessionId))
                .andExpect(status().is2xxSuccessful());

        MvcResult listed = mockMvc.perform(mcpPost("""
                        {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
                        """).with(authentication(authentication)).header("Mcp-Session-Id", sessionId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("search_spaces")))
                .andExpect(content().string(containsString("cancel_booking")))
                .andExpect(content().string(containsString("submit_maintenance_request")))
                .andReturn();

        String toolsBody = listed.getResponse().getContentAsString();
        assertThat(org.springframework.util.StringUtils.countOccurrencesOf(toolsBody, "inputSchema"))
                .isEqualTo(10);
        assertThat(toolsBody).doesNotContain("update_maintenance_status");

        mockMvc.perform(mcpPost("""
                        {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"list_user_bookings","arguments":{}}}
                        """).with(authentication(authentication)).header("Mcp-Session-Id", sessionId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("success")));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder mcpPost(String body) {
        return post("/mcp")
                .header("Host", "localhost")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .content(body);
    }
}
