import type { AdminFacilityBooking } from '../../types'

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec']
const ACTIVE_STATUSES = new Set(['CONFIRMED', 'COMPLETED'])

export interface SingaporeAnalyticsWindow {
  today: string
  startDate: string
  startFrom: string
  startTo: string
  trendDates: string[]
}

export interface ReservationTrendPoint {
  date: string
  count: number
}

export interface FacilityUsageEntry {
  facilityId: number
  facilityName: string
  reservationCount: number
}

function singaporeDate(now: Date) {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Singapore',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).formatToParts(now)
  const value = (type: Intl.DateTimeFormatPartTypes) => parts.find((part) => part.type === type)?.value ?? ''
  return `${value('year')}-${value('month')}-${value('day')}`
}

function shiftDate(date: string, days: number) {
  const [year, month, day] = date.split('-').map(Number)
  const shifted = new Date(Date.UTC(year, month - 1, day + days))
  return `${shifted.getUTCFullYear()}-${String(shifted.getUTCMonth() + 1).padStart(2, '0')}-${String(shifted.getUTCDate()).padStart(2, '0')}`
}

function bookingDate(booking: AdminFacilityBooking) {
  const match = booking.startDateTime.match(/^\d{4}-\d{2}-\d{2}/)
  return match?.[0] ?? ''
}

function isActive(booking: AdminFacilityBooking) {
  return ACTIVE_STATUSES.has(booking.status)
}

export function getSingaporeAnalyticsWindow(now = new Date()): SingaporeAnalyticsWindow {
  const today = singaporeDate(now)
  const startDate = shiftDate(today, -29)
  return {
    today,
    startDate,
    startFrom: `${startDate}T00:00:00`,
    startTo: `${today}T23:59:59`,
    trendDates: Array.from({ length: 7 }, (_, index) => shiftDate(today, index - 6)),
  }
}

export function formatAnalyticsDateLabel(date: string) {
  const match = date.match(/^\d{4}-(\d{2})-(\d{2})$/)
  if (!match) return date
  return `${Number(match[2])} ${MONTHS[Number(match[1]) - 1]}`
}

export function buildReservationTrend(
  bookings: AdminFacilityBooking[],
  dates: string[],
): ReservationTrendPoint[] {
  const counts = new Map(dates.map((date) => [date, 0]))
  bookings.filter(isActive).forEach((booking) => {
    const date = bookingDate(booking)
    if (counts.has(date)) counts.set(date, (counts.get(date) ?? 0) + 1)
  })
  return dates.map((date) => ({ date, count: counts.get(date) ?? 0 }))
}

export function countTodayReservations(bookings: AdminFacilityBooking[], today: string) {
  return bookings.filter((booking) => isActive(booking) && bookingDate(booking) === today).length
}

export function buildFacilityUsageRanking(bookings: AdminFacilityBooking[]): FacilityUsageEntry[] {
  const usage = new Map<number, FacilityUsageEntry>()
  bookings.filter(isActive).forEach((booking) => {
    const current = usage.get(booking.spaceId)
    if (current) {
      current.reservationCount += 1
    } else {
      usage.set(booking.spaceId, {
        facilityId: booking.spaceId,
        facilityName: booking.spaceName,
        reservationCount: 1,
      })
    }
  })

  return [...usage.values()]
    .sort((left, right) =>
      right.reservationCount - left.reservationCount
      || left.facilityName.localeCompare(right.facilityName, 'en')
      || left.facilityId - right.facilityId)
    .slice(0, 8)
}
