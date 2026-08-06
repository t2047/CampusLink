import { Chip } from '@mui/material'
import type { ClaimStatus, ReportStatus } from '../types'

export function StatusChip({ status }: { status: ClaimStatus | ReportStatus }) {
  const color = status === 'APPROVED' || status === 'CLAIMED' ? 'success' : status === 'REJECTED' || status === 'CLOSED' ? 'default' : 'primary'
  return <Chip size="small" label={status} color={color} variant="outlined" />
}
