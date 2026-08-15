import { Alert, Box, Button, Card, CardContent, Chip, CircularProgress, Paper, Stack, Typography } from '@mui/material'
import { useEffect, useState } from 'react'
import { Link as RouterLink } from 'react-router-dom'
import { apiErrorMessage } from '../../api/client'
import { facilitiesApi, type BookingResponse } from '../../api/facilities'
import { formatFacilityDate, formatFacilityTimeRange, sortBookings } from './bookingDateTime'

const statusLabel = (status: string) => status.replaceAll('_', ' ')

export function MyBookingsPage() {
  const [bookings, setBookings] = useState<BookingResponse[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')

  useEffect(() => {
    let active = true
    setLoading(true)
    setError('')
    facilitiesApi.listBookings()
      .then((result) => { if (active) setBookings(sortBookings(result)) })
      .catch((requestError) => { if (active) setError(apiErrorMessage(requestError)) })
      .finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [])

  return (
    <Stack spacing={3}>
      <Box>
        <Typography component="h2" variant="h5" fontWeight={700}>My Bookings</Typography>
        <Typography color="text.secondary">View your upcoming and previous campus space bookings.</Typography>
      </Box>

      {error && <Alert severity="error">{error}</Alert>}
      {loading ? <Box sx={{ py: 8, textAlign: 'center' }}><CircularProgress /></Box> : bookings.length ? (
        <Stack spacing={2}>
          {bookings.map((booking) => (
            <Card key={booking.bookingId} variant="outlined">
              <CardContent>
                <Stack direction={{ xs: 'column', sm: 'row' }} justifyContent="space-between" gap={2}>
                  <Stack spacing={0.75}>
                    <Stack direction="row" alignItems="center" spacing={1} flexWrap="wrap">
                      <Typography component="h3" variant="h6" fontWeight={700}>{booking.space.name}</Typography>
                      <Chip
                        size="small"
                        label={statusLabel(booking.status)}
                        color={booking.status === 'CONFIRMED' ? 'success' : 'default'}
                      />
                    </Stack>
                    <Typography>{booking.space.building} · Room {booking.space.roomNumber}</Typography>
                    <Typography color="text.secondary">
                      {formatFacilityDate(booking.startDateTime)} · {formatFacilityTimeRange(booking.startDateTime, booking.endDateTime)}
                    </Typography>
                  </Stack>
                  <Button component={RouterLink} to={`/facilities/bookings/${booking.bookingId}`} sx={{ alignSelf: { xs: 'flex-start', sm: 'center' } }}>
                    View details
                  </Button>
                </Stack>
              </CardContent>
            </Card>
          ))}
        </Stack>
      ) : !error && (
        <Paper sx={{ p: 6, textAlign: 'center' }}>
          <Typography variant="h6">No bookings yet</Typography>
          <Typography color="text.secondary" sx={{ mb: 2 }}>Find a campus space and make your first booking.</Typography>
          <Button variant="contained" component={RouterLink} to="/facilities">Search spaces</Button>
        </Paper>
      )}
    </Stack>
  )
}
