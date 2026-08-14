import ArrowBackIcon from '@mui/icons-material/ArrowBack'
import CalendarTodayIcon from '@mui/icons-material/CalendarToday'
import LocationOnIcon from '@mui/icons-material/LocationOn'
import { Alert, Box, Button, Card, CardMedia, Chip, CircularProgress, Dialog, DialogActions, DialogContent, DialogTitle, Divider, Grid, Stack, TextField, Typography } from '@mui/material'
import { useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { apiErrorMessage } from '../api/client'
import { closeReport, deleteReport, getReport, submitClaim } from '../api/lostFound'
import { EditReportDialog } from '../components/EditReportDialog'
import { StatusChip } from '../components/StatusChip'
import { categoryLabels, reportTypeLabels } from '../labels'
import type { LostFoundReport } from '../types'

export function ReportDetailPage() {
  const { reportId } = useParams()
  const navigate = useNavigate()
  const [report, setReport] = useState<LostFoundReport | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [claimOpen, setClaimOpen] = useState(false)
  const [proof, setProof] = useState('')
  const [claiming, setClaiming] = useState(false)
  const [success, setSuccess] = useState('')
  const [editOpen, setEditOpen] = useState(false)
  const [confirmAction, setConfirmAction] = useState<'close' | 'delete' | null>(null)
  const [acting, setActing] = useState(false)

  useEffect(() => {
    if (!reportId) return
    let active = true
    getReport(reportId)
      .then((data) => { if (active) setReport(data) })
      .catch((requestError) => { if (active) setError(apiErrorMessage(requestError)) })
      .finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [reportId])

  function handleUpdated(updated: LostFoundReport) {
    setReport(updated)
    setSuccess('Report updated.')
  }

  async function runClose() {
    if (!report) return
    setActing(true)
    setError('')
    try {
      setReport(await closeReport(report.id))
      setConfirmAction(null)
      setSuccess('Report closed.')
    } catch (requestError) {
      setError(apiErrorMessage(requestError))
    } finally {
      setActing(false)
    }
  }

  async function runDelete() {
    if (!report) return
    setActing(true)
    setError('')
    try {
      await deleteReport(report.id)
      navigate('/lost-found', { replace: true })
    } catch (requestError) {
      setError(apiErrorMessage(requestError))
    } finally {
      setActing(false)
    }
  }

  async function sendClaim() {
    if (!report || proof.trim().length < 10) {
      setError('Please provide at least 10 characters of identifying proof.')
      return
    }
    setClaiming(true)
    setError('')
    try {
      await submitClaim(report.id, proof.trim())
      setClaimOpen(false)
      setSuccess('Your claim was submitted to the person who posted this item.')
    } catch (requestError) {
      setError(apiErrorMessage(requestError))
    } finally {
      setClaiming(false)
    }
  }

  if (loading) return <Box sx={{ py: 8, textAlign: 'center' }}><CircularProgress /></Box>
  if (!report) return <Alert severity="error">{error || 'Report not found.'}</Alert>

  return (
    <Stack spacing={3}>
      <Button startIcon={<ArrowBackIcon />} onClick={() => navigate(-1)} sx={{ alignSelf: 'flex-start' }}>Back</Button>
      {error && <Alert severity="error" onClose={() => setError('')}>{error}</Alert>}
      {success && <Alert severity="success" onClose={() => setSuccess('')}>{success}</Alert>}
      <Grid container spacing={4}>
        <Grid size={{ xs: 12, md: 6 }}>
          {report.images.length ? <Grid container spacing={1}>{report.images.map((image, index) => <Grid key={image.id} size={index === 0 ? 12 : 6}><CardMedia component="img" image={image.url} alt={`${report.itemName} ${index + 1}`} sx={{ borderRadius: 2, width: '100%', height: index === 0 ? 360 : 180, objectFit: 'cover' }} /></Grid>)}</Grid> : <Box sx={{ height: 360, bgcolor: 'grey.200', borderRadius: 2, display: 'grid', placeItems: 'center' }}><Typography color="text.secondary">No images provided</Typography></Box>}
        </Grid>
        <Grid size={{ xs: 12, md: 6 }}>
          <Card sx={{ p: 3 }}>
            <Stack direction="row" spacing={1} sx={{ mb: 2 }}><Chip label={reportTypeLabels[report.reportType]} color={report.reportType === 'FOUND' ? 'success' : 'warning'} /><StatusChip status={report.status} />{report.createdByMe && <Chip label="Your report" variant="outlined" />}</Stack>
            <Typography variant="h4" fontWeight={700}>{report.itemName}</Typography>
            <Typography color="text.secondary" sx={{ mt: 1 }}>{categoryLabels[report.category]}{report.colour ? ` · ${report.colour}` : ''}</Typography>
            <Divider sx={{ my: 3 }} />
            <Stack spacing={1}><Typography><LocationOnIcon fontSize="small" /> {report.location}</Typography><Typography><CalendarTodayIcon fontSize="small" /> {report.eventDate}{report.timeDescription ? ` · ${report.timeDescription}` : ''}</Typography></Stack>
            <Typography sx={{ mt: 3, whiteSpace: 'pre-wrap' }}>{report.description}</Typography>
            {report.reportType === 'FOUND' && report.status === 'OPEN' && !report.createdByMe && <Button fullWidth size="large" variant="contained" sx={{ mt: 4 }} onClick={() => setClaimOpen(true)}>Submit a claim</Button>}
            {report.createdByMe && report.reportType === 'FOUND' && <Button fullWidth variant="outlined" sx={{ mt: 4 }} onClick={() => navigate('/claims/received')}>Review received claims</Button>}
            {report.createdByMe && report.status === 'OPEN' && <Stack direction="row" spacing={1} sx={{ mt: 4 }}><Button fullWidth variant="contained" onClick={() => setEditOpen(true)}>Edit</Button><Button fullWidth variant="outlined" color="info" onClick={() => setConfirmAction('close')}>Close</Button><Button fullWidth variant="outlined" color="error" onClick={() => setConfirmAction('delete')}>Delete</Button></Stack>}
          </Card>
        </Grid>
      </Grid>

      <Dialog open={claimOpen} onClose={() => !claiming && setClaimOpen(false)} fullWidth maxWidth="sm">
        <DialogTitle>Prove that this item belongs to you</DialogTitle>
        <DialogContent><Typography color="text.secondary" sx={{ mb: 2 }}>Describe a detail only the owner is likely to know. Your proof is only visible to you and the report publisher.</Typography><TextField autoFocus fullWidth multiline minRows={4} label="Identifying proof" inputProps={{ minLength: 10, maxLength: 1000 }} value={proof} onChange={(e) => setProof(e.target.value)} /></DialogContent>
        <DialogActions><Button onClick={() => setClaimOpen(false)} disabled={claiming}>Cancel</Button><Button variant="contained" onClick={sendClaim} disabled={claiming}>Submit claim</Button></DialogActions>
      </Dialog>

      {report.createdByMe && <EditReportDialog report={report} open={editOpen} onClose={() => setEditOpen(false)} onUpdated={handleUpdated} />}

      <Dialog open={confirmAction !== null} onClose={() => !acting && setConfirmAction(null)} fullWidth maxWidth="xs">
        <DialogTitle>{confirmAction === 'close' ? 'Close this report?' : 'Delete this report?'}</DialogTitle>
        <DialogContent>
          <Typography color="text.secondary">
            {confirmAction === 'close'
              ? 'Closing marks this report as resolved and removes it from open listings. This cannot be undone.'
              : 'Deleting permanently removes this report, its images and any related claims. This cannot be undone.'}
          </Typography>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setConfirmAction(null)} disabled={acting}>Cancel</Button>
          <Button color={confirmAction === 'delete' ? 'error' : 'primary'} variant="contained" onClick={confirmAction === 'close' ? runClose : runDelete} disabled={acting}>
            {confirmAction === 'close' ? 'Close report' : 'Delete report'}
          </Button>
        </DialogActions>
      </Dialog>
    </Stack>
  )
}
