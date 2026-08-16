import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { StrictMode } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { searchAdminAuditLogs } from '../../../api/adminLostFound'
import type { AdminAuditLog, AuditAction, PageResponse } from '../../../types'
import { dashboardAuditActionLabels } from '../../../labels'
import { RecentAuditActivitySection } from './RecentAuditActivitySection'

vi.mock('../../../api/adminLostFound', () => ({
  searchAdminAuditLogs: vi.fn(),
}))

const searchAuditLogs = vi.mocked(searchAdminAuditLogs)

const auditFixture: AdminAuditLog = {
  id: 1,
  reportId: 42,
  itemName: 'Black Headphones',
  action: 'REPORT_DELISTED',
  actorEmail: 'administrator@nus.edu.sg',
  reason: 'Duplicate report',
  detail: 'The report duplicated an existing Lost & Found record.',
  createdAt: '2026-08-07T03:00:00Z',
}

function auditPage(content: AdminAuditLog[]): PageResponse<AdminAuditLog> {
  return {
    content,
    page: 0,
    size: 5,
    totalElements: content.length,
    totalPages: content.length === 0 ? 0 : Math.ceil(content.length / 5),
    first: true,
    last: content.length <= 5,
  }
}

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((resolvePromise) => {
    resolve = resolvePromise
  })
  return { promise, resolve }
}

function renderSection() {
  return render(
    <MemoryRouter>
      <RecentAuditActivitySection />
    </MemoryRouter>,
  )
}

