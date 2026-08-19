import { Alert, Box, Button, Card, CardContent, CircularProgress, Stack, Typography } from '@mui/material'
import { useCallback, useEffect, useRef, useState } from 'react'
import { getAdminFacilitiesOverview } from '../../../api/adminFacilities'
import { apiErrorMessage } from '../../../api/client'
import type {
  AdminFacilitiesOverview,
  AdminFacilitiesStatusCount,
  AdminFacilitySpaceStatus,
} from '../../../types'

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

const STATUS_COLORS: Record<AdminFacilitySpaceStatus, string> = {
  AVAILABLE: 'success.main',
  OUT_OF_SERVICE: 'error.main',
  INACTIVE: 'text.disabled',
}

const STATUS_LABELS: Record<AdminFacilitySpaceStatus, string> = {
  AVAILABLE: 'Available',
  OUT_OF_SERVICE: 'Out of Service',
  INACTIVE: 'Inactive',
}

function statusLabel(status: AdminFacilitySpaceStatus) {
  return STATUS_LABELS[status]
}

interface SpaceStatusChartProps {
  breakdown: AdminFacilitiesStatusCount<AdminFacilitySpaceStatus>[]
}

function SpaceStatusChart({ breakdown }: SpaceStatusChartProps) {
  const maxCount = Math.max(0, ...breakdown.map(({ count }) => count))

  return (
    <Card component="section" variant="outlined" aria-labelledby="space-status-heading">
      <CardContent>
        <Stack spacing={3}>
          <Typography id="space-status-heading" component="h3" variant="h6" fontWeight={700}>
            Space Status
          </Typography>
          <Stack spacing={2.5}>
            {breakdown.map(({ status, count }) => {
              const label = statusLabel(status)
              const width = maxCount === 0 ? 0 : (count / maxCount) * 100

              return (
                <Box key={status} role="group" aria-label={`${label}: ${count}`}>
                  <Stack direction="row" justifyContent="space-between" spacing={2} sx={{ mb: 0.75 }}>
                    <Typography fontWeight={600}>{label}</Typography>
                    <Typography aria-label={`${label} count`}>{count}</Typography>
                  </Stack>
                  <Box sx={{ height: '0.75rem', borderRadius: 999, bgcolor: 'action.hover', overflow: 'hidden' }}>
                    <Box
                      role="img"
                      aria-label={`${label} facilities: ${count}`}
                      sx={{
                        height: '100%',
                        width: `${width}%`,
                        minWidth: count > 0 ? 4 : 0,
                        borderRadius: 999,
                        bgcolor: STATUS_COLORS[status],
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

export function FacilitiesOverviewSection() {
  const [overview, setOverview] = useState<AdminFacilitiesOverview | null>(null)
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
      const data = await getAdminFacilitiesOverview()
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

  const hasNoSpaceData = overview?.spaceStatusBreakdown.every(({ count }) => count === 0) ?? false

  return (
    <Box component="section" aria-labelledby="facilities-overview-heading" sx={{ display: 'grid', gap: 3 }}>
      <Typography id="facilities-overview-heading" component="h2" variant="h5" fontWeight={700}>
        Facilities Overview
      </Typography>

      {loading && (
        <Stack role="status" direction="row" spacing={2} alignItems="center">
          <CircularProgress size={24} aria-label="Loading Facilities overview" />
          <Typography>Loading Facilities overview</Typography>
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
          <Box sx={{
            display: 'grid',
            gap: 2,
            gridTemplateColumns: { xs: '1fr', sm: 'repeat(2, minmax(0, 1fr))', lg: 'repeat(4, minmax(0, 1fr))' },
          }}>
            <MetricCard label="Total Facilities" value={overview.summary.totalSpaces} />
            <MetricCard label="Available Facilities" value={overview.summary.availableSpaces} />
            <MetricCard label="Total Bookings" value={overview.summary.totalBookings} />
            <MetricCard label="Open Maintenance" value={overview.summary.openMaintenanceRequests} />
          </Box>

          {hasNoSpaceData && (
            <Typography color="text.secondary" role="note">
              No facility data is currently available.
            </Typography>
          )}

          <SpaceStatusChart breakdown={overview.spaceStatusBreakdown} />
        </>
      )}
    </Box>
  )
}
