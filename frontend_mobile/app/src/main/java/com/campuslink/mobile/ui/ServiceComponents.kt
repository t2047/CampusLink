package com.campuslink.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal enum class CampusStatusTone {
    SUCCESS,
    WARNING,
    INFO,
    NEUTRAL,
    ERROR,
}

internal data class CampusActionCopy(val title: String, val subtitle: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CampusTopAppBar(
    title: String,
    onBack: () -> Unit,
    backDescription: String,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = backDescription)
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            scrolledContainerColor = MaterialTheme.colorScheme.surface,
        ),
    )
}

@Composable
internal fun CampusPageHeader(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(CampusSpacing.ExtraSmall),
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun CampusActionCard(
    copy: CampusActionCopy,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    prominent: Boolean = false,
) {
    Card(
        onClick = onClick,
        modifier = modifier.semantics(mergeDescendants = true) { role = Role.Button },
        shape = RoundedCornerShape(if (prominent) CampusCorners.ExtraLarge else CampusCorners.Large),
        colors = CardDefaults.cardColors(
            containerColor = if (prominent) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
        border = if (prominent) null else CardDefaults.outlinedCardBorder(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(CampusSpacing.Large),
            verticalArrangement = Arrangement.spacedBy(CampusSpacing.Medium),
        ) {
            CampusIconContainer(
                icon = icon,
                contentDescription = null,
                containerColor = if (prominent) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.secondaryContainer
                },
                contentColor = if (prominent) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSecondaryContainer
                },
            )
            Text(copy.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                text = copy.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (prominent) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                minLines = 2,
            )
        }
    }
}

@Composable
internal fun CampusSurfaceCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(CampusCorners.Large),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = CardDefaults.outlinedCardBorder(),
    ) {
        content()
    }
}

@Composable
internal fun CampusStatusChip(
    label: String,
    tone: CampusStatusTone,
    modifier: Modifier = Modifier,
) {
    val colors = statusColors(tone)
    Surface(
        modifier = modifier,
        color = colors.container,
        contentColor = colors.content,
        shape = RoundedCornerShape(CampusCorners.ExtraLarge),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(
                horizontal = CampusSpacing.Medium,
                vertical = CampusSpacing.ExtraSmall,
            ),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
internal fun CampusInfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(CampusSpacing.ExtraSmall),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
internal fun CampusLoadingState(
    label: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(CampusSpacing.ExtraHuge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(CampusSpacing.Medium),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(32.dp))
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun CampusEmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Default.Inbox,
) {
    CampusSurfaceCard(modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(CampusSpacing.ExtraHuge),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(CampusSpacing.Medium),
        ) {
            CampusIconContainer(
                icon = icon,
                contentDescription = null,
                modifier = Modifier.size(52.dp),
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.primary,
            )
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun CampusErrorState(
    title: String,
    message: String,
    retryLabel: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CampusSurfaceCard(modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(CampusSpacing.ExtraLarge),
            verticalArrangement = Arrangement.spacedBy(CampusSpacing.Medium),
        ) {
            CampusIconContainer(
                icon = Icons.Default.ErrorOutline,
                contentDescription = null,
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onRetry) { Text(retryLabel) }
        }
    }
}

@Composable
private fun statusColors(tone: CampusStatusTone): CampusStatusColors = when (tone) {
    CampusStatusTone.SUCCESS -> CampusStatusColors(
        MaterialTheme.colorScheme.primaryContainer,
        MaterialTheme.colorScheme.onPrimaryContainer,
    )
    CampusStatusTone.WARNING -> CampusStatusColors(
        MaterialTheme.colorScheme.tertiaryContainer,
        MaterialTheme.colorScheme.onTertiaryContainer,
    )
    CampusStatusTone.INFO -> CampusStatusColors(
        MaterialTheme.colorScheme.secondaryContainer,
        MaterialTheme.colorScheme.onSecondaryContainer,
    )
    CampusStatusTone.NEUTRAL -> CampusStatusColors(
        MaterialTheme.colorScheme.surfaceVariant,
        MaterialTheme.colorScheme.onSurfaceVariant,
    )
    CampusStatusTone.ERROR -> CampusStatusColors(
        MaterialTheme.colorScheme.errorContainer,
        MaterialTheme.colorScheme.onErrorContainer,
    )
}

private data class CampusStatusColors(val container: Color, val content: Color)
