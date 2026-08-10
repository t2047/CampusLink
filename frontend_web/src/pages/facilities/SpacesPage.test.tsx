import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Outlet, Route, Routes, useLocation } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { facilitiesApi, type Space } from '../../api/facilities'
import { FacilitiesLayout } from './FacilitiesLayout'
import { SpaceDetailsPage } from './SpaceDetailsPage'
import { SpacesPage } from './SpacesPage'

vi.mock('../../api/facilities', () => ({
  facilitiesApi: { searchSpaces: vi.fn(), getSpace: vi.fn() },
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

function LocationProbe() {
  const location = useLocation()
  return <output aria-label="Current location">{location.pathname}{location.search}</output>
}

function renderFacilities(path = '/facilities') {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <LocationProbe />
      <Routes>
        <Route path="/facilities" element={<FacilitiesLayout />}>
          <Route index element={<SpacesPage />} />
          <Route path="spaces/:spaceId" element={<SpaceDetailsPage />} />
        </Route>
        <Route path="*" element={<Outlet />} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('Facilities spaces pages', () => {
  const searchSpaces = vi.mocked(facilitiesApi.searchSpaces)
  const getSpace = vi.mocked(facilitiesApi.getSpace)

  beforeEach(() => {
    searchSpaces.mockReset()
    getSpace.mockReset()
  })
  afterEach(() => cleanup())

  it('shows a loading state while spaces are requested', () => {
    searchSpaces.mockReturnValue(new Promise(() => {}))
    renderFacilities()

    expect(screen.getByRole('progressbar')).toBeInTheDocument()
  })

  it('renders search results and navigates to the selected space details', async () => {
    searchSpaces.mockResolvedValue([space])
    getSpace.mockResolvedValue(space)
    renderFacilities()

    expect(await screen.findByRole('heading', { name: space.name })).toBeInTheDocument()
    expect(screen.getByText('COM3 · Floor 2 · Room 02-01')).toBeInTheDocument()
    expect(screen.getByText('Projector, Whiteboard', { exact: false })).toBeInTheDocument()

    fireEvent.click(screen.getByRole('link', { name: `View ${space.name}` }))

    await waitFor(() => expect(screen.getByLabelText('Current location')).toHaveTextContent('/facilities/spaces/7'))
    expect(await screen.findByRole('heading', { name: space.name })).toBeInTheDocument()
    expect(getSpace).toHaveBeenCalledWith(7)
    expect(screen.getByRole('heading', { name: 'Space Details' })).toBeInTheDocument()
    expect(screen.getByText('08:00:00')).toBeInTheDocument()
    expect(screen.getByText('22:00:00')).toBeInTheDocument()
  })

  it('renders an empty result state', async () => {
    searchSpaces.mockResolvedValue([])
    renderFacilities()

    expect(await screen.findByText('No matching spaces')).toBeInTheDocument()
  })

  it('renders a safe search error', async () => {
    searchSpaces.mockRejectedValue(new Error('Facilities service unavailable'))
    renderFacilities()

    expect(await screen.findByText('Facilities service unavailable')).toBeInTheDocument()
    expect(screen.queryByText('No matching spaces')).not.toBeInTheDocument()
  })

  it('restores URL filters and sends them to the search API without changing local datetime', async () => {
    searchSpaces.mockResolvedValue([])
    renderFacilities('/facilities?building=COM3&minimumCapacity=4&equipment=Projector&equipment=Whiteboard&startDateTime=2026-08-11T14%3A00&endDateTime=2026-08-11T16%3A00')

    await waitFor(() => expect(searchSpaces).toHaveBeenCalledWith({
      query: undefined,
      building: 'COM3',
      spaceType: undefined,
      minimumCapacity: 4,
      equipment: ['Projector', 'Whiteboard'],
      startDateTime: '2026-08-11T14:00',
      endDateTime: '2026-08-11T16:00',
    }))
    expect(screen.getByLabelText('Building')).toHaveValue('COM3')
    expect(screen.getByLabelText('Minimum Capacity')).toHaveValue(4)
    expect(screen.getByLabelText('Equipment')).toHaveValue('Projector, Whiteboard')
    expect(screen.getByLabelText('Start datetime')).toHaveValue('2026-08-11T14:00')
  })

  it('updates URL search state and clears it with Reset', async () => {
    searchSpaces.mockResolvedValue([])
    renderFacilities()
    await screen.findByText('No matching spaces')

    fireEvent.change(screen.getByLabelText('Keyword'), { target: { value: 'study' } })
    fireEvent.change(screen.getByLabelText('Building'), { target: { value: 'COM3' } })
    fireEvent.change(screen.getByLabelText('Equipment'), { target: { value: 'Projector, Whiteboard' } })
    fireEvent.click(screen.getByRole('button', { name: 'Search' }))

    await waitFor(() => expect(screen.getByLabelText('Current location')).toHaveTextContent('/facilities?query=study&building=COM3&equipment=Projector&equipment=Whiteboard'))
    fireEvent.click(screen.getByRole('button', { name: 'Reset' }))
    await waitFor(() => expect(screen.getByLabelText('Current location')).toHaveTextContent(/^\/facilities$/))
    expect(screen.getByLabelText('Keyword')).toHaveValue('')
    expect(screen.getByLabelText('Building')).toHaveValue('')
  })

  it('renders a friendly details not-found state', async () => {
    getSpace.mockRejectedValue({ isAxiosError: true, response: { status: 404 } })
    renderFacilities('/facilities/spaces/999')

    expect(await screen.findByText('Space not found.')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Back to spaces' })).toHaveAttribute('href', '/facilities')
  })
})
