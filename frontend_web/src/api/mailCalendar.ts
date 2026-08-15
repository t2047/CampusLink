import { apiClient } from './client'
import type {
  CalendarEvent,
  CalendarEventInput,
  CalendarEventUpdate,
  CalendarImportResponse,
  ExtractResponse,
  ExtractedSchedule,
} from '../types'

/** List the current user's calendar events, optionally bounded by ISO datetime range. */
export async function listCalendarEvents(start?: string, end?: string): Promise<CalendarEvent[]> {
  const response = await apiClient.get<CalendarEvent[]>('/mail/calendar/events', {
    params: { start, end },
  })
  return response.data
}

export async function createCalendarEvent(input: CalendarEventInput): Promise<CalendarEvent> {
  const response = await apiClient.post<CalendarEvent>('/mail/calendar/events', input)
  return response.data
}

export async function updateCalendarEvent(
  id: string,
  patch: CalendarEventUpdate,
): Promise<CalendarEvent> {
  const response = await apiClient.patch<CalendarEvent>(`/mail/calendar/events/${id}`, patch)
  return response.data
}

export async function deleteCalendarEvent(id: string): Promise<void> {
  await apiClient.delete(`/mail/calendar/events/${id}`)
}

/**
 * Scan recent emails for date/time mentions and return proposed schedules.
 * `days` = how many past days to scan (0 = today only). Nothing is imported yet.
 */
export async function extractCalendarSchedules(days: number): Promise<ExtractResponse> {
  const response = await apiClient.post<ExtractResponse>('/mail/calendar/extract', null, {
    params: { days },
  })
  return response.data
}

/** Import user-confirmed schedules extracted from mail into the calendar. */
export async function importCalendarSchedules(
  events: ExtractedSchedule[],
): Promise<CalendarImportResponse> {
  const response = await apiClient.post<CalendarImportResponse>(
    '/mail/calendar/import',
    { events },
    { timeout: 60_000 },
  )
  return response.data
}
