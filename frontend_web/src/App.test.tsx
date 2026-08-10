import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, useLocation, useNavigate } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import { TOKEN_KEY, USER_KEY } from './api/client'
import { AuthProvider } from './auth/AuthContext'

vi.mock('./pages/ReportsPage', () => ({ ReportsPage: () => <p>Lost and Found page</p> }))

const adminApiMocks = vi.hoisted(() => ({
  getClaimDetail: vi.fn(),
  approveClaim: vi.fn(),
  rejectClaim: vi.fn(),
}))

const claimDetail = {
  id: 42,
  status: 'SUBMITTED',
  proofDescription: 'Serial number and a scratch on the left hinge.',
  decisionNote: null,
  claimant: { id: 7, email: 'claimant@nus.edu.sg', role: 'STUDENT' },
  report: {
    id: 12,
    reportType: 'FOUND',
    itemName: 'Black Headphones',
    category: 'ELECTRONICS',
    description: 'Headphones in a black hard-shell case.',
    colour: 'Black',
    location: 'Central Library',
    eventDate: '2026-08-07',
    timeDescription: 'Around noon',
    status: 'OPEN',
    adminHidden: false,
    owner: { id: 8, email: 'owner@nus.edu.sg' },
    images: [],
  },
  review: { reviewed: false, decisionNote: null, reviewedAt: null },
  createdAt: '2026-08-07T03:00:00Z',
  updatedAt: '2026-08-07T03:00:00Z',
}
vi.mock('./api/facilities', () => ({
  facilitiesApi: {
    getDashboard: vi.fn().mockResolvedValue({
      summary: { totalFacilities: 5, availableFacilities: 3, todayReservations: 2, underMaintenance: 1 },
      statusBreakdown: [{ status: 'AVAILABLE', count: 3 }],
      reservationTrend: [],
      facilityUsage: [],
    }),
    getReservations: vi.fn().mockResolvedValue([]),
    getMaintenance: vi.fn().mockResolvedValue([]),
    getMaintenanceDetail: vi.fn(),
    updateMaintenance: vi.fn(),
  },
}))
vi.mock('./api/adminLostFound', () => ({
  getAdminLostFoundOverview: vi.fn().mockResolvedValue({
    totalReports: 0,
    openReports: 0,
    claimedReports: 0,
    closedReports: 0,
    lostReports: 0,
    foundReports: 0,
    submittedClaims: 0,
  }),
  searchAdminLostFoundReports: vi.fn().mockResolvedValue({
    content: [],
    page: 0,
    size: 25,
    totalElements: 0,
    totalPages: 0,
    first: true,
    last: true,
  }),
  getAdminClaimDetail: adminApiMocks.getClaimDetail,
  approveAdminClaim: adminApiMocks.approveClaim,
  rejectAdminClaim: adminApiMocks.rejectClaim,
}))

function setDesktopViewport() {
  Object.defineProperty(window, 'matchMedia', {
    configurable: true,
    writable: true,
    value: vi.fn().mockImplementation((query: string) => ({
      matches: true,
      media: query,
      onchange: null,
      addListener: vi.fn(),
      removeListener: vi.fn(),
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      dispatchEvent: vi.fn(),
    })),
  })
}

function storeSession(role: string) {
  sessionStorage.setItem(TOKEN_KEY, 'token')
  sessionStorage.setItem(USER_KEY, JSON.stringify({ email: 'admin@nus.edu.sg', role }))
}

function NavigationProbe() {
  const location = useLocation()
  const navigate = useNavigate()

  return (
    <>
      <output aria-label="Current path">{location.pathname}</output>
      <button type="button" onClick={() => navigate(-1)}>Back</button>
    </>
  )
}

function renderApp(path: string) {
  return render(
    <MemoryRouter initialEntries={['/lost-found', path]} initialIndex={1}>
      <NavigationProbe />
      <AuthProvider>
        <App />
      </AuthProvider>
    </MemoryRouter>,
  )
}

