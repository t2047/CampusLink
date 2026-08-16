import { describe, expect, it } from 'vitest'
import type { AdminFacilityBooking } from '../../types'
import {
  buildFacilityUsageRanking,
  buildReservationTrend,
  countTodayReservations,
  getSingaporeAnalyticsWindow,
} from './facilitiesAnalytics'

function booking(overrides: Partial<AdminFacilityBooking> = {}): AdminFacilityBooking {
  return {
    bookingId: 1,
    userId: 10,
    userEmail: 'student@nus.edu.sg',
    spaceId: 5,
    spaceName: 'Seminar Room 2',
    building: 'COM3',
    floor: '2',
    roomNumber: '02-10',
    spaceType: 'SEMINAR_ROOM',
    startDateTime: '2026-08-15T10:00:00',
    endDateTime: '2026-08-15T11:00:00',
    status: 'CONFIRMED',
    createdAt: '2026-08-14T10:00:00',
    updatedAt: '2026-08-14T10:00:00',
    ...overrides,
  }
}

describe('Facilities analytics Singapore window', () => {
  it('uses the Singapore calendar date when UTC is still on the previous date', () => {
    const window = getSingaporeAnalyticsWindow(new Date('2026-08-14T16:30:00Z'))

    expect(window.today).toBe('2026-08-15')
    expect(window.startDate).toBe('2026-07-17')
    expect(window.startFrom).toBe('2026-07-17T00:00:00')
    expect(window.startTo).toBe('2026-08-15T23:59:59')
    expect(window.startFrom).not.toContain('Z')
    expect(window.startTo).not.toContain('Z')
  })

  it('always produces the last seven Singapore dates including today', () => {
    const window = getSingaporeAnalyticsWindow(new Date('2026-08-15T04:00:00Z'))
    expect(window.trendDates).toEqual([
      '2026-08-09', '2026-08-10', '2026-08-11', '2026-08-12',
      '2026-08-13', '2026-08-14', '2026-08-15',
    ])
  })
})

describe('Reservation trend and today reservations', () => {
  const trendDates = ['2026-08-09', '2026-08-10', '2026-08-11', '2026-08-12', '2026-08-13', '2026-08-14', '2026-08-15']

  it('fills missing dates with zero and counts confirmed and completed by start date', () => {
    const trend = buildReservationTrend([
      booking({ bookingId: 1, status: 'CONFIRMED', startDateTime: '2026-08-09T09:00:00' }),
      booking({ bookingId: 2, status: 'COMPLETED', startDateTime: '2026-08-13T09:00:00' }),
      booking({ bookingId: 3, status: 'CANCELLED', startDateTime: '2026-08-13T10:00:00' }),
      booking({ bookingId: 4, status: 'CONFIRMED', startDateTime: '2026-08-15T10:00:00' }),
    ], trendDates)

    expect(trend).toEqual([
      { date: '2026-08-09', count: 1 }, { date: '2026-08-10', count: 0 },
      { date: '2026-08-11', count: 0 }, { date: '2026-08-12', count: 0 },
      { date: '2026-08-13', count: 1 }, { date: '2026-08-14', count: 0 },
      { date: '2026-08-15', count: 1 },
    ])
  })

  it('renders a stable all-zero trend', () => {
    expect(buildReservationTrend([], trendDates).every(({ count }) => count === 0)).toBe(true)
  })

  it('counts only active system-wide bookings starting today', () => {
    expect(countTodayReservations([
      booking({ bookingId: 1, status: 'CONFIRMED' }),
      booking({ bookingId: 2, status: 'COMPLETED' }),
      booking({ bookingId: 3, status: 'CANCELLED' }),
      booking({ bookingId: 4, status: 'CONFIRMED', startDateTime: '2026-08-14T23:59:59' }),
    ], '2026-08-15')).toBe(2)
  })
})

describe('Facility usage ranking', () => {
  it('groups by space, excludes cancelled, and applies deterministic sorting', () => {
    const ranking = buildFacilityUsageRanking([
      booking({ bookingId: 1, spaceId: 2, spaceName: 'Beta Room', status: 'CONFIRMED' }),
      booking({ bookingId: 2, spaceId: 2, spaceName: 'Beta Room', status: 'COMPLETED' }),
      booking({ bookingId: 3, spaceId: 1, spaceName: 'Alpha Room', status: 'CONFIRMED' }),
      booking({ bookingId: 4, spaceId: 3, spaceName: 'Alpha Room', status: 'COMPLETED' }),
      booking({ bookingId: 5, spaceId: 4, spaceName: 'Cancelled Room', status: 'CANCELLED' }),
    ])

    expect(ranking).toEqual([
      { facilityId: 2, facilityName: 'Beta Room', reservationCount: 2 },
      { facilityId: 1, facilityName: 'Alpha Room', reservationCount: 1 },
      { facilityId: 3, facilityName: 'Alpha Room', reservationCount: 1 },
    ])
  })

  it('returns at most the top eight facilities', () => {
    const ranking = buildFacilityUsageRanking(Array.from({ length: 10 }, (_, index) => booking({
      bookingId: index + 1,
      spaceId: index + 1,
      spaceName: `Facility ${String(index + 1).padStart(2, '0')}`,
    })))
    expect(ranking).toHaveLength(8)
  })

  it('returns an empty ranking when no active bookings exist', () => {
    expect(buildFacilityUsageRanking([booking({ status: 'CANCELLED' })])).toEqual([])
  })
})
