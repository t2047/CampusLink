import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type {
  AdminAuditLog,
  AdminFacilityBooking,
  AdminFacilityMaintenance,
  AdminLostFoundReport,
  PageResponse,
} from '../../../types'
import { fetchAllPages, loadDashboardReportSnapshot } from './dashboardReportData'

const mocks = vi.hoisted(() => ({
  listAdminUsers: vi.fn(),
  getAdminFacilitiesOverview: vi.fn(),
  getAdminLostFoundOverview: vi.fn(),
  searchBookings: vi.fn(),
  searchMaintenance: vi.fn(),
  searchReports: vi.fn(),
  searchClaims: vi.fn(),
  searchAuditLogs: vi.fn(),
  getClaimDetail: vi.fn(),
}))

vi.mock('../../../api/adminUsers', () => ({ listAdminUsers: mocks.listAdminUsers }))
vi.mock('../../../api/adminFacilities', () => ({
  getAdminFacilitiesOverview: mocks.getAdminFacilitiesOverview,
  searchAdminFacilityBookings: mocks.searchBookings,
  searchAdminFacilityMaintenance: mocks.searchMaintenance,
}))
vi.mock('../../../api/adminLostFound', () => ({
  getAdminLostFoundOverview: mocks.getAdminLostFoundOverview,
  searchAdminLostFoundReports: mocks.searchReports,
  searchAdminClaims: mocks.searchClaims,
  searchAdminAuditLogs: mocks.searchAuditLogs,
  getAdminClaimDetail: mocks.getClaimDetail,
}))

function page<T>(content: T[], pageIndex = 0, totalPages = content.length === 0 ? 0 : 1): PageResponse<T> {
  return {
    content,
    page: pageIndex,
    size: 100,
    totalElements: content.length,
    totalPages,
    first: pageIndex === 0,
    last: totalPages === 0 || pageIndex === totalPages - 1,
  }
}

const bookings: AdminFacilityBooking[] = [
  {
    bookingId: 1, userId: 10, userEmail: 'student@nus.edu.sg', spaceId: 5, spaceName: 'Seminar Room 2',
    building: 'COM3', floor: '2', roomNumber: '02-10', spaceType: 'SEMINAR_ROOM',
    startDateTime: '2026-08-16T10:00:00', endDateTime: '2026-08-16T11:00:00', status: 'CONFIRMED',
    createdAt: '2026-08-15T10:00:00', updatedAt: '2026-08-15T10:00:00',
  },
  {
    bookingId: 2, userId: 11, userEmail: 'student2@nus.edu.sg', spaceId: 5, spaceName: 'Seminar Room 2',
    building: 'COM3', floor: '2', roomNumber: '02-10', spaceType: 'SEMINAR_ROOM',
    startDateTime: '2026-08-14T10:00:00', endDateTime: '2026-08-14T11:00:00', status: 'COMPLETED',
    createdAt: '2026-08-13T10:00:00', updatedAt: '2026-08-14T11:00:00',
  },
  {
    bookingId: 3, userId: 12, userEmail: 'student3@nus.edu.sg', spaceId: 8, spaceName: 'Discussion Room 1',
    building: 'COM2', floor: '1', roomNumber: '01-08', spaceType: 'DISCUSSION_ROOM',
    startDateTime: '2026-08-12T10:00:00', endDateTime: '2026-08-12T11:00:00', status: 'CANCELLED',
    createdAt: '2026-08-11T10:00:00', updatedAt: '2026-08-11T12:00:00',
  },
]

const maintenance: AdminFacilityMaintenance[] = [
  {
    ticketId: 3, userId: 10, userEmail: 'student@nus.edu.sg', spaceId: 5, spaceName: 'Seminar Room 2',
    spaceType: 'SEMINAR_ROOM', building: 'COM3', floor: '2', roomNumber: '02-10', facilityType: 'PROJECTOR',
    description: 'Projector is not working.', priority: 'HIGH', status: 'SUBMITTED',
    createdAt: '2026-08-15T10:00:00', updatedAt: '2026-08-15T10:00:00',
  },
  {
    ticketId: 4, userId: 11, userEmail: 'student2@nus.edu.sg', spaceId: 8, spaceName: 'Discussion Room 1',
    spaceType: 'DISCUSSION_ROOM', building: 'COM2', floor: '1', roomNumber: '01-08', facilityType: 'AIR_CONDITIONING',
    description: 'Room is too warm.', priority: 'MEDIUM', status: 'RESOLVED',
    createdAt: '2026-08-10T10:00:00', updatedAt: '2026-08-11T10:00:00',
  },
]

const reports: AdminLostFoundReport[] = [
  {
    id: 12, reportType: 'FOUND', itemName: 'Black Headphones', category: 'ELECTRONICS', colour: 'Black',
    location: 'Central Library', eventDate: '2026-08-06', status: 'OPEN', adminHidden: false,
    createdByEmail: 'owner@nus.edu.sg', createdAt: '2026-08-07T03:00:00Z', updatedAt: '2026-08-07T04:00:00Z',
  },
  {
    id: 13, reportType: 'LOST', itemName: 'Blue Wallet', category: 'WALLET_PURSE', colour: 'Blue',
    location: 'COM2', eventDate: '2026-08-05', status: 'CLOSED', adminHidden: false,
    createdByEmail: 'student@nus.edu.sg', createdAt: '2026-08-06T03:00:00Z', updatedAt: '2026-08-08T04:00:00Z',
  },
]

