import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { facilitiesApi, type MaintenanceResponse, type Space } from '../../api/facilities'
import { FacilitiesLayout } from './FacilitiesLayout'
import { MaintenanceDetailsPage } from './MaintenanceDetailsPage'
import { MyMaintenancePage } from './MyMaintenancePage'
import { SubmitMaintenancePage } from './SubmitMaintenancePage'

vi.mock('../../api/facilities', () => ({
  facilitiesApi: {
    searchSpaces: vi.fn(),
    submitMaintenanceRequest: vi.fn(),
    listMaintenanceRequests: vi.fn(),
    getMaintenanceRequest: vi.fn(),
  },
}))

const space: Space = {
  spaceId: 7,
  name: 'COM3 Study Room 01',
  building: 'COM3',
  floor: '2',
  roomNumber: '02-01',
  spaceType: 'STUDY_ROOM',
  capacity: 6,
  equipment: ['Projector', 'Whiteboard'],
  openingTime: '08:00:00',
  closingTime: '22:00:00',
  status: 'AVAILABLE',
}

const maintenanceRequest: MaintenanceResponse = {
  success: true,
  ticketId: 81,
  spaceId: 7,
  spaceName: space.name,
  building: 'COM3',
  roomNumber: '02-01',
  facilityType: 'projector',
  description: 'The projector does not turn on.',
  priority: 'HIGH',
  status: 'SUBMITTED',
  createdAt: '2099-08-13T10:00:00',
  updatedAt: '2099-08-13T10:00:00',
}

function renderMaintenance(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/facilities" element={<FacilitiesLayout />}>
          <Route path="maintenance" element={<MyMaintenancePage />} />
          <Route path="maintenance/new" element={<SubmitMaintenancePage />} />
          <Route path="maintenance/:requestId" element={<MaintenanceDetailsPage />} />
        </Route>
      </Routes>
    </MemoryRouter>,
  )
}

async function renderPrefilledForm() {
  renderMaintenance('/facilities/maintenance/new?spaceId=7')
  await waitFor(() => expect(screen.getByRole('combobox', { name: /^Space/ })).toHaveTextContent(space.name))
}

function completeForm() {
  fireEvent.change(screen.getByRole('textbox', { name: /^Issue type/ }), { target: { value: ' projector ' } })
  fireEvent.change(screen.getByRole('textbox', { name: /^Description/ }), { target: { value: ' The projector does not turn on. ' } })
}

function submitForm() {
  const form = screen.getByRole('button', { name: 'Submit Request' }).closest('form')
  if (!form) throw new Error('maintenance form is missing')
  fireEvent.submit(form)
  return form
}

