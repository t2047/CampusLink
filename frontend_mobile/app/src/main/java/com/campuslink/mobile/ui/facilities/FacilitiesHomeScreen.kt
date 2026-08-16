package com.campuslink.mobile.ui.facilities

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.automirrored.filled.EventNote
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.campuslink.mobile.ui.CampusActionCard
import com.campuslink.mobile.ui.CampusActionCopy
import com.campuslink.mobile.ui.CampusPageHeader
import com.campuslink.mobile.ui.CampusSpacing
import com.campuslink.mobile.ui.CampusTopAppBar

@Composable
fun FacilitiesHomeScreen(
    onBack: () -> Unit,
    onSearchSpaces: () -> Unit,
    onMyBookings: () -> Unit,
    onReportMaintenance: () -> Unit,
    onMyMaintenance: () -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { CampusTopAppBar("Facilities", onBack, "Back to Home") },
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
                    title = "Facilities",
                    subtitle = "Find spaces, manage bookings and report issues.",
                )
            }
            item {
                CampusActionCard(
                    copy = CampusActionCopy("Find a Space", "Search campus rooms and check real availability."),
                    icon = Icons.Default.Search,
                    onClick = onSearchSpaces,
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
                        copy = CampusActionCopy("My Bookings", "View and manage reservations."),
                        icon = Icons.AutoMirrored.Filled.EventNote,
                        onClick = onMyBookings,
                        modifier = Modifier.weight(1f),
                    )
                    CampusActionCard(
                        copy = CampusActionCopy("Report Maintenance", "Tell Facilities about an issue."),
                        icon = Icons.Default.Build,
                        onClick = onReportMaintenance,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            item {
                CampusActionCard(
                    copy = CampusActionCopy("My Maintenance", "Track your submitted Facilities requests."),
                    icon = Icons.AutoMirrored.Filled.Assignment,
                    onClick = onMyMaintenance,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
