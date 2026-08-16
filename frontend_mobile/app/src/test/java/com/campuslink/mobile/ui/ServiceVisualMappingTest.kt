package com.campuslink.mobile.ui

import com.campuslink.mobile.core.model.BookingStatus
import com.campuslink.mobile.core.model.MaintenanceStatus
import com.campuslink.mobile.core.model.ReportStatus
import com.campuslink.mobile.ui.facilities.bookingStatusTone
import com.campuslink.mobile.ui.facilities.maintenanceStatusTone
import com.campuslink.mobile.ui.lostfound.reportStatusTone
import org.junit.Assert.assertEquals
import org.junit.Test

class ServiceVisualMappingTest {
    @Test
    fun bookingStatusesUseSemanticTones() {
        assertEquals(CampusStatusTone.SUCCESS, bookingStatusTone(BookingStatus.CONFIRMED))
        assertEquals(CampusStatusTone.ERROR, bookingStatusTone(BookingStatus.CANCELLED))
        assertEquals(CampusStatusTone.NEUTRAL, bookingStatusTone(BookingStatus.COMPLETED))
    }

    @Test
    fun maintenanceStatusesUseSemanticTones() {
        assertEquals(CampusStatusTone.INFO, maintenanceStatusTone(MaintenanceStatus.SUBMITTED))
        assertEquals(CampusStatusTone.WARNING, maintenanceStatusTone(MaintenanceStatus.IN_PROGRESS))
        assertEquals(CampusStatusTone.SUCCESS, maintenanceStatusTone(MaintenanceStatus.RESOLVED))
        assertEquals(CampusStatusTone.ERROR, maintenanceStatusTone(MaintenanceStatus.CANCELLED))
    }

    @Test
    fun reportStatusesUseSemanticTones() {
        assertEquals(CampusStatusTone.SUCCESS, reportStatusTone(ReportStatus.OPEN))
        assertEquals(CampusStatusTone.INFO, reportStatusTone(ReportStatus.CLAIMED))
        assertEquals(CampusStatusTone.NEUTRAL, reportStatusTone(ReportStatus.CLOSED))
    }
}
