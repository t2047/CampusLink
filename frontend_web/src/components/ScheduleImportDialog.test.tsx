import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  extractCalendarSchedules,
  importCalendarSchedules,
} from '../api/mailCalendar'
import { ScheduleImportDialog } from './ScheduleImportDialog'
import type { ExtractedSchedule } from '../types'

vi.mock('../api/mailCalendar', () => ({
  extractCalendarSchedules: vi.fn(),
  importCalendarSchedules: vi.fn(),
}))

const schedule: ExtractedSchedule = {
  key: 'k1',
  title: 'CS2103 Final Exam',
  description: 'The exam will be held on 2026-08-10 at 14:00 in LT19.',
  location: 'LT19',
  start_time: '2026-08-10T14:00:00',
  end_time: '2026-08-10T15:00:00',
  all_day: false,
  source_email_id: 'msg-1',
  email_subject: 'CS2103 Final Exam',
}

describe('ScheduleImportDialog', () => {
  const extract = vi.mocked(extractCalendarSchedules)
  const importSchedules = vi.mocked(importCalendarSchedules)

  beforeEach(() => {
    extract.mockReset()
    importSchedules.mockReset()
    extract.mockResolvedValue({ days: 0, scanned: 1, mode: 'llm', events: [schedule] })
    importSchedules.mockResolvedValue({ imported: 1, skipped: 0, events: [] })
  })

  afterEach(() => cleanup())

  it('scans mail and shows extracted schedules for confirmation', async () => {
    render(<ScheduleImportDialog open onClose={() => {}} />)

    fireEvent.click(screen.getByRole('button', { name: /scan mail for schedules/i }))

    expect(await screen.findByText('CS2103 Final Exam')).toBeInTheDocument()
    expect(screen.getByText(/2026-08-10 14:00 – 15:00/)).toBeInTheDocument()
    expect(screen.getByText(/LT19/)).toBeInTheDocument()
    expect(extract).toHaveBeenCalledWith(0)
  })

  it('imports only after user confirms and reports the result', async () => {
    const onImported = vi.fn()
    render(<ScheduleImportDialog open onClose={() => {}} onImported={onImported} />)

    fireEvent.click(screen.getByRole('button', { name: /scan mail for schedules/i }))
    fireEvent.click(await screen.findByRole('button', { name: /import 1 to calendar/i }))

    await waitFor(() => expect(importSchedules).toHaveBeenCalledWith([schedule]))
    expect(await screen.findByText(/Imported 1 schedule/)).toBeInTheDocument()
    expect(onImported).toHaveBeenCalledWith(1)
  })

  it('skips import when nothing is selected', async () => {
    render(<ScheduleImportDialog open onClose={() => {}} />)

    fireEvent.click(screen.getByRole('button', { name: /scan mail for schedules/i }))
    const checkbox = (await screen.findByRole('checkbox')) as HTMLInputElement
    fireEvent.click(checkbox)

    expect(screen.getByRole('button', { name: /import 0 to calendar/i })).toBeDisabled()
  })

  it('shows a helpful message when Gmail is not connected', async () => {
    extract.mockRejectedValue({
      response: { data: { code: 'GMAIL_NOT_CONNECTED' } },
    })
    render(<ScheduleImportDialog open onClose={() => {}} />)

    fireEvent.click(screen.getByRole('button', { name: /scan mail for schedules/i }))

    expect(await screen.findByText(/Gmail is not connected/i)).toBeInTheDocument()
  })
})
