import axios from 'axios'
import {
  Alert,
  Box,
  Button,
  Chip,
  CircularProgress,
  Paper,
  Stack,
  Typography,
} from '@mui/material'
import { useEffect, useMemo, useRef, useState } from 'react'
import { Link as RouterLink, useParams, useSearchParams } from 'react-router-dom'
import { approveAdminClaim, getAdminClaimDetail, rejectAdminClaim } from '../../api/adminLostFound'
import { apiErrorMessage } from '../../api/client'
import {
  categoryLabels,
  claimStatusLabels,
  reportStatusLabels,
  reportTypeLabels,
} from '../../labels'
import type { AdminClaimDetail, ApiErrorBody } from '../../types'
import { AdminClaimDecisionDialog } from './AdminClaimDecisionDialog'
import type { AdminClaimDecisionAction } from './AdminClaimDecisionDialog'
import { buildAdminClaimsListSearchParams, parseAdminClaimRouteState } from './adminClaimRouteState'

function parseClaimId(value: string | undefined): number | null {
  if (!value || !/^\d+$/.test(value)) return null
  const id = Number(value)
  return Number.isSafeInteger(id) && id > 0 ? id : null
}

function formatDateTime(value: string | null | undefined, fallback = 'Not provided') {
  if (!value) return fallback
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return fallback
  return new Intl.DateTimeFormat('en-SG', {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(date)
}

function isAlreadyDecidedConflict(error: unknown) {
  return axios.isAxiosError<ApiErrorBody>(error)
    && error.response?.status === 409
    && error.response.data?.code === 'CLAIM_ALREADY_DECIDED'
}

interface DetailFieldProps {
  label: string
  value: string | number
}

function DetailField({ label, value }: DetailFieldProps) {
  return (
    <Box>
      <Typography variant="caption" color="text.secondary">{label}</Typography>
      <Typography sx={{ overflowWrap: 'anywhere' }}>{value}</Typography>
    </Box>
  )
}

export function AdminClaimDetailPage() {
  const { claimId: claimIdParam } = useParams()
  const [searchParams] = useSearchParams()
  const claimId = parseClaimId(claimIdParam)
  const backHref = useMemo(() => {
    const routeState = parseAdminClaimRouteState(searchParams)
    return `/admin/lost-found?${buildAdminClaimsListSearchParams(routeState).toString()}`
  }, [searchParams])
  const [claim, setClaim] = useState<AdminClaimDetail | null>(null)
  const [loading, setLoading] = useState(claimId !== null)
  const [notFound, setNotFound] = useState(claimId === null)
  const [error, setError] = useState('')
  const [retryCounter, setRetryCounter] = useState(0)
  const [brokenImages, setBrokenImages] = useState<Set<number>>(() => new Set())
  const [decisionAction, setDecisionAction] = useState<AdminClaimDecisionAction | null>(null)
  const [actionBusy, setActionBusy] = useState(false)
  const [actionError, setActionError] = useState('')
  const [pageAlert, setPageAlert] = useState<{ severity: 'success' | 'warning'; message: string } | null>(null)
  const actionInFlight = useRef(false)

  useEffect(() => {
    setBrokenImages(new Set())
    if (claimId === null) {
      setClaim(null)
      setLoading(false)
      setNotFound(true)
      setError('')
      return
    }

    let active = true
    setLoading(true)
    setNotFound(false)
    setError('')
    getAdminClaimDetail(claimId)
      .then((data) => {
        if (active) setClaim(data)
      })
      .catch((requestError) => {
        if (!active) return
        setClaim(null)
        if (axios.isAxiosError(requestError) && requestError.response?.status === 404) {
          setNotFound(true)
        } else {
          setError(apiErrorMessage(requestError))
        }
      })
      .finally(() => {
        if (active) setLoading(false)
      })

    return () => { active = false }
  }, [claimId, retryCounter])

  function openDecision(action: AdminClaimDecisionAction) {
    if (!claim || claim.status !== 'SUBMITTED' || actionInFlight.current) return
    setPageAlert(null)
    setActionError('')
    setDecisionAction(action)
  }

  function closeDecision() {
    if (actionInFlight.current) return
    setDecisionAction(null)
    setActionError('')
  }

  async function confirmDecision(trimmedNote: string) {
    if (!claim || claim.status !== 'SUBMITTED' || !decisionAction || actionInFlight.current) return
    const action = decisionAction
    actionInFlight.current = true
    setActionBusy(true)
    setActionError('')

    try {
      const updated = action === 'approve'
        ? await approveAdminClaim(claim.id, trimmedNote ? { decisionNote: trimmedNote } : {})
        : await rejectAdminClaim(claim.id, { decisionNote: trimmedNote })
      setClaim(updated)
      setDecisionAction(null)
      setPageAlert({
        severity: 'success',
        message: action === 'approve'
          ? 'Claim approved. The related report is now marked as claimed.'
          : 'Claim rejected.',
      })
    } catch (requestError) {
      if (isAlreadyDecidedConflict(requestError)) {
        setDecisionAction(null)
        setActionError('')
        setPageAlert(null)
        setClaim(null)
        setNotFound(false)
        setError('')
        setLoading(true)
        try {
          const latest = await getAdminClaimDetail(claim.id)
          setClaim(latest)
          setPageAlert({
            severity: 'warning',
            message: 'This claim has already been decided. The latest details have been loaded.',
          })
        } catch (refreshError) {
          if (axios.isAxiosError(refreshError) && refreshError.response?.status === 404) {
            setNotFound(true)
          } else {
            setError(apiErrorMessage(refreshError))
          }
        } finally {
          setLoading(false)
        }
      } else {
        setActionError(apiErrorMessage(requestError))
      }
    } finally {
      actionInFlight.current = false
      setActionBusy(false)
    }
  }

  const backLink = <Button component={RouterLink} to={backHref}>Back to Claims</Button>

  if (loading) {
    return (
      <Stack spacing={3}>
        {backLink}
        <Box sx={{ minHeight: 320, display: 'grid', placeItems: 'center' }}><CircularProgress /></Box>
      </Stack>
    )
  }

  if (notFound) {
    return (
      <Stack spacing={2}>
        <Typography component="h1" variant="h4" fontWeight={700}>Claim not found</Typography>
        <Typography color="text.secondary">The requested claim does not exist or the claim ID is invalid.</Typography>
        <Box>{backLink}</Box>
      </Stack>
    )
  }

  if (error) {
    return (
      <Stack spacing={2}>
        <Alert
          severity="error"
          action={<Button color="inherit" size="small" onClick={() => setRetryCounter((counter) => counter + 1)}>Retry</Button>}
        >
          {error}
        </Alert>
        <Box>{backLink}</Box>
      </Stack>
    )
  }

  if (!claim) return null

  const reviewNote = claim.review.decisionNote ?? claim.decisionNote ?? 'Not provided'
  const reviewState = claim.review.reviewed ? claimStatusLabels[claim.status] : 'Pending review'
  const reviewedAt = claim.review.reviewed
    ? formatDateTime(claim.review.reviewedAt, 'Not reviewed')
    : 'Not reviewed'

  return (
    <Stack spacing={3}>
      <Paper component="section" aria-label="Claim summary" variant="outlined" sx={{ p: 3 }}>
        <Stack direction={{ xs: 'column', sm: 'row' }} justifyContent="space-between" spacing={2}>
          <Box>
            <Typography component="h1" variant="h4" fontWeight={700}>Claim #{claim.id}</Typography>
            <Chip sx={{ mt: 1 }} label={claimStatusLabels[claim.status]} />
          </Box>
          <Box>{backLink}</Box>
        </Stack>
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={3} sx={{ mt: 2 }}>
          <Typography color="text.secondary">Created: {formatDateTime(claim.createdAt)}</Typography>
          <Typography color="text.secondary">Updated: {formatDateTime(claim.updatedAt)}</Typography>
        </Stack>
      </Paper>

      {pageAlert && <Alert severity={pageAlert.severity}>{pageAlert.message}</Alert>}

      {claim.status === 'SUBMITTED' ? (
        <Paper component="section" aria-label="Review Actions" variant="outlined" sx={{ p: 3 }}>
          <Typography component="h2" variant="h6" fontWeight={700}>Review Actions</Typography>
          <Typography color="text.secondary" sx={{ mt: 1 }}>
            Review the submitted evidence before approving or rejecting this claim.
          </Typography>
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1} sx={{ mt: 2 }}>
            <Button variant="contained" onClick={() => openDecision('approve')} disabled={actionBusy}>
              Approve Claim
            </Button>
            <Button variant="outlined" color="error" onClick={() => openDecision('reject')} disabled={actionBusy}>
              Reject Claim
            </Button>
          </Stack>
        </Paper>
      ) : (
        <Alert severity="info">This claim has already been {claim.status.toLowerCase()} and is read-only.</Alert>
      )}

      <Paper component="section" aria-label="Claimant" variant="outlined" sx={{ p: 3 }}>
        <Typography component="h2" variant="h6" fontWeight={700} sx={{ mb: 2 }}>Claimant</Typography>
        <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: { xs: '1fr', sm: 'repeat(3, 1fr)' } }}>
          <DetailField label="Email" value={claim.claimant.email} />
          <DetailField label="User ID" value={`#${claim.claimant.id}`} />
          <DetailField label="Role" value={claim.claimant.role} />
        </Box>
      </Paper>

      <Paper component="section" aria-label="Proof Submitted" variant="outlined" sx={{ p: 3 }}>
        <Typography component="h2" variant="h6" fontWeight={700} sx={{ mb: 2 }}>Proof Submitted</Typography>
        <Typography sx={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>
          {claim.proofDescription}
        </Typography>
      </Paper>

      <Paper component="section" aria-label="Related Report" variant="outlined" sx={{ p: 3 }}>
        <Typography component="h2" variant="h6" fontWeight={700} sx={{ mb: 2 }}>Related Report</Typography>
        <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: { xs: '1fr', sm: 'repeat(2, 1fr)', lg: 'repeat(3, 1fr)' } }}>
          <DetailField label="Report ID" value={`#${claim.report.id}`} />
          <DetailField label="Report Type" value={reportTypeLabels[claim.report.reportType]} />
          <DetailField label="Item Name" value={claim.report.itemName} />
          <DetailField label="Category" value={categoryLabels[claim.report.category]} />
          <DetailField label="Colour" value={claim.report.colour ?? 'Not provided'} />
          <DetailField label="Location" value={claim.report.location} />
          <DetailField label="Event Date" value={claim.report.eventDate} />
          <DetailField label="Time Description" value={claim.report.timeDescription ?? 'Not provided'} />
          <DetailField label="Report Status" value={reportStatusLabels[claim.report.status]} />
          <DetailField label="Admin Visibility" value={claim.report.adminHidden ? 'Hidden' : 'Visible'} />
          <DetailField label="Report Owner" value={claim.report.owner.email} />
          <DetailField label="Owner User ID" value={`#${claim.report.owner.id}`} />
        </Box>
        <Box sx={{ mt: 3 }}>
          <Typography variant="caption" color="text.secondary">Description</Typography>
          <Typography sx={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>
            {claim.report.description}
          </Typography>
        </Box>
      </Paper>

      <Paper component="section" aria-label="Report Images" variant="outlined" sx={{ p: 3 }}>
        <Typography component="h2" variant="h6" fontWeight={700} sx={{ mb: 2 }}>Report Images</Typography>
        {claim.report.images.length ? (
          <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: { xs: '1fr', md: 'repeat(2, 1fr)' } }}>
            {claim.report.images.map((image, index) => (
              <Paper key={image.id} variant="outlined" sx={{ minHeight: 220, overflow: 'hidden' }}>
                {brokenImages.has(image.id) ? (
                  <Box sx={{ minHeight: 220, display: 'grid', placeItems: 'center', bgcolor: 'grey.100' }}>
                    <Typography color="text.secondary">Image unavailable.</Typography>
                  </Box>
                ) : (
                  <Box
                    component="img"
                    src={image.url}
                    alt={`${claim.report.itemName} image ${index + 1}`}
                    onError={() => setBrokenImages((current) => {
                      const next = new Set(current)
                      next.add(image.id)
                      return next
                    })}
                    sx={{ display: 'block', width: '100%', height: 280, objectFit: 'cover' }}
                  />
                )}
              </Paper>
            ))}
          </Box>
        ) : (
          <Typography color="text.secondary">No images provided.</Typography>
        )}
      </Paper>

      <Paper component="section" aria-label="Review Information" variant="outlined" sx={{ p: 3 }}>
        <Typography component="h2" variant="h6" fontWeight={700} sx={{ mb: 2 }}>Review Information</Typography>
        <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: { xs: '1fr', sm: 'repeat(3, 1fr)' } }}>
          <DetailField label="Review Status" value={reviewState} />
          <DetailField label="Decision Note" value={reviewNote} />
          <DetailField label="Reviewed Time" value={reviewedAt} />
        </Box>
      </Paper>

      <AdminClaimDecisionDialog
        open={decisionAction !== null}
        action={decisionAction}
        claimId={claim.id}
        reportId={claim.report.id}
        itemName={claim.report.itemName}
        busy={actionBusy}
        error={actionError}
        onClose={closeDecision}
        onConfirm={confirmDecision}
      />
    </Stack>
  )
}
