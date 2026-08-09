import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { TOKEN_KEY, USER_KEY } from '../../api/client'
import { AuthProvider } from '../../auth/AuthContext'
import { AdminForbiddenPage } from './AdminForbiddenPage'

function renderForbiddenPage() {
  return render(
    <MemoryRouter initialEntries={['/forbidden']}>
      <AuthProvider>
        <Routes>
          <Route path="/forbidden" element={<AdminForbiddenPage />} />
          <Route path="/lost-found" element={<p>CampusLink home</p>} />
          <Route path="/login" element={<p>Login page</p>} />
        </Routes>
      </AuthProvider>
    </MemoryRouter>,
  )
}

describe('AdminForbiddenPage', () => {
  beforeEach(() => sessionStorage.clear())
  afterEach(() => cleanup())

  it('renders safely without a user and returns to CampusLink', () => {
    renderForbiddenPage()

    expect(screen.getByRole('heading', { name: 'Access Denied' })).toBeInTheDocument()
    expect(screen.getByText('You do not have permission to access this page.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Sign Out' })).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Return to CampusLink' }))
    expect(screen.getByText('CampusLink home')).toBeInTheDocument()
  })

  it('signs out an authenticated user and navigates to login', () => {
    sessionStorage.setItem(TOKEN_KEY, 'token')
    sessionStorage.setItem(USER_KEY, JSON.stringify({ email: 'student@nus.edu.sg', role: 'STUDENT' }))
    renderForbiddenPage()

    fireEvent.click(screen.getByRole('button', { name: 'Sign Out' }))

    expect(screen.getByText('Login page')).toBeInTheDocument()
    expect(sessionStorage.getItem(TOKEN_KEY)).toBeNull()
    expect(sessionStorage.getItem(USER_KEY)).toBeNull()
  })
})
