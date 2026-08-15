package com.campuslink.mobile.core.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    @Query("SELECT * FROM conversations WHERE ownerEmail = :email ORDER BY updatedAt DESC")
    fun conversations(email: String): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id LIMIT 1")
    suspend fun conversation(id: String): ConversationEntity?

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    fun messages(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE id = :id LIMIT 1")
    suspend fun message(id: String): MessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertConversation(value: ConversationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessage(value: MessageEntity)

    @Update
    suspend fun updateMessage(value: MessageEntity)

    @Query("DELETE FROM conversations WHERE id = :id AND ownerEmail = :email")
    suspend fun deleteConversation(id: String, email: String)

    @Query("DELETE FROM conversations WHERE ownerEmail = :email")
    suspend fun clearForUser(email: String)

    @Query("UPDATE conversations SET updatedAt = :updatedAt, pendingConfirmationJson = :pending WHERE id = :id")
    suspend fun updateConversationState(id: String, updatedAt: Long, pending: String?)

    @Query("UPDATE messages SET status = 'INTERRUPTED' WHERE status = 'STREAMING'")
    suspend fun markInterruptedStreams()
}
