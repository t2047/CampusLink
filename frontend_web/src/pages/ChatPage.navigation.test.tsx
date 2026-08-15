import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { TOKEN_KEY, USER_KEY } from '../api/client'
import { AuthProvider } from '../auth/AuthContext'
import ChatPage from './ChatPage'

function CurrentPath() {
  return <output aria-label="Current path">{useLocation().pathname}</output>
}

describe('Chat Services navigation', () => {
  beforeEach(() => {
    sessionStorage.clear()
    localStorage.clear()
    sessionStorage.setItem(TOKEN_KEY, 'token')
    sessionStorage.setItem(USER_KEY, JSON.stringify({ email: 'student@nus.edu.sg', role: 'USER' }))
    Object.defineProperty(window, 'matchMedia', {
      configurable: true,
      value: vi.fn().mockImplementation((query: string) => ({
        matches: false,
        media: query,
        onchange: null,
        addListener: vi.fn(),
        removeListener: vi.fn(),
        addEventListener: vi.fn(),
        removeEventListener: vi.fn(),
        dispatchEvent: vi.fn(),
      })),
    })
    Object.defineProperty(Element.prototype, 'scrollIntoView', {
      configurable: true,
      value: vi.fn(),
    })
  })

  afterEach(() => cleanup())

  it('shows Mail, Lost & Found, and Facilities as peer Services entries and opens Facilities', () => {
    render(
      <MemoryRouter initialEntries={['/chat']}>
        <AuthProvider>
          <CurrentPath />
          <Routes>
            <Route path="/chat" element={<ChatPage />} />
            <Route path="/facilities" element={<h1>Facilities service</h1>} />
          </Routes>
        </AuthProvider>
      </MemoryRouter>,
    )

    fireEvent.click(screen.getByRole('button', { name: /Services/ }))

    const mail = screen.getByRole('link', { name: /Mail/ })
    const lostFound = screen.getByRole('link', { name: /Lost & Found/ })
    const facilities = screen.getByRole('link', { name: /Facilities/ })
    expect(mail).toHaveAttribute('href', '/mail')
    expect(lostFound).toHaveAttribute('href', '/lost-found')
    expect(facilities).toHaveAttribute('href', '/facilities')
    expect(mail.parentElement).toBe(lostFound.parentElement)
    expect(lostFound.parentElement).toBe(facilities.parentElement)

    fireEvent.click(facilities)

    expect(screen.getByRole('heading', { name: 'Facilities service' })).toBeInTheDocument()
    expect(screen.getByLabelText('Current path')).toHaveTextContent('/facilities')
  })
})