const auditLogs: AdminAuditLog[] = [
  {
    id: 10, reportId: 12, itemName: 'Black Headphones', action: 'REPORT_CREATED',
    actorEmail: 'owner@nus.edu.sg', reason: null, detail: null, createdAt: '2026-08-15T03:00:00Z',
  },
  {
    id: 11, reportId: 12, itemName: 'Black Headphones', action: 'REPORT_CLAIMED',
    actorEmail: 'owner@nus.edu.sg', reason: null, detail: null, createdAt: '2026-08-15T02:00:00Z',
  },
  {
    id: 12, reportId: 12, itemName: 'Black Headphones', action: 'REPORT_DELISTED',
    actorEmail: 'admin@nus.edu.sg', reason: 'Duplicate report', detail: 'Internal moderation detail', createdAt: '2026-08-14T03:00:00Z',
  },
  {
    id: 13, reportId: 13, itemName: 'Blue Wallet', action: 'REPORT_RESTORED',
    actorEmail: 'admin@nus.edu.sg', reason: null, detail: null, createdAt: '2026-08-13T03:00:00Z',
  },
  {
    id: 14, reportId: 13, itemName: 'Blue Wallet', action: 'REPORT_DELETED_BY_ADMIN',
    actorEmail: 'admin@nus.edu.sg', reason: null, detail: null, createdAt: '2026-08-12T03:00:00Z',
  },
  {
    id: 15, reportId: 12, itemName: 'Black Headphones', action: 'CLAIM_APPROVED_BY_ADMIN',
    actorEmail: 'admin@nus.edu.sg', reason: null, detail: null, createdAt: '2026-08-11T03:00:00Z',
  },
  {
    id: 16, reportId: 13, itemName: 'Blue Wallet', action: 'CLAIM_REJECTED_BY_ADMIN',
    actorEmail: 'admin@nus.edu.sg', reason: null, detail: null, createdAt: '2026-08-10T03:00:00Z',
  },
]

function setSuccessfulDefaults() {
  mocks.listAdminUsers.mockResolvedValue([
    { id: 1, email: 'student@nus.edu.sg', role: 'STUDENT' },
    { id: 2, email: 'admin@nus.edu.sg', role: 'ADMIN' },
    { id: 3, email: 'super@nus.edu.sg', role: 'SUPER_ADMIN' },
    { id: 4, email: 'student2@nus.edu.sg', role: 'STUDENT' },
  ])
  mocks.getAdminFacilitiesOverview.mockResolvedValue({
    summary: {
      totalSpaces: 2, availableSpaces: 2, outOfServiceSpaces: 0, inactiveSpaces: 0,
      totalBookings: 3, confirmedBookings: 1, cancelledBookings: 1, completedBookings: 1,
      totalMaintenanceRequests: 4, submittedMaintenanceRequests: 1, inProgressMaintenanceRequests: 1,
      resolvedMaintenanceRequests: 1, cancelledMaintenanceRequests: 1, openMaintenanceRequests: 2,
    },
    spaceStatusBreakdown: [], bookingStatusBreakdown: [], maintenanceStatusBreakdown: [],
  })
  mocks.getAdminLostFoundOverview.mockResolvedValue({
    totalReports: 2, openReports: 1, claimedReports: 0, closedReports: 1, lostReports: 1,
    foundReports: 1, submittedClaims: 1, processedClaims: 2, hiddenReports: 0,
  })
  mocks.searchBookings.mockResolvedValue(page(bookings))
  mocks.searchMaintenance.mockResolvedValue(page(maintenance))
  mocks.searchReports.mockResolvedValue(page(reports))
  mocks.searchClaims.mockResolvedValue(page([]))
  mocks.searchAuditLogs.mockResolvedValue(page(auditLogs))
}

describe('fetchAllPages', () => {
  it('loads all pages sequentially and preserves backend order', async () => {
    const fetchPage = vi.fn()
      .mockResolvedValueOnce(page(['a', 'b'], 0, 3))
      .mockResolvedValueOnce(page(['c'], 1, 3))
      .mockResolvedValueOnce(page(['d'], 2, 3))

    await expect(fetchAllPages(fetchPage)).resolves.toEqual(['a', 'b', 'c', 'd'])
    expect(fetchPage.mock.calls).toEqual([[0, 100], [1, 100], [2, 100]])
  })

  it('returns an empty array for an empty page set', async () => {
    await expect(fetchAllPages(vi.fn().mockResolvedValue(page([], 0, 0)))).resolves.toEqual([])
  })
})

