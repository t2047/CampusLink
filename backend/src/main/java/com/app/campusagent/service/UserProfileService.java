package com.app.campusagent.service;

import com.app.campusagent.domain.User;
import com.app.campusagent.dto.UpdateProfileRequest;
import com.app.campusagent.dto.UserProfileResponse;
import com.app.campusagent.exception.BusinessException;
import com.app.campusagent.exception.ErrorCode;
import com.app.campusagent.lostfound.service.LostFoundImageRules;
import com.app.campusagent.lostfound.storage.ObjectStorageService;
import com.app.campusagent.lostfound.storage.StoredObject;
import com.app.campusagent.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

/**
 * 用户资料（个人中心需求 §9.3 / §11.2）：昵称编辑与头像上传。
 *
 * <p>昵称/头像属于跨模块用户资料，落库到 {@code users} 表（可空列），
 * 由 {@code GET /api/users/me/profile} 暴露给全站（顶部导航、个人中心）。
 * 头像对象键为 {@code avatar-{uuid}.{ext}}（无目录、不可枚举），
 * 经公开代理端点 {@code /api/users/avatar/{objectKey}} 回显。</p>
 */
@Service
public class UserProfileService {

    private static final String AVATAR_URL_TEMPLATE = "/api/users/avatar/%s";

    private final UserRepository userRepository;
    private final ObjectStorageService storageService;

    public UserProfileService(UserRepository userRepository, ObjectStorageService storageService) {
        this.userRepository = userRepository;
        this.storageService = storageService;
    }

    @Transactional(readOnly = true)
    public UserProfileResponse getProfile(User currentUser) {
        return toProfile(currentUser);
    }

    @Transactional
    public UserProfileResponse updateNickname(User currentUser, UpdateProfileRequest request) {
        if (request == null || request.nickname() == null) {
            throw new BusinessException(ErrorCode.NICKNAME_REQUIRED);
        }
        String nickname = request.nickname().trim();
        if (nickname.isEmpty()) {
            throw new BusinessException(ErrorCode.NICKNAME_REQUIRED);
        }
        if (nickname.length() > 30) {
            throw new BusinessException(ErrorCode.NICKNAME_INVALID_LENGTH);
        }
        currentUser.setNickname(nickname);
        userRepository.save(currentUser);
        return toProfile(currentUser);
    }

    @Transactional
    public UserProfileResponse uploadAvatar(User currentUser, MultipartFile file) {
        LostFoundImageRules.validateAvatar(file);
        String objectKey = "avatar-" + UUID.randomUUID() + extension(file.getContentType());
        StoredObject stored = storageService.upload(file, objectKey);

        String previous = currentUser.getAvatarUrl();
        currentUser.setAvatarUrl(stored.objectKey());
        userRepository.save(currentUser);

        if (previous != null) {
            storageService.delete(previous);
        }
        return toProfile(currentUser);
    }

    /** 公开回显端点的 Content-Type：由对象键扩展名推导（与上传时写入 MinIO 的类型一致）。 */
    public static String avatarContentType(String objectKey) {
        String lower = objectKey.toLowerCase();
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        return "image/jpeg";
    }

    private UserProfileResponse toProfile(User user) {
        return new UserProfileResponse(
                user.getEmail(),
                user.getRole().name(),
                user.getNickname(),
                user.getAvatarUrl() == null ? null : AVATAR_URL_TEMPLATE.formatted(user.getAvatarUrl()));
    }

    private String extension(String contentType) {
        if ("image/png".equals(contentType)) {
            return ".png";
        }
        if ("image/webp".equals(contentType)) {
            return ".webp";
        }
        return ".jpg";
    }
}
