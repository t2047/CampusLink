import { describe, expect, it } from 'vitest'
import { formatDashboardInstant } from './dashboardDateTime'

describe('formatDashboardInstant', () => {
  it('formats a UTC instant in Singapore time', () => {
    expect(formatDashboardInstant('2026-08-07T03:00:00Z')).toBe('7 Aug 2026, 11:00 am')
  })

  it('formats an offset instant as the same Singapore time point', () => {
    expect(formatDashboardInstant('2026-08-07T11:00:00+08:00')).toBe('7 Aug 2026, 11:00 am')
  })

  it('returns invalid values unchanged', () => {
    expect(formatDashboardInstant('not-a-date')).toBe('not-a-date')
  })

  it('uses an explicit Singapore timezone rather than the machine timezone', () => {
    expect(formatDashboardInstant('2026-08-06T17:30:00-04:00')).toBe('7 Aug 2026, 5:30 am')
  })
})