describe('RecentAuditActivitySection', () => {
  beforeEach(() => {
    searchAuditLogs.mockReset()
    searchAuditLogs.mockResolvedValue(auditPage([auditFixture]))
  })

  afterEach(() => cleanup())

  it('keeps a complete type-safe label mapping for every AuditAction', () => {
    const expected: Record<AuditAction, string> = {
      REPORT_CREATED: 'Report Created',
      REPORT_UPDATED: 'Report Updated',
      REPORT_CLOSED: 'Report Closed',
      REPORT_DELETED: 'Report Deleted',
      REPORT_DELISTED: 'Report Delisted',
      REPORT_RESTORED: 'Report Restored',
      REPORT_DELETED_BY_ADMIN: 'Report Deleted By Admin',
      REPORT_CLAIMED: 'Report Claimed',
      CLAIM_APPROVED_BY_ADMIN: 'Claim Approved By Admin',
      CLAIM_REJECTED_BY_ADMIN: 'Claim Rejected By Admin',
    }
    expect(dashboardAuditActionLabels).toEqual(expected)
  })

  it('requests the recent audit page and shows accessible loading', () => {
    const pending = deferred<PageResponse<AdminAuditLog>>()
    searchAuditLogs.mockReturnValue(pending.promise)

    renderSection()

    expect(searchAuditLogs).toHaveBeenCalledWith({ page: 0, size: 5, sort: 'createdAt,desc' })
    expect(screen.getByRole('status')).toHaveTextContent('Loading recent administrative activity')
    expect(screen.getByLabelText('Loading recent administrative activity')).toBeInTheDocument()
  })

  it('displays approved and rejected claim actions with natural labels', async () => {
    searchAuditLogs.mockResolvedValue(auditPage([
      { ...auditFixture, id: 2, action: 'CLAIM_APPROVED_BY_ADMIN' },
      { ...auditFixture, id: 3, action: 'CLAIM_REJECTED_BY_ADMIN' },
    ]))

    renderSection()

    const table = await screen.findByRole('table', { name: 'Recent administrative activity' })
    expect(within(table).getByText('Claim Approved By Admin')).toBeInTheDocument()
    expect(within(table).getByText('Claim Rejected By Admin')).toBeInTheDocument()
  })

  it('shows time, item/report, actor, reason, supporting detail, and View All', async () => {
    renderSection()

    const table = await screen.findByRole('table', { name: 'Recent administrative activity' })
    expect(within(table).getByText('7 Aug 2026, 11:00 am')).toBeInTheDocument()
    expect(within(table).getByText('Report Delisted')).toBeInTheDocument()
    expect(within(table).getByText('Black Headphones')).toBeInTheDocument()
    expect(within(table).getByText('Report #42')).toBeInTheDocument()
    expect(within(table).getByText('administrator@nus.edu.sg')).toBeInTheDocument()
    expect(within(table).getByText('Duplicate report')).toBeInTheDocument()
    expect(within(table).getByText('The report duplicated an existing Lost & Found record.')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'View All' })).toHaveAttribute('href', '/admin/lost-found?tab=audit')
  })

  it('uses detail as fallback, an em dash when absent, and summarizes long text', async () => {
    const longReason = 'x'.repeat(130)
    searchAuditLogs.mockResolvedValue(auditPage([
      { ...auditFixture, id: 2, reason: null, detail: 'Detail-only audit entry.' },
      { ...auditFixture, id: 3, reason: null, detail: null },
      { ...auditFixture, id: 4, reason: longReason, detail: null },
    ]))

    renderSection()

    const table = await screen.findByRole('table', { name: 'Recent administrative activity' })
    expect(within(table).getByText('Detail-only audit entry.')).toBeInTheDocument()
    expect(within(table).getByText('?')).toBeInTheDocument()
    expect(within(table).getByText(`${'x'.repeat(117)}...`)).toBeInTheDocument()
    expect(within(table).queryByText(longReason)).not.toBeInTheDocument()
  })

  it('shows at most five audit records', async () => {
    searchAuditLogs.mockResolvedValue(auditPage(Array.from({ length: 6 }, (_, index) => ({
      ...auditFixture,
      id: index + 1,
      reportId: index + 10,
      itemName: `Audit Item ${index + 1}`,
    }))))

    renderSection()

    const table = await screen.findByRole('table', { name: 'Recent administrative activity' })
    expect(within(table).getAllByRole('row')).toHaveLength(6)
    expect(within(table).getByText('Audit Item 5')).toBeInTheDocument()
    expect(within(table).queryByText('Audit Item 6')).not.toBeInTheDocument()
  })

  it('shows the empty state', async () => {
    searchAuditLogs.mockResolvedValue(auditPage([]))

    renderSection()

    expect(await screen.findByText('No administrative activity is currently available.')).toBeInTheDocument()
  })

  it('shows an error without stale data and retries successfully', async () => {
    const retry = deferred<PageResponse<AdminAuditLog>>()
    searchAuditLogs.mockRejectedValueOnce(new Error('Unable to load audit activity.')).mockReturnValueOnce(retry.promise)

    renderSection()

    expect(await screen.findByRole('alert')).toHaveTextContent('Unable to load audit activity.')
    expect(screen.queryByRole('table', { name: 'Recent administrative activity' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }))
    expect(screen.getByRole('status')).toHaveTextContent('Loading recent administrative activity')
    expect(searchAuditLogs).toHaveBeenCalledTimes(2)

    await act(async () => {
      retry.resolve(auditPage([auditFixture]))
      await retry.promise
    })

    await waitFor(() => expect(screen.queryByRole('alert')).not.toBeInTheDocument())
    expect(screen.getByRole('table', { name: 'Recent administrative activity' })).toBeInTheDocument()
  })

  it('prevents duplicate concurrent requests and settles safely after unmount', async () => {
    const pending = deferred<PageResponse<AdminAuditLog>>()
    searchAuditLogs.mockReturnValue(pending.promise)
    const view = render(
      <StrictMode>
        <MemoryRouter><RecentAuditActivitySection /></MemoryRouter>
      </StrictMode>,
    )

    expect(searchAuditLogs).toHaveBeenCalledTimes(1)
    view.unmount()
    await act(async () => {
      pending.resolve(auditPage([auditFixture]))
      await pending.promise
    })
    expect(searchAuditLogs).toHaveBeenCalledTimes(1)
  })
})


