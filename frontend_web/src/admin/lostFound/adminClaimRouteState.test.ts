import { describe, expect, it } from 'vitest'
import {
  buildAdminClaimDetailHref,
  buildAdminClaimsListSearchParams,
  parseAdminClaimRouteState,
} from './adminClaimRouteState'

describe('adminClaimRouteState', () => {
  it('normalizes status, trims keyword, validates page, and ignores unrelated parameters', () => {
    expect(parseAdminClaimRouteState(new URLSearchParams(
      'status=OPEN&keyword=%20%20wallet%20%20&page=-3&returnUrl=https%3A%2F%2Fevil.test',
    ))).toEqual({ status: 'SUBMITTED', keyword: 'wallet', page: 0 })
  })

  it('preserves ALL and a safe zero-based page', () => {
    expect(parseAdminClaimRouteState(new URLSearchParams('status=ALL&keyword=headphones&page=2'))).toEqual({
      status: 'ALL',
      keyword: 'headphones',
      page: 2,
    })
  })

  it('serializes the canonical Claims list query and omits the default page', () => {
    expect(buildAdminClaimsListSearchParams({
      status: 'SUBMITTED',
      keyword: '  wallet  ',
      page: 0,
    }).toString()).toBe('tab=claims&status=SUBMITTED&keyword=wallet')
  })

  it('builds a Detail href from only the normalized Claims route context', () => {
    expect(buildAdminClaimDetailHref(42, {
      status: 'ALL',
      keyword: '  wallet  ',
      page: 2,
    })).toBe('/admin/lost-found/claims/42?status=ALL&keyword=wallet&page=2')
  })

  it('keeps page zero in Detail links so the captured list context is explicit', () => {
    expect(buildAdminClaimDetailHref(42, { status: 'SUBMITTED', keyword: '', page: 0 }))
      .toBe('/admin/lost-found/claims/42?status=SUBMITTED&page=0')
  })
})