describe('admin application routes', () => {
  beforeEach(() => {
    sessionStorage.clear()
    vi.clearAllMocks()
    adminApiMocks.getClaimDetail.mockResolvedValue(claimDetail)
    setDesktopViewport()
  })
  afterEach(() => cleanup())

  it('redirects an administrator from /admin to the dashboard', async () => {
    storeSession('ADMIN')
    renderApp('/admin')

    expect(await screen.findByRole('heading', { name: 'Dashboard Overview' })).toBeInTheDocument()
    expect(screen.getByText('CampusLink Administration')).toBeInTheDocument()
    expect(screen.getByLabelText('Current path')).toHaveTextContent('/admin/dashboard')
    expect(screen.getByRole('link', { name: 'Overview' })).toHaveAttribute('aria-current', 'page')
    expect(screen.getByText('admin@nus.edu.sg')).toBeInTheDocument()
    expect(screen.getByText('ADMIN')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Sign Out' })).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Browse' })).not.toBeInTheDocument()
    expect(screen.queryByText('Report')).not.toBeInTheDocument()
    expect(screen.queryByText('Claims')).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Back' }))
    expect(await screen.findByText('Lost and Found page')).toBeInTheDocument()
    expect(screen.getByLabelText('Current path')).toHaveTextContent('/lost-found')
  })


  it.each([
    ['/admin/users', 'User Management', 'The scope of this module is pending team confirmation.'],
  ])('renders the %s administrator placeholder', async (path, heading, description) => {
    storeSession('ADMIN')
    renderApp(path)

    expect(await screen.findByRole('heading', { name: heading })).toBeInTheDocument()
    expect(screen.getByText(description)).toBeInTheDocument()
    expect(screen.getByText('Coming Soon')).toBeInTheDocument()
    expect(screen.getByText('CampusLink Administration')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: heading })).toHaveAttribute('aria-current', 'page')
    expect(screen.getByRole('link', { name: 'Return to Overview' })).toHaveAttribute('href', '/admin/dashboard')
  })

  it('renders the Facilities dashboard at the facilities route', async () => {
    storeSession('ADMIN')
    renderApp('/admin/facilities')

    expect(await screen.findByRole('heading', { name: 'Facilities Dashboard' })).toBeInTheDocument()
    expect(await screen.findByText('Total Facilities')).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: 'Reservations' })).toHaveAttribute('href', '/admin/facilities/reservations')
    expect(screen.getByRole('tab', { name: 'Maintenance' })).toHaveAttribute('href', '/admin/facilities/maintenance')
  })

  it('renders the administrator Lost & Found page at the lost-found route', async () => {
    storeSession('ADMIN')
    renderApp('/admin/lost-found')

    expect(await screen.findByRole('heading', { name: 'Lost & Found' })).toBeInTheDocument()
    expect(await screen.findByText('No reports found')).toBeInTheDocument()
  })


  it.each([
    ['Lost & Found', '/admin/lost-found', 'Lost & Found'],
    ['Facilities', '/admin/facilities', 'Facilities Dashboard'],
    ['User Management', '/admin/users', 'User Management'],
  ])('navigates from the %s dashboard card to its page', async (label, path, heading) => {
    storeSession('ADMIN')
    renderApp('/admin/dashboard')

    expect(await screen.findByRole('heading', { name: 'Dashboard Overview' })).toBeInTheDocument()
    fireEvent.click(within(screen.getByRole('main')).getByRole('link', { name: label }))

    expect(await screen.findByRole('heading', { name: heading })).toBeInTheDocument()
    expect(screen.getByLabelText('Current path')).toHaveTextContent(path)
    expect(screen.getByRole('link', { name: label })).toHaveAttribute('aria-current', 'page')
  })

  it('navigates from the Facilities dashboard card to the Facilities dashboard', async () => {
    storeSession('ADMIN')
    renderApp('/admin/dashboard')

    fireEvent.click(within(screen.getByRole('main')).getByRole('link', { name: 'Facilities' }))

    expect(await screen.findByRole('heading', { name: 'Facilities Dashboard' })).toBeInTheDocument()
    expect(screen.getByLabelText('Current path')).toHaveTextContent('/admin/facilities')
  })

  it('navigates from the dashboard to the Lost & Found administration page', async () => {
    storeSession('ADMIN')
    renderApp('/admin/dashboard')

    expect(await screen.findByRole('heading', { name: 'Dashboard Overview' })).toBeInTheDocument()
    fireEvent.click(within(screen.getByRole('main')).getByRole('link', { name: 'Lost & Found' }))

    await waitFor(() => expect(screen.getByLabelText('Current path')).toHaveTextContent('/admin/lost-found'))
    expect(await screen.findByRole('heading', { name: 'Lost & Found' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Lost & Found' })).toHaveAttribute('aria-current', 'page')
  })

  it.each(['ADMIN', 'SUPER_ADMIN'])('renders the Claim detail route inside the Admin Layout for %s', async (role) => {
    storeSession(role)
    renderApp('/admin/lost-found/claims/42')

    expect(await screen.findByRole('heading', { name: 'Claim #42' })).toBeInTheDocument()
    expect(screen.getByText('CampusLink Administration')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Lost & Found' })).toHaveAttribute('aria-current', 'page')
    expect(screen.getByLabelText('Current path')).toHaveTextContent('/admin/lost-found/claims/42')
  })

  it('keeps a student out of the Claim detail route', async () => {
    storeSession('STUDENT')
    renderApp('/admin/lost-found/claims/42')

    expect(await screen.findByRole('heading', { name: 'Access Denied' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Claim #42' })).not.toBeInTheDocument()
    expect(screen.queryByText('CampusLink Administration')).not.toBeInTheDocument()
  })

  it('redirects an anonymous Claim detail request to login first', async () => {
    renderApp('/admin/lost-found/claims/42')

    expect(await screen.findByRole('heading', { name: 'Welcome back' })).toBeInTheDocument()
    expect(screen.getByLabelText('Current path')).toHaveTextContent('/login')
    expect(screen.queryByRole('heading', { name: 'Claim #42' })).not.toBeInTheDocument()
  })

  it('renders the administration not-found page after the admin access boundary', async () => {
    storeSession('ADMIN')
    renderApp('/admin/unknown')

    expect(await screen.findByRole('heading', { name: 'Page Not Found' })).toBeInTheDocument()
    expect(screen.getByText('The administration page you requested does not exist or is no longer available.')).toBeInTheDocument()
    expect(screen.getByText('CampusLink Administration')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Return to Overview' })).toHaveAttribute('href', '/admin/dashboard')
  })

  it('keeps a student out of the administration not-found page', async () => {
    storeSession('STUDENT')
    renderApp('/admin/unknown')

    expect(await screen.findByRole('heading', { name: 'Access Denied' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Page Not Found' })).not.toBeInTheDocument()
    expect(screen.queryByText('CampusLink Administration')).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Overview' })).not.toBeInTheDocument()
  })

  it('redirects an anonymous unknown administration route to login first', async () => {
    renderApp('/admin/unknown')

    expect(await screen.findByRole('heading', { name: 'Welcome back' })).toBeInTheDocument()
    expect(screen.getByLabelText('Current path')).toHaveTextContent('/login')
    expect(screen.queryByRole('heading', { name: 'Page Not Found' })).not.toBeInTheDocument()
    expect(screen.queryByText('CampusLink Administration')).not.toBeInTheDocument()
  })

  it('keeps a student out of the admin dashboard and admin navigation', async () => {
    storeSession('STUDENT')
    renderApp('/admin/dashboard')

    expect(await screen.findByRole('heading', { name: 'Access Denied' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Dashboard Overview' })).not.toBeInTheDocument()
    expect(screen.queryByText('CampusLink Administration')).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Overview' })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Browse' })).not.toBeInTheDocument()
    expect(screen.queryByText('Claims')).not.toBeInTheDocument()
  })
})
