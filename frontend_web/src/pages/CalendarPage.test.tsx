import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  createCalendarEvent,
  listCalendarEvents,
  updateCalendarEvent,
} from '../api/mailCalendar'
import { CalendarPage } from './CalendarPage'
import type { CalendarEvent } from '../types'

vi.mock('../api/mailCalendar', () => ({
  listCalendarEvents: vi.fn(),
  createCalendarEvent: vi.fn(),
  updateCalendarEvent: vi.fn(),
  deleteCalendarEvent: vi.fn(),
  extractCalendarSchedules: vi.fn(),
  importCalendarSchedules: vi.fn(),
}))

const today = new Date()
const year = today.getFullYear()
const month = String(today.getMonth() + 1).padStart(2, '0')
const day = String(today.getDate()).padStart(2, '0')
const eventDay = `${year}-${month}-${day}`

const event: CalendarEvent = {
  id: 'evt-1',
  user_id: 'user-1',
  title: 'Team Standup',
  description: 'Daily sync',
  location: 'Zoom',
  start_time: `${eventDay}T09:00:00`,
  end_time: `${eventDay}T09:30:00`,
  all_day: false,
  source: 'manual',
  source_email_id: null,
  created_at: '2026-08-01T00:00:00Z',
  updated_at: '2026-08-01T00:00:00Z',
}

describe('CalendarPage', () => {
  const list = vi.mocked(listCalendarEvents)
  const create = vi.mocked(createCalendarEvent)
  const update = vi.mocked(updateCalendarEvent)

  beforeEach(() => {
    list.mockReset()
    create.mockReset()
    update.mockReset()
    list.mockResolvedValue([event])
    create.mockResolvedValue(event)
    update.mockResolvedValue(event)
  })

  afterEach(() => cleanup())

  it('renders the month grid and loads events', async () => {
    render(<CalendarPage />)

    expect(await screen.findByText(/Team Standup/)).toBeInTheDocument()
    expect(screen.getByText(/Calendar/)).toBeInTheDocument()
    expect(list).toHaveBeenCalled()
  })

  it('creates a new event from the dialog', async () => {
    render(<CalendarPage />)
    await screen.findByText(/Team Standup/)

    fireEvent.click(screen.getByRole('button', { name: /new event/i }))
    fireEvent.change(screen.getByLabelText(/title/i), { target: { value: 'Lunch with advisor' } })
    fireEvent.click(screen.getByRole('button', { name: /create event/i }))

    await waitFor(() =>
      expect(create).toHaveBeenCalledWith(
        expect.objectContaining({ title: 'Lunch with advisor' }),
      ),
    )
  })

  it('opens an event for editing with prefilled values', async () => {
    render(<CalendarPage />)
    await screen.findByText(/Team Standup/)

    fireEvent.click(screen.getByText(/Team Standup/))
    expect(screen.getByLabelText(/title/i)).toHaveValue('Team Standup')
    expect(screen.getByText('Edit event')).toBeInTheDocument()
  })

  it('saves changes when editing an event', async () => {
    render(<CalendarPage />)
    await screen.findByText(/Team Standup/)

    fireEvent.click(screen.getByText(/Team Standup/))
    fireEvent.change(screen.getByLabelText(/title/i), { target: { value: 'Standup moved' } })
    fireEvent.click(screen.getByRole('button', { name: /save changes/i }))

    await waitFor(() =>
      expect(update).toHaveBeenCalledWith(
        'evt-1',
        expect.objectContaining({ title: 'Standup moved' }),
      ),
    )
  })
})
