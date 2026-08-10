import type { ClaimStatus } from '../../types'

export type AdminClaimStatusFilter = ClaimStatus | 'ALL'

export interface AdminClaimRouteState {
  status: AdminClaimStatusFilter
  keyword: string
  page: number
}

export const adminClaimStatuses: ClaimStatus[] = ['SUBMITTED', 'APPROVED', 'REJECTED']

function normalizeStatus(value: string | null): AdminClaimStatusFilter {
  return value === 'ALL' || adminClaimStatuses.includes(value as ClaimStatus)
    ? value as AdminClaimStatusFilter
    : 'SUBMITTED'
}

function normalizePage(value: string | null): number {
  if (!value || !/^\d+$/.test(value)) return 0
  const page = Number(value)
  return Number.isSafeInteger(page) ? page : 0
}

export function parseAdminClaimRouteState(searchParams: URLSearchParams): AdminClaimRouteState {
  return {
    status: normalizeStatus(searchParams.get('status')),
    keyword: (searchParams.get('keyword') ?? '').trim(),
    page: normalizePage(searchParams.get('page')),
  }
}

function appendRouteState(
  params: URLSearchParams,
  state: AdminClaimRouteState,
  includeDefaultPage: boolean,
) {
  params.set('status', state.status)
  const keyword = state.keyword.trim()
  if (keyword) params.set('keyword', keyword)
  if (includeDefaultPage || state.page > 0) params.set('page', String(state.page))
}

export function buildAdminClaimsListSearchParams(state: AdminClaimRouteState) {
  const params = new URLSearchParams()
  params.set('tab', 'claims')
  appendRouteState(params, state, false)
  return params
}

export function buildAdminClaimDetailHref(claimId: number, state: AdminClaimRouteState) {
  const params = new URLSearchParams()
  appendRouteState(params, state, true)
  return `/admin/lost-found/claims/${claimId}?${params.toString()}`
}
