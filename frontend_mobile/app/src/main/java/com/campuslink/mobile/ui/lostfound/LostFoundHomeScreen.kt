package com.campuslink.mobile.ui.lostfound

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.AssignmentTurnedIn
import androidx.compose.material.icons.filled.FindInPage
import androidx.compose.material.icons.filled.Inventory
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.campuslink.mobile.core.model.ReportType
import com.campuslink.mobile.ui.CampusActionCard
import com.campuslink.mobile.ui.CampusActionCopy
import com.campuslink.mobile.ui.CampusPageHeader
import com.campuslink.mobile.ui.CampusSpacing
import com.campuslink.mobile.ui.CampusTopAppBar

@Composable
fun LostFoundHomeScreen(
    onBack: () -> Unit,
    onBrowse: () -> Unit,
    onCreate: (ReportType) -> Unit,
    onClaims: () -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { CampusTopAppBar("Lost & Found", onBack, "Back to Home") },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(
                start = CampusSpacing.ExtraLarge,
                top = CampusSpacing.Medium,
                end = CampusSpacing.ExtraLarge,
                bottom = CampusSpacing.Huge,
            ),
            verticalArrangement = Arrangement.spacedBy(CampusSpacing.Large),
        ) {
            item {
                CampusPageHeader(
                    title = "Lost & Found",
                    subtitle = "Find, report and recover campus items.",
                )
            }
            item {
                CampusActionCard(
                    copy = CampusActionCopy("Browse Reports", "Search real open lost and found reports."),
                    icon = Icons.Default.FindInPage,
                    onClick = onBrowse,
                    modifier = Modifier.fillMaxWidth(),
                    prominent = true,
                )
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(CampusSpacing.Medium),
                ) {
                    CampusActionCard(
                        copy = CampusActionCopy("Report Lost Item", "Publish details and images."),
                        icon = Icons.Default.AddCircle,
                        onClick = { onCreate(ReportType.LOST) },
                        modifier = Modifier.weight(1f),
                    )
                    CampusActionCard(
                        copy = CampusActionCopy("Report Found Item", "Help return an item to its owner."),
                        icon = Icons.Default.Inventory,
                        onClick = { onCreate(ReportType.FOUND) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            item {
                CampusActionCard(
                    copy = CampusActionCopy("Claims", "Track your claims and review received requests."),
                    icon = Icons.Default.AssignmentTurnedIn,
                    onClick = onClaims,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
