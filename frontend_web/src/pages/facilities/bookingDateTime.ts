import type { BookingResponse } from '../../api/facilities'

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec']

interface LocalDateTimeParts {
  year: number
  month: number
  day: number
  hour: number
  minute: number
}

function parseLocalDateTime(value: string): LocalDateTimeParts | null {
  const match = value.match(/^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})/)
  if (!match) return null
  return {
    year: Number(match[1]),
    month: Number(match[2]),
    day: Number(match[3]),
    hour: Number(match[4]),
    minute: Number(match[5]),
  }
}

function formatTime(parts: LocalDateTimeParts) {
  const hour = parts.hour % 12 || 12
  return `${hour}:${String(parts.minute).padStart(2, '0')} ${parts.hour < 12 ? 'AM' : 'PM'}`
}

export function formatFacilityDate(value: string) {
  const parts = parseLocalDateTime(value)
  return parts ? `${parts.day} ${MONTHS[parts.month - 1]} ${parts.year}` : value
}

export function formatFacilityDateTime(value: string) {
  const parts = parseLocalDateTime(value)
  return parts ? `${formatFacilityDate(value)}, ${formatTime(parts)}` : value
}

export function formatFacilityTimeRange(start: string, end: string) {
  const startParts = parseLocalDateTime(start)
  const endParts = parseLocalDateTime(end)
  if (!startParts || !endParts) return `${start} – ${end}`
  return `${formatTime(startParts)} – ${formatTime(endParts)}`
}

export function singaporeLocalNow() {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Singapore',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hourCycle: 'h23',
  }).formatToParts(new Date())
  const value = (type: Intl.DateTimeFormatPartTypes) => parts.find((part) => part.type === type)?.value ?? ''
  return `${value('year')}-${value('month')}-${value('day')}T${value('hour')}:${value('minute')}:${value('second')}`
}

export function canCancelBooking(booking: BookingResponse) {
  return booking.status === 'CONFIRMED' && booking.startDateTime > singaporeLocalNow()
}

export function sortBookings(bookings: BookingResponse[]) {
  const now = singaporeLocalNow()
  const group = (booking: BookingResponse) => {
    if (booking.status === 'CONFIRMED' && booking.startDateTime > now) return 0
    if (booking.startDateTime > now) return 1
    return 2
  }
  return [...bookings].sort((left, right) => {
    const groupDifference = group(left) - group(right)
    if (groupDifference) return groupDifference
    return group(left) < 2
      ? left.startDateTime.localeCompare(right.startDateTime)
      : right.startDateTime.localeCompare(left.startDateTime)
  })
}
