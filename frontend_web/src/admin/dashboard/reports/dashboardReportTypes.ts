import type { UserRole } from '../../../api/adminUsers'
import type {
  AdminFacilityBookingStatus,
  AdminFacilityMaintenanceStatus,
  AuditAction,
  ReportStatus,
  ReportType,
} from '../../../types'

export const DASHBOARD_REPORT_TITLE = 'CampusLink Administrative Usage Report'
export const DASHBOARD_REPORT_PERIOD_DAYS = 30
export const DASHBOARD_REPORT_AUDIT_SCOPE_NOTE = 'Lost & Found administrative audit activity'

export type AdministrativeAuditAction =
  | 'REPORT_DELISTED'
  | 'REPORT_RESTORED'
  | 'REPORT_DELETED_BY_ADMIN'
  | 'CLAIM_APPROVED_BY_ADMIN'
  | 'CLAIM_REJECTED_BY_ADMIN'

export type DeepReadonly<T> =
  T extends (...args: never[]) => unknown ? T
    : T extends readonly (infer U)[] ? readonly DeepReadonly<U>[]
      : T extends object ? { readonly [K in keyof T]: DeepReadonly<T[K]> }
        : T

export interface DashboardReportAuditLog {
  auditId: number
  reportId: number
  itemName: string
  action: AdministrativeAuditAction
  actorEmail: string
  createdAt: string
}

export interface DashboardReportFacilityUsage {
  facilityId: number
  facilityName: string
  reservationCount: number
}

interface DashboardReportSnapshotData {
  metadata: {
    title: string
    generatedAt: string
    generatedBy: string
    reportingPeriod: {
      days: number
      startDate: string
      endDate: string
    }
  }
  userManagement: {
    totalUsers: number
    roleDistribution: Record<UserRole, number>
    administratorCount: number
    superAdministratorCount: number
    unavailableMetrics: string[]
  }
  facilities: {
    bookingsInPeriod: number
    bookingStatusDistribution: Record<AdminFacilityBookingStatus, number>
    topFacilities: DashboardReportFacilityUsage[]
    maintenanceRequestsInPeriod: number
    maintenanceStatusDistribution: Record<AdminFacilityMaintenanceStatus, number>
    unresolvedMaintenanceRequests: number
  }
  lostFound: {
    reportsInPeriod: number
    reportTypeDistribution: Record<ReportType, number>
    reportStatusDistribution: Record<ReportStatus, number>
    pendingClaims: number
    processedClaims: number
    claimMetricNote: string
  }
  administration: {
    scopeNote: string
    actionsInPeriod: number
    actionDistribution: Record<AdministrativeAuditAction, number>
    recentActivity: DashboardReportAuditLog[]
  }
}

export type DashboardReportSnapshot = DeepReadonly<DashboardReportSnapshotData>

// Keep the complete domain action type available to callers that need the broader contract.
export type DashboardReportDomainAuditAction = AuditAction
