package com.app.campusagent.facilities.dto.admin;

import java.util.List;

public record AdminFacilitiesOverviewResponse(
        Summary summary,
        List<StatusCount> spaceStatusBreakdown,
        List<StatusCount> bookingStatusBreakdown,
        List<StatusCount> maintenanceStatusBreakdown
) {
    public record Summary(
            long totalSpaces,
            long availableSpaces,
            long outOfServiceSpaces,
            long inactiveSpaces,
            long totalBookings,
            long confirmedBookings,
            long cancelledBookings,
            long completedBookings,
            long totalMaintenanceRequests,
            long submittedMaintenanceRequests,
            long inProgressMaintenanceRequests,
            long resolvedMaintenanceRequests,
            long cancelledMaintenanceRequests,
            long openMaintenanceRequests
    ) {
    }

    public record StatusCount(String status, long count) {
    }
}
