import { getAdminFacilitiesOverview, searchAdminFacilityBookings, searchAdminFacilityMaintenance } from '../../../api/adminFacilities'
import { getAdminLostFoundOverview, searchAdminAuditLogs, searchAdminLostFoundReports } from '../../../api/adminLostFound'
import { listAdminUsers, type AdminUser, type UserRole } from '../../../api/adminUsers'
import type {
  AdminAuditLog,
  AdminFacilityBooking,
  AdminFacilityBookingStatus,
  AdminFacilityMaintenance,
  AdminFacilityMaintenanceStatus,
  AdminLostFoundReport,
  PageResponse,
  ReportStatus,
  ReportType,
} from '../../../types'
import {
  DASHBOARD_REPORT_AUDIT_SCOPE_NOTE,
  DASHBOARD_REPORT_PERIOD_DAYS,
  DASHBOARD_REPORT_TITLE,
  type AdministrativeAuditAction,
  type DashboardReportAuditLog,
  type DashboardReportFacilityUsage,
  type DashboardReportSnapshot,
  type DeepReadonly,
} from './dashboardReportTypes'

const REPORT_PAGE_SIZE = 100
const MAX_REPORT_PAGES = 10_000
const ACTIVE_BOOKING_STATUSES = new Set<AdminFacilityBookingStatus>(['CONFIRMED', 'COMPLETED'])
const ADMINISTRATIVE_AUDIT_ACTIONS: AdministrativeAuditAction[] = [
  'REPORT_DELISTED',
  'REPORT_RESTORED',
  'REPORT_DELETED_BY_ADMIN',
  'CLAIM_APPROVED_BY_ADMIN',
  'CLAIM_REJECTED_BY_ADMIN',
]

function validatePage<T>(response: PageResponse<T>, requestedPage: number, expectedTotalPages?: number) {
  if (!Array.isArray(response.content)) {
    throw new Error('Invalid pagination contract: content must be an array.')
  }
  if (!Number.isInteger(response.page) || response.page !== requestedPage) {
    throw new Error(`Invalid pagination contract: expected page ${requestedPage}.`)
  }
  if (!Number.isInteger(response.totalPages) || response.totalPages < 0 || response.totalPages > MAX_REPORT_PAGES) {
    throw new Error('Invalid pagination contract: totalPages is outside the safe range.')
  }
  if (expectedTotalPages !== undefined && response.totalPages !== expectedTotalPages) {
    throw new Error('Invalid pagination contract: totalPages changed while loading the report.')
  }
  if (response.totalPages === 0 && response.content.length > 0) {
    throw new Error('Invalid pagination contract: an empty page set returned content.')
  }
}

export async function fetchAllPages<T>(
  fetchPage: (page: number, size: number) => Promise<PageResponse<T>>,
): Promise<T[]> {
  const firstPage = await fetchPage(0, REPORT_PAGE_SIZE)
  validatePage(firstPage, 0)

  const totalPages = firstPage.totalPages
  if (totalPages === 0) return []

  const content = [...firstPage.content]
  for (let page = 1; page < totalPages; page += 1) {
    const response = await fetchPage(page, REPORT_PAGE_SIZE)
    validatePage(response, page, totalPages)
    content.push(...response.content)
  }
  return content
}

function immutableCopy<T>(value: T): DeepReadonly<T> {
  if (Array.isArray(value)) {
    return Object.freeze(value.map((item) => immutableCopy(item))) as DeepReadonly<T>
  }
  if (value !== null && typeof value === 'object') {
    const clone: Record<string, unknown> = {}
    Object.entries(value).forEach(([key, item]) => {
      clone[key] = immutableCopy(item)
    })
    return Object.freeze(clone) as DeepReadonly<T>
  }
  return value as DeepReadonly<T>
}

function singaporeParts(now: Date) {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Singapore',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hourCycle: 'h23',
  }).formatToParts(now)
  const value = (type: Intl.DateTimeFormatPartTypes) => parts.find((part) => part.type === type)?.value ?? ''
  return {
    date: `${value('year')}-${value('month')}-${value('day')}`,
    localDateTime: `${value('year')}-${value('month')}-${value('day')}T${value('hour')}:${value('minute')}:${value('second')}`,
  }
}

