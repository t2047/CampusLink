import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { AdminClaimDecisionDialog } from './AdminClaimDecisionDialog'

const baseProps = {
  open: true,
  action: 'approve' as const,
  claimId: 42,
  reportId: 12,
  itemName: 'Black Headphones',
  busy: false,
  error: '',
  onClose: vi.fn(),
  onConfirm: vi.fn(),
}

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe('AdminClaimDecisionDialog', () => {
  it('renders the Approve warning and submits a blank optional note as an empty value', () => {
    const onConfirm = vi.fn()
    render(<AdminClaimDecisionDialog {...baseProps} onConfirm={onConfirm} />)

    expect(screen.getByRole('heading', { name: 'Approve Claim' })).toBeInTheDocument()
    expect(screen.getByText('Claim #42 | Report #12 | Black Headphones')).toBeInTheDocument()
    expect(screen.getByText(/mark the related report as claimed/i)).toBeInTheDocument()
    expect(screen.getByText(/automatically reject any other submitted claims/i)).toBeInTheDocument()
    expect(screen.getByLabelText('Decision Note (optional)')).toBeInTheDocument()

    fireEvent.change(screen.getByLabelText('Decision Note (optional)'), { target: { value: '   ' } })
    fireEvent.click(screen.getByRole('button', { name: 'Confirm Approve' }))

    expect(onConfirm).toHaveBeenCalledWith('')
  })

  it('trims a non-empty Approve note before confirm', () => {
    const onConfirm = vi.fn()
    render(<AdminClaimDecisionDialog {...baseProps} onConfirm={onConfirm} />)

    fireEvent.change(screen.getByLabelText('Decision Note (optional)'), {
      target: { value: '  Evidence verified  ' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Confirm Approve' }))

    expect(onConfirm).toHaveBeenCalledWith('Evidence verified')
  })

  it('blocks an Approve note over 500 characters with clear helper text', () => {
    render(<AdminClaimDecisionDialog {...baseProps} />)

    fireEvent.change(screen.getByLabelText('Decision Note (optional)'), {
      target: { value: 'a'.repeat(501) },
    })

    expect(screen.getByText('Decision note must be at most 500 characters.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Confirm Approve' })).toBeDisabled()
  })

  it('applies the Approve length limit after trimming surrounding whitespace', () => {
    const onConfirm = vi.fn()
    render(<AdminClaimDecisionDialog {...baseProps} onConfirm={onConfirm} />)
    const input = screen.getByLabelText('Decision Note (optional)')

    fireEvent.change(input, { target: { value: `  ${'a'.repeat(500)}  ` } })
    fireEvent.click(screen.getByRole('button', { name: 'Confirm Approve' }))
    expect(onConfirm).toHaveBeenCalledWith('a'.repeat(500))

    fireEvent.change(input, { target: { value: `  ${'a'.repeat(501)}  ` } })
    expect(screen.getByText('Decision note must be at most 500 characters.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Confirm Approve' })).toBeDisabled()
  })

  it('requires a trimmed Reject reason without imposing a ten-character minimum', () => {
    const onConfirm = vi.fn()
    render(<AdminClaimDecisionDialog {...baseProps} action="reject" onConfirm={onConfirm} />)

    expect(screen.getByRole('heading', { name: 'Reject Claim' })).toBeInTheDocument()
    expect(screen.getByText('Provide a clear reason for rejecting this claim.')).toBeInTheDocument()
    const input = screen.getByLabelText('Rejection Reason')
    expect(input).toBeRequired()
    expect(screen.getByText('A rejection reason is required.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Confirm Reject' })).toBeDisabled()

    fireEvent.change(input, { target: { value: '   ' } })
    expect(screen.getByRole('button', { name: 'Confirm Reject' })).toBeDisabled()

    fireEvent.change(input, { target: { value: '  N  ' } })
    fireEvent.click(screen.getByRole('button', { name: 'Confirm Reject' }))
    expect(onConfirm).toHaveBeenCalledWith('N')
  })

  it('allows a 500-character Reject reason and blocks 501 characters', () => {
    const onConfirm = vi.fn()
    render(<AdminClaimDecisionDialog {...baseProps} action="reject" onConfirm={onConfirm} />)
    const input = screen.getByLabelText('Rejection Reason')

    fireEvent.change(input, { target: { value: `  ${'r'.repeat(500)}  ` } })
    fireEvent.click(screen.getByRole('button', { name: 'Confirm Reject' }))
    expect(onConfirm).toHaveBeenCalledWith('r'.repeat(500))

    fireEvent.change(input, { target: { value: `  ${'r'.repeat(501)}  ` } })
    expect(screen.getByText('Rejection reason must be at most 500 characters.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Confirm Reject' })).toBeDisabled()
  })

  it('disables confirm and cancel, shows ASCII loading text, and ignores close attempts while busy', () => {
    const onClose = vi.fn()
    const { rerender } = render(<AdminClaimDecisionDialog {...baseProps} busy onClose={onClose} />)

    expect(screen.getByRole('button', { name: 'Approving...' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Cancel' })).toBeDisabled()
    fireEvent.keyDown(document, { key: 'Escape' })
    expect(onClose).not.toHaveBeenCalled()

    rerender(<AdminClaimDecisionDialog {...baseProps} action="reject" busy onClose={onClose} />)
    expect(screen.getByRole('button', { name: 'Rejecting...' })).toBeDisabled()
  })

  it('shows action errors without clearing the current input', () => {
    render(<AdminClaimDecisionDialog {...baseProps} error="Service unavailable" />)
    const input = screen.getByLabelText('Decision Note (optional)')

    fireEvent.change(input, { target: { value: 'Keep this note' } })

    expect(screen.getByText('Service unavailable')).toBeInTheDocument()
    expect(input).toHaveValue('Keep this note')
  })

  it('clears old input when reopened for a new action', async () => {
    const { rerender } = render(<AdminClaimDecisionDialog {...baseProps} />)
    fireEvent.change(screen.getByLabelText('Decision Note (optional)'), {
      target: { value: 'Old note' },
    })

    rerender(<AdminClaimDecisionDialog {...baseProps} open={false} />)
    rerender(<AdminClaimDecisionDialog {...baseProps} open action="reject" error="" />)

    await waitFor(() => expect(screen.getByLabelText('Rejection Reason')).toHaveValue(''))
  })
})
