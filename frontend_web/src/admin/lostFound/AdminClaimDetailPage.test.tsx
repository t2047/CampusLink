import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AdminClaimDetail } from '../../types'
import { AdminClaimDetailPage } from './AdminClaimDetailPage'

const apiMocks = vi.hoisted(() => ({
  getClaimDetail: vi.fn(),
  approveClaim: vi.fn(),
  rejectClaim: vi.fn(),
}))

vi.mock('../../api/adminLostFound', () => ({
  getAdminClaimDetail: apiMocks.getClaimDetail,
  approveAdminClaim: apiMocks.approveClaim,
  rejectAdminClaim: apiMocks.rejectClaim,
}))

const detail: AdminClaimDetail = {
  id: 42,
  status: 'APPROVED',
  proofDescription: 'The case has a scratch near the hinge.\nSerial number ends in 1234.',
  decisionNote: 'Ownership evidence verified.',
  claimant: { id: 7, email: 'claimant@nus.edu.sg', role: 'STUDENT' },
  report: {
    id: 12,
    reportType: 'FOUND',
    itemName: 'Black Headphones',
    category: 'ELECTRONICS',
    description: 'Headphones in a black hard-shell case.',
    colour: 'Black',
    location: 'Central Library',
    eventDate: '2026-08-07',
    timeDescription: 'Around noon',
    status: 'CLAIMED',
    adminHidden: true,
    owner: { id: 8, email: 'owner@nus.edu.sg' },
    images: [
      { id: 1, url: 'https://objects.example.test/one', contentType: 'image/jpeg', fileSize: 100, sortOrder: 0 },
      { id: 2, url: 'https://objects.example.test/two', contentType: 'image/png', fileSize: 200, sortOrder: 1 },
    ],
  },
  review: {
    reviewed: true,
    decisionNote: 'Ownership evidence verified.',
    reviewedAt: '2026-08-08T04:00:00Z',
  },
  createdAt: '2026-08-07T03:00:00Z',
  updatedAt: '2026-08-08T04:00:00Z',
}

const submittedDetail: AdminClaimDetail = {
  ...detail,
  status: 'SUBMITTED',
  decisionNote: null,
  report: { ...detail.report, status: 'OPEN', adminHidden: false },
  review: { reviewed: false, decisionNote: null, reviewedAt: null },
}

const approvedDetail: AdminClaimDetail = {
  ...submittedDetail,
  status: 'APPROVED',
  decisionNote: 'Evidence verified',
  report: { ...submittedDetail.report, status: 'CLAIMED' },
  review: {
    reviewed: true,
    decisionNote: 'Evidence verified',
    reviewedAt: '2026-08-09T04:00:00Z',
  },
  updatedAt: '2026-08-09T04:00:00Z',
}

const rejectedDetail: AdminClaimDetail = {
  ...submittedDetail,
  status: 'REJECTED',
  decisionNote: 'Insufficient evidence',
  review: {
    reviewed: true,
    decisionNote: 'Insufficient evidence',
    reviewedAt: '2026-08-09T04:00:00Z',
  },
  updatedAt: '2026-08-09T04:00:00Z',
}

