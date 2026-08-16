import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  getAdminFacilityMaintenance,
  searchAdminFacilityMaintenance,
} from '../../api/adminFacilities'
import { facilitiesApi } from '../../api/facilities'
import type { AdminFacilityMaintenance, PageResponse } from '../../types'
import { MaintenanceDetailPage, MaintenancePage } from './FacilitiesPage'

vi.mock('../../api/adminFacilities', () => ({
  getAdminFacilityMaintenance: vi.fn(),
  searchAdminFacilityBookings: vi.fn(),
  searchAdminFacilityMaintenance: vi.fn(),
}))

const searchMaintenance = vi.mocked(searchAdminFacilityMaintenance)
const getMaintenanceDetail = vi.mocked(getAdminFacilityMaintenance)

const firstTicket: AdminFacilityMaintenance = {
  ticketId: 41,
  userId: 12,
  userEmail: 'first.student@nus.edu.sg',
  spaceId: 5,
  spaceName: 'Seminar Room 2',
  spaceType: 'SEMINAR_ROOM',
  building: 'COM3',
  floor: '2',
  roomNumber: '02-10',
  facilityType: 'AIR_CONDITIONING',
  description: 'The air conditioning is too warm.',
  priority: 'HIGH',
  status: 'SUBMITTED',
  createdAt: '2026-08-15T00:30:00',
  updatedAt: '2026-08-15T01:45:00',
}

const secondTicket: AdminFacilityMaintenance = {
  ...firstTicket,
  ticketId: 42,
  userId: 13,
  userEmail: null,
  spaceId: null,
  spaceName: null,
  spaceType: null,
  building: 'COM2',
  floor: null,
  roomNumber: '03-12',
  facilityType: 'LIGHTING',
  description: 'The ceiling light is flickering.',
  priority: 'MEDIUM',
  status: 'IN_PROGRESS',
  createdAt: '2026-08-15T02:00:00',
  updatedAt: '2026-08-15T03:00:00',
}

function maintenancePage(
  content: AdminFacilityMaintenance[],
  page = 0,
  size = 25,
  totalElements = content.length,
): PageResponse<AdminFacilityMaintenance> {
  return {
    content,
    page,
    size,
    totalElements,
    totalPages: totalElements === 0 ? 0 : Math.ceil(totalElements / size),
    first: page === 0,
    last: totalElements === 0 || page >= Math.ceil(totalElements / size) - 1,
  }
}

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((resolvePromise) => {
    resolve = resolvePromise
  })
  return { promise, resolve }
}

function renderList() {
  return render(
    <MemoryRouter initialEntries={['/admin/facilities/maintenance']}>
      <MaintenancePage />
    </MemoryRouter>,
  )
}