function shiftDate(date: string, days: number) {
  const [year, month, day] = date.split('-').map(Number)
  const shifted = new Date(Date.UTC(year, month - 1, day + days))
  return `${shifted.getUTCFullYear()}-${String(shifted.getUTCMonth() + 1).padStart(2, '0')}-${String(shifted.getUTCDate()).padStart(2, '0')}`
}

function buildReportingWindow(now: Date) {
  const singaporeNow = singaporeParts(now)
  const startDate = shiftDate(singaporeNow.date, -(DASHBOARD_REPORT_PERIOD_DAYS - 1))
  return {
    startDate,
    endDate: singaporeNow.date,
    startLocalDateTime: `${startDate}T00:00:00`,
    endLocalDateTime: singaporeNow.localDateTime,
    startInstant: new Date(`${startDate}T00:00:00+08:00`).getTime(),
    endInstant: now.getTime(),
  }
}

function withinPeriod(value: string, startInstant: number, endInstant: number) {
  const instant = new Date(value).getTime()
  return Number.isFinite(instant) && instant >= startInstant && instant <= endInstant
}

function roleDistribution(users: AdminUser[]): Record<UserRole, number> {
  return users.reduce<Record<UserRole, number>>((counts, user) => {
    counts[user.role] += 1
    return counts
  }, { STUDENT: 0, ADMIN: 0, SUPER_ADMIN: 0 })
}

function bookingStatusDistribution(bookings: AdminFacilityBooking[]): Record<AdminFacilityBookingStatus, number> {
  return bookings.reduce<Record<AdminFacilityBookingStatus, number>>((counts, booking) => {
    counts[booking.status] += 1
    return counts
  }, { CONFIRMED: 0, COMPLETED: 0, CANCELLED: 0 })
}

function maintenanceStatusDistribution(
  maintenance: AdminFacilityMaintenance[],
): Record<AdminFacilityMaintenanceStatus, number> {
  return maintenance.reduce<Record<AdminFacilityMaintenanceStatus, number>>((counts, ticket) => {
    counts[ticket.status] += 1
    return counts
  }, { SUBMITTED: 0, IN_PROGRESS: 0, RESOLVED: 0, CANCELLED: 0 })
}

function reportTypeDistribution(reports: AdminLostFoundReport[]): Record<ReportType, number> {
  return reports.reduce<Record<ReportType, number>>((counts, report) => {
    counts[report.reportType] += 1
    return counts
  }, { LOST: 0, FOUND: 0 })
}

function reportStatusDistribution(reports: AdminLostFoundReport[]): Record<ReportStatus, number> {
  return reports.reduce<Record<ReportStatus, number>>((counts, report) => {
    counts[report.status] += 1
    return counts
  }, { OPEN: 0, CLAIMED: 0, CLOSED: 0 })
}

function actionDistribution(logs: AdminAuditLog[]): Record<AdministrativeAuditAction, number> {
  const counts = Object.fromEntries(ADMINISTRATIVE_AUDIT_ACTIONS.map((action) => [action, 0])) as Record<AdministrativeAuditAction, number>
  logs.forEach((log) => {
    if (ADMINISTRATIVE_AUDIT_ACTIONS.includes(log.action as AdministrativeAuditAction)) {
      counts[log.action as AdministrativeAuditAction] += 1
    }
  })
  return counts
}

function topFacilities(bookings: AdminFacilityBooking[]): DashboardReportFacilityUsage[] {
  const usage = new Map<number, DashboardReportFacilityUsage>()
  bookings.filter((booking) => ACTIVE_BOOKING_STATUSES.has(booking.status)).forEach((booking) => {
    const current = usage.get(booking.spaceId)
    if (current) {
      current.reservationCount += 1
    } else {
      usage.set(booking.spaceId, {
        facilityId: booking.spaceId,
        facilityName: booking.spaceName,
        reservationCount: 1,
      })
    }
  })
  return [...usage.values()]
    .sort((left, right) => right.reservationCount - left.reservationCount
      || left.facilityName.localeCompare(right.facilityName, 'en')
      || left.facilityId - right.facilityId)
    .slice(0, 8)
}

