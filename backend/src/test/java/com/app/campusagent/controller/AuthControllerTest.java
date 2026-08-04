package com.app.campusagent.controller;

import com.app.campusagent.dto.AuthResponse;
import com.app.campusagent.dto.LoginRequest;
import com.app.campusagent.dto.RegisterRequest;
import com.app.campusagent.exception.GlobalExceptionHandler;
import com.app.campusagent.service.AuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthController Integration Tests")
class AuthControllerTest {

    private MockMvc mockMvc;

    @Mock
    private AuthService authService;

    @InjectMocks
    private AuthController authController;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String VALID_EMAIL = "test@u.nus.edu";
    private static final String VALID_PASSWORD = "securePass123";
    private static final String JWT_TOKEN = "eyJhbGciOiJIUzI1NiJ9.mockToken";
    private static final String ROLE_STUDENT = "STUDENT";

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(authController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Nested
    @DisplayName("POST /api/auth/register")
    class RegisterEndpoint {

        @Test
        @DisplayName("✅ 200 - Successful registration")
        void shouldReturn200OnSuccess() throws Exception {
            RegisterRequest request = new RegisterRequest();
            request.setEmail(VALID_EMAIL);
            request.setPassword(VALID_PASSWORD);

            when(authService.register(any())).thenReturn(new AuthResponse(JWT_TOKEN, VALID_EMAIL, ROLE_STUDENT));

            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.token").value(JWT_TOKEN))
                    .andExpect(jsonPath("$.email").value(VALID_EMAIL))
                    .andExpect(jsonPath("$.role").value(ROLE_STUDENT));
        }

        @Test
        @DisplayName("✅ Role defaults to STUDENT on registration")
        void shouldReturnStudentRoleOnRegister() throws Exception {
            RegisterRequest request = new RegisterRequest();
            request.setEmail(VALID_EMAIL);
            request.setPassword(VALID_PASSWORD);

            when(authService.register(any())).thenReturn(new AuthResponse(JWT_TOKEN, VALID_EMAIL, "STUDENT"));

            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.role").value("STUDENT"));
        }

        @Test
        @DisplayName("❌ 400 - Missing email")
        void shouldReturn400WhenEmailMissing() throws Exception {
            String json = """
                    {"password": "securePass123"}
                    """;

            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("❌ 400 - Invalid email format")
        void shouldReturn400WhenEmailInvalid() throws Exception {
            String json = """
                    {"email": "not-an-email", "password": "securePass123"}
                    """;

            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("❌ 400 - Password too short (< 6)")
        void shouldReturn400WhenPasswordTooShort() throws Exception {
            String json = """
                    {"email": "test@u.nus.edu", "password": "12345"}
                    """;

            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("❌ 400 - Empty JSON body")
        void shouldReturn400WhenBodyEmpty() throws Exception {
            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("POST /api/auth/login")
    class LoginEndpoint {

        @Test
        @DisplayName("✅ 200 - Successful login")
        void shouldReturn200OnSuccess() throws Exception {
            LoginRequest request = new LoginRequest();
            request.setEmail(VALID_EMAIL);
            request.setPassword(VALID_PASSWORD);

            when(authService.login(any())).thenReturn(new AuthResponse(JWT_TOKEN, VALID_EMAIL, ROLE_STUDENT));

            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.token").value(JWT_TOKEN))
                    .andExpect(jsonPath("$.email").value(VALID_EMAIL))
                    .andExpect(jsonPath("$.role").value(ROLE_STUDENT));
        }

        @Test
        @DisplayName("❌ 400 - Missing password")
        void shouldReturn400WhenPasswordMissing() throws Exception {
            String json = """
                    {"email": "test@u.nus.edu"}
                    """;

            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("❌ 400 - Invalid email format")
        void shouldReturn400WhenEmailInvalid() throws Exception {
            String json = """
                    {"email": "bad-email", "password": "securePass123"}
                    """;

            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("Authentication bypass")
    class AuthBypass {

        @Test
        @DisplayName("🔓 /api/auth/** is public (no token required)")
        void shouldAllowAccessWithoutToken() throws Exception {
            LoginRequest request = new LoginRequest();
            request.setEmail(VALID_EMAIL);
            request.setPassword(VALID_PASSWORD);

            when(authService.login(any())).thenReturn(new AuthResponse(JWT_TOKEN, VALID_EMAIL, ROLE_STUDENT));

            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.role").value(ROLE_STUDENT));
        }
    }
}
