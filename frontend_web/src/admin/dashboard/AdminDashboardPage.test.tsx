import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { getAdminFacilitiesOverview, searchAdminFacilityBookings, searchAdminFacilityMaintenance } from '../../api/adminFacilities'
import {
  getAdminLostFoundOverview,
  searchAdminAuditLogs,
  searchAdminClaims,
  searchAdminLostFoundReports,
} from '../../api/adminLostFound'
import { listAdminUsers } from '../../api/adminUsers'
import type {
  AdminFacilitiesOverview,
  AdminFacilityBooking,
  AdminLostFoundOverview,
  PageResponse,
} from '../../types'
import { TOKEN_KEY, USER_KEY } from '../../api/client'
import { AuthProvider } from '../../auth/AuthContext'
import { AdminDashboardPage } from './AdminDashboardPage'

vi.mock('../../api/adminFacilities', () => ({
  getAdminFacilitiesOverview: vi.fn(),
  searchAdminFacilityBookings: vi.fn(),
  searchAdminFacilityMaintenance: vi.fn(),
}))

vi.mock('../../api/adminLostFound', () => ({
  getAdminLostFoundOverview: vi.fn(),
  searchAdminClaims: vi.fn(),
  searchAdminLostFoundReports: vi.fn(),
  searchAdminAuditLogs: vi.fn(),
}))

vi.mock('../../api/adminUsers', () => ({
  listAdminUsers: vi.fn(),
}))

vi.mock('./reports/DashboardReportDialog', () => ({
  DashboardReportDialog: ({ open, generatedBy, onClose }: { open: boolean; generatedBy: string; onClose: () => void }) => open ? (
    <div role="dialog" aria-label="Administrative Usage Report Preview">
      <span>{generatedBy}</span>
      <button onClick={onClose}>Close</button>
    </div>
  ) : null,
}))


const lostFoundOverviewFixture: AdminLostFoundOverview = {
  totalReports: 12,
  openReports: 5,
  claimedReports: 3,
  closedReports: 4,
  lostReports: 7,
  foundReports: 5,
  submittedClaims: 2,
  processedClaims: 0,
  hiddenReports: 0,
}

const facilitiesOverviewFixture: AdminFacilitiesOverview = {
  summary: {
    totalSpaces: 12,
    availableSpaces: 8,
    outOfServiceSpaces: 3,
    inactiveSpaces: 1,
    totalBookings: 20,
    confirmedBookings: 9,
    cancelledBookings: 4,
    completedBookings: 7,
    totalMaintenanceRequests: 14,
    submittedMaintenanceRequests: 5,
    inProgressMaintenanceRequests: 3,
    resolvedMaintenanceRequests: 4,
    cancelledMaintenanceRequests: 2,
    openMaintenanceRequests: 8,
  },
  spaceStatusBreakdown: [
    { status: 'AVAILABLE', count: 8 },
    { status: 'OUT_OF_SERVICE', count: 3 },
    { status: 'INACTIVE', count: 1 },
  ],
  bookingStatusBreakdown: [
    { status: 'CONFIRMED', count: 9 },
    { status: 'CANCELLED', count: 4 },
    { status: 'COMPLETED', count: 7 },
  ],
  maintenanceStatusBreakdown: [
    { status: 'SUBMITTED', count: 5 },
    { status: 'IN_PROGRESS', count: 3 },
    { status: 'RESOLVED', count: 4 },
    { status: 'CANCELLED', count: 2 },
  ],
}

const bookingFixture: AdminFacilityBooking = {
  bookingId: 1,
  userId: 10,
  userEmail: 'student@nus.edu.sg',
  spaceId: 5,
  spaceName: 'Seminar Room 2',
  building: 'COM3',
  floor: '2',
  roomNumber: '02-10',
  spaceType: 'SEMINAR_ROOM',
  startDateTime: '2026-08-16T10:00:00',
  endDateTime: '2026-08-16T11:00:00',
  status: 'CONFIRMED',
  createdAt: '2026-08-15T10:00:00',
  updatedAt: '2026-08-15T10:00:00',
}