function safeAuditLog(log: AdminAuditLog): DashboardReportAuditLog {
  return {
    auditId: log.id,
    reportId: log.reportId,
    itemName: log.itemName,
    action: log.action as AdministrativeAuditAction,
    actorEmail: log.actorEmail,
    createdAt: log.createdAt,
  }
}

export async function loadDashboardReportSnapshot(
  generatedBy: string,
  now = new Date(),
): Promise<DashboardReportSnapshot> {
  const reportingWindow = buildReportingWindow(now)
  const [
    users,
    facilitiesOverview,
    lostFoundOverview,
    bookings,
    maintenance,
    allReports,
    allAuditLogs,
  ] = await Promise.all([
    listAdminUsers(),
    getAdminFacilitiesOverview(),
    getAdminLostFoundOverview(),
    fetchAllPages((page, size) => searchAdminFacilityBookings({
      startFrom: reportingWindow.startLocalDateTime,
      startTo: reportingWindow.endLocalDateTime,
      page,
      size,
      sort: 'startDateTime,asc',
    })),
    fetchAllPages((page, size) => searchAdminFacilityMaintenance({
      createdFrom: reportingWindow.startLocalDateTime,
      createdTo: reportingWindow.endLocalDateTime,
      page,
      size,
      sort: 'createdAt,desc',
    })),
    fetchAllPages((page, size) => searchAdminLostFoundReports({ page, size, sort: 'createdAt,desc' })),
    fetchAllPages((page, size) => searchAdminAuditLogs({ page, size, sort: 'createdAt,desc' })),
  ])

  const reports = allReports.filter((report) => withinPeriod(report.createdAt, reportingWindow.startInstant, reportingWindow.endInstant))
  const auditLogs = allAuditLogs
    .filter((log) => withinPeriod(log.createdAt, reportingWindow.startInstant, reportingWindow.endInstant))
    .filter((log) => ADMINISTRATIVE_AUDIT_ACTIONS.includes(log.action as AdministrativeAuditAction))
    .sort((left, right) => right.createdAt.localeCompare(left.createdAt) || right.id - left.id)
  const roles = roleDistribution(users)

  return immutableCopy({
    metadata: {
      title: DASHBOARD_REPORT_TITLE,
      generatedAt: now.toISOString(),
      generatedBy: generatedBy.trim() || 'Unknown administrator',
      reportingPeriod: {
        days: DASHBOARD_REPORT_PERIOD_DAYS,
        startDate: reportingWindow.startDate,
        endDate: reportingWindow.endDate,
      },
    },
    userManagement: {
      totalUsers: users.length,
      roleDistribution: roles,
      administratorCount: roles.ADMIN,
      superAdministratorCount: roles.SUPER_ADMIN,
      unavailableMetrics: ['Recent registrations are unavailable because AdminUser has no createdAt field.'],
    },
    facilities: {
      bookingsInPeriod: bookings.length,
      bookingStatusDistribution: bookingStatusDistribution(bookings),
      topFacilities: topFacilities(bookings),
      maintenanceRequestsInPeriod: maintenance.length,
      maintenanceStatusDistribution: maintenanceStatusDistribution(maintenance),
      unresolvedMaintenanceRequests: facilitiesOverview.summary.openMaintenanceRequests,
    },
    lostFound: {
      reportsInPeriod: reports.length,
      reportTypeDistribution: reportTypeDistribution(reports),
      reportStatusDistribution: reportStatusDistribution(reports),
      pendingClaims: lostFoundOverview.submittedClaims ?? 0,
      processedClaims: lostFoundOverview.processedClaims ?? 0,
      claimMetricNote: 'Pending and processed claim counts come from the safe Lost & Found overview aggregate contract.',
    },
    administration: {
      scopeNote: DASHBOARD_REPORT_AUDIT_SCOPE_NOTE,
      actionsInPeriod: auditLogs.length,
      actionDistribution: actionDistribution(auditLogs),
      recentActivity: auditLogs.slice(0, 5).map(safeAuditLog),
    },
  })
}