function renderDetail(
  initialEntry = '/admin/lost-found/claims/42',
  route = '/admin/lost-found/claims/:claimId',
) {
  return render(
    <MemoryRouter initialEntries={[initialEntry]}>
      <Routes>
        <Route path={route} element={<AdminClaimDetailPage />} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('AdminClaimDetailPage', () => {
  beforeEach(() => {
    apiMocks.getClaimDetail.mockResolvedValue(detail)
    apiMocks.approveClaim.mockResolvedValue(approvedDetail)
    apiMocks.rejectClaim.mockResolvedValue(rejectedDetail)
  })

  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
  })

  it('requests the numeric claim ID and shows loading before the response resolves', async () => {
    let resolveRequest: (value: AdminClaimDetail) => void = () => undefined
    apiMocks.getClaimDetail.mockReturnValue(new Promise((resolve) => { resolveRequest = resolve }))
    renderDetail()

    expect(apiMocks.getClaimDetail).toHaveBeenCalledWith(42)
    expect(screen.getByRole('progressbar')).toBeInTheDocument()
    expect(screen.queryByText('Claim not found')).not.toBeInTheDocument()

    resolveRequest(detail)
    expect(await screen.findByRole('heading', { name: 'Claim #42' })).toBeInTheDocument()
  })

  it('renders the complete read-only claim, claimant, proof, report and review information', async () => {
    renderDetail()

    expect(await screen.findByRole('heading', { name: 'Claim #42' })).toBeInTheDocument()
    expect(within(screen.getByRole('region', { name: 'Claim summary' })).getByText('Approved')).toBeInTheDocument()
    expect(screen.getByText(/Created:/)).toHaveTextContent('7 Aug 2026')
    expect(screen.getByText(/Updated:/)).toHaveTextContent('8 Aug 2026')

    const claimant = screen.getByRole('region', { name: 'Claimant' })
    expect(within(claimant).getByText('claimant@nus.edu.sg')).toBeInTheDocument()
    expect(within(claimant).getByText('#7')).toBeInTheDocument()
    expect(within(claimant).getByText('STUDENT')).toBeInTheDocument()

    const proof = screen.getByRole('region', { name: 'Proof Submitted' })
    expect(proof).toHaveTextContent('The case has a scratch near the hinge. Serial number ends in 1234.')

    const report = screen.getByRole('region', { name: 'Related Report' })
    expect(within(report).getByText('#12')).toBeInTheDocument()
    expect(within(report).getByText('Found')).toBeInTheDocument()
    expect(within(report).getByText('Black Headphones')).toBeInTheDocument()
    expect(within(report).getByText('Electronics')).toBeInTheDocument()
    expect(within(report).getByText('Headphones in a black hard-shell case.')).toBeInTheDocument()
    expect(within(report).getByText('Black')).toBeInTheDocument()
    expect(within(report).getByText('Central Library')).toBeInTheDocument()
    expect(within(report).getByText('2026-08-07')).toBeInTheDocument()
    expect(within(report).getByText('Around noon')).toBeInTheDocument()
    expect(within(report).getByText('Claimed')).toBeInTheDocument()
    expect(within(report).getByText('Hidden')).toBeInTheDocument()
    expect(within(report).getByText('owner@nus.edu.sg')).toBeInTheDocument()
    expect(within(report).getByText('#8')).toBeInTheDocument()

    const review = screen.getByRole('region', { name: 'Review Information' })
    expect(within(review).getByText('Approved')).toBeInTheDocument()
    expect(within(review).getByText('Ownership evidence verified.')).toBeInTheDocument()
    expect(within(review).getByText(/8 Aug 2026/)).toBeInTheDocument()
    expect(screen.getAllByText('Ownership evidence verified.')).toHaveLength(1)

    expect(screen.queryByRole('button', { name: /approve/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /reject/i })).not.toBeInTheDocument()
  })

  it('renders multiple Backend image URLs with understandable alt text', async () => {
    renderDetail()
    await screen.findByRole('heading', { name: 'Claim #42' })

    expect(screen.getByRole('img', { name: 'Black Headphones image 1' })).toHaveAttribute(
      'src', 'https://objects.example.test/one',
    )
    expect(screen.getByRole('img', { name: 'Black Headphones image 2' })).toHaveAttribute(
      'src', 'https://objects.example.test/two',
    )
  })

  it('shows a no-images state', async () => {
    apiMocks.getClaimDetail.mockResolvedValue({
      ...detail,
      report: { ...detail.report, images: [] },
    })
    renderDetail()

    expect(await screen.findByText('No images provided.')).toBeInTheDocument()
  })

  it('replaces only a broken image with a local placeholder', async () => {
    renderDetail()
    const firstImage = await screen.findByRole('img', { name: 'Black Headphones image 1' })
    const secondImage = screen.getByRole('img', { name: 'Black Headphones image 2' })

    fireEvent.error(firstImage)

    expect(screen.getByText('Image unavailable.')).toBeInTheDocument()
    expect(screen.queryByRole('img', { name: 'Black Headphones image 1' })).not.toBeInTheDocument()
    expect(secondImage).toBeInTheDocument()
  })

  it('renders pending review and optional report fallbacks safely', async () => {
    apiMocks.getClaimDetail.mockResolvedValue({
      ...detail,
      status: 'SUBMITTED',
      decisionNote: null,
      report: { ...detail.report, colour: null, timeDescription: null, adminHidden: false, images: [] },
      review: { reviewed: false, decisionNote: null, reviewedAt: null },
    })
    renderDetail()

    const report = await screen.findByRole('region', { name: 'Related Report' })
    expect(within(report).getAllByText('Not provided')).toHaveLength(2)
    expect(within(report).getByText('Visible')).toBeInTheDocument()

    const review = screen.getByRole('region', { name: 'Review Information' })
    expect(within(review).getByText('Pending review')).toBeInTheDocument()
    expect(within(review).getByText('Not provided')).toBeInTheDocument()
    expect(within(review).getByText('Not reviewed')).toBeInTheDocument()
  })

  it('uses the top-level decision note only as a review fallback and does not duplicate it', async () => {
    apiMocks.getClaimDetail.mockResolvedValue({
      ...detail,
      review: { ...detail.review, decisionNote: null },
    })
    renderDetail()

    expect(await screen.findByText('Ownership evidence verified.')).toBeInTheDocument()
    expect(screen.getAllByText('Ownership evidence verified.')).toHaveLength(1)
  })

  it('builds the default Back to Claims target for direct visits', async () => {
    renderDetail()

    expect(await screen.findByRole('link', { name: 'Back to Claims' })).toHaveAttribute(
      'href', '/admin/lost-found?tab=claims&status=SUBMITTED',
    )
  })

  it('restores only normalized Claims list context', async () => {
    renderDetail('/admin/lost-found/claims/42?status=ALL&keyword=%20wallet%20&page=2&returnUrl=https%3A%2F%2Fevil.test&unknown=x')

    expect(await screen.findByRole('link', { name: 'Back to Claims' })).toHaveAttribute(
      'href', '/admin/lost-found?tab=claims&status=ALL&keyword=wallet&page=2',
    )
  })

  it('normalizes invalid list context to the submitted queue', async () => {
    renderDetail('/admin/lost-found/claims/42?status=OPEN&keyword=%20wallet%20&page=-2')

    expect(await screen.findByRole('link', { name: 'Back to Claims' })).toHaveAttribute(
      'href', '/admin/lost-found?tab=claims&status=SUBMITTED&keyword=wallet',
    )
  })

  it.each(['0', '-1', '1.5', 'abc', '9007199254740992'])('rejects invalid claimId=%s without calling the API', async (claimId) => {
    renderDetail(`/admin/lost-found/claims/${claimId}`)

    expect(await screen.findByText('Claim not found')).toBeInTheDocument()
    expect(apiMocks.getClaimDetail).not.toHaveBeenCalled()
    expect(screen.getByRole('link', { name: 'Back to Claims' })).toHaveAttribute(
      'href', '/admin/lost-found?tab=claims&status=SUBMITTED',
    )
  })

  it('distinguishes a Backend 404 from generic errors', async () => {
    apiMocks.getClaimDetail.mockRejectedValue({
      isAxiosError: true,
      response: { status: 404 },
    })
    renderDetail()

    expect(await screen.findByText('Claim not found')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Retry' })).not.toBeInTheDocument()
  })

  it('shows generic errors and retries the single Detail GET without navigation or writes', async () => {
    apiMocks.getClaimDetail
      .mockRejectedValueOnce(new Error('Service unavailable'))
      .mockResolvedValueOnce(detail)
    renderDetail('/admin/lost-found/claims/42?status=APPROVED&keyword=wallet&page=2')

    expect(await screen.findByText('Service unavailable')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }))

    expect(await screen.findByRole('heading', { name: 'Claim #42' })).toBeInTheDocument()
    expect(apiMocks.getClaimDetail).toHaveBeenCalledTimes(2)
    expect(apiMocks.getClaimDetail).toHaveBeenLastCalledWith(42)
    expect(screen.getByRole('link', { name: 'Back to Claims' })).toHaveAttribute(
      'href', '/admin/lost-found?tab=claims&status=APPROVED&keyword=wallet&page=2',
    )
  })

  it('shows Review Actions only for a SUBMITTED claim', async () => {
    apiMocks.getClaimDetail.mockResolvedValue(submittedDetail)
    renderDetail()

    expect(await screen.findByRole('button', { name: 'Approve Claim' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Reject Claim' })).toBeInTheDocument()

    cleanup()
    vi.clearAllMocks()
    apiMocks.getClaimDetail.mockResolvedValue(rejectedDetail)
    renderDetail()
    await screen.findByRole('heading', { name: 'Claim #42' })
    expect(screen.queryByRole('button', { name: 'Approve Claim' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Reject Claim' })).not.toBeInTheDocument()
  })

  it('opens the Approve Dialog with the report-impact warning', async () => {
    apiMocks.getClaimDetail.mockResolvedValue(submittedDetail)
    renderDetail()

    fireEvent.click(await screen.findByRole('button', { name: 'Approve Claim' }))

    expect(screen.getByRole('heading', { name: 'Approve Claim' })).toBeInTheDocument()
    expect(screen.getByText(/mark the related report as claimed/i)).toBeInTheDocument()
    expect(screen.getByText(/other submitted claims/i)).toBeInTheDocument()
  })

  it('approves with a blank payload, uses the POST response, and performs no additional GET', async () => {
    apiMocks.getClaimDetail.mockResolvedValue(submittedDetail)
    apiMocks.approveClaim.mockResolvedValue(approvedDetail)
    renderDetail('/admin/lost-found/claims/42?status=SUBMITTED&keyword=wallet&page=2')
    fireEvent.click(await screen.findByRole('button', { name: 'Approve Claim' }))

    fireEvent.change(screen.getByLabelText('Decision Note (optional)'), { target: { value: '   ' } })
    fireEvent.click(screen.getByRole('button', { name: 'Confirm Approve' }))

    expect(await screen.findByText('Claim approved. The related report is now marked as claimed.')).toBeInTheDocument()
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(apiMocks.approveClaim).toHaveBeenCalledTimes(1)
    expect(apiMocks.approveClaim).toHaveBeenCalledWith(42, {})
    expect(apiMocks.getClaimDetail).toHaveBeenCalledTimes(1)
    expect(within(screen.getByRole('region', { name: 'Claim summary' })).getByText('Approved')).toBeInTheDocument()
    expect(within(screen.getByRole('region', { name: 'Related Report' })).getByText('Claimed')).toBeInTheDocument()
    expect(within(screen.getByRole('region', { name: 'Review Information' })).getByText('Evidence verified')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Approve Claim' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Reject Claim' })).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Back to Claims' })).toHaveAttribute(
      'href', '/admin/lost-found?tab=claims&status=SUBMITTED&keyword=wallet&page=2',
    )
  })

  it('trims a non-empty Approve note and sends only one POST while busy', async () => {
    apiMocks.getClaimDetail.mockResolvedValue(submittedDetail)
    let resolveApprove: (value: AdminClaimDetail) => void = () => undefined
    apiMocks.approveClaim.mockReturnValue(new Promise((resolve) => { resolveApprove = resolve }))
    renderDetail()
    fireEvent.click(await screen.findByRole('button', { name: 'Approve Claim' }))
    fireEvent.change(screen.getByLabelText('Decision Note (optional)'), {
      target: { value: '  Evidence verified  ' },
    })

    const confirm = screen.getByRole('button', { name: 'Confirm Approve' })
    fireEvent.click(confirm)
    fireEvent.click(confirm)

    expect(apiMocks.approveClaim).toHaveBeenCalledTimes(1)
    expect(apiMocks.approveClaim).toHaveBeenCalledWith(42, { decisionNote: 'Evidence verified' })
    expect(screen.getByRole('button', { name: 'Approving...' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Cancel' })).toBeDisabled()

    resolveApprove(approvedDetail)
    expect(await screen.findByText('Claim approved. The related report is now marked as claimed.')).toBeInTheDocument()
  })

  it('rejects with a trimmed reason, uses the POST response, and performs no additional GET', async () => {
    apiMocks.getClaimDetail.mockResolvedValue(submittedDetail)
    renderDetail('/admin/lost-found/claims/42?status=SUBMITTED&keyword=wallet&page=2')
    fireEvent.click(await screen.findByRole('button', { name: 'Reject Claim' }))

    const confirm = screen.getByRole('button', { name: 'Confirm Reject' })
    expect(confirm).toBeDisabled()
    fireEvent.change(screen.getByLabelText('Rejection Reason'), {
      target: { value: '  Insufficient evidence  ' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Confirm Reject' }))

    expect(await screen.findByText('Claim rejected.')).toBeInTheDocument()
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(apiMocks.rejectClaim).toHaveBeenCalledTimes(1)
    expect(apiMocks.rejectClaim).toHaveBeenCalledWith(42, { decisionNote: 'Insufficient evidence' })
    expect(apiMocks.getClaimDetail).toHaveBeenCalledTimes(1)
    expect(within(screen.getByRole('region', { name: 'Claim summary' })).getByText('Rejected')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Approve Claim' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Reject Claim' })).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Back to Claims' })).toHaveAttribute(
      'href', '/admin/lost-found?tab=claims&status=SUBMITTED&keyword=wallet&page=2',
    )
  })

  it('keeps the Approve Dialog and input open for ordinary action errors without a Detail GET', async () => {
    apiMocks.getClaimDetail.mockResolvedValue(submittedDetail)
    apiMocks.approveClaim.mockRejectedValue(new Error('Service unavailable'))
    renderDetail()
    fireEvent.click(await screen.findByRole('button', { name: 'Approve Claim' }))
    fireEvent.change(screen.getByLabelText('Decision Note (optional)'), {
      target: { value: 'Keep this note' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Confirm Approve' }))

    expect(await screen.findByText('Service unavailable')).toBeInTheDocument()
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    expect(screen.getByLabelText('Decision Note (optional)')).toHaveValue('Keep this note')
    expect(apiMocks.getClaimDetail).toHaveBeenCalledTimes(1)
  })

  it('shows Backend 422 errors inside the Reject Dialog and preserves the reason', async () => {
    apiMocks.getClaimDetail.mockResolvedValue(submittedDetail)
    apiMocks.rejectClaim.mockRejectedValue({
      isAxiosError: true,
      response: { status: 422, data: { message: 'A decision note is required when rejecting a claim' } },
      message: 'Unprocessable Entity',
    })
    renderDetail()
    fireEvent.click(await screen.findByRole('button', { name: 'Reject Claim' }))
    fireEvent.change(screen.getByLabelText('Rejection Reason'), { target: { value: 'Reason' } })
    fireEvent.click(screen.getByRole('button', { name: 'Confirm Reject' }))

    expect(await screen.findByText('A decision note is required when rejecting a claim')).toBeInTheDocument()
    expect(screen.getByLabelText('Rejection Reason')).toHaveValue('Reason')
    expect(apiMocks.getClaimDetail).toHaveBeenCalledTimes(1)
  })

  it('shows the conflict warning only after one refresh GET succeeds and does not retry POST', async () => {
    let resolveRefresh: (value: AdminClaimDetail) => void = () => undefined
    apiMocks.getClaimDetail
      .mockResolvedValueOnce(submittedDetail)
      .mockReturnValueOnce(new Promise((resolve) => { resolveRefresh = resolve }))
    apiMocks.approveClaim.mockRejectedValue({
      isAxiosError: true,
      response: {
        status: 409,
        data: { code: 'CLAIM_ALREADY_DECIDED', message: 'This claim has already been decided' },
      },
      message: 'Conflict',
    })
    renderDetail('/admin/lost-found/claims/42?status=SUBMITTED&keyword=wallet&page=2')
    fireEvent.click(await screen.findByRole('button', { name: 'Approve Claim' }))
    fireEvent.click(screen.getByRole('button', { name: 'Confirm Approve' }))

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(screen.queryByText('This claim has already been decided. The latest details have been loaded.')).not.toBeInTheDocument()
    expect(apiMocks.approveClaim).toHaveBeenCalledTimes(1)
    expect(apiMocks.getClaimDetail).toHaveBeenCalledTimes(2)

    resolveRefresh(approvedDetail)
    expect(await screen.findByText('This claim has already been decided. The latest details have been loaded.')).toBeInTheDocument()
    expect(within(screen.getByRole('region', { name: 'Claim summary' })).getByText('Approved')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Approve Claim' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Reject Claim' })).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Back to Claims' })).toHaveAttribute(
      'href', '/admin/lost-found?tab=claims&status=SUBMITTED&keyword=wallet&page=2',
    )
  })

  it('shows Claim not found when the conflict refresh GET returns 404', async () => {
    apiMocks.getClaimDetail
      .mockResolvedValueOnce(submittedDetail)
      .mockRejectedValueOnce({
        isAxiosError: true,
        response: { status: 404, data: { message: 'Claim not found' } },
        message: 'Not Found',
      })
    apiMocks.approveClaim.mockRejectedValue({
      isAxiosError: true,
      response: { status: 409, data: { code: 'CLAIM_ALREADY_DECIDED' } },
      message: 'Conflict',
    })
    renderDetail()
    fireEvent.click(await screen.findByRole('button', { name: 'Approve Claim' }))
    fireEvent.click(screen.getByRole('button', { name: 'Confirm Approve' }))

    expect(await screen.findByText('Claim not found')).toBeInTheDocument()
    expect(screen.queryByText('This claim has already been decided. The latest details have been loaded.')).not.toBeInTheDocument()
    expect(apiMocks.approveClaim).toHaveBeenCalledTimes(1)
    expect(apiMocks.getClaimDetail).toHaveBeenCalledTimes(2)
  })

  it('shows the conflict refresh error without retrying the POST', async () => {
    apiMocks.getClaimDetail
      .mockResolvedValueOnce(submittedDetail)
      .mockRejectedValueOnce(new Error('Refresh unavailable'))
    apiMocks.rejectClaim.mockRejectedValue({
      isAxiosError: true,
      response: { status: 409, data: { code: 'CLAIM_ALREADY_DECIDED' } },
      message: 'Conflict',
    })
    renderDetail()
    fireEvent.click(await screen.findByRole('button', { name: 'Reject Claim' }))
    fireEvent.change(screen.getByLabelText('Rejection Reason'), { target: { value: 'Reason' } })
    fireEvent.click(screen.getByRole('button', { name: 'Confirm Reject' }))

    expect(await screen.findByText('Refresh unavailable')).toBeInTheDocument()
    expect(screen.queryByText('This claim has already been decided. The latest details have been loaded.')).not.toBeInTheDocument()
    expect(apiMocks.rejectClaim).toHaveBeenCalledTimes(1)
    expect(apiMocks.getClaimDetail).toHaveBeenCalledTimes(2)
  })

  it('keeps a non-target 409 in the Dialog as a normal action error', async () => {
    apiMocks.getClaimDetail.mockResolvedValue(submittedDetail)
    apiMocks.approveClaim.mockRejectedValue({
      isAxiosError: true,
      response: { status: 409, data: { code: 'REPORT_NOT_OPEN', message: 'Report is no longer open' } },
      message: 'Conflict',
    })
    renderDetail()
    fireEvent.click(await screen.findByRole('button', { name: 'Approve Claim' }))
    fireEvent.click(screen.getByRole('button', { name: 'Confirm Approve' }))

    expect(await screen.findByText('Report is no longer open')).toBeInTheDocument()
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    expect(apiMocks.approveClaim).toHaveBeenCalledTimes(1)
    expect(apiMocks.getClaimDetail).toHaveBeenCalledTimes(1)
  })

})
