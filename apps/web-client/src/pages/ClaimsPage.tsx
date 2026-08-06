import CheckIcon from '@mui/icons-material/Check'
import CloseIcon from '@mui/icons-material/Close'
import { Alert, Box, Button, Card, CircularProgress, Dialog, DialogActions, DialogContent, DialogTitle, Divider, Stack, TextField, Typography } from '@mui/material'
import { useCallback, useEffect, useState } from 'react'
import { Link as RouterLink } from 'react-router-dom'
import { apiErrorMessage } from '../api/client'
import { decideClaim, getMyClaims, getReceivedClaims } from '../api/lostFound'
import { StatusChip } from '../components/StatusChip'
import { categoryLabels } from '../labels'
import type { LostFoundClaim } from '../types'

interface Decision { claim: LostFoundClaim; action: 'approve' | 'reject' }

export function ClaimsPage({ view }: { view: 'mine' | 'received' }) {
  const [claims, setClaims] = useState<LostFoundClaim[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [success, setSuccess] = useState('')
  const [decision, setDecision] = useState<Decision | null>(null)
  const [note, setNote] = useState('')
  const [submitting, setSubmitting] = useState(false)

  const load = useCallback(async () => {
    setLoading(true)
    setError('')
    try {
      setClaims(await (view === 'mine' ? getMyClaims() : getReceivedClaims()))
    } catch (requestError) {
      setError(apiErrorMessage(requestError))
    } finally {
      setLoading(false)
    }
  }, [view])

  useEffect(() => { void load() }, [load])

  async function submitDecision() {
    if (!decision) return
    setSubmitting(true)
    setError('')
    try {
      await decideClaim(decision.claim.id, decision.action, note.trim())
      setSuccess(decision.action === 'approve' ? 'Claim approved. Other pending claims were rejected.' : 'Claim rejected.')
      setDecision(null)
      setNote('')
      await load()
    } catch (requestError) {
      setError(apiErrorMessage(requestError))
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <Stack spacing={3}>
      <Box><Typography variant="h4" fontWeight={700}>Claims</Typography><Typography color="text.secondary">Track your requests and review requests for items you found.</Typography></Box>
      <Stack direction="row" spacing={1}><Button component={RouterLink} to="/claims/mine" variant={view === 'mine' ? 'contained' : 'outlined'}>My claims</Button><Button component={RouterLink} to="/claims/received" variant={view === 'received' ? 'contained' : 'outlined'}>Received claims</Button></Stack>
      {error && <Alert severity="error" onClose={() => setError('')}>{error}</Alert>}
      {success && <Alert severity="success" onClose={() => setSuccess('')}>{success}</Alert>}
      {loading ? <Box sx={{ py: 8, textAlign: 'center' }}><CircularProgress /></Box> : claims.length === 0 ? <Card sx={{ p: 6, textAlign: 'center' }}><Typography variant="h6">No claims here yet</Typography><Typography color="text.secondary">{view === 'mine' ? 'Claims you submit will appear here.' : 'Claims submitted for your found-item reports will appear here.'}</Typography></Card> : claims.map((claim) => (
        <Card key={claim.id} sx={{ p: 3 }}>
          <Stack direction={{ xs: 'column', sm: 'row' }} justifyContent="space-between" gap={2}>
            <Box>
              <Stack direction="row" spacing={1} alignItems="center"><Typography variant="h6">{claim.report.itemName}</Typography><StatusChip status={claim.status} /></Stack>
              <Typography color="text.secondary" variant="body2">{categoryLabels[claim.report.category]} · {claim.report.location} · Claim #{claim.id}</Typography>
            </Box>
            <Button component={RouterLink} to={`/lost-found/${claim.report.id}`}>View report</Button>
          </Stack>
          <Divider sx={{ my: 2 }} />
          <Typography variant="subtitle2">Ownership proof</Typography><Typography sx={{ whiteSpace: 'pre-wrap', mt: 0.5 }}>{claim.proofDescription}</Typography>
          {claim.decisionNote && <Alert severity={claim.status === 'APPROVED' ? 'success' : 'info'} sx={{ mt: 2 }}>Decision note: {claim.decisionNote}</Alert>}
          {view === 'received' && claim.status === 'SUBMITTED' && <Stack direction="row" spacing={1} justifyContent="flex-end" sx={{ mt: 2 }}><Button color="error" variant="outlined" startIcon={<CloseIcon />} onClick={() => setDecision({ claim, action: 'reject' })}>Reject</Button><Button color="success" variant="contained" startIcon={<CheckIcon />} onClick={() => setDecision({ claim, action: 'approve' })}>Approve</Button></Stack>}
        </Card>
      ))}

      <Dialog open={Boolean(decision)} onClose={() => !submitting && setDecision(null)} fullWidth maxWidth="sm">
        <DialogTitle>{decision?.action === 'approve' ? 'Approve this claim?' : 'Reject this claim?'}</DialogTitle>
        <DialogContent><Typography color="text.secondary" sx={{ mb: 2 }}>{decision?.action === 'approve' ? 'The report will be marked claimed and all other pending claims will be rejected.' : 'The claimant will see this decision.'}</Typography><TextField fullWidth multiline minRows={3} label="Decision note (optional)" inputProps={{ maxLength: 500 }} value={note} onChange={(e) => setNote(e.target.value)} /></DialogContent>
        <DialogActions><Button onClick={() => setDecision(null)} disabled={submitting}>Cancel</Button><Button color={decision?.action === 'approve' ? 'success' : 'error'} variant="contained" onClick={submitDecision} disabled={submitting}>Confirm</Button></DialogActions>
      </Dialog>
    </Stack>
  )
}
