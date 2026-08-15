package com.app.campusagent.lostfound.repository;

import com.app.campusagent.lostfound.domain.LfChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface LfChatMessageRepository extends JpaRepository<LfChatMessage, Long> {

    List<LfChatMessage> findByChatSessionIdOrderByCreatedAtAsc(Long chatSessionId);

    @Modifying
    @Query("delete from LfChatMessage m where m.chatSession.id in :sessionIds")
    void deleteByChatSessionIdIn(@Param("sessionIds") Collection<Long> sessionIds);
}
