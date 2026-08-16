package com.campuslink.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.automirrored.filled.EventNote
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.campuslink.mobile.core.settings.AppLanguage
import java.time.LocalTime

internal data class HomeActions(
    val openAgentCore: () -> Unit,
    val openFacilities: () -> Unit,
    val openLostFound: () -> Unit,
    val openMyBookings: () -> Unit,
    val openMyMaintenance: () -> Unit,
    val openMyClaims: () -> Unit,
)

internal const val HOME_LIST_TAG = "home-list"

@Composable
internal fun HomeScreen(
    actions: HomeActions,
    text: HomeStrings,
) {
    val greeting = remember(text) { greetingForHour(LocalTime.now().hour, text) }
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag(HOME_LIST_TAG),
        contentPadding = PaddingValues(
            start = CampusSpacing.ExtraLarge,
            top = CampusSpacing.Huge,
            end = CampusSpacing.ExtraLarge,
            bottom = CampusSpacing.Huge,
        ),
        verticalArrangement = Arrangement.spacedBy(CampusSpacing.Large),
    ) {
        item { HomeHeader(greeting, text) }
        item { AgentHeroCard(text, actions.openAgentCore) }
        item { CampusSectionHeader(text.campusServices, Modifier.padding(top = CampusSpacing.Small)) }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(CampusSpacing.Medium),
            ) {
                CampusServiceCard(
                    copy = ServiceCardCopy(text.facilities, text.facilitiesSubtitle),
                    icon = Icons.Default.MeetingRoom,
                    onClick = actions.openFacilities,
                    modifier = Modifier.weight(1f),
                )
                CampusServiceCard(
                    copy = ServiceCardCopy(text.lostFound, text.lostFoundSubtitle),
                    icon = Icons.Default.Search,
                    onClick = actions.openLostFound,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        item {
            CampusServiceCard(
                copy = ServiceCardCopy(text.mail, text.mailSubtitle, text.agentBadge),
                icon = Icons.Default.Email,
                onClick = actions.openAgentCore,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item { CampusSectionHeader(text.quickAccess, Modifier.padding(top = CampusSpacing.Small)) }
        item {
            QuickActionCard(
                title = text.myBookings,
                subtitle = text.bookingsSubtitle,
                icon = Icons.AutoMirrored.Filled.EventNote,
                onClick = actions.openMyBookings,
            )
        }
        item {
            QuickActionCard(
                title = text.myMaintenance,
                subtitle = text.maintenanceSubtitle,
                icon = Icons.Default.Build,
                onClick = actions.openMyMaintenance,
            )
        }
        item {
            QuickActionCard(
                title = text.myClaims,
                subtitle = text.claimsSubtitle,
                icon = Icons.AutoMirrored.Filled.Assignment,
                onClick = actions.openMyClaims,
            )
        }
    }
}

@Composable
private fun HomeHeader(greeting: String, text: HomeStrings) {
    Column(verticalArrangement = Arrangement.spacedBy(CampusSpacing.ExtraSmall)) {
        Text(
            text = greeting,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(text = text.appName, style = MaterialTheme.typography.headlineLarge)
        Text(
            text = text.subtitle,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AgentHeroCard(text: HomeStrings, onClick: () -> Unit) {
    Card(
        shape = RoundedCornerShape(CampusCorners.ExtraLarge),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(CampusSpacing.ExtraLarge),
            verticalArrangement = Arrangement.spacedBy(CampusSpacing.Small),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(CampusCorners.Medium),
            ) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.padding(CampusSpacing.Medium).size(24.dp),
                )
            }
            Text(text.agentName, style = MaterialTheme.typography.headlineSmall)
            Text(
                text = text.agentTagline,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                text = text.agentDescription,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Button(onClick = onClick, modifier = Modifier.padding(top = CampusSpacing.ExtraSmall)) {
                Text(text.askAgent)
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier.padding(start = CampusSpacing.Small),
                )
            }
        }
    }
}

@Composable
private fun CampusServiceCard(
    copy: ServiceCardCopy,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = if (copy.badge != null) 136.dp else 172.dp)
            .semantics(mergeDescendants = true) { role = Role.Button },
        shape = RoundedCornerShape(CampusCorners.Large),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = CardDefaults.outlinedCardBorder(),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(CampusSpacing.Large),
            verticalArrangement = Arrangement.spacedBy(CampusSpacing.Medium),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                CampusIconContainer(icon = icon, contentDescription = null)
                copy.badge?.let { AgentBadge(it) }
            }
            Text(copy.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                text = copy.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private data class ServiceCardCopy(
    val title: String,
    val subtitle: String,
    val badge: String? = null,
)

@Composable
private fun AgentBadge(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(CampusCorners.ExtraLarge),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = CampusSpacing.Medium, vertical = CampusSpacing.ExtraSmall),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun QuickActionCard(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) { role = Role.Button },
        shape = RoundedCornerShape(CampusCorners.Medium),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = CardDefaults.outlinedCardBorder(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(CampusSpacing.Large),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(CampusSpacing.Medium),
        ) {
            CampusIconContainer(
                icon = icon,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.primary,
            )
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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

internal fun greetingForHour(hour: Int, text: HomeStrings): String = when (hour) {
    in 5..11 -> text.goodMorning
    in 12..17 -> text.goodAfternoon
    else -> text.goodEvening
}

@Preview(name = "Home - small phone", widthDp = 320, heightDp = 640)
@Composable
@Suppress("UnusedPrivateMember")
private fun HomeSmallPreview() {
    CampusLinkTheme(darkTheme = false) {
        HomeScreen(
            actions = HomeActions({}, {}, {}, {}, {}, {}),
            text = strings(AppLanguage.ENGLISH).home,
        )
    }
}
