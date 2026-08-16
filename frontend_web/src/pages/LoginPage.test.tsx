import { cleanup, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { TOKEN_KEY, USER_KEY } from '../api/client'
import { AuthProvider } from '../auth/AuthContext'
import LoginPage from './LoginPage'

describe('LoginPage', () => {
  beforeEach(() => sessionStorage.clear())
  afterEach(() => cleanup())

  function renderLogin(initialEntries: string[]) {
    return render(
      <MemoryRouter initialEntries={initialEntries}>
        <AuthProvider>
          <Routes>
            <Route path="/login" element={<LoginPage />} />
            <Route path="/register" element={<LoginPage />} />
            <Route path="/admin/dashboard" element={<h1>Admin dashboard</h1>} />
            <Route path="/chat" element={<h1>Chat page</h1>} />
          </Routes>
        </AuthProvider>
      </MemoryRouter>,
    )
  }

  it('redirects an authenticated ADMIN to /admin/dashboard', () => {
    sessionStorage.setItem(TOKEN_KEY, 'token')
    sessionStorage.setItem(USER_KEY, JSON.stringify({ email: 'admin@example.test', role: 'ADMIN' }))

    renderLogin(['/login'])

    expect(screen.getByRole('heading', { name: 'Admin dashboard' })).toBeInTheDocument()
  })

  it('redirects an authenticated student to /chat', () => {
    sessionStorage.setItem(TOKEN_KEY, 'token')
    sessionStorage.setItem(USER_KEY, JSON.stringify({ email: 'student@example.test', role: 'STUDENT' }))

    renderLogin(['/login'])

    expect(screen.getByRole('heading', { name: 'Chat page' })).toBeInTheDocument()
  })

  it('renders the login form when not authenticated', () => {
    renderLogin(['/login'])

    expect(screen.getByRole('heading', { name: 'Campus Link' })).toBeInTheDocument()
    expect(screen.getByLabelText('Email')).toBeInTheDocument()
    expect(screen.getByLabelText('Password')).toBeInTheDocument()
    // Tab 与提交按钮均为「Log in」→ 用 getAllByRole
    expect(screen.getAllByRole('button', { name: 'Log in' }).length).toBe(2)
  })

  it('starts in register mode on the /register route', () => {
    renderLogin(['/register'])

    // 提交按钮为 Create account（register 模式），且无 Email 之外的表单字段
    expect(screen.getByRole('button', { name: 'Create account' })).toBeInTheDocument()
    expect(screen.getByLabelText('Email')).toBeInTheDocument()
    expect(screen.getByLabelText('Password')).toBeInTheDocument()
  })
})
