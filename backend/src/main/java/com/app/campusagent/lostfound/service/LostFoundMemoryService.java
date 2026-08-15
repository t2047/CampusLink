package com.app.campusagent.lostfound.service;

import com.app.campusagent.domain.User;
import com.app.campusagent.lostfound.domain.LfChatMessage;
import com.app.campusagent.lostfound.domain.LfChatSession;
import com.app.campusagent.lostfound.domain.LfFactStatus;
import com.app.campusagent.lostfound.domain.LfUserMemoryFact;
import com.app.campusagent.lostfound.dto.memory.MemoryAppendMessageRequest;
import com.app.campusagent.lostfound.dto.memory.MemoryDeleteResponse;
import com.app.campusagent.lostfound.dto.memory.MemoryFactResponse;
import com.app.campusagent.lostfound.dto.memory.MemoryMessageResponse;
import com.app.campusagent.lostfound.dto.memory.MemoryPruneResponse;
import com.app.campusagent.lostfound.dto.memory.MemorySessionResponse;
import com.app.campusagent.lostfound.dto.memory.MemoryUpsertFactRequest;
import com.app.campusagent.lostfound.dto.memory.MemoryUpsertSessionRequest;
import com.app.campusagent.lostfound.dto.memory.MemoryUserMemoryResponse;
import com.app.campusagent.lostfound.exception.LostFoundApiException;
import com.app.campusagent.lostfound.repository.LfChatMessageRepository;
import com.app.campusagent.lostfound.repository.LfChatSessionRepository;
import com.app.campusagent.lostfound.repository.LfUserMemoryFactRepository;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * L&F 记忆存储：会话 + 滚动摘要 + pending 确认草稿 + 用户长期事实。
 * 所有权一律取自 delegation token 解析出的 User（agent 无 DB 连接，仅经内部 API 读写）。
 */
@Service
public class LostFoundMemoryService {

    private static final Logger log = LoggerFactory.getLogger(LostFoundMemoryService.class);

    private static final int MAX_SESSION_ID_LENGTH = 200;

    private final LfChatSessionRepository sessionRepository;
    private final LfChatMessageRepository messageRepository;
    private final LfUserMemoryFactRepository factRepository;
    private final ObjectMapper objectMapper;

    public LostFoundMemoryService(
            LfChatSessionRepository sessionRepository,
            LfChatMessageRepository messageRepository,
            LfUserMemoryFactRepository factRepository,
            ObjectMapper objectMapper) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.factRepository = factRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public MemorySessionResponse upsertSession(User user, MemoryUpsertSessionRequest request) {
        String sessionId = requireValidSessionId(request.sessionId());
        LfChatSession session = sessionRepository
                .findByUserIdAndSessionId(user.getId(), sessionId)
                .orElseGet(() -> new LfChatSession(user, sessionId));
        if (request.title() != null) {
            session.updateTitle(request.title());
        }
        if (request.summary() != null) {
            session.updateSummary(request.summary());
        }
        // pending 非破坏性 upsert：显式写入或清除，缺省保留已有草稿（§7.5）。
        if (request.pendingConfirmation() != null) {
            session.updatePendingConfirmation(toJson(request.pendingConfirmation()));
        } else if (Boolean.TRUE.equals(request.clearPendingConfirmation())) {
            session.updatePendingConfirmation(null);
        }
        session.touch();
        return toSessionResponse(sessionRepository.save(session));
    }

    @Transactional(readOnly = true)
    public MemorySessionResponse getSession(User user, String sessionId) {
        LfChatSession session = requireSession(user, sessionId);
        return toSessionResponse(session);
    }

    @Transactional
    public MemoryMessageResponse appendMessage(User user, String sessionId, MemoryAppendMessageRequest request) {
        LfChatSession session = requireSession(user, sessionId);
        LfChatMessage message = new LfChatMessage(
                request.role(),
                request.messageText(),
                request.intent(),
                toJson(request.extractedFields()),
                toJson(request.imageObjectKeys()),
                request.traceId());
        session.addMessage(message);
        session.touch();
        sessionRepository.save(session);
        return toMessageResponse(message);
    }

    @Transactional
    public MemoryPruneResponse pruneMessages(User user, String sessionId, int keepLatest) {
        LfChatSession session = requireSession(user, sessionId);
        List<LfChatMessage> messages = messageRepository.findByChatSessionIdOrderByCreatedAtAsc(session.getId());
        int keep = Math.max(1, Math.min(200, keepLatest));
        if (messages.size() <= keep) {
            return new MemoryPruneResponse(messages.size(), 0);
        }
        List<LfChatMessage> toDelete = messages.subList(0, messages.size() - keep);
        messageRepository.deleteAll(toDelete);
        return new MemoryPruneResponse(keep, toDelete.size());
    }

    @Transactional(readOnly = true)
    public MemoryUserMemoryResponse getUserFacts(User user) {
        List<MemoryFactResponse> facts = factRepository.findByUserIdOrderByUpdatedAtDesc(user.getId()).stream()
                .map(this::toFactResponse)
                .toList();
        return new MemoryUserMemoryResponse(facts);
    }