function renderDetail() {
  return render(
    <MemoryRouter initialEntries={['/admin/facilities/maintenance/41']}>
      <Routes>
        <Route path="/admin/facilities/maintenance/:id" element={<MaintenanceDetailPage />} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('MaintenancePage', () => {
  beforeEach(() => {
    searchMaintenance.mockReset()
    searchMaintenance.mockResolvedValue(maintenancePage([firstTicket, secondTicket]))
    vi.spyOn(facilitiesApi, 'getMaintenance').mockRejectedValue(new Error('Student list API must not be called'))
  })

  afterEach(() => {
    cleanup()
    vi.restoreAllMocks()
  })

  it('loads system-level maintenance records through the Admin API', async () => {
    renderList()

    expect(screen.getByRole('status')).toHaveTextContent('Loading maintenance requests')
    expect(searchMaintenance).toHaveBeenCalledWith({
      statuses: undefined,
      priority: undefined,
      userEmail: undefined,
      page: 0,
      size: 25,
      sort: 'createdAt,desc',
    })

    const table = await screen.findByRole('table', { name: 'System maintenance requests' })
    expect(within(table).getByText('41')).toBeInTheDocument()
    expect(within(table).getByText('Seminar Room 2')).toBeInTheDocument()
    expect(within(table).getByText('COM2 / 03-12')).toBeInTheDocument()
    expect(within(table).getByText('first.student@nus.edu.sg')).toBeInTheDocument()
    expect(within(table).getByText('Unknown user')).toBeInTheDocument()
    expect(within(table).getAllByText('15 Aug 2026, 12:30 AM')).toHaveLength(1)
    expect(within(table).getAllByRole('link', { name: 'View' })[0]).toHaveAttribute(
      'href',
      '/admin/facilities/maintenance/41',
    )
    expect(facilitiesApi.getMaintenance).not.toHaveBeenCalled()
    expect(screen.queryByText(/current account/i)).not.toBeInTheDocument()
  })

  it('applies status, priority, and trimmed user email filters through the backend', async () => {
    renderList()
    await screen.findByRole('table', { name: 'System maintenance requests' })

    fireEvent.change(screen.getByLabelText('Status filter'), { target: { value: 'IN_PROGRESS' } })
    fireEvent.change(screen.getByLabelText('Priority filter'), { target: { value: 'HIGH' } })
    fireEvent.change(screen.getByRole('textbox', { name: 'User Email' }), {
      target: { value: '  first.student@nus.edu.sg  ' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Apply Filters' }))

    await waitFor(() => expect(searchMaintenance).toHaveBeenCalledTimes(2))
    expect(searchMaintenance).toHaveBeenLastCalledWith({
      statuses: ['IN_PROGRESS'],
      priority: 'HIGH',
      userEmail: 'first.student@nus.edu.sg',
      page: 0,
      size: 25,
      sort: 'createdAt,desc',
    })
  })

  it('uses backend pagination and supports changing page size', async () => {
    searchMaintenance.mockImplementation(async (params) => maintenancePage(
      [firstTicket],
      params.page ?? 0,
      params.size ?? 25,
      60,
    ))
    renderList()

    await screen.findByRole('table', { name: 'System maintenance requests' })
    fireEvent.click(screen.getByRole('button', { name: 'Go to next page' }))
    await waitFor(() => expect(searchMaintenance).toHaveBeenLastCalledWith(expect.objectContaining({
      page: 1,
      size: 25,
    })))

    fireEvent.mouseDown(screen.getByRole('combobox', { name: 'Rows per page:' }))
    fireEvent.click(screen.getByRole('option', { name: '50' }))
    await waitFor(() => expect(searchMaintenance).toHaveBeenLastCalledWith(expect.objectContaining({
      page: 0,
      size: 50,
    })))
  })

  it('shows an error and retries the same backend request', async () => {
    const retry = deferred<PageResponse<AdminFacilityMaintenance>>()
    searchMaintenance
      .mockRejectedValueOnce(new Error('Unable to load maintenance requests.'))
      .mockReturnValueOnce(retry.promise)

    renderList()

    expect(await screen.findByRole('alert')).toHaveTextContent('Unable to load maintenance requests.')
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }))
    expect(screen.getByRole('status')).toHaveTextContent('Loading maintenance requests')

    await act(async () => {
      retry.resolve(maintenancePage([firstTicket]))
      await retry.promise
    })

    expect(await screen.findByRole('table', { name: 'System maintenance requests' })).toBeInTheDocument()
    expect(searchMaintenance).toHaveBeenCalledTimes(2)
  })

  it('shows the system-level empty result message', async () => {
    searchMaintenance.mockResolvedValue(maintenancePage([]))

    renderList()

    expect(await screen.findByText('No maintenance requests found.')).toBeInTheDocument()
    expect(screen.queryByText(/current account/i)).not.toBeInTheDocument()
    expect(screen.queryByRole('table', { name: 'System maintenance requests' })).not.toBeInTheDocument()
  })
})

describe('MaintenanceDetailPage', () => {
  beforeEach(() => {
    getMaintenanceDetail.mockReset()
    getMaintenanceDetail.mockResolvedValue(firstTicket)
    vi.spyOn(facilitiesApi, 'getMaintenanceDetail').mockRejectedValue(new Error('Student detail API must not be called'))
    vi.spyOn(facilitiesApi, 'updateMaintenance').mockResolvedValue({
      success: true,
      ticketId: firstTicket.ticketId,
      spaceId: firstTicket.spaceId,
      spaceName: firstTicket.spaceName,
      building: firstTicket.building,
      roomNumber: firstTicket.roomNumber,
      facilityType: firstTicket.facilityType,
      description: firstTicket.description,
      priority: firstTicket.priority,
      status: 'IN_PROGRESS',
      createdAt: firstTicket.createdAt,
      updatedAt: firstTicket.updatedAt,
    })
  })

  afterEach(() => {
    cleanup()
    vi.restoreAllMocks()
  })

  it('loads another user ticket from the Admin detail API and shows complete fields', async () => {
    renderDetail()

    expect(await screen.findByRole('heading', { name: 'Maintenance Detail' })).toBeInTheDocument()
    expect(getMaintenanceDetail).toHaveBeenCalledWith(41)
    expect(facilitiesApi.getMaintenanceDetail).not.toHaveBeenCalled()
    expect(screen.getByText(/Ticket ID: 41/)).toBeInTheDocument()
    expect(screen.getByText('first.student@nus.edu.sg')).toBeInTheDocument()
    expect(screen.getByText('Seminar Room 2')).toBeInTheDocument()
    expect(screen.getByText('COM3 / 02-10')).toBeInTheDocument()
    expect(screen.getByText('AIR_CONDITIONING')).toBeInTheDocument()
    expect(screen.getByText('The air conditioning is too warm.')).toBeInTheDocument()
    expect(screen.getByText('HIGH')).toBeInTheDocument()
    expect(screen.getAllByText('SUBMITTED').length).toBeGreaterThan(0)
    expect(screen.getByText('15 Aug 2026, 12:30 AM')).toBeInTheDocument()
    expect(screen.getByText('15 Aug 2026, 1:45 AM')).toBeInTheDocument()
  })

  it('keeps the existing PATCH and reloads Admin detail after a status update', async () => {
    const updatedTicket = {
      ...firstTicket,
      status: 'IN_PROGRESS' as const,
      updatedAt: '2026-08-15T02:00:00',
    }
    getMaintenanceDetail
      .mockResolvedValueOnce(firstTicket)
      .mockResolvedValueOnce(updatedTicket)

    renderDetail()
    await screen.findByRole('heading', { name: 'Maintenance Detail' })

    fireEvent.mouseDown(screen.getByRole('combobox', { name: 'Maintenance status' }))
    fireEvent.click(screen.getByRole('option', { name: 'IN PROGRESS' }))
    fireEvent.click(screen.getByRole('button', { name: 'Save Changes' }))

    await waitFor(() => expect(facilitiesApi.updateMaintenance).toHaveBeenCalledWith(41, 'IN_PROGRESS'))
    await waitFor(() => expect(getMaintenanceDetail).toHaveBeenCalledTimes(2))
    expect(await screen.findByText('15 Aug 2026, 2:00 AM')).toBeInTheDocument()
    expect(screen.getAllByText('IN PROGRESS').length).toBeGreaterThan(0)
  })

  it('shows API errors, supports retry, and reports not found responses', async () => {
    getMaintenanceDetail
      .mockRejectedValueOnce(new Error('Maintenance request not found.'))
      .mockResolvedValueOnce(firstTicket)

    renderDetail()

    expect(await screen.findByRole('alert')).toHaveTextContent('Maintenance request not found.')
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }))
    expect(await screen.findByRole('heading', { name: 'Maintenance Detail' })).toBeInTheDocument()
    expect(getMaintenanceDetail).toHaveBeenCalledTimes(2)
  })
})
