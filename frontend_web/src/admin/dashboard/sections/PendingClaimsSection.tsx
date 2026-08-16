import {
  Alert,
  Box,
  Button,
  Card,
  Chip,
  CircularProgress,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Typography,
} from '@mui/material'
import { Link as RouterLink } from 'react-router-dom'
import { useCallback, useEffect, useRef, useState } from 'react'
import { searchAdminClaims } from '../../../api/adminLostFound'
import { apiErrorMessage } from '../../../api/client'
import type { AdminClaimSummary, PageResponse } from '../../../types'
import { formatDashboardInstant } from '../dashboardDateTime'

export function PendingClaimsSection() {
  const [page, setPage] = useState<PageResponse<AdminClaimSummary> | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const mountedRef = useRef(false)
  const requestInFlightRef = useRef(false)

  const loadClaims = useCallback(async () => {
    if (requestInFlightRef.current) return

    requestInFlightRef.current = true
    if (mountedRef.current) {
      setLoading(true)
      setError('')
      setPage(null)
    }

    try {
      const data = await searchAdminClaims({
        status: 'SUBMITTED',
        page: 0,
        size: 5,
        sort: 'createdAt,desc',
      })
      if (mountedRef.current) {
        setPage(data)
        setError('')
      }
    } catch (requestError) {
      if (mountedRef.current) {
        setPage(null)
        setError(apiErrorMessage(requestError))
      }
    } finally {
      requestInFlightRef.current = false
      if (mountedRef.current) setLoading(false)
    }
  }, [])

  useEffect(() => {
    mountedRef.current = true
    void loadClaims()

    return () => {
      mountedRef.current = false
    }
  }, [loadClaims])

  const claims = page?.content.slice(0, 5) ?? []

  return (
    <Box component="section" aria-labelledby="pending-claims-heading" sx={{ display: 'grid', gap: 3 }}>
      <Stack direction={{ xs: 'column', sm: 'row' }} justifyContent="space-between" alignItems={{ sm: 'center' }} spacing={2}>
        <Typography id="pending-claims-heading" component="h2" variant="h5" fontWeight={700}>
          Pending Claims
        </Typography>
        <Button
          component={RouterLink}
          to="/admin/lost-found?tab=claims&status=SUBMITTED"
          variant="outlined"
        >
          View All
        </Button>
      </Stack>

      {loading && (
        <Stack role="status" direction="row" spacing={2} alignItems="center">
          <CircularProgress size={24} aria-label="Loading pending claims" />
          <Typography>Loading pending claims</Typography>
        </Stack>
      )}

      {!loading && error && (
        <Alert
          severity="error"
          action={<Button color="inherit" onClick={() => void loadClaims()}>Retry</Button>}
        >
          {error}
        </Alert>
      )}

      {!loading && page && claims.length === 0 && (
        <Alert severity="info">No pending claims require review.</Alert>
      )}

      {!loading && claims.length > 0 && (
        <Card variant="outlined">
          <TableContainer sx={{ overflowX: 'auto' }}>
            <Table size="small" aria-label="Pending claims requiring review">
              <TableHead>
                <TableRow>
                  <TableCell>Item</TableCell>
                  <TableCell>Claimant</TableCell>
                  <TableCell>Report Owner</TableCell>
                  <TableCell>Submitted</TableCell>
                  <TableCell>Status</TableCell>
                  <TableCell>Action</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {claims.map((claim) => (
                  <TableRow key={claim.id}>
                    <TableCell>{claim.report.itemName}</TableCell>
                    <TableCell>{claim.claimant.email}</TableCell>
                    <TableCell>{claim.report.owner.email}</TableCell>
                    <TableCell sx={{ whiteSpace: 'nowrap' }}>{formatDashboardInstant(claim.createdAt)}</TableCell>
                    <TableCell><Chip label="SUBMITTED" size="small" color="warning" /></TableCell>
                    <TableCell>
                      <Button component={RouterLink} to={`/admin/lost-found/claims/${claim.id}`} size="small">
                        Review
                      </Button>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableContainer>
        </Card>
      )}
    </Box>
  )
}
