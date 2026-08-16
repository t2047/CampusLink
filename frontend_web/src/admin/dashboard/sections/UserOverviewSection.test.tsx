import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { StrictMode } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { listAdminUsers, type AdminUser } from '../../../api/adminUsers'
import { UserOverviewSection } from './UserOverviewSection'

vi.mock('../../../api/adminUsers', () => ({
  listAdminUsers: vi.fn(),
}))

const listUsers = vi.mocked(listAdminUsers)

const usersFixture: AdminUser[] = [
  { id: 1, email: 'first.student@nus.edu.sg', role: 'STUDENT' },
  { id: 2, email: 'admin.one@nus.edu.sg', role: 'ADMIN' },
  { id: 3, email: 'second.student@nus.edu.sg', role: 'STUDENT' },
  { id: 4, email: 'super.admin@nus.edu.sg', role: 'SUPER_ADMIN' },
  { id: 5, email: 'third.student@nus.edu.sg', role: 'STUDENT' },
  { id: 6, email: 'admin.two@nus.edu.sg', role: 'ADMIN' },
  { id: 7, email: 'fourth.student@nus.edu.sg', role: 'STUDENT' },
]

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((resolvePromise) => {
    resolve = resolvePromise
  })
  return { promise, resolve }
}

function renderSection() {
  return render(
    <MemoryRouter>
      <UserOverviewSection />
    </MemoryRouter>,
  )
}

describe('UserOverviewSection', () => {
  beforeEach(() => {
    listUsers.mockReset()
    listUsers.mockResolvedValue(usersFixture)
  })

  afterEach(() => cleanup())

  it('calls the Admin Users API and shows accessible initial loading', () => {
    const pending = deferred<AdminUser[]>()
    listUsers.mockReturnValue(pending.promise)

    renderSection()

    expect(listUsers).toHaveBeenCalledTimes(1)
    expect(screen.getByRole('status')).toHaveTextContent('Loading User Management overview')
    expect(screen.getByLabelText('Loading User Management overview')).toBeInTheDocument()
  })

  it('shows all user KPIs and accurate role distribution counts', async () => {
    renderSection()

    expect(await screen.findByRole('group', { name: 'Total Users' })).toHaveTextContent('7')
    expect(screen.getByRole('group', { name: 'Students' })).toHaveTextContent('4')
    expect(screen.getByRole('group', { name: 'Administrators' })).toHaveTextContent('2')
    expect(screen.getByRole('group', { name: 'Super Administrators' })).toHaveTextContent('1')

    expect(screen.getByRole('heading', { name: 'User Role Distribution' })).toBeInTheDocument()
    expect(screen.getByRole('group', { name: 'Student: 4' })).toBeInTheDocument()
    expect(screen.getByRole('group', { name: 'Administrator: 2' })).toBeInTheDocument()
    expect(screen.getByRole('group', { name: 'Super Administrator: 1' })).toBeInTheDocument()
    expect(screen.getByRole('img', { name: 'Student users: 4' })).toBeInTheDocument()
    expect(screen.getByRole('img', { name: 'Administrator users: 2' })).toBeInTheDocument()
    expect(screen.getByRole('img', { name: 'Super Administrator users: 1' })).toBeInTheDocument()
  })

  it('shows at most five accounts in descending ID order with email and role chips', async () => {
    renderSection()

    const table = await screen.findByRole('table', { name: 'User accounts overview' })
    const rows = within(table).getAllByRole('row')
    expect(rows).toHaveLength(6)
    expect(within(rows[1]).getByText('7')).toBeInTheDocument()
    expect(within(rows[1]).getByText('fourth.student@nus.edu.sg')).toBeInTheDocument()
    expect(within(rows[1]).getByLabelText('Student role')).toHaveTextContent('Student')
    expect(within(rows[5]).getByText('3')).toBeInTheDocument()
    expect(within(table).queryByText('admin.one@nus.edu.sg')).not.toBeInTheDocument()
    expect(within(table).queryByText('first.student@nus.edu.sg')).not.toBeInTheDocument()
  })

  it('links View All to the complete User Management page', async () => {
    renderSection()

    await screen.findByRole('heading', { name: 'User Accounts' })
    expect(screen.getByRole('link', { name: 'View All' })).toHaveAttribute('href', '/admin/users')
  })

  it('treats an empty array as valid data and shows all-zero content', async () => {
    listUsers.mockResolvedValue([])

    renderSection()

    expect(await screen.findByRole('group', { name: 'Total Users' })).toHaveTextContent('0')
    expect(screen.getByRole('group', { name: 'Students' })).toHaveTextContent('0')
    expect(screen.getByRole('group', { name: 'Administrators' })).toHaveTextContent('0')
    expect(screen.getByRole('group', { name: 'Super Administrators' })).toHaveTextContent('0')
    expect(screen.getByRole('group', { name: 'Student: 0' })).toBeInTheDocument()
    expect(screen.getByRole('group', { name: 'Administrator: 0' })).toBeInTheDocument()
    expect(screen.getByRole('group', { name: 'Super Administrator: 0' })).toBeInTheDocument()
    expect(screen.getByText('No user accounts are currently available.')).toBeInTheDocument()
    expect(screen.queryByRole('alert', { name: /error/i })).not.toBeInTheDocument()
  })

  it('shows an API error and retries successfully without a duplicate request', async () => {
    const retry = deferred<AdminUser[]>()
    listUsers
      .mockRejectedValueOnce(new Error('Unable to load user accounts.'))
      .mockReturnValueOnce(retry.promise)

    renderSection()

    expect(await screen.findByRole('alert')).toHaveTextContent('Unable to load user accounts.')
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }))
    expect(screen.getByRole('status')).toHaveTextContent('Loading User Management overview')
    expect(listUsers).toHaveBeenCalledTimes(2)

    await act(async () => {
      retry.resolve(usersFixture)
      await retry.promise
    })

    expect(await screen.findByRole('group', { name: 'Total Users' })).toHaveTextContent('7')
  })

  it('prevents duplicate concurrent requests under StrictMode', () => {
    const pending = deferred<AdminUser[]>()
    listUsers.mockReturnValue(pending.promise)

    render(
      <StrictMode>
        <MemoryRouter>
          <UserOverviewSection />
        </MemoryRouter>
      </StrictMode>,
    )

    expect(listUsers).toHaveBeenCalledTimes(1)
  })

  it('does not update state after unmount', async () => {
    const pending = deferred<AdminUser[]>()
    listUsers.mockReturnValue(pending.promise)
    const view = renderSection()

    view.unmount()

    await act(async () => {
      pending.resolve(usersFixture)
      await pending.promise
    })

    expect(listUsers).toHaveBeenCalledTimes(1)
    expect(screen.queryByRole('heading', { name: 'User Management Overview' })).not.toBeInTheDocument()
  })
})
