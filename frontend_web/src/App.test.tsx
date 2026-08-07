import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter, useLocation, useNavigate } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import { TOKEN_KEY, USER_KEY } from './api/client'
import { AuthProvider } from './auth/AuthContext'

vi.mock('./pages/ReportsPage', () => ({ ReportsPage: () => <p>Lost and Found page</p> }))

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
    <MemoryRouter initialEntries={['/origin', path]} initialIndex={1}>
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
    ['/admin/lost-found', 'Lost & Found', 'Claim review and administration features are coming soon.'],
    ['/admin/facilities', 'Facilities', 'Facilities administration will be available here.'],
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


  it.each([
    ['Lost & Found', '/admin/lost-found'],
    ['Facilities', '/admin/facilities'],
    ['User Management', '/admin/users'],
  ])('navigates from the %s dashboard card to its placeholder', async (label, path) => {
    storeSession('ADMIN')
    renderApp('/admin/dashboard')

    expect(await screen.findByRole('heading', { name: 'Dashboard Overview' })).toBeInTheDocument()
    fireEvent.click(within(screen.getByRole('main')).getByRole('link', { name: label }))

    expect(await screen.findByRole('heading', { name: label })).toBeInTheDocument()
    expect(screen.getByLabelText('Current path')).toHaveTextContent(path)
    expect(screen.getByRole('link', { name: label })).toHaveAttribute('aria-current', 'page')
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
