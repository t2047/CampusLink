import { cleanup, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import { TOKEN_KEY, USER_KEY } from './api/client'
import { AuthProvider } from './auth/AuthContext'

vi.mock('./pages/ProfilePage', () => ({ ProfilePage: () => <p>Profile page content</p> }))
vi.mock('./pages/MyReportsPage', () => ({ MyReportsPage: () => <p>My reports page content</p> }))
vi.mock('./pages/LostFoundFaqPage', () => ({ LostFoundFaqPage: () => <p>FAQ page content</p> }))

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
  sessionStorage.setItem(USER_KEY, JSON.stringify({ email: 'student@nus.edu.sg', role }))
}

function renderApp(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AuthProvider>
        <App />
      </AuthProvider>
    </MemoryRouter>,
  )
}

beforeEach(() => {
  sessionStorage.clear()
  setDesktopViewport()
})

afterEach(() => {
  cleanup()
  sessionStorage.clear()
})

describe('personal center routes', () => {
  it('renders the personal center at /lost-found/profile for authenticated users', async () => {
    storeSession('STUDENT')
    renderApp('/lost-found/profile')

    expect(await screen.findByText('Profile page content')).toBeInTheDocument()
  })

  it('redirects unauthenticated personal center visitors to login', async () => {
    renderApp('/lost-found/profile')

    expect(await screen.findByRole('heading', { name: 'Campus Link' })).toBeInTheDocument()
  })

  it('renders My Lost Items at /lost-found/profile/lost', async () => {
    storeSession('STUDENT')
    renderApp('/lost-found/profile/lost')

    expect(await screen.findByText('My reports page content')).toBeInTheDocument()
  })

  it('renders the FAQ at /lost-found/faq', async () => {
    storeSession('STUDENT')
    renderApp('/lost-found/faq')

    expect(await screen.findByText('FAQ page content')).toBeInTheDocument()
  })

  it('shows the avatar entry in the top navigation linking to the personal center', async () => {
    storeSession('STUDENT')
    renderApp('/lost-found/profile')

    expect(await screen.findByRole('link', { name: 'Personal center' })).toHaveAttribute('href', '/lost-found/profile')
  })
})
