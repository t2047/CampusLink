import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation, useNavigate } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { TOKEN_KEY, USER_KEY } from '../api/client'
import { AuthProvider } from './AuthContext'
import { AdminRoute } from './AdminRoute'

function LoginPage() {
  const location = useLocation()
  const navigate = useNavigate()
  const from = (location.state as { from?: { pathname?: string } } | null)?.from?.pathname

  return (
    <>
      <p>Login page</p>
      <p>Requested path: {from}</p>
      <button type="button" onClick={() => navigate(-1)}>Back</button>
    </>
  )
}

function storeSession(user: { email: string; role?: unknown }) {
  sessionStorage.setItem(TOKEN_KEY, 'token')
  sessionStorage.setItem(USER_KEY, JSON.stringify(user))
}

function renderAdminRoute() {
  return render(
    <MemoryRouter initialEntries={['/origin', '/admin/dashboard']} initialIndex={1}>
      <AuthProvider>
        <Routes>
          <Route path="/origin" element={<p>Origin page</p>} />
          <Route path="/login" element={<LoginPage />} />
          <Route path="/forbidden" element={<p>Forbidden page</p>} />
          <Route element={<AdminRoute />}>
            <Route path="/admin/dashboard" element={<p>Admin content</p>} />
          </Route>
        </Routes>
      </AuthProvider>
    </MemoryRouter>,
  )
}

describe('AdminRoute', () => {
  beforeEach(() => sessionStorage.clear())
  afterEach(() => cleanup())

  it('redirects anonymous users to login with the requested location and replaces history', () => {
    renderAdminRoute()

    expect(screen.getByText('Login page')).toBeInTheDocument()
    expect(screen.getByText('Requested path: /admin/dashboard')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Back' }))
    expect(screen.getByText('Origin page')).toBeInTheDocument()
  })

  it.each(['ADMIN', 'SUPER_ADMIN'])('renders admin content for the %s role', (role) => {
    storeSession({ email: 'admin@nus.edu.sg', role })
    renderAdminRoute()

    expect(screen.getByText('Admin content')).toBeInTheDocument()
    expect(screen.queryByText('Forbidden page')).not.toBeInTheDocument()
  })

  it('redirects students to forbidden without rendering admin content', () => {
    storeSession({ email: 'student@nus.edu.sg', role: 'STUDENT' })
    renderAdminRoute()

    expect(screen.getByText('Forbidden page')).toBeInTheDocument()
    expect(screen.queryByText('Admin content')).not.toBeInTheDocument()
  })

  it.each([undefined, '', 'admin', 'FACULTY'])(
    'redirects an invalid role (%s) to forbidden',
    (role) => {
      storeSession({ email: 'user@nus.edu.sg', role })
      renderAdminRoute()

      expect(screen.getByText('Forbidden page')).toBeInTheDocument()
      expect(screen.queryByText('Admin content')).not.toBeInTheDocument()
    },
  )
})
