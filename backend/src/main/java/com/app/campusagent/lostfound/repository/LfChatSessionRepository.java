package com.app.campusagent.lostfound.repository;

import com.app.campusagent.lostfound.domain.LfChatSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface LfChatSessionRepository extends JpaRepository<LfChatSession, Long> {

    /** 会话隔离边界必须是 (user_id, session_id)，禁止只按 session_id 查。 */
    Optional<LfChatSession> findByUserIdAndSessionId(Long userId, String sessionId);

    List<LfChatSession> findByUserIdOrderByLastActiveAtDesc(Long userId);

    @Modifying
    @Query("delete from LfChatSession s where s.user.id = :userId")
    void deleteByUserId(@Param("userId") Long userId);
}
