import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  Chip,
  CircularProgress,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Divider,
  Stack,
  Typography,
} from '@mui/material'
import axios from 'axios'
import { useEffect, useRef, useState } from 'react'
import { Link as RouterLink, useParams } from 'react-router-dom'
import { apiErrorMessage } from '../../api/client'
import { facilitiesApi, type BookingResponse } from '../../api/facilities'
import { canCancelBooking, formatFacilityDate, formatFacilityDateTime, formatFacilityTimeRange } from './bookingDateTime'

const statusLabel = (status: string) => status.replaceAll('_', ' ')

export function BookingDetailsPage() {
  const { bookingId } = useParams()
  const [booking, setBooking] = useState<BookingResponse | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [cancelOpen, setCancelOpen] = useState(false)
  const [cancelling, setCancelling] = useState(false)
  const [cancelError, setCancelError] = useState('')
  const [success, setSuccess] = useState('')
  const cancellingRef = useRef(false)

  useEffect(() => {
    let active = true
    const id = Number(bookingId)
    setBooking(null)
    setError('')
    setSuccess('')
    setLoading(true)
    if (!Number.isInteger(id) || id < 1) {
      setError('Booking not found.')
      setLoading(false)
      return () => { active = false }
    }
    facilitiesApi.getBooking(id)
      .then((result) => { if (active) setBooking(result) })
      .catch((requestError) => {
        if (!active) return
        setError(axios.isAxiosError(requestError) && requestError.response?.status === 404
          ? 'Booking not found.'
          : apiErrorMessage(requestError))
      })
      .finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [bookingId])

  async function confirmCancellation() {
    if (!booking || cancellingRef.current) return
    cancellingRef.current = true
    setCancelling(true)
    setCancelError('')
    try {
      const updated = await facilitiesApi.cancelBooking(booking.bookingId)
      setBooking(updated)
      setSuccess('Booking cancelled successfully.')
      setCancelOpen(false)
    } catch (requestError) {
      setCancelError(apiErrorMessage(requestError))
    } finally {
      cancellingRef.current = false
      setCancelling(false)
    }
  }

  if (loading) return <Box sx={{ py: 8, textAlign: 'center' }}><CircularProgress /></Box>
  if (error || !booking) return <Alert severity="error" action={<Button component={RouterLink} to="/facilities/bookings" color="inherit">Back to My Bookings</Button>}>{error || 'Booking not found.'}</Alert>

  const cancellable = canCancelBooking(booking)
  return (
    <Stack spacing={3}>
      <Box>
        <Button component={RouterLink} to="/facilities/bookings">← Back to My Bookings</Button>
        <Stack direction={{ xs: 'column', sm: 'row' }} justifyContent="space-between" alignItems={{ sm: 'center' }} gap={1} sx={{ mt: 1 }}>
          <Typography component="h2" variant="h5" fontWeight={700}>Booking #{booking.bookingId}</Typography>
          <Chip label={statusLabel(booking.status)} color={booking.status === 'CONFIRMED' ? 'success' : 'default'} />
        </Stack>
      </Box>

      {success && <Alert severity="success">{success}</Alert>}
      {cancelError && !cancelOpen && <Alert severity="error">{cancelError}</Alert>}

      <Card variant="outlined"><CardContent><Stack spacing={2}>
        <Box>
          <Typography component="h3" variant="h6" fontWeight={700}>{booking.space.name}</Typography>
          <Typography color="text.secondary">{booking.space.building} · Floor {booking.space.floor} · Room {booking.space.roomNumber}</Typography>
        </Box>
        <Divider />
        <Typography><strong>Date:</strong> {formatFacilityDate(booking.startDateTime)}</Typography>
        <Typography><strong>Time:</strong> {formatFacilityTimeRange(booking.startDateTime, booking.endDateTime)}</Typography>
        <Typography><strong>Space type:</strong> {statusLabel(booking.space.spaceType)}</Typography>
        <Typography><strong>Capacity:</strong> {booking.space.capacity}</Typography>
        <Typography><strong>Equipment:</strong> {booking.space.equipment.length ? booking.space.equipment.join(', ') : 'None listed'}</Typography>
        <Divider />
        <Typography><strong>Created:</strong> {formatFacilityDateTime(booking.createdAt)}</Typography>
        <Typography><strong>Last updated:</strong> {formatFacilityDateTime(booking.updatedAt)}</Typography>
        {cancellable && <Button variant="outlined" color="error" onClick={() => { setCancelError(''); setCancelOpen(true) }} sx={{ alignSelf: 'flex-start' }}>Cancel Booking</Button>}
      </Stack></CardContent></Card>

      <Dialog open={cancelOpen} onClose={() => !cancelling && setCancelOpen(false)} fullWidth maxWidth="xs">
        <DialogTitle>Cancel this booking?</DialogTitle>
        <DialogContent>
          <Stack spacing={1} sx={{ pt: 0.5 }}>
            <Typography fontWeight={700}>{booking.space.name}</Typography>
            <Typography>{booking.space.building} · Room {booking.space.roomNumber}</Typography>
            <Typography>{formatFacilityDate(booking.startDateTime)}</Typography>
            <Typography>{formatFacilityTimeRange(booking.startDateTime, booking.endDateTime)}</Typography>
            {cancelError && <Alert severity="error" sx={{ mt: 1 }}>{cancelError}</Alert>}
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setCancelOpen(false)} disabled={cancelling}>Keep Booking</Button>
          <Button color="error" variant="contained" onClick={confirmCancellation} disabled={cancelling}>
            {cancelling ? <><CircularProgress size={18} color="inherit" sx={{ mr: 1 }} />Cancelling...</> : 'Confirm Cancellation'}
          </Button>
        </DialogActions>
      </Dialog>
    </Stack>
  )
}
