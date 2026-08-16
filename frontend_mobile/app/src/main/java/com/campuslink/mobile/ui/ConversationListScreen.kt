package com.campuslink.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.campuslink.mobile.core.model.Conversation
import com.campuslink.mobile.core.settings.AppLanguage
import java.text.DateFormat
import java.util.Date

@Composable
fun ConversationListScreen(
    viewModel: ConversationListViewModel,
    text: UiStrings,
    onOpen: (String) -> Unit,
) {
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    AgentCoreContent(
        conversations = conversations,
        text = text.agentCore,
        onCreate = { viewModel.create(onOpen) },
        onOpen = onOpen,
        onDelete = viewModel::delete,
    )
}

@Composable
internal fun AgentCoreContent(
    conversations: List<Conversation>,
    text: AgentCoreStrings,
    onCreate: () -> Unit,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = CampusSpacing.ExtraLarge,
            top = CampusSpacing.Huge,
            end = CampusSpacing.ExtraLarge,
            bottom = CampusSpacing.Huge,
        ),
        verticalArrangement = Arrangement.spacedBy(CampusSpacing.Large),
    ) {
        item { AgentCoreHeader(text) }
        if (conversations.isEmpty()) {
            item { AgentEmptyState(text, onCreate) }
        } else {
            item {
                Button(onClick = onCreate, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Text(text.newChat, modifier = Modifier.padding(start = CampusSpacing.Small))
                }
            }
            item {
                CampusSectionHeader(
                    title = text.recentConversations,
                    modifier = Modifier.padding(top = CampusSpacing.Small),
                )
            }
            items(conversations, key = { it.id }) { conversation ->
                ConversationCard(
                    conversation = conversation,
                    deleteDescription = text.deleteConversation,
                    onOpen = { onOpen(conversation.id) },
                    onDelete = { onDelete(conversation.id) },
                )
            }
        }
    }
}

@Composable
private fun AgentCoreHeader(text: AgentCoreStrings) {
    Column(verticalArrangement = Arrangement.spacedBy(CampusSpacing.ExtraSmall)) {
        Text(text.title, style = MaterialTheme.typography.headlineLarge)
        Text(
            text = text.subtitle,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AgentEmptyState(text: AgentCoreStrings, onCreate: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(CampusCorners.ExtraLarge),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = CardDefaults.outlinedCardBorder(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(CampusSpacing.ExtraHuge),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(CampusSpacing.Medium),
        ) {
            CampusIconContainer(
                icon = Icons.Default.AutoAwesome,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = text.emptyTitle,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = text.emptySubtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onCreate, modifier = Modifier.padding(top = CampusSpacing.Small)) {
                Icon(Icons.Default.Add, contentDescription = null)
                Text(text.startNewChat, modifier = Modifier.padding(start = CampusSpacing.Small))
            }
        }
    }
}

@Composable
private fun ConversationCard(
    conversation: Conversation,
    deleteDescription: String,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) { role = Role.Button },
        shape = RoundedCornerShape(CampusCorners.Large),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = CardDefaults.outlinedCardBorder(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(
                start = CampusSpacing.Large,
                top = CampusSpacing.Medium,
                end = CampusSpacing.Small,
                bottom = CampusSpacing.Medium,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(CampusSpacing.Medium),
        ) {
            CampusIconContainer(
                icon = Icons.Default.AutoAwesome,
                contentDescription = null,
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.primary,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(CampusSpacing.ExtraSmall)) {
                Text(conversation.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                        .format(Date(conversation.updatedAt)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.DeleteOutline,
                    contentDescription = deleteDescription,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Preview(name = "Agent Core - empty", widthDp = 320, heightDp = 640)
@Composable
@Suppress("UnusedPrivateMember")
private fun AgentCoreEmptyPreview() {
    CampusLinkTheme(darkTheme = false) {
        AgentCoreContent(
            conversations = emptyList(),
            text = strings(AppLanguage.ENGLISH).agentCore,
            onCreate = {},
            onOpen = {},
            onDelete = {},
        )
    }
}
