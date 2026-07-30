package com.app.campusagent.service;

import com.app.campusagent.config.JwtTokenProvider;
import com.app.campusagent.domain.User;
import com.app.campusagent.dto.AuthResponse;
import com.app.campusagent.dto.LoginRequest;
import com.app.campusagent.dto.RegisterRequest;
import com.app.campusagent.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthService Unit Tests")
class AuthServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private JwtTokenProvider jwtTokenProvider;

    @InjectMocks
    private AuthService authService;

    private static final String EMAIL = "test@u.nus.edu";
    private static final String RAW_PASSWORD = "securePass123";
    private static final String ENCODED_PASSWORD = "$2a$10$encodedPasswordHash";
    private static final String JWT_TOKEN = "eyJhbGciOiJIUzI1NiJ9.validJwtToken";

    // ─────────────────── REGISTER ───────────────────

    @Nested
    @DisplayName("Registration")
    class RegisterTests {

        @Test
        @DisplayName("✅ Should register a new user successfully")
        void shouldRegisterSuccessfully() {
            RegisterRequest request = new RegisterRequest();
            request.setEmail(EMAIL);
            request.setPassword(RAW_PASSWORD);

            when(userRepository.existsByEmail(EMAIL)).thenReturn(false);
            when(passwordEncoder.encode(RAW_PASSWORD)).thenReturn(ENCODED_PASSWORD);
            when(jwtTokenProvider.generateToken(EMAIL)).thenReturn(JWT_TOKEN);

            AuthResponse response = authService.register(request);

            assertThat(response.email()).isEqualTo(EMAIL);
            assertThat(response.token()).isEqualTo(JWT_TOKEN);

            verify(userRepository).save(any(User.class));
            verify(passwordEncoder).encode(RAW_PASSWORD);
        }

        @Test
        @DisplayName("❌ Should throw when email already registered")
        void shouldThrowWhenEmailExists() {
            RegisterRequest request = new RegisterRequest();
            request.setEmail(EMAIL);
            request.setPassword(RAW_PASSWORD);

            when(userRepository.existsByEmail(EMAIL)).thenReturn(true);

            assertThatThrownBy(() -> authService.register(request))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Email already registered");

            verify(userRepository, never()).save(any());
            verify(passwordEncoder, never()).encode(any());
        }

        @Test
        @DisplayName("🔐 Should store encoded password, not raw password")
        void shouldStoreEncodedPassword() {
            RegisterRequest request = new RegisterRequest();
            request.setEmail(EMAIL);
            request.setPassword(RAW_PASSWORD);

            when(userRepository.existsByEmail(EMAIL)).thenReturn(false);
            when(passwordEncoder.encode(RAW_PASSWORD)).thenReturn(ENCODED_PASSWORD);
            when(jwtTokenProvider.generateToken(EMAIL)).thenReturn(JWT_TOKEN);

            authService.register(request);

            verify(userRepository).save(argThat(user ->
                    user.getEmail().equals(EMAIL) &&
                    !user.getPassword().equals(RAW_PASSWORD) &&
                    user.getPassword().equals(ENCODED_PASSWORD)
            ));
        }
    }

    // ─────────────────── LOGIN ───────────────────

    @Nested
    @DisplayName("Login")
    class LoginTests {

        private User existingUser;

        @BeforeEach
        void setUp() {
            existingUser = new User(EMAIL, ENCODED_PASSWORD);
        }

        @Test
        @DisplayName("✅ Should login successfully with correct credentials")
        void shouldLoginSuccessfully() {
            LoginRequest request = new LoginRequest();
            request.setEmail(EMAIL);
            request.setPassword(RAW_PASSWORD);

            when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(existingUser));
            when(passwordEncoder.matches(RAW_PASSWORD, ENCODED_PASSWORD)).thenReturn(true);
            when(jwtTokenProvider.generateToken(EMAIL)).thenReturn(JWT_TOKEN);

            AuthResponse response = authService.login(request);

            assertThat(response.email()).isEqualTo(EMAIL);
            assertThat(response.token()).isEqualTo(JWT_TOKEN);
        }

        @Test
        @DisplayName("❌ Should throw when email not found")
        void shouldThrowWhenEmailNotFound() {
            LoginRequest request = new LoginRequest();
            request.setEmail("ghost@u.nus.edu");
            request.setPassword(RAW_PASSWORD);

            when(userRepository.findByEmail("ghost@u.nus.edu")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> authService.login(request))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Invalid email or password");

            verify(passwordEncoder, never()).matches(any(), any());
            verify(jwtTokenProvider, never()).generateToken(any());
        }

        @Test
        @DisplayName("❌ Should throw when password is wrong")
        void shouldThrowWhenPasswordWrong() {
            LoginRequest request = new LoginRequest();
            request.setEmail(EMAIL);
            request.setPassword("wrongPassword");

            when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(existingUser));
            when(passwordEncoder.matches("wrongPassword", ENCODED_PASSWORD)).thenReturn(false);

            assertThatThrownBy(() -> authService.login(request))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Invalid email or password");

            verify(jwtTokenProvider, never()).generateToken(any());
        }

        @Test
        @DisplayName("🔒 Should not leak which credential is wrong (OWASP)")
        void shouldNotLeakWhichCredentialIsWrong() {
            // Wrong email
            LoginRequest wrongEmail = new LoginRequest();
            wrongEmail.setEmail("wrong@u.nus.edu");
            wrongEmail.setPassword(RAW_PASSWORD);
            when(userRepository.findByEmail("wrong@u.nus.edu")).thenReturn(Optional.empty());

            Throwable e1 = catchThrowable(() -> authService.login(wrongEmail));

            // Wrong password
            LoginRequest wrongPwd = new LoginRequest();
            wrongPwd.setEmail(EMAIL);
            wrongPwd.setPassword("wrong");
            when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(existingUser));
            when(passwordEncoder.matches("wrong", ENCODED_PASSWORD)).thenReturn(false);

            Throwable e2 = catchThrowable(() -> authService.login(wrongPwd));

            assertThat(e1.getMessage()).isEqualTo(e2.getMessage());
            assertThat(e1.getMessage()).contains("Invalid email or password");
        }
    }

    // ─────────────────── JWT ───────────────────

    @Nested
    @DisplayName("JWT Token Generation")
    class JwtTokenTests {

        @Test
        @DisplayName("🔑 Should return unique tokens for different users")
        void shouldReturnUniqueTokens() {
            RegisterRequest reqA = new RegisterRequest();
            reqA.setEmail("a@u.nus.edu");
            reqA.setPassword("pass");

            when(userRepository.existsByEmail("a@u.nus.edu")).thenReturn(false);
            when(passwordEncoder.encode("pass")).thenReturn(ENCODED_PASSWORD);
            when(jwtTokenProvider.generateToken("a@u.nus.edu")).thenReturn("token-a");

            AuthResponse resA = authService.register(reqA);

            RegisterRequest reqB = new RegisterRequest();
            reqB.setEmail("b@u.nus.edu");
            reqB.setPassword("pass");

            when(userRepository.existsByEmail("b@u.nus.edu")).thenReturn(false);
            when(passwordEncoder.encode("pass")).thenReturn(ENCODED_PASSWORD);
            when(jwtTokenProvider.generateToken("b@u.nus.edu")).thenReturn("token-b");

            AuthResponse resB = authService.register(reqB);

            assertThat(resA.token()).isNotEqualTo(resB.token());
            assertThat(resA.email()).isNotEqualTo(resB.email());
        }
    }
}
