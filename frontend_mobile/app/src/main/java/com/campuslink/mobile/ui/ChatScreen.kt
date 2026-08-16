package com.campuslink.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.campuslink.mobile.BuildConfig
import com.campuslink.mobile.core.model.ChatMessage
import com.campuslink.mobile.core.model.MatchResult
import com.campuslink.mobile.core.model.MessageRole
import com.campuslink.mobile.core.model.PendingConfirmation
import kotlinx.serialization.json.JsonPrimitive

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(viewModel: ChatViewModel, text: UiStrings, onBack: () -> Unit) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size, messages.lastOrNull()?.content) {
        if (messages.isNotEmpty()) listState.scrollToItem(messages.lastIndex)
    }

    state.pendingConfirmation?.let { pending ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text(text.confirmAction) },
            text = {
                Text(
                    confirmationDisplayText(pending, text.confirmAction),
                    fontWeight = FontWeight.Bold,
                )
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.resolveConfirmation(true) },
                    enabled = !state.streaming,
                ) { Text(text.approve) }
            },
            dismissButton = {
                TextButton(
                    onClick = { viewModel.resolveConfirmation(false) },
                    enabled = !state.streaming,
                ) { Text(text.cancel) }
            },
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(text.home.agentName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = text.back)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            state.error?.let {
                ErrorBanner(message = it, dismissLabel = text.dismiss, onDismiss = viewModel::clearError)
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                itemsIndexed(messages, key = { _, message -> message.id }) { index, message ->
                    val retryContent = messages
                        .take(index)
                        .lastOrNull { it.role == MessageRole.USER }
                        ?.content
                    MessageBubble(
                        message = message,
                        text = text,
                        onRetry = retryContent
                            ?.takeIf { message.status.name == "FAILED" || message.status.name == "INTERRUPTED" }
                            ?.let { { viewModel.retry(message.id) } },
                    )
                }
            }
            ChatComposer(
                input = input,
                onInputChange = { input = it },
                streaming = state.streaming,
                confirmationPending = state.pendingConfirmation != null,
                text = text,
                onStop = viewModel::stop,
                onSend = {
                    input = ""
                    viewModel.send(it)
                },
            )
        }
    }
}

/**
 * 确认详情中的 confirmation_id、action、expires_at 等字段仅供内部流程使用，
 * 移动端只向用户展示 Agent 生成的可读说明或业务摘要。
 */
internal fun confirmationDisplayText(pending: PendingConfirmation, fallback: String): String =
    pending.message
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?: listOf("message", "summary")
            .asSequence()
            .mapNotNull { key -> (pending.details[key] as? JsonPrimitive)?.content?.trim() }
            .firstOrNull(String::isNotEmpty)
        ?: fallback

@Composable
private fun ErrorBanner(message: String, dismissLabel: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(message, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f))
        TextButton(onClick = onDismiss) { Text(dismissLabel) }
    }
}

@Composable
@Suppress("LongParameterList")
private fun ChatComposer(
    input: String,
    onInputChange: (String) -> Unit,
    streaming: Boolean,
    confirmationPending: Boolean,
    text: UiStrings,
    onStop: () -> Unit,
    onSend: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(12.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = input,
            onValueChange = { if (it.length <= 4_000) onInputChange(it) },
            label = { Text(text.typeMessage) },
            minLines = 1,
            maxLines = 5,
            enabled = !streaming && !confirmationPending,
            shape = RoundedCornerShape(CampusCorners.ExtraLarge),
            modifier = Modifier.weight(1f),
        )
        if (streaming) {
            Button(onClick = onStop, modifier = Modifier.height(56.dp)) { Text(text.stop) }
        } else {
            IconButton(
                onClick = { onSend(input) },
                enabled = input.isNotBlank() && !confirmationPending,
                modifier = Modifier.height(56.dp),
            ) { Icon(Icons.AutoMirrored.Filled.Send, text.send) }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage, text: UiStrings, onRetry: (() -> Unit)?) {
    val user = message.role == MessageRole.USER
    var activityExpanded by remember(message.id) { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (user) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(if (user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (message.content.isNotBlank()) SafeMarkdownText(message.content)
            if (message.steps.isNotEmpty()) {
                AssistChip(
                    onClick = { activityExpanded = !activityExpanded },
                    label = { Text(if (activityExpanded) text.hideActivity else text.showActivity) },
                )
                if (activityExpanded) {
                    Text(text.agentSteps, style = MaterialTheme.typography.labelLarge)
                    message.steps.forEach { step ->
                        Text(
                            "${if (step.status == "error") "⚠" else "›"} ${step.label}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            if (message.matches.isNotEmpty()) {
                Text(text.matches, style = MaterialTheme.typography.labelLarge)
                message.matches.forEach { MatchCard(it) }
            }
            if (onRetry != null) {
                AssistChip(onClick = onRetry, label = { Text(text.retry) })
            }
        }
    }
}

@Composable
private fun MatchCard(item: MatchResult) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            item.imageUrls.firstOrNull()?.let { url ->
                val resolved = if (url.startsWith("http")) url else BuildConfig.API_BASE_URL.trimEnd('/') + "/" + url.trimStart('/')
                AsyncImage(
                    model = resolved,
                    contentDescription = item.itemName,
                    modifier = Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(10.dp)),
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("#${item.itemId} ${item.itemName}", fontWeight = FontWeight.Bold)
                Text("${(item.matchScore * 100).toInt()}%")
            }
            Text("${item.reportType} · ${item.category} · ${item.location} · ${item.eventDate}")
            if (item.description.isNotBlank()) Text(item.description)
            if (item.matchReason.isNotEmpty()) Text(item.matchReason.joinToString("；"))
            if (item.scoreBreakdown.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    item.scoreBreakdown.entries.take(4).forEach { (name, score) ->
                        AssistChip(onClick = {}, label = { Text("$name ${(score * 100).toInt()}%") })
                    }
                }
            }
        }
    }
}
