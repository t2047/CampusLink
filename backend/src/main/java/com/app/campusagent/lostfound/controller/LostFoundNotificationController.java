package com.app.campusagent.lostfound.controller;

import com.app.campusagent.domain.User;
import com.app.campusagent.lostfound.dto.LostFoundNotificationResponse;
import com.app.campusagent.lostfound.dto.UnreadNotificationCountResponse;
import com.app.campusagent.lostfound.dto.PageResponse;
import com.app.campusagent.lostfound.exception.LostFoundApiException;
import com.app.campusagent.lostfound.service.LostFoundNotificationService;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/lost-found/notifications")
public class LostFoundNotificationController {

    private final LostFoundNotificationService service;

    public LostFoundNotificationController(LostFoundNotificationService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<LostFoundNotificationResponse> mine(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            @AuthenticationPrincipal User currentUser) {
        return service.mine(currentUser, pageable(page, size), unreadOnly);
    }

    @GetMapping("/unread-count")
    public UnreadNotificationCountResponse unreadCount(
            @AuthenticationPrincipal User currentUser) {
        return new UnreadNotificationCountResponse(service.unreadCount(currentUser));
    }

    @PostMapping("/{id}/read")
    public LostFoundNotificationResponse markRead(
            @PathVariable Long id,
            @AuthenticationPrincipal User currentUser) {
        return service.markRead(id, currentUser);
    }

    private Pageable pageable(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new LostFoundApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "INVALID_PAGINATION",
                    "page must be at least 0 and size must be between 1 and 100");
        }
        return PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
    }
}
