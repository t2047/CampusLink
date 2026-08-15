package com.campuslink.mobile.core.storage

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "conversations", indices = [Index("ownerEmail"), Index("updatedAt")])
data class ConversationEntity(
    @PrimaryKey val id: String,
    val ownerEmail: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val pendingConfirmationJson: String? = null,
)

@Entity(
    tableName = "messages",
    foreignKeys = [ForeignKey(
        entity = ConversationEntity::class,
        parentColumns = ["id"],
        childColumns = ["conversationId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("conversationId"), Index(value = ["conversationId", "timestamp"])],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val role: String,
    val content: String,
    val status: String,
    val timestamp: Long,
    val stepsJson: String = "[]",
    val matchesJson: String = "[]",
)
