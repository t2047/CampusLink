package com.app.campusagent.service;

import com.app.campusagent.domain.Role;
import com.app.campusagent.domain.User;
import com.app.campusagent.dto.UpdateProfileRequest;
import com.app.campusagent.dto.UserProfileResponse;
import com.app.campusagent.exception.BusinessException;
import com.app.campusagent.lostfound.storage.ObjectStorageService;
import com.app.campusagent.lostfound.storage.StoredObject;
import com.app.campusagent.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserProfileServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private ObjectStorageService storageService;

    @InjectMocks
    private UserProfileService profileService;

    private User user() {
        User user = new User("student@example.edu", "encoded");
        user.setRole(Role.STUDENT);
        return user;
    }

    @Test
    void updateNicknameTrimsAndPersists() {
        User user = user();
        when(userRepository.save(user)).thenReturn(user);

        UserProfileResponse result = profileService.updateNickname(user, new UpdateProfileRequest("  Alex  "));

        assertThat(result.nickname()).isEqualTo("Alex");
        assertThat(user.getNickname()).isEqualTo("Alex");
        verify(userRepository).save(user);
    }

    @Test
    void updateNicknameRejectsMissingOrBlank() {
        User user = user();
        assertThatThrownBy(() -> profileService.updateNickname(user, null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode.code").isEqualTo("NICKNAME_REQUIRED");
        assertThatThrownBy(() -> profileService.updateNickname(user, new UpdateProfileRequest(null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode.code").isEqualTo("NICKNAME_REQUIRED");
        assertThatThrownBy(() -> profileService.updateNickname(user, new UpdateProfileRequest("   ")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode.code").isEqualTo("NICKNAME_REQUIRED");
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void updateNicknameRejectsTooLong() {
        User user = user();
        assertThatThrownBy(() -> profileService.updateNickname(user, new UpdateProfileRequest("x".repeat(31))))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode.code").isEqualTo("NICKNAME_INVALID_LENGTH");
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void uploadAvatarStoresKeyDerivesUrlAndDeletesPrevious() {
        User user = user();
        user.setAvatarUrl("avatar-old.jpg");
        byte[] png = Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==");
        MultipartFile file = new MockMultipartFile("file", "avatar.png", "image/png", png);
        when(storageService.upload(any(MultipartFile.class), anyString())).thenReturn(
                new StoredObject("avatar-new.png", "avatar.png", "image/png", png.length));
        when(userRepository.save(user)).thenReturn(user);

        UserProfileResponse result = profileService.uploadAvatar(user, file);

        assertThat(result.avatarUrl()).isEqualTo("/api/users/avatar/avatar-new.png");
        assertThat(user.getAvatarUrl()).isEqualTo("avatar-new.png");
        verify(storageService).delete("avatar-old.jpg");
    }

    @Test
    void getProfileDerivesAvatarUrlFromObjectKey() {
        User user = user();
        user.setNickname("Alex");
        user.setAvatarUrl("avatar-abc.jpg");

        UserProfileResponse result = profileService.getProfile(user);

        assertThat(result.nickname()).isEqualTo("Alex");
        assertThat(result.avatarUrl()).isEqualTo("/api/users/avatar/avatar-abc.jpg");
        assertThat(result.email()).isEqualTo("student@example.edu");
        assertThat(result.role()).isEqualTo("STUDENT");
    }

    @Test
    void getProfileWithoutAvatarReturnsNullUrl() {
        UserProfileResponse result = profileService.getProfile(user());
        assertThat(result.avatarUrl()).isNull();
        assertThat(result.nickname()).isNull();
    }
}
