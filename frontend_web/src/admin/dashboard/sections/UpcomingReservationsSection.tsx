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
import { useCallback, useEffect, useRef, useState } from 'react'
import { Link as RouterLink } from 'react-router-dom'
import { searchAdminFacilityBookings } from '../../../api/adminFacilities'
import { apiErrorMessage } from '../../../api/client'
import type { AdminFacilityBooking, PageResponse } from '../../../types'
import { formatFacilityDateTime, singaporeLocalNow } from '../../../pages/facilities/bookingDateTime'

function statusLabel(status: AdminFacilityBooking['status']) {
  return status.charAt(0) + status.slice(1).toLowerCase()
}

function statusColor(status: AdminFacilityBooking['status']): 'success' | 'default' | 'info' {
  if (status === 'CONFIRMED') return 'success'
  if (status === 'COMPLETED') return 'info'
  return 'default'
}

export function UpcomingReservationsSection() {
  const [page, setPage] = useState<PageResponse<AdminFacilityBooking> | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const mountedRef = useRef(false)
  const requestInFlightRef = useRef(false)

  const loadReservations = useCallback(async () => {
    if (requestInFlightRef.current) return

    requestInFlightRef.current = true
    if (mountedRef.current) {
      setLoading(true)
      setError('')
      setPage(null)
    }

    try {
      const data = await searchAdminFacilityBookings({
        status: 'CONFIRMED',
        startFrom: singaporeLocalNow(),
        page: 0,
        size: 5,
        sort: 'startDateTime,asc',
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
    void loadReservations()

    return () => {
      mountedRef.current = false
    }
  }, [loadReservations])

  const reservations = page?.content.slice(0, 5) ?? []

  return (
    <Box component="section" aria-labelledby="upcoming-reservations-heading" sx={{ display: 'grid', gap: 3 }}>
      <Stack direction={{ xs: 'column', sm: 'row' }} justifyContent="space-between" alignItems={{ sm: 'center' }} spacing={2}>
        <Typography id="upcoming-reservations-heading" component="h2" variant="h5" fontWeight={700}>
          Upcoming Reservations
        </Typography>
        <Button component={RouterLink} to="/admin/facilities/reservations" variant="outlined">
          View All
        </Button>
      </Stack>

      {loading && (
        <Stack role="status" direction="row" spacing={2} alignItems="center">
          <CircularProgress size={24} aria-label="Loading upcoming reservations" />
          <Typography>Loading upcoming reservations</Typography>
        </Stack>
      )}

      {!loading && error && (
        <Alert
          severity="error"
          action={<Button color="inherit" onClick={() => void loadReservations()}>Retry</Button>}
        >
          {error}
        </Alert>
      )}

      {!loading && page && reservations.length === 0 && (
        <Alert severity="info">No upcoming reservations.</Alert>
      )}

      {!loading && reservations.length > 0 && (
        <Card variant="outlined">
          <TableContainer sx={{ overflowX: 'auto' }}>
            <Table size="small" aria-label="Upcoming reservations">
              <TableHead>
                <TableRow>
                  <TableCell>Facility</TableCell>
                  <TableCell>User</TableCell>
                  <TableCell>Start</TableCell>
                  <TableCell>End</TableCell>
                  <TableCell>Status</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {reservations.map((reservation) => (
                  <TableRow key={reservation.bookingId}>
                    <TableCell>
                      <Stack spacing={0.25}>
                        <Typography fontWeight={600}>{reservation.spaceName}</Typography>
                        <Typography variant="body2" color="text.secondary">
                          {reservation.building} / {reservation.roomNumber}
                        </Typography>
                      </Stack>
                    </TableCell>
                    <TableCell>{reservation.userEmail ?? 'Unknown user'}</TableCell>
                    <TableCell sx={{ whiteSpace: 'nowrap' }}>{formatFacilityDateTime(reservation.startDateTime)}</TableCell>
                    <TableCell sx={{ whiteSpace: 'nowrap' }}>{formatFacilityDateTime(reservation.endDateTime)}</TableCell>
                    <TableCell>
                      <Chip
                        label={statusLabel(reservation.status)}
                        color={statusColor(reservation.status)}
                        size="small"
                      />
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