describe('Facilities maintenance user pages', () => {
  const searchSpaces = vi.mocked(facilitiesApi.searchSpaces)
  const submitMaintenanceRequest = vi.mocked(facilitiesApi.submitMaintenanceRequest)
  const listMaintenanceRequests = vi.mocked(facilitiesApi.listMaintenanceRequests)
  const getMaintenanceRequest = vi.mocked(facilitiesApi.getMaintenanceRequest)

  beforeEach(() => {
    searchSpaces.mockReset().mockResolvedValue([space])
    submitMaintenanceRequest.mockReset()
    listMaintenanceRequests.mockReset()
    getMaintenanceRequest.mockReset()
  })
  afterEach(() => cleanup())

  it('renders the real maintenance form and safely prefills a known space from Space Details', async () => {
    await renderPrefilledForm()

    expect(screen.getByRole('heading', { name: 'Report Maintenance' })).toBeInTheDocument()
    expect(searchSpaces).toHaveBeenCalledWith()
    expect(screen.getByRole('textbox', { name: /^Issue type/ })).toBeRequired()
    expect(screen.getByRole('textbox', { name: /^Description/ })).toBeRequired()
    expect(screen.getByRole('combobox', { name: 'Priority' })).toHaveTextContent('Medium')
    expect(screen.getByRole('tab', { name: 'My Maintenance Requests' })).toHaveAttribute('href', '/facilities/maintenance')
  })

  it('validates required fields without calling the submit API', async () => {
    renderMaintenance('/facilities/maintenance/new')
    await waitFor(() => expect(searchSpaces).toHaveBeenCalled())

    submitForm()

    expect(await screen.findByText('Choose a space and provide both the issue type and description.')).toBeInTheDocument()
    expect(submitMaintenanceRequest).not.toHaveBeenCalled()
  })

  it('submits the backend request body and provides result navigation', async () => {
    submitMaintenanceRequest.mockResolvedValue(maintenanceRequest)
    await renderPrefilledForm()
    completeForm()

    submitForm()

    await waitFor(() => expect(submitMaintenanceRequest).toHaveBeenCalledWith({
      spaceId: 7,
      facilityType: 'projector',
      description: 'The projector does not turn on.',
      priority: 'MEDIUM',
    }))
    expect(submitMaintenanceRequest.mock.calls[0][0]).not.toHaveProperty('userId')
    expect(await screen.findByText('Maintenance request submitted')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'View request' })).toHaveAttribute('href', '/facilities/maintenance/81')
    expect(screen.getByRole('link', { name: 'View My Maintenance Requests' })).toHaveAttribute('href', '/facilities/maintenance')
  })

  it('locks submission and prevents duplicate maintenance requests', async () => {
    submitMaintenanceRequest.mockReturnValue(new Promise(() => {}))
    await renderPrefilledForm()
    completeForm()

    const form = submitForm()
    fireEvent.submit(form)

    expect(await screen.findByRole('button', { name: 'Submitting...' })).toBeDisabled()
    expect(screen.getByRole('textbox', { name: /^Issue type/ })).toBeDisabled()
    expect(submitMaintenanceRequest).toHaveBeenCalledOnce()
  })

  it('shows backend validation errors from maintenance submission', async () => {
    submitMaintenanceRequest.mockRejectedValue({
      isAxiosError: true,
      response: { status: 400, data: { code: 'INVALID_PRIORITY', error: 'Priority must be LOW, MEDIUM, or HIGH' } },
    })
    await renderPrefilledForm()
    completeForm()

    submitForm()

    expect(await screen.findByText('Priority must be LOW, MEDIUM, or HIGH')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Submit Request' })).toBeEnabled()
  })

  it('shows generic submission failures safely', async () => {
    submitMaintenanceRequest.mockRejectedValue(new Error('Facilities service unavailable'))
    await renderPrefilledForm()
    completeForm()

    submitForm()

    expect(await screen.findByText('Facilities service unavailable')).toBeInTheDocument()
  })

  it('shows loading while owned maintenance requests are requested', () => {
    listMaintenanceRequests.mockReturnValue(new Promise(() => {}))
    renderMaintenance('/facilities/maintenance')

    expect(screen.getByRole('progressbar')).toBeInTheDocument()
    expect(listMaintenanceRequests).toHaveBeenCalledOnce()
  })

  it('renders active requests first and navigates to maintenance details', async () => {
    const resolved = {
      ...maintenanceRequest,
      ticketId: 82,
      facilityType: 'lighting',
      description: 'The lighting issue has been resolved.',
      priority: 'LOW' as const,
      status: 'RESOLVED' as const,
      createdAt: '2099-08-14T10:00:00',
      updatedAt: '2099-08-14T12:00:00',
    }
    listMaintenanceRequests.mockResolvedValue([resolved, maintenanceRequest])
    getMaintenanceRequest.mockResolvedValue(maintenanceRequest)
    renderMaintenance('/facilities/maintenance')

    expect(await screen.findByRole('heading', { name: 'Request #81 · projector' })).toBeInTheDocument()
    expect(screen.getAllByRole('link', { name: 'View details' })[0]).toHaveAttribute('href', '/facilities/maintenance/81')
    expect(screen.getByText('HIGH PRIORITY')).toBeInTheDocument()
    expect(screen.getByText('The projector does not turn on.')).toBeInTheDocument()

    fireEvent.click(screen.getAllByRole('link', { name: 'View details' })[0])
    expect(await screen.findByRole('heading', { name: 'Maintenance Request #81' })).toBeInTheDocument()
    expect(getMaintenanceRequest).toHaveBeenCalledWith(81)
  })

  it('renders maintenance list empty and error states', async () => {
    listMaintenanceRequests.mockResolvedValueOnce([])
    const { unmount } = renderMaintenance('/facilities/maintenance')
    expect(await screen.findByText('No maintenance requests yet')).toBeInTheDocument()
    unmount()

    listMaintenanceRequests.mockRejectedValueOnce(new Error('Maintenance service unavailable'))
    renderMaintenance('/facilities/maintenance')
    expect(await screen.findByText('Maintenance service unavailable')).toBeInTheDocument()
    expect(screen.queryByText('No maintenance requests yet')).not.toBeInTheDocument()
  })

  it('renders detail status and fields without a user status-update action', async () => {
    getMaintenanceRequest.mockResolvedValue({ ...maintenanceRequest, status: 'IN_PROGRESS' })
    renderMaintenance('/facilities/maintenance/81')

    expect(screen.getByRole('progressbar')).toBeInTheDocument()
    expect(await screen.findByRole('heading', { name: 'Maintenance Request #81' })).toBeInTheDocument()
    expect(screen.getByText('IN PROGRESS')).toBeInTheDocument()
    expect(screen.getByText('Facilities staff are working on this request.')).toBeInTheDocument()
    expect(screen.getByText('The projector does not turn on.', { exact: false })).toBeInTheDocument()
    expect(screen.getAllByText('13 Aug 2099, 10:00 AM', { exact: false })).toHaveLength(2)
    expect(screen.queryByRole('button', { name: /update|save/i })).not.toBeInTheDocument()
  })

  it('renders owner-safe not-found and API error states', async () => {
    getMaintenanceRequest.mockRejectedValueOnce({ isAxiosError: true, response: { status: 404 } })
    const { unmount } = renderMaintenance('/facilities/maintenance/999')
    expect(await screen.findByText('Maintenance request not found.')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Back to My Maintenance Requests' })).toHaveAttribute('href', '/facilities/maintenance')
    unmount()

    getMaintenanceRequest.mockRejectedValueOnce(new Error('Maintenance service unavailable'))
    renderMaintenance('/facilities/maintenance/81')
    expect(await screen.findByText('Maintenance service unavailable')).toBeInTheDocument()
  })
})
