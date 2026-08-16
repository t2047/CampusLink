package com.app.campusagent.controller;

import com.app.campusagent.domain.User;
import com.app.campusagent.dto.UpdateProfileRequest;
import com.app.campusagent.dto.UserProfileResponse;
import com.app.campusagent.lostfound.exception.LostFoundApiException;
import com.app.campusagent.lostfound.storage.ObjectStorageService;
import com.app.campusagent.service.UserProfileService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.concurrent.TimeUnit;

/**
 * 用户资料端点（个人中心需求 §9.3）。
 *
 * <p>{@code /api/users/me/**} 需登录；{@code /api/users/avatar/{objectKey}}
 * 为公开回显代理（头像对象键为 {@code avatar-{uuid}.{ext}}，不可枚举，
 * 仅允许该前缀的键，避免成为 MinIO 其他对象的读接口）。</p>
 */
@RestController
@RequestMapping("/api/users")
public class UserProfileController {

    private static final String AVATAR_KEY_PREFIX = "avatar-";

    private final UserProfileService profileService;
    private final ObjectStorageService storageService;

    public UserProfileController(
            UserProfileService profileService,
            ObjectStorageService storageService) {
        this.profileService = profileService;
        this.storageService = storageService;
    }

    @GetMapping("/me/profile")
    public UserProfileResponse getProfile(@AuthenticationPrincipal User currentUser) {
        return profileService.getProfile(currentUser);
    }

    @PutMapping("/me/profile")
    public UserProfileResponse updateNickname(
            @RequestBody UpdateProfileRequest request,
            @AuthenticationPrincipal User currentUser) {
        return profileService.updateNickname(currentUser, request);
    }

    @PostMapping(value = "/me/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public UserProfileResponse uploadAvatar(
            @RequestPart("file") MultipartFile file,
            @AuthenticationPrincipal User currentUser) {
        return profileService.uploadAvatar(currentUser, file);
    }

    /**
     * 头像公开回显：{@code <img>} 标签不携带 JWT，因此该端点放行鉴权。
     * 对象键随机 UUID、上传后不可变，可安全缓存（删除记录后 404 兜底）。
     */
    @GetMapping("/avatar/{objectKey}")
    public ResponseEntity<byte[]> downloadAvatar(@PathVariable String objectKey) {
        if (!objectKey.startsWith(AVATAR_KEY_PREFIX)
                || objectKey.contains("/")
                || objectKey.contains("\\")) {
            throw new LostFoundApiException(
                    HttpStatus.NOT_FOUND,
                    "AVATAR_NOT_FOUND",
                    "The requested avatar does not exist");
        }
        byte[] content = storageService.download(objectKey);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(UserProfileService.avatarContentType(objectKey)))
                .cacheControl(CacheControl.maxAge(1, TimeUnit.DAYS).cachePublic())
                .body(content);
    }
}
