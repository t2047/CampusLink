import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { searchAdminFacilityMaintenance } from '../../../api/adminFacilities'
import type { AdminFacilityMaintenance, PageResponse } from '../../../types'
import { OpenMaintenanceSection } from './OpenMaintenanceSection'

vi.mock('../../../api/adminFacilities', () => ({
  searchAdminFacilityMaintenance: vi.fn(),
}))

const searchMaintenance = vi.mocked(searchAdminFacilityMaintenance)

const ticketFixture: AdminFacilityMaintenance = {
  ticketId: 1,
  userId: 10,
  userEmail: 'student@nus.edu.sg',
  spaceId: 5,
  spaceName: 'Seminar Room 2',
  spaceType: 'SEMINAR_ROOM',
  building: 'COM3',
  floor: '2',
  roomNumber: '02-10',
  facilityType: 'projector',
  description: 'Projector cannot turn on',
  priority: 'HIGH',
  status: 'SUBMITTED',
  createdAt: '2026-08-15T10:00:00',
  updatedAt: '2026-08-15T10:00:00',
}

function page(content: AdminFacilityMaintenance[]): PageResponse<AdminFacilityMaintenance> {
  return {
    content,
    page: 0,
    size: 5,
    totalElements: content.length,
    totalPages: content.length === 0 ? 0 : 1,
    first: true,
    last: true,
  }
}

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
      <OpenMaintenanceSection />
    </MemoryRouter>,
  )
}

describe('OpenMaintenanceSection', () => {
  beforeEach(() => {
    searchMaintenance.mockReset()
    searchMaintenance.mockResolvedValue(page([ticketFixture]))
  })

  afterEach(() => cleanup())

  it('requests submitted and in-progress tickets and shows loading', () => {
    const pending = deferred<PageResponse<AdminFacilityMaintenance>>()
    searchMaintenance.mockReturnValue(pending.promise)

    renderSection()

    expect(screen.getByRole('status')).toHaveTextContent('Loading open maintenance')
    expect(searchMaintenance).toHaveBeenCalledWith({
      statuses: ['SUBMITTED', 'IN_PROGRESS'],
      page: 0,
      size: 5,
      sort: 'createdAt,asc',
    })
  })

  it('shows facility, issue summary, submitter, priority, status, time, and links', async () => {
    renderSection()

    const table = await screen.findByRole('table', { name: 'Open maintenance requests' })
    expect(within(table).getByText('Seminar Room 2')).toBeInTheDocument()
    expect(within(table).getByText('Projector cannot turn on')).toBeInTheDocument()
    expect(within(table).getByText('student@nus.edu.sg')).toBeInTheDocument()
    expect(within(table).getByText('HIGH')).toBeInTheDocument()
    expect(within(table).getAllByText('Submitted').length).toBeGreaterThan(0)
    expect(within(table).getByText('15 Aug 2026, 10:00 AM')).toBeInTheDocument()
    expect(within(table).getByRole('link', { name: 'View' })).toHaveAttribute(
      'href',
      '/admin/facilities/maintenance/1',
    )
    expect(screen.getByRole('link', { name: 'View All' })).toHaveAttribute(
      'href',
      '/admin/facilities/maintenance',
    )
  })

  it('uses building and room fallback and Unknown user', async () => {
    searchMaintenance.mockResolvedValue(page([{
      ...ticketFixture,
      userEmail: null,
      spaceName: null,
      spaceId: null,
      spaceType: null,
      floor: null,
      building: 'COM9',
      roomNumber: '99-01',
    }]))

    renderSection()

    const table = await screen.findByRole('table', { name: 'Open maintenance requests' })
    expect(within(table).getByText('COM9 / 99-01')).toBeInTheDocument()
    expect(within(table).getByText('Unknown user')).toBeInTheDocument()
  })

  it('summarizes long descriptions and shows at most five tickets', async () => {
    const tickets = Array.from({ length: 6 }, (_, index) => ({
      ...ticketFixture,
      ticketId: index + 1,
      description: index === 0 ? `${'x'.repeat(110)} end` : `Issue ${index + 1}`,
    }))
    searchMaintenance.mockResolvedValue(page(tickets))

    renderSection()

    const table = await screen.findByRole('table', { name: 'Open maintenance requests' })
    expect(within(table).getAllByRole('row')).toHaveLength(6)
    expect(within(table).queryByText('Issue 6')).not.toBeInTheDocument()
    expect(within(table).getByText(`${'x'.repeat(97)}...`)).toBeInTheDocument()
  })

  it('shows empty state', async () => {
    searchMaintenance.mockResolvedValue(page([]))

    renderSection()

    expect(await screen.findByText('No open maintenance requests.')).toBeInTheDocument()
  })

  it('shows error and retries successfully without duplicate requests', async () => {
    const retry = deferred<PageResponse<AdminFacilityMaintenance>>()
    searchMaintenance
      .mockRejectedValueOnce(new Error('Unable to load open maintenance.'))
      .mockReturnValueOnce(retry.promise)

    renderSection()

    expect(await screen.findByRole('alert')).toHaveTextContent('Unable to load open maintenance.')
    const retryButton = screen.getByRole('button', { name: 'Retry' })
    fireEvent.click(retryButton)
    expect(screen.getByRole('status')).toHaveTextContent('Loading open maintenance')
    fireEvent.click(retryButton)
    expect(searchMaintenance).toHaveBeenCalledTimes(2)

    await act(async () => {
      retry.resolve(page([ticketFixture]))
      await retry.promise
    })

    expect(await screen.findByRole('table', { name: 'Open maintenance requests' })).toBeInTheDocument()
  })

  it('settles a pending request safely after unmount', async () => {
    const pending = deferred<PageResponse<AdminFacilityMaintenance>>()
    searchMaintenance.mockReturnValue(pending.promise)
    const { unmount } = renderSection()

    expect(searchMaintenance).toHaveBeenCalledTimes(1)
    unmount()

    await act(async () => {
      pending.resolve(page([ticketFixture]))
      await pending.promise
    })

    expect(searchMaintenance).toHaveBeenCalledTimes(1)
  })
})
