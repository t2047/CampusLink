import { cleanup, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { TOKEN_KEY, USER_KEY } from '../../api/client'
import { AuthProvider } from '../../auth/AuthContext'
import { AdminDashboardPage } from './AdminDashboardPage'

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
  beforeEach(() => sessionStorage.clear())
  afterEach(() => cleanup())

  it('renders the dashboard overview with unavailable and empty states', () => {
    storeSession()
    renderDashboard()

    expect(screen.getByRole('heading', { name: 'Dashboard Overview' })).toBeInTheDocument()
    expect(screen.getByText('Welcome to CampusLink Administration.')).toBeInTheDocument()
    expect(screen.getByText('Signed in as admin@nus.edu.sg')).toBeInTheDocument()
    expect(screen.getByText('Role: ADMIN')).toBeInTheDocument()

    expect(screen.getByRole('heading', { name: 'Lost & Found' })).toBeInTheDocument()
    expect(screen.getByText('Review report volume, status metrics, and operational records.')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Facilities' })).toBeInTheDocument()
    expect(screen.getByText('Facilities administration will be available here.')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'User Management' })).toBeInTheDocument()
    expect(screen.getByText('The scope of this module is pending team confirmation.')).toBeInTheDocument()
    expect(screen.getByText('Available')).toBeInTheDocument()
    expect(screen.getAllByText('Coming Soon')).toHaveLength(2)
    expect(screen.getByRole('link', { name: 'Lost & Found' })).toHaveAttribute('href', '/admin/lost-found')
    expect(screen.getByRole('link', { name: 'Facilities' })).toHaveAttribute('href', '/admin/facilities')
    expect(screen.getByRole('link', { name: 'User Management' })).toHaveAttribute('href', '/admin/users')

    expect(screen.getByRole('heading', { name: 'Overview Metrics' })).toBeInTheDocument()
    expect(screen.getByText('Data source not connected')).toBeInTheDocument()
    expect(screen.getByText('Module metrics will appear here after the corresponding data sources are connected.')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Action Required' })).toBeInTheDocument()
    expect(screen.getByText('No operational data is connected yet.')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Recent Activity' })).toBeInTheDocument()
    expect(screen.getByText('No activity data is connected yet.')).toBeInTheDocument()
  })

  it('shows the current super administrator role', () => {
    storeSession('SUPER_ADMIN')
    renderDashboard()

    expect(screen.getByText('Signed in as admin@nus.edu.sg')).toBeInTheDocument()
    expect(screen.getByText('Role: SUPER_ADMIN')).toBeInTheDocument()
  })

  it('renders safely when no current user is available', () => {
    renderDashboard()

    expect(screen.getByRole('heading', { name: 'Dashboard Overview' })).toBeInTheDocument()
    expect(screen.getByText('Signed in as Unknown user')).toBeInTheDocument()
    expect(screen.getByText('Role: Unknown role')).toBeInTheDocument()
  })
})
