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

interface ReportStatusChartProps {
  open: number
  claimed: number
  closed: number
}

function ReportStatusChart({ open, claimed, closed }: ReportStatusChartProps) {
  const statuses = [
    { label: 'Open', count: open, color: 'primary.main' },
    { label: 'Claimed', count: claimed, color: 'warning.main' },
    { label: 'Closed', count: closed, color: 'success.main' },
  ]
  const maxCount = Math.max(0, ...statuses.map(({ count }) => count))

  return (
    <Card component="section" variant="outlined" aria-labelledby="lost-found-status-heading">
      <CardContent>
        <Stack spacing={3}>
          <Typography id="lost-found-status-heading" component="h3" variant="h6" fontWeight={700}>
            Lost & Found Report Status
          </Typography>
          <Stack spacing={2.5}>
            {statuses.map(({ label, count, color }) => {
              const width = maxCount === 0 ? 0 : (count / maxCount) * 100
              return (
                <Box key={label} role="group" aria-label={`${label}: ${count}`}>
                  <Stack direction="row" justifyContent="space-between" spacing={2} sx={{ mb: 0.75 }}>
                    <Typography fontWeight={600}>{label}</Typography>
                    <Typography aria-label={`${label} report count`}>{count}</Typography>
                  </Stack>
                  <Box sx={{ height: 12, borderRadius: 999, bgcolor: 'action.hover', overflow: 'hidden' }}>
                    <Box
                      role="img"
                      aria-label={`${label} reports: ${count}`}
                      sx={{
                        height: '100%',
                        width: `${width}%`,
                        minWidth: count > 0 ? 4 : 0,
                        borderRadius: 999,
                        bgcolor: color,
                      }}
                    />
                  </Box>
                </Box>
              )
            })}
          </Stack>
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
          <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: { xs: '1fr', sm: 'repeat(2, minmax(0, 1fr))', lg: 'repeat(4, minmax(0, 1fr))' } }}>
            <MetricCard label="Total Reports" value={overview.totalReports} />
            <MetricCard label="Open Reports" value={overview.openReports} />
            <MetricCard label="Pending Claims" value={overview.submittedClaims} />
            <MetricCard label="Hidden Reports" value={overview.hiddenReports} />
          </Box>

          <ReportStatusChart
            open={overview.openReports}
            claimed={overview.claimedReports}
            closed={overview.closedReports}
          />
        </>
      )}
    </Box>
  )
}
