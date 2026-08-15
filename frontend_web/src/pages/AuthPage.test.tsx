import { cleanup, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { TOKEN_KEY, USER_KEY } from '../api/client'
import { AuthProvider } from '../auth/AuthContext'
import { AuthPage } from './AuthPage'

describe('AuthPage', () => {
  beforeEach(() => sessionStorage.clear())
  afterEach(() => cleanup())

  it('returns an authenticated session to the originally requested path', () => {
    sessionStorage.setItem(TOKEN_KEY, 'token')
    sessionStorage.setItem(USER_KEY, JSON.stringify({ email: 'admin@example.test', role: 'ADMIN' }))

    render(
      <MemoryRouter
        initialEntries={[{ pathname: '/login', state: { from: { pathname: '/admin/dashboard' } } }]}
      >
        <AuthProvider>
          <Routes>
            <Route path="/login" element={<AuthPage mode="login" />} />
            <Route path="/admin/dashboard" element={<h1>Admin dashboard</h1>} />
            <Route path="/lost-found" element={<h1>Lost and Found</h1>} />
          </Routes>
        </AuthProvider>
      </MemoryRouter>,
    )

    expect(screen.getByRole('heading', { name: 'Admin dashboard' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Lost and Found' })).not.toBeInTheDocument()
  })

  it('keeps Central Agent Chat as the default destination for an authenticated user', () => {
    sessionStorage.setItem(TOKEN_KEY, 'token')
    sessionStorage.setItem(USER_KEY, JSON.stringify({ email: 'student@example.test', role: 'USER' }))

    render(
      <MemoryRouter initialEntries={['/login']}>
        <AuthProvider>
          <Routes>
            <Route path="/login" element={<AuthPage mode="login" />} />
            <Route path="/chat" element={<h1>Central Agent Chat</h1>} />
          </Routes>
        </AuthProvider>
      </MemoryRouter>,
    )

    expect(screen.getByRole('heading', { name: 'Central Agent Chat' })).toBeInTheDocument()
  })
})