describe('loadDashboardReportSnapshot', () => {
  beforeEach(() => {
    Object.values(mocks).forEach((mock) => mock.mockReset())
    setSuccessfulDefaults()
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-08-16T04:30:00Z'))
  })

  afterEach(() => vi.useRealTimers())

  it('uses the safe Lost & Found overview aggregates and filters only administrator audit actions', async () => {
    const snapshot = await loadDashboardReportSnapshot('admin@nus.edu.sg')

    expect(mocks.getAdminLostFoundOverview).toHaveBeenCalledTimes(1)
    expect(mocks.searchClaims).not.toHaveBeenCalled()
    expect(mocks.getClaimDetail).not.toHaveBeenCalled()
    expect(snapshot.lostFound.pendingClaims).toBe(1)
    expect(snapshot.lostFound.processedClaims).toBe(2)
    expect(snapshot.administration.scopeNote).toBe('Lost & Found administrative audit activity')
    expect(snapshot.administration.actionsInPeriod).toBe(5)
    expect(snapshot.administration.actionDistribution).toMatchObject({
      REPORT_DELISTED: 1,
      REPORT_RESTORED: 1,
      REPORT_DELETED_BY_ADMIN: 1,
      CLAIM_APPROVED_BY_ADMIN: 1,
      CLAIM_REJECTED_BY_ADMIN: 1,
    })
    expect(snapshot.administration.actionDistribution).not.toHaveProperty('REPORT_CREATED')
    expect(snapshot.administration.actionDistribution).not.toHaveProperty('REPORT_CLAIMED')
    expect(snapshot.administration.recentActivity.map(({ action }) => action)).toEqual([
      'REPORT_DELISTED', 'REPORT_RESTORED', 'REPORT_DELETED_BY_ADMIN', 'CLAIM_APPROVED_BY_ADMIN', 'CLAIM_REJECTED_BY_ADMIN',
    ])
  })

  it('builds the existing 30-day usage aggregates and immutable safe snapshot', async () => {
    const snapshot = await loadDashboardReportSnapshot('admin@nus.edu.sg')

    expect(snapshot.metadata).toEqual({
      title: 'CampusLink Administrative Usage Report',
      generatedAt: '2026-08-16T04:30:00.000Z',
      generatedBy: 'admin@nus.edu.sg',
      reportingPeriod: { days: 30, startDate: '2026-07-18', endDate: '2026-08-16' },
    })
    expect(mocks.searchBookings).toHaveBeenCalledWith({
      startFrom: '2026-07-18T00:00:00', startTo: '2026-08-16T12:30:00', page: 0, size: 100, sort: 'startDateTime,asc',
    })
    expect(mocks.searchMaintenance).toHaveBeenCalledWith({
      createdFrom: '2026-07-18T00:00:00', createdTo: '2026-08-16T12:30:00', page: 0, size: 100, sort: 'createdAt,desc',
    })
    expect(snapshot.userManagement.totalUsers).toBe(4)
    expect(snapshot.facilities.bookingsInPeriod).toBe(3)
    expect(snapshot.lostFound.reportsInPeriod).toBe(2)
    expect(Object.isFrozen(snapshot)).toBe(true)
    expect(Object.isFrozen(snapshot.administration.recentActivity)).toBe(true)
    expect(JSON.stringify(snapshot)).not.toContain('proofSummary')
    expect(JSON.stringify(snapshot)).not.toContain('decisionNote')
    expect(JSON.stringify(snapshot)).not.toContain('proofDescription')
    expect(JSON.stringify(snapshot)).not.toContain('claimant')
    expect(JSON.stringify(snapshot)).not.toContain('reportOwner')
  })

  it('returns stable zero aggregates for empty data', async () => {
    mocks.listAdminUsers.mockResolvedValue([])
    mocks.getAdminFacilitiesOverview.mockResolvedValue({ summary: { openMaintenanceRequests: 0 } })
    mocks.getAdminLostFoundOverview.mockResolvedValue({ submittedClaims: 0, processedClaims: 0 })
    mocks.searchBookings.mockResolvedValue(page([]))
    mocks.searchMaintenance.mockResolvedValue(page([]))
    mocks.searchReports.mockResolvedValue(page([]))
    mocks.searchAuditLogs.mockResolvedValue(page([]))

    const snapshot = await loadDashboardReportSnapshot('  ')
    expect(snapshot.metadata.generatedBy).toBe('Unknown administrator')
    expect(snapshot.lostFound.pendingClaims).toBe(0)
    expect(snapshot.lostFound.processedClaims).toBe(0)
    expect(snapshot.administration.actionsInPeriod).toBe(0)
    expect(snapshot.administration.recentActivity).toEqual([])
  })

  it('loads every report and rejects the whole report when a required source fails', async () => {
    const snapshot = await loadDashboardReportSnapshot('admin@nus.edu.sg')
    expect(snapshot.lostFound.reportsInPeriod).toBe(2)
    expect(mocks.searchClaims).not.toHaveBeenCalled()

    mocks.searchAuditLogs.mockRejectedValue(new Error('Audit unavailable'))
    await expect(loadDashboardReportSnapshot('admin@nus.edu.sg')).rejects.toThrow('Audit unavailable')
  })
})
