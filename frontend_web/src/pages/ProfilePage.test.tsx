import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { TOKEN_KEY, USER_KEY } from '../api/client'
import { getMyClaims, searchReports } from '../api/lostFound'
import { changePassword, getMyProfile, updateNickname } from '../api/users'
import { facilitiesApi } from '../api/facilities'
import { AuthProvider } from '../auth/AuthContext'
import type { UserProfile } from '../types'
import { ProfilePage } from './ProfilePage'

vi.mock('../api/lostFound', () => ({ getMyClaims: vi.fn(), searchReports: vi.fn() }))
vi.mock('../api/users', () => ({
  getMyProfile: vi.fn(),
  updateNickname: vi.fn(),
  uploadAvatar: vi.fn(),
  changePassword: vi.fn(),
}))
vi.mock('../api/facilities', () => ({ facilitiesApi: { listBookings: vi.fn(), listMaintenanceRequests: vi.fn() } }))

const baseProfile: UserProfile = { email: 'student@example.edu', role: 'STUDENT', nickname: 'Alex', avatarUrl: null }

function emptyPage() {
  return { content: [], page: 0, size: 1, totalElements: 0, totalPages: 1, first: true, last: true }
}

function storeSession() {
  sessionStorage.setItem(TOKEN_KEY, 'token')
  sessionStorage.setItem(USER_KEY, JSON.stringify({ email: 'student@example.edu', role: 'STUDENT' }))
}

function renderPage() {
  return render(
    <MemoryRouter>
      <AuthProvider>
        <ProfilePage />
      </AuthProvider>
    </MemoryRouter>,
  )
}

beforeEach(() => {
  storeSession()
  vi.mocked(getMyProfile).mockResolvedValue(baseProfile)
  vi.mocked(getMyClaims).mockResolvedValue([])
  vi.mocked(searchReports).mockResolvedValue(emptyPage())
  vi.mocked(facilitiesApi.listBookings).mockResolvedValue([])
  vi.mocked(facilitiesApi.listMaintenanceRequests).mockResolvedValue([])
  vi.mocked(changePassword).mockResolvedValue(undefined)
  vi.clearAllMocks()
})

afterEach(() => {
  cleanup()
  sessionStorage.clear()
})

describe('ProfilePage', () => {
  it('renders the profile section with email, role and nickname', async () => {
    renderPage()

    expect(await screen.findByText('Alex')).toBeInTheDocument()
    expect(screen.getByText('student@example.edu')).toBeInTheDocument()
    expect(screen.getByText('STUDENT')).toBeInTheDocument()
  })

  it('falls back to the email prefix when there is no nickname', async () => {
    vi.mocked(getMyProfile).mockResolvedValue({ ...baseProfile, nickname: null })
    renderPage()

    expect(await screen.findByText('student')).toBeInTheDocument()
  })

  it('renders My Claims, My Lost, My Found and FAQ entries with the right routes', async () => {
    renderPage()

    expect(await screen.findByText('My Claims')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /My Claims/ })).toHaveAttribute('href', '/claims/mine')
    expect(screen.getByRole('link', { name: /My Lost Items/ })).toHaveAttribute('href', '/lost-found/profile/lost')
    expect(screen.getByRole('link', { name: /My Found Items/ })).toHaveAttribute('href', '/lost-found/profile/found')
    expect(screen.getByRole('link', { name: /My Bookings/ })).toHaveAttribute('href', '/facilities/bookings')
    expect(screen.getByRole('link', { name: /My Maintenance Requests/ })).toHaveAttribute('href', '/facilities/maintenance')
    expect(await screen.findByText('0 bookings')).toBeInTheDocument()
    expect(screen.getByText('0 maintenance requests')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /FAQ/ })).toHaveAttribute('href', '/lost-found/faq')
  })

  it('shows report counts from the owner=me search totalElements', async () => {
    vi.mocked(searchReports).mockResolvedValue({ ...emptyPage(), totalElements: 3 })
    renderPage()

    expect(await screen.findByText('3 lost-item reports')).toBeInTheDocument()
    expect(screen.getByText('3 found-item reports')).toBeInTheDocument()
  })

  it('lets the user edit their nickname and syncs the profile', async () => {
    vi.mocked(updateNickname).mockResolvedValue({ ...baseProfile, nickname: 'Bob' })
    renderPage()

    fireEvent.click(await screen.findByRole('button', { name: 'Edit profile' }))
    const input = screen.getByLabelText('Nickname')
    fireEvent.change(input, { target: { value: 'Bob' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(updateNickname).toHaveBeenCalledWith('Bob'))
    expect(await screen.findByText('Bob')).toBeInTheDocument()
  })

  it('lets the user change their password and shows a success message', async () => {
    renderPage()

    fireEvent.click(await screen.findByRole('button', { name: 'Change password' }))
    fireEvent.change(screen.getByLabelText('Current password'), { target: { value: 'old-pass' } })
    fireEvent.change(screen.getByLabelText('New password'), { target: { value: 'new-pass-123' } })
    fireEvent.change(screen.getByLabelText('Confirm new password'), { target: { value: 'new-pass-123' } })
    fireEvent.click(screen.getByRole('button', { name: 'Update' }))

    await waitFor(() => expect(changePassword).toHaveBeenCalledWith('old-pass', 'new-pass-123'))
    expect(await screen.findByText(/Password updated/)).toBeInTheDocument()
  })

  it('rejects a password change when the current password is incorrect', async () => {
    vi.mocked(changePassword).mockRejectedValue({
      isAxiosError: true,
      response: { data: { code: 'PASSWORD_CURRENT_INCORRECT' } },
    })
    renderPage()

    fireEvent.click(await screen.findByRole('button', { name: 'Change password' }))
    fireEvent.change(screen.getByLabelText('Current password'), { target: { value: 'wrong-pass' } })
    fireEvent.change(screen.getByLabelText('New password'), { target: { value: 'new-pass-123' } })
    fireEvent.change(screen.getByLabelText('Confirm new password'), { target: { value: 'new-pass-123' } })
    fireEvent.click(screen.getByRole('button', { name: 'Update' }))

    expect(await screen.findByText('Current password is incorrect.')).toBeInTheDocument()
    expect(changePassword).toHaveBeenCalledWith('wrong-pass', 'new-pass-123')
  })

  it('blocks a password change when the confirmation does not match', async () => {
    renderPage()

    fireEvent.click(await screen.findByRole('button', { name: 'Change password' }))
    fireEvent.change(screen.getByLabelText('Current password'), { target: { value: 'old-pass' } })
    fireEvent.change(screen.getByLabelText('New password'), { target: { value: 'new-pass-123' } })
    fireEvent.change(screen.getByLabelText('Confirm new password'), { target: { value: 'different-pass' } })
    fireEvent.click(screen.getByRole('button', { name: 'Update' }))

    expect(await screen.findByText('New password and confirmation do not match.')).toBeInTheDocument()
    expect(changePassword).not.toHaveBeenCalled()
  })
})
