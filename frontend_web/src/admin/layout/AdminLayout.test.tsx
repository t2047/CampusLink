import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { TOKEN_KEY, USER_KEY } from '../../api/client'
import { AdminRoute } from '../../auth/AdminRoute'
import { AuthProvider } from '../../auth/AuthContext'
import { AdminLayout } from './AdminLayout'

function setViewportMatches(matches: boolean) {
  Object.defineProperty(window, 'matchMedia', {
    configurable: true,
    writable: true,
    value: vi.fn().mockImplementation((query: string) => ({
      matches,
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

function storeSession(role = 'ADMIN') {
  sessionStorage.setItem(TOKEN_KEY, 'token')
  sessionStorage.setItem(USER_KEY, JSON.stringify({ email: 'admin@nus.edu.sg', role }))
}

function renderLayout(path = '/admin/dashboard') {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AuthProvider>
        <Routes>
          <Route path="/login" element={<p>Login page</p>} />
          <Route path="/forbidden" element={<p>Forbidden page</p>} />
          <Route element={<AdminRoute />}>
            <Route element={<AdminLayout />}>
              <Route path="/admin/dashboard" element={<h1>Dashboard content</h1>} />
              <Route path="/admin/source" element={<h1>Source content</h1>} />
              <Route path="/admin/lost-found" element={<h1>Lost & Found content</h1>} />
              <Route path="/admin/facilities" element={<h1>Facilities content</h1>} />
              <Route path="/admin/users" element={<h1>User Management content</h1>} />
              <Route path="/admin/users-example" element={<h1>Users example content</h1>} />
            </Route>
          </Route>
        </Routes>
      </AuthProvider>
    </MemoryRouter>,
  )
}

describe('AdminLayout', () => {
  beforeEach(() => {
    sessionStorage.clear()
    setViewportMatches(true)
  })
  afterEach(() => cleanup())

  it('renders the shared desktop administration layout for an administrator', () => {
    storeSession()
    renderLayout()

    expect(screen.getByText('CampusLink Administration')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Dashboard content' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Overview' })).toHaveAttribute('aria-current', 'page')
    expect(screen.getByText('admin@nus.edu.sg')).toBeInTheDocument()
    expect(screen.getByText('ADMIN')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Sign Out' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Open administration menu' })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Browse' })).not.toBeInTheDocument()
    expect(screen.queryByText('Report')).not.toBeInTheDocument()
    expect(screen.queryByText('Claims')).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Lost & Found' })).toHaveAttribute('href', '/admin/lost-found')
    expect(screen.getByRole('link', { name: 'Facilities' })).toHaveAttribute('href', '/admin/facilities')
    expect(screen.getByRole('link', { name: 'User Management' })).toHaveAttribute('href', '/admin/users')
  })

  it('renders the same shared layout for a super administrator', () => {
    storeSession('SUPER_ADMIN')
    renderLayout()

    expect(screen.getByText('CampusLink Administration')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Overview' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Lost & Found' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Facilities' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'User Management' })).toBeInTheDocument()
    expect(screen.getByText('SUPER_ADMIN')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Dashboard content' })).toBeInTheDocument()
  })

  it.each([
    ['/admin/lost-found', 'Lost & Found'],
    ['/admin/facilities', 'Facilities'],
    ['/admin/users', 'User Management'],
  ])('marks %s as the active navigation item', (path, label) => {
    storeSession()
    renderLayout(path)

    expect(screen.getByRole('link', { name: label })).toHaveAttribute('aria-current', 'page')
    expect(screen.getByRole('link', { name: 'Overview' })).not.toHaveAttribute('aria-current')
  })

  it('does not activate a module for a partial path-segment match', () => {
    storeSession()
    renderLayout('/admin/users-example')

    expect(screen.getByRole('link', { name: 'User Management' })).not.toHaveAttribute('aria-current')
  })

  it('signs out through AuthContext and navigates to login', () => {
    storeSession()
    renderLayout()

    fireEvent.click(screen.getByRole('button', { name: 'Sign Out' }))

    expect(screen.getByText('Login page')).toBeInTheDocument()
    expect(sessionStorage.getItem(TOKEN_KEY)).toBeNull()
    expect(sessionStorage.getItem(USER_KEY)).toBeNull()
  })

  it('opens and closes the small-screen drawer and closes it after navigation', async () => {
    setViewportMatches(false)
    storeSession()
    renderLayout('/admin/source')

    const menuButton = screen.getByRole('button', { name: 'Open administration menu' })
    expect(screen.queryByRole('link', { name: 'Overview' })).not.toBeInTheDocument()

    fireEvent.click(menuButton)
    expect(await screen.findByRole('link', { name: 'Overview' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Lost & Found' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Facilities' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'User Management' })).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Close administration menu' }))
    await waitFor(() => expect(screen.queryByRole('link', { name: 'Overview' })).not.toBeInTheDocument())

    fireEvent.click(menuButton)
    fireEvent.click(await screen.findByRole('link', { name: 'Facilities' }))

    expect(await screen.findByRole('heading', { name: 'Facilities content' })).toBeInTheDocument()
    await waitFor(() => expect(screen.queryByRole('link', { name: 'Overview' })).not.toBeInTheDocument())
  })
})