const bookingsPage: PageResponse<AdminFacilityBooking> = {
  content: [bookingFixture],
  page: 0,
  size: 5,
  totalElements: 1,
  totalPages: 1,
  first: true,
  last: true,
}

function storeSession(role = 'ADMIN') {
  sessionStorage.setItem(TOKEN_KEY, 'token')
  sessionStorage.setItem(USER_KEY, JSON.stringify({ email: 'admin@nus.edu.sg', role }))
}

function renderDashboard() {
  return render(
    <MemoryRouter>
      <AuthProvider>
        <AdminDashboardPage />
      </AuthProvider>
    </MemoryRouter>,
  )
}

describe('AdminDashboardPage', () => {
  beforeEach(() => {
    sessionStorage.clear()
    vi.mocked(getAdminLostFoundOverview).mockReset()
    vi.mocked(getAdminLostFoundOverview).mockResolvedValue(lostFoundOverviewFixture)
    vi.mocked(searchAdminClaims).mockReset()
    vi.mocked(searchAdminClaims).mockResolvedValue({
      content: [],
      page: 0,
      size: 5,
      totalElements: 0,
      totalPages: 0,
      first: true,
      last: true,
    })
    vi.mocked(searchAdminLostFoundReports).mockReset()
    vi.mocked(searchAdminLostFoundReports).mockResolvedValue({
      content: [], page: 0, size: 5, totalElements: 0, totalPages: 0, first: true, last: true,
    })
    vi.mocked(searchAdminAuditLogs).mockReset()
    vi.mocked(searchAdminAuditLogs).mockResolvedValue({
      content: [], page: 0, size: 5, totalElements: 0, totalPages: 0, first: true, last: true,
    })
    vi.mocked(getAdminFacilitiesOverview).mockReset()
    vi.mocked(getAdminFacilitiesOverview).mockResolvedValue(facilitiesOverviewFixture)
    vi.mocked(searchAdminFacilityBookings).mockReset()
    vi.mocked(searchAdminFacilityBookings).mockResolvedValue(bookingsPage)
    vi.mocked(searchAdminFacilityMaintenance).mockReset()
    vi.mocked(searchAdminFacilityMaintenance).mockResolvedValue({
      content: [],
      page: 0,
      size: 5,
      totalElements: 0,
      totalPages: 0,
      first: true,
      last: true,
    })
    vi.mocked(listAdminUsers).mockReset()
    vi.mocked(listAdminUsers).mockResolvedValue([
      { id: 1, email: 'student@nus.edu.sg', role: 'STUDENT' },
      { id: 2, email: 'admin@nus.edu.sg', role: 'ADMIN' },
      { id: 3, email: 'super.admin@nus.edu.sg', role: 'SUPER_ADMIN' },
    ])
  })
  afterEach(() => cleanup())

  it('renders available module cards and all dashboard overview sections', async () => {
    storeSession()
    renderDashboard()

    expect(screen.getByRole('heading', { name: 'Dashboard Overview' })).toBeInTheDocument()
    expect(screen.getByText('Welcome to CampusLink Administration.')).toBeInTheDocument()
    expect(screen.getByText('Signed in as admin@nus.edu.sg')).toBeInTheDocument()
    expect(screen.getByText('Role: ADMIN')).toBeInTheDocument()

    const lostFoundLink = screen.getByRole('link', { name: 'Lost & Found' })
    const facilitiesLink = screen.getByRole('link', { name: 'Facilities' })
    const usersLink = screen.getByRole('link', { name: 'User Management' })

    expect(lostFoundLink).toHaveAttribute('href', '/admin/lost-found')
    expect(facilitiesLink).toHaveAttribute('href', '/admin/facilities')
    expect(usersLink).toHaveAttribute('href', '/admin/users')
    expect(within(lostFoundLink).getByText('Available')).toBeInTheDocument()
    expect(within(facilitiesLink).getByText('Available')).toBeInTheDocument()
    expect(within(usersLink).getByText('Available')).toBeInTheDocument()
    expect(screen.queryByText('Coming Soon')).not.toBeInTheDocument()

    expect(screen.getByText('Review report volume, status metrics, and operational records.')).toBeInTheDocument()
    expect(screen.getByText('Monitor campus facilities, reservations, availability, and maintenance requests.')).toBeInTheDocument()
    expect(screen.getByText('View system users, monitor role distribution, and manage account roles where authorized.')).toBeInTheDocument()

    expect(await screen.findByRole('group', { name: 'Total Reports' })).toBeInTheDocument()
    expect(await screen.findByRole('group', { name: 'Total Facilities' })).toBeInTheDocument()
    expect(await screen.findByRole('table', { name: 'Upcoming reservations' })).toBeInTheDocument()
    const groupHeadings = [
      screen.getByRole('heading', { name: 'System Summary', level: 2 }),
      screen.getByRole('heading', { name: 'User Management', level: 2 }),
      screen.getByRole('heading', { name: 'Facilities', level: 2 }),
      screen.getByRole('heading', { name: 'Lost & Found', level: 2 }),
      screen.getByRole('heading', { name: 'Administration', level: 2 }),
    ]
    groupHeadings.slice(0, -1).forEach((heading, index) => {
      expect(heading.compareDocumentPosition(groupHeadings[index + 1]) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    })

    const userGroup = screen.getByRole('region', { name: 'User Management' })
    expect(within(userGroup).getByRole('heading', { name: 'User Management Overview' })).toBeInTheDocument()

    const facilitiesGroup = screen.getByRole('region', { name: 'Facilities' })
    const facilitiesHeadings = [
      within(facilitiesGroup).getByRole('heading', { name: 'Facilities Overview' }),
      within(facilitiesGroup).getByRole('heading', { name: 'Upcoming Reservations' }),
      within(facilitiesGroup).getByRole('heading', { name: 'Open Maintenance' }),
    ]
    facilitiesHeadings.slice(0, -1).forEach((heading, index) => {
      expect(heading.compareDocumentPosition(facilitiesHeadings[index + 1]) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    })

    const lostFoundGroup = screen.getByRole('region', { name: 'Lost & Found' })
    const lostFoundHeadings = [
      within(lostFoundGroup).getByRole('heading', { name: 'Lost & Found Overview' }),
      within(lostFoundGroup).getByRole('heading', { name: 'Pending Claims' }),
      within(lostFoundGroup).getByRole('heading', { name: 'Recent Lost & Found Reports' }),
    ]
    lostFoundHeadings.slice(0, -1).forEach((heading, index) => {
      expect(heading.compareDocumentPosition(lostFoundHeadings[index + 1]) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    })
    expect(within(lostFoundGroup).getByRole('heading', { name: 'Recent Lost & Found Reports' })).toBeInTheDocument()
    expect(within(screen.getByRole('region', { name: 'Administration' })).getByRole('heading', { name: 'Recent Administrative Activity' })).toBeInTheDocument()
    expect(screen.getByText('No pending claims require review.')).toBeInTheDocument()
    expect(screen.getByText('Seminar Room 2')).toBeInTheDocument()
    expect(vi.mocked(getAdminLostFoundOverview)).toHaveBeenCalledTimes(1)
    expect(vi.mocked(searchAdminClaims)).toHaveBeenCalledWith({
      status: 'SUBMITTED',
      page: 0,
      size: 5,
      sort: 'createdAt,desc',
    })
    expect(vi.mocked(searchAdminLostFoundReports)).toHaveBeenCalledWith({ page: 0, size: 5, sort: 'createdAt,desc' })
    expect(vi.mocked(searchAdminAuditLogs)).toHaveBeenCalledWith({ page: 0, size: 5, sort: 'createdAt,desc' })
    expect(vi.mocked(getAdminFacilitiesOverview)).toHaveBeenCalledTimes(1)
    expect(vi.mocked(listAdminUsers)).toHaveBeenCalledTimes(1)
    expect(vi.mocked(searchAdminFacilityBookings)).toHaveBeenCalledTimes(1)
    expect(vi.mocked(searchAdminFacilityMaintenance)).toHaveBeenCalledTimes(1)

    expect(screen.queryByText('Claim review actions are not available in this read-only dashboard yet.')).not.toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Recent Activity' })).not.toBeInTheDocument()
    expect(screen.queryByText('Activity data is not available yet.')).not.toBeInTheDocument()
  })

  it('opens and closes the Administrative Usage Report only after Generate Usage Report is clicked', () => {
    storeSession()
    renderDashboard()

    expect(screen.queryByRole('dialog', { name: 'Administrative Usage Report Preview' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Generate Usage Report' }))
    expect(screen.getByRole('dialog', { name: 'Administrative Usage Report Preview' })).toBeInTheDocument()
    expect(screen.getByRole('dialog')).toHaveTextContent('admin@nus.edu.sg')
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Close' }))
    expect(screen.queryByRole('dialog', { name: 'Administrative Usage Report Preview' })).not.toBeInTheDocument()
  })

  it('shows the current super administrator role', async () => {
    storeSession('SUPER_ADMIN')
    renderDashboard()

    expect(await screen.findByRole('heading', { name: 'Lost & Found Overview' })).toBeInTheDocument()
    expect(await screen.findByRole('heading', { name: 'Pending Claims' })).toBeInTheDocument()
    expect(await screen.findByRole('heading', { name: 'Facilities Overview' })).toBeInTheDocument()
    expect(await screen.findByRole('heading', { name: 'User Management Overview' })).toBeInTheDocument()
    expect(await screen.findByRole('heading', { name: 'Upcoming Reservations' })).toBeInTheDocument()
    expect(await screen.findByRole('heading', { name: 'Open Maintenance' })).toBeInTheDocument()
    expect(await screen.findByRole('heading', { name: 'Recent Lost & Found Reports' })).toBeInTheDocument()
    expect(await screen.findByRole('heading', { name: 'Recent Administrative Activity' })).toBeInTheDocument()
    expect(screen.getByText('Signed in as admin@nus.edu.sg')).toBeInTheDocument()
    expect(screen.getByText('Role: SUPER_ADMIN')).toBeInTheDocument()
  })

  it('renders safely when no current user is available', async () => {
    renderDashboard()

    expect(screen.getByRole('heading', { name: 'Dashboard Overview' })).toBeInTheDocument()
    expect(screen.getByText('Signed in as Unknown user')).toBeInTheDocument()
    expect(screen.getByText('Role: Unknown role')).toBeInTheDocument()
    expect(await screen.findByRole('heading', { name: 'Lost & Found Overview' })).toBeInTheDocument()
    expect(await screen.findByRole('heading', { name: 'Pending Claims' })).toBeInTheDocument()
    expect(await screen.findByRole('heading', { name: 'Facilities Overview' })).toBeInTheDocument()
    expect(await screen.findByRole('heading', { name: 'User Management Overview' })).toBeInTheDocument()
    expect(await screen.findByRole('heading', { name: 'Upcoming Reservations' })).toBeInTheDocument()
    expect(await screen.findByRole('heading', { name: 'Open Maintenance' })).toBeInTheDocument()
    expect(await screen.findByRole('heading', { name: 'Recent Lost & Found Reports' })).toBeInTheDocument()
    expect(await screen.findByRole('heading', { name: 'Recent Administrative Activity' })).toBeInTheDocument()
  })
})
