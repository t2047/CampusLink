import { Alert, Box, Button, Card, CardContent, CircularProgress, Stack, Typography } from '@mui/material'
import { useCallback, useEffect, useRef, useState } from 'react'
import { getAdminLostFoundOverview } from '../../../api/adminLostFound'
import { apiErrorMessage } from '../../../api/client'
import type { AdminLostFoundOverview } from '../../../types'

interface MetricCardProps {
  label: string
  value: number
}

function MetricCard({ label, value }: MetricCardProps) {
  return (
    <Card variant="outlined" role="group" aria-label={label} sx={{ height: '100%' }}>
      <CardContent>
        <Stack spacing={1}>
          <Typography color="text.secondary">{label}</Typography>
          <Typography variant="h4" fontWeight={700}>{value}</Typography>
        </Stack>
      </CardContent>
    </Card>
  )
}

export function LostFoundOverviewSection() {
  const [overview, setOverview] = useState<AdminLostFoundOverview | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const mountedRef = useRef(false)
  const requestInFlightRef = useRef(false)

  const loadOverview = useCallback(async () => {
    if (requestInFlightRef.current) return

    requestInFlightRef.current = true
    if (mountedRef.current) {
      setLoading(true)
      setError('')
      setOverview(null)
    }

    try {
      const data = await getAdminLostFoundOverview()
      if (mountedRef.current) {
        setOverview(data)
        setError('')
      }
    } catch (requestError) {
      if (mountedRef.current) {
        setOverview(null)
        setError(apiErrorMessage(requestError))
      }
    } finally {
      requestInFlightRef.current = false
      if (mountedRef.current) setLoading(false)
    }
  }, [])

  useEffect(() => {
    mountedRef.current = true
    void loadOverview()

    return () => {
      mountedRef.current = false
    }
  }, [loadOverview])

  return (
    <Box component="section" aria-labelledby="lost-found-overview-heading" sx={{ display: 'grid', gap: 3 }}>
      <Typography id="lost-found-overview-heading" component="h2" variant="h5" fontWeight={700}>
        Lost & Found Overview
      </Typography>

      {loading && (
        <Stack role="status" direction="row" spacing={2} alignItems="center">
          <CircularProgress size={24} aria-label="Loading Lost & Found overview" />
          <Typography>Loading Lost & Found overview</Typography>
        </Stack>
      )}

      {!loading && error && (
        <Alert
          severity="error"
          action={<Button color="inherit" onClick={() => void loadOverview()}>Retry</Button>}
        >
          {error}
        </Alert>
      )}

      {!loading && overview && (
        <>
          <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: { xs: '1fr', sm: 'repeat(3, minmax(0, 1fr))' } }}>
            <MetricCard label="Total Reports" value={overview.totalReports} />
            <MetricCard label="Open Reports" value={overview.openReports} />
            <MetricCard label="Pending Claims" value={overview.submittedClaims} />
          </Box>

          <Card
            component="section"
            variant="outlined"
            aria-labelledby="action-required-heading"
          >
            <CardContent>
              <Stack spacing={1}>
                <Typography id="action-required-heading" component="h2" variant="h6" fontWeight={700}>
                  Action Required
                </Typography>
                {overview.submittedClaims === 0 ? (
                  <Typography color="text.secondary">No pending claims require review.</Typography>
                ) : (
                  <>
                    <Typography color="text.secondary">
                      {overview.submittedClaims === 1
                        ? '1 pending claim requires review.'
                        : `${overview.submittedClaims} pending claims require review.`}
                    </Typography>
                    <Typography color="text.secondary">
                      Claim review actions are not available in this read-only dashboard yet.
                    </Typography>
                  </>
                )}
              </Stack>
            </CardContent>
          </Card>
        </>
      )}
    </Box>
  )
}
