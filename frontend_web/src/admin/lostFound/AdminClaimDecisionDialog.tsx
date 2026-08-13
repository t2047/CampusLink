import {
  Alert,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Stack,
  TextField,
  Typography,
} from '@mui/material'
import { useEffect, useState } from 'react'

export type AdminClaimDecisionAction = 'approve' | 'reject'

interface AdminClaimDecisionDialogProps {
  open: boolean
  action: AdminClaimDecisionAction | null
  claimId: number
  reportId: number
  itemName: string
  busy: boolean
  error: string
  onClose: () => void
  onConfirm: (trimmedNote: string) => void
}

export function AdminClaimDecisionDialog({
  open,
  action,
  claimId,
  reportId,
  itemName,
  busy,
  error,
  onClose,
  onConfirm,
}: AdminClaimDecisionDialogProps) {
  const [note, setNote] = useState('')

  useEffect(() => {
    if (open) setNote('')
  }, [action, open])

  const isApprove = action === 'approve'
  const label = isApprove ? 'Decision Note (optional)' : 'Rejection Reason'
  const trimmedNote = note.trim()
  const tooLong = trimmedNote.length > 500
  const reasonRequired = action === 'reject' && !trimmedNote
  const helperText = tooLong
    ? isApprove
      ? 'Decision note must be at most 500 characters.'
      : 'Rejection reason must be at most 500 characters.'
    : reasonRequired
      ? 'A rejection reason is required.'
      : undefined
  const confirmDisabled = busy || action === null || tooLong || reasonRequired

  function close() {
    if (!busy) onClose()
  }

  function confirm() {
    if (!confirmDisabled) onConfirm(trimmedNote)
  }

  return (
    <Dialog
      open={open}
      onClose={() => close()}
      disableEscapeKeyDown={busy}
      fullWidth
      maxWidth="sm"
    >
      <DialogTitle>{isApprove ? 'Approve Claim' : 'Reject Claim'}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ pt: 1 }}>
          <Typography color="text.secondary">
            Claim #{claimId} | Report #{reportId} | {itemName}
          </Typography>
          <Typography>
            {isApprove
              ? 'Approving this claim will mark the related report as claimed and automatically reject any other submitted claims for the same report.'
              : 'Provide a clear reason for rejecting this claim.'}
          </Typography>
          {error && <Alert severity="error">{error}</Alert>}
          <TextField
            label={label}
            required={!isApprove}
            multiline
            minRows={3}
            value={note}
            onChange={(event) => setNote(event.target.value)}
            error={tooLong || reasonRequired}
            helperText={helperText}
            inputProps={{ 'aria-label': label }}
            disabled={busy}
            fullWidth
          />
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={close} disabled={busy}>Cancel</Button>
        <Button variant="contained" color={isApprove ? 'primary' : 'error'} onClick={confirm} disabled={confirmDisabled}>
          {busy
            ? isApprove ? 'Approving...' : 'Rejecting...'
            : isApprove ? 'Confirm Approve' : 'Confirm Reject'}
        </Button>
      </DialogActions>
    </Dialog>
  )
}
