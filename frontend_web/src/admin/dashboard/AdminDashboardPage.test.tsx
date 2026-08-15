import { cleanup, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { getAdminLostFoundOverview } from '../../api/adminLostFound'
import type { AdminLostFoundOverview } from '../../types'
import { TOKEN_KEY, USER_KEY } from '../../api/client'
import { AuthProvider } from '../../auth/AuthContext'
import { AdminDashboardPage } from './AdminDashboardPage'

vi.mock('../../api/adminLostFound', () => ({
  getAdminLostFoundOverview: vi.fn(),
}))

const overviewFixture: AdminLostFoundOverview = {
  totalReports: 12,
  openReports: 5,
  claimedReports: 3,
  closedReports: 4,
  lostReports: 7,
  foundReports: 5,
  submittedClaims: 2,
  hiddenReports: 0,
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
    vi.mocked(getAdminLostFoundOverview).mockResolvedValue(overviewFixture)
  })
  afterEach(() => cleanup())

  it('renders the dashboard overview with live Lost & Found data and preserved module states', async () => {
    storeSession()
    renderDashboard()

    expect(screen.getByRole('heading', { name: 'Dashboard Overview' })).toBeInTheDocument()
    expect(screen.getByText('Welcome to CampusLink Administration.')).toBeInTheDocument()
    expect(screen.getByText('Signed in as admin@nus.edu.sg')).toBeInTheDocument()
    expect(screen.getByText('Role: ADMIN')).toBeInTheDocument()

    expect(screen.getByRole('heading', { name: 'Lost & Found Overview' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Facilities Overview' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'User Management' })).not.toBeInTheDocument()
    expect(screen.queryByText('Coming Soon')).not.toBeInTheDocument()

    expect(
      await screen.findByRole('group', { name: 'Total Reports' }),
    ).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Lost & Found Overview' })).toBeInTheDocument()
    expect(screen.getByText('Total Reports')).toBeInTheDocument()
    expect(screen.getByText('Open Reports')).toBeInTheDocument()
    expect(screen.getByText('Pending Claims')).toBeInTheDocument()
    expect(screen.getByText('2 pending claims require review.')).toBeInTheDocument()
    expect(vi.mocked(getAdminLostFoundOverview)).toHaveBeenCalledTimes(1)

    expect(screen.getByRole('heading', { name: 'Recent Activity' })).toBeInTheDocument()
    expect(screen.getByText('Activity data is not available yet.')).toBeInTheDocument()
  })

  it('shows the current super administrator role', async () => {
    storeSession('SUPER_ADMIN')
    renderDashboard()

    expect(await screen.findByRole('heading', { name: 'Lost & Found Overview' })).toBeInTheDocument()
    expect(screen.getByText('Signed in as admin@nus.edu.sg')).toBeInTheDocument()
    expect(screen.getByText('Role: SUPER_ADMIN')).toBeInTheDocument()
  })

  it('renders safely when no current user is available', async () => {
    renderDashboard()

    expect(screen.getByRole('heading', { name: 'Dashboard Overview' })).toBeInTheDocument()
    expect(screen.getByText('Signed in as Unknown user')).toBeInTheDocument()
    expect(screen.getByText('Role: Unknown role')).toBeInTheDocument()
    expect(await screen.findByRole('heading', { name: 'Lost & Found Overview' })).toBeInTheDocument()
  })
})