    @Transactional
    public MemoryFactResponse upsertFact(User user, MemoryUpsertFactRequest request) {
        // 去重键 (user_id, fact_type, category, location)：category/location 可为 null，
        // JPQL 对 null 永不匹配，故在内存层空安全比较（单用户事实量极小）。
        LfUserMemoryFact existing = factRepository
                .findByUserIdAndFactTypeOrderByUpdatedAtDesc(user.getId(), request.factType())
                .stream()
                .filter(fact -> equalNullable(fact.getCategory(), request.category()))
                .filter(fact -> equalNullable(fact.getLocation(), request.location()))
                .findFirst()
                .orElse(null);
        if (existing != null) {
            existing.mergeNonNull(
                    request.itemName(),
                    request.category(),
                    request.colour(),
                    request.location(),
                    request.eventDate(),
                    request.timeDescription(),
                    request.status(),
                    request.confidence());
            return toFactResponse(factRepository.save(existing));
        }
        LfUserMemoryFact fact = new LfUserMemoryFact(
                user,
                request.sessionId(),
                request.factType(),
                request.itemName(),
                request.category(),
                request.colour(),
                request.location(),
                request.eventDate(),
                request.timeDescription(),
                request.status() != null ? request.status() : LfFactStatus.OPEN,
                request.confidence());
        return toFactResponse(factRepository.save(fact));
    }

    @Transactional
    public MemoryDeleteResponse deleteUserMemory(User user) {
        long deletedFacts = factRepository.deleteByUserId(user.getId());
        List<LfChatSession> sessions = sessionRepository.findByUserIdOrderByLastActiveAtDesc(user.getId());
        long deletedSessions = sessions.size();
        if (!sessions.isEmpty()) {
            List<Long> sessionIds = sessions.stream().map(LfChatSession::getId).toList();
            messageRepository.deleteByChatSessionIdIn(sessionIds);
            sessionRepository.deleteAllInBatch(sessions);
        }
        return new MemoryDeleteResponse(deletedFacts, deletedSessions);
    }

    private LfChatSession requireSession(User user, String sessionId) {
        String validId = requireValidSessionId(sessionId);
        return sessionRepository.findByUserIdAndSessionId(user.getId(), validId)
                .orElseThrow(() -> new LostFoundApiException(
                        HttpStatus.NOT_FOUND,
                        "LOST_FOUND_SESSION_NOT_FOUND",
                        "L&F memory session not found"));
    }

    private static String requireValidSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank() || sessionId.length() > MAX_SESSION_ID_LENGTH) {
            throw new LostFoundApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "INVALID_SESSION_ID",
                    "sessionId must be non-blank and at most " + MAX_SESSION_ID_LENGTH + " characters");
        }
        return sessionId;
    }

    private static boolean equalNullable(String left, String right) {
        if (left == null || right == null) {
            return left == right;
        }
        return left.equals(right);
    }

    private MemorySessionResponse toSessionResponse(LfChatSession session) {
        List<MemoryMessageResponse> messages = session.getMessages().stream()
                .sorted((a, b) -> {
                    var ta = a.getCreatedAt();
                    var tb = b.getCreatedAt();
                    if (ta == null || tb == null) {
                        return 0;
                    }
                    return ta.compareTo(tb);
                })
                .map(this::toMessageResponse)
                .toList();
        return new MemorySessionResponse(
                session.getId(),
                session.getSessionId(),
                session.getTitle(),
                session.getSummary(),
                fromJson(session.getPendingConfirmation(), new TypeReference<>() {
                }),
                session.isArchived(),
                session.getLastActiveAt(),
                messages);
    }

    private MemoryMessageResponse toMessageResponse(LfChatMessage message) {
        return new MemoryMessageResponse(
                message.getId(),
                message.getRole(),
                message.getMessageText(),
                message.getIntent(),
                fromJson(message.getExtractedFields(), new TypeReference<>() {
                }),
                fromJson(message.getImageObjectKeys(), new TypeReference<>() {
                }),
                message.getTraceId(),
                message.getCreatedAt());
    }

    private MemoryFactResponse toFactResponse(LfUserMemoryFact fact) {
        return new MemoryFactResponse(
                fact.getId(),
                fact.getFactType(),
                fact.getItemName(),
                fact.getCategory(),
                fact.getColour(),
                fact.getLocation(),
                fact.getEventDate(),
                fact.getTimeDescription(),
                fact.getStatus(),
                fact.getConfidence(),
                fact.getUpdatedAt());
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException ex) {
            // 记忆是增强而非主流程依赖：JSON 写入失败仅记录，不阻断核心功能。
            log.warn("L&F memory: failed to serialize json field, storing null", ex);
            return null;
        }
    }

    private <T> T fromJson(String json, TypeReference<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (JacksonException ex) {
            log.warn("L&F memory: failed to deserialize json field", ex);
            return null;
        }
    }
}
