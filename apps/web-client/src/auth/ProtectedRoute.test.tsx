import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it } from 'vitest'
import { TOKEN_KEY, USER_KEY } from '../api/client'
import { AuthProvider } from './AuthContext'
import { ProtectedRoute } from './ProtectedRoute'

function renderRoute() {
  return render(
    <MemoryRouter initialEntries={['/private']}>
      <AuthProvider>
        <Routes>
          <Route path="/login" element={<p>Login page</p>} />
          <Route element={<ProtectedRoute />}><Route path="/private" element={<p>Private page</p>} /></Route>
        </Routes>
      </AuthProvider>
    </MemoryRouter>,
  )
}

describe('ProtectedRoute', () => {
  beforeEach(() => sessionStorage.clear())

  it('redirects anonymous users to login', () => {
    renderRoute()
    expect(screen.getByText('Login page')).toBeInTheDocument()
  })

  it('renders protected content for a stored session', () => {
    sessionStorage.setItem(TOKEN_KEY, 'token')
    sessionStorage.setItem(USER_KEY, JSON.stringify({ email: 'user@nus.edu.sg', role: 'STUDENT' }))
    renderRoute()
    expect(screen.getByText('Private page')).toBeInTheDocument()
  })
})
