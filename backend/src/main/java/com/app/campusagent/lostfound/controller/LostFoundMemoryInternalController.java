package com.app.campusagent.lostfound.controller;

import com.app.campusagent.domain.User;
import com.app.campusagent.lostfound.dto.memory.MemoryAppendMessageRequest;
import com.app.campusagent.lostfound.dto.memory.MemoryDeleteResponse;
import com.app.campusagent.lostfound.dto.memory.MemoryFactResponse;
import com.app.campusagent.lostfound.dto.memory.MemoryMessageResponse;
import com.app.campusagent.lostfound.dto.memory.MemoryPruneMessagesRequest;
import com.app.campusagent.lostfound.dto.memory.MemoryPruneResponse;
import com.app.campusagent.lostfound.dto.memory.MemorySessionResponse;
import com.app.campusagent.lostfound.dto.memory.MemoryUpsertFactRequest;
import com.app.campusagent.lostfound.dto.memory.MemoryUpsertSessionRequest;
import com.app.campusagent.lostfound.dto.memory.MemoryUserMemoryResponse;
import com.app.campusagent.lostfound.service.LostFoundMemoryService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * L&F 记忆内部 API（agent 无 DB 连接，经 delegation token 读写）。
 * 所有权一律取 delegation token sub 解析出的 currentUser；用户级接口固定 /users/me。
 */
@RestController
@RequestMapping("/api/internal/lost-found/memory")
@PreAuthorize("hasRole('AGENT_LOST_FOUND')")
public class LostFoundMemoryInternalController {

    private final LostFoundMemoryService memoryService;

    public LostFoundMemoryInternalController(LostFoundMemoryService memoryService) {
        this.memoryService = memoryService;
    }

    @PostMapping("/sessions")
    public MemorySessionResponse upsertSession(
            @Valid @RequestBody MemoryUpsertSessionRequest request,
            @AuthenticationPrincipal User currentUser) {
        return memoryService.upsertSession(currentUser, request);
    }

    @GetMapping("/sessions/{sessionId}")
    public MemorySessionResponse getSession(
            @PathVariable String sessionId,
            @AuthenticationPrincipal User currentUser) {
        return memoryService.getSession(currentUser, sessionId);
    }

    @PostMapping("/sessions/{sessionId}/messages")
    public MemoryMessageResponse appendMessage(
            @PathVariable String sessionId,
            @Valid @RequestBody MemoryAppendMessageRequest request,
            @AuthenticationPrincipal User currentUser) {
        return memoryService.appendMessage(currentUser, sessionId, request);
    }

    @PostMapping("/sessions/{sessionId}/messages/prune")
    public MemoryPruneResponse pruneMessages(
            @PathVariable String sessionId,
            @Valid @RequestBody MemoryPruneMessagesRequest request,
            @AuthenticationPrincipal User currentUser) {
        return memoryService.pruneMessages(currentUser, sessionId, request.keepLatest());
    }

    @GetMapping("/users/me")
    public MemoryUserMemoryResponse getUserFacts(@AuthenticationPrincipal User currentUser) {
        return memoryService.getUserFacts(currentUser);
    }

    @PostMapping("/users/me/facts")
    public MemoryFactResponse upsertFact(
            @Valid @RequestBody MemoryUpsertFactRequest request,
            @AuthenticationPrincipal User currentUser) {
        return memoryService.upsertFact(currentUser, request);
    }

    @DeleteMapping("/users/me")
    public MemoryDeleteResponse deleteUserMemory(@AuthenticationPrincipal User currentUser) {
        return memoryService.deleteUserMemory(currentUser);
    }
}
