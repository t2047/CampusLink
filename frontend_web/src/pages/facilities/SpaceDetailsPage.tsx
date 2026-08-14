import {
  Alert,
  AlertTitle,
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
  Grid,
  Stack,
  TextField,
  Typography,
} from '@mui/material'
import axios from 'axios'
import { useEffect, useRef, useState } from 'react'
import { Link as RouterLink, useParams } from 'react-router-dom'
import { apiErrorMessage } from '../../api/client'
import {
  facilitiesApi,
  type AvailabilityResponse,
  type BookingResponse,
  type CreateBookingRequest,
  type Space,
} from '../../api/facilities'

const displayLabel = (value: string) => value.replaceAll('_', ' ')

interface BookingSelection {
  date: string
  startTime: string
  endTime: string
}

type AvailabilityState =
  | { status: 'idle' | 'checking' }
  | { status: 'available' | 'unavailable'; response: AvailabilityResponse }
  | { status: 'error'; message: string }

type BookingState =
  | { status: 'idle' | 'creating' }
  | { status: 'success'; response: BookingResponse }
  | { status: 'error'; message: string }

const emptySelection: BookingSelection = { date: '', startTime: '', endTime: '' }

function localDateTime(date: string, time: string) {
  return `${date}T${time.length === 5 ? `${time}:00` : time}`
}

function selectedDateTimes(selection: BookingSelection) {
  if (!selection.date || !selection.startTime || !selection.endTime) return null
  return {
    startDateTime: localDateTime(selection.date, selection.startTime),
    endDateTime: localDateTime(selection.date, selection.endTime),
  }
}

function unavailableMessage(reasonCode: string | null) {
  if (reasonCode === 'BOOKING_CONFLICT') {
    return 'This space is already booked during the selected time.'
  }
  if (reasonCode === 'SPACE_UNAVAILABLE') {
    return 'This space is currently unavailable for booking.'
  }
  return 'This space is not available for the selected time.'
}

function bookingDateTime(value: string) {
  const [date, time = ''] = value.split('T')
  return `${date} ${time.slice(0, 5)}`
}

export function SpaceDetailsPage() {
  const { spaceId } = useParams()
  const [space, setSpace] = useState<Space | null>(null)
  const [error, setError] = useState('')
  const [selection, setSelection] = useState<BookingSelection>(emptySelection)
  const [validationError, setValidationError] = useState('')
  const [availability, setAvailability] = useState<AvailabilityState>({ status: 'idle' })
  const [booking, setBooking] = useState<BookingState>({ status: 'idle' })
  const [confirmationOpen, setConfirmationOpen] = useState(false)
  const availabilityRequestId = useRef(0)

  useEffect(() => {
    let active = true
    const id = Number(spaceId)
    availabilityRequestId.current += 1
    setSpace(null)
    setSelection(emptySelection)
    setValidationError('')
    setAvailability({ status: 'idle' })
    setBooking({ status: 'idle' })
    setConfirmationOpen(false)
    if (!Number.isInteger(id) || id < 1) {
      setError('Space not found.')
      return () => { active = false }
    }
    setError('')
    facilitiesApi.getSpace(id)
      .then((result) => { if (active) setSpace(result) })
      .catch((requestError) => {
        if (!active) return
        setError(axios.isAxiosError(requestError) && requestError.response?.status === 404 ? 'Space not found.' : apiErrorMessage(requestError))
      })
    return () => { active = false }
  }, [spaceId])

  function updateSelection(field: keyof BookingSelection, value: string) {
    availabilityRequestId.current += 1
    setSelection((current) => ({ ...current, [field]: value }))
    setValidationError('')
    setAvailability({ status: 'idle' })
    setBooking({ status: 'idle' })
    setConfirmationOpen(false)
  }

  function validatedDateTimes() {
    const dateTimes = selectedDateTimes(selection)
    if (!dateTimes) {
      setValidationError('Choose a date, start time, and end time.')
      return null
    }
    if (dateTimes.endDateTime <= dateTimes.startDateTime) {
      setValidationError('End time must be later than start time.')
      return null
    }
    setValidationError('')
    return dateTimes
  }

  async function checkAvailability() {
    if (!space) return
    const dateTimes = validatedDateTimes()
    if (!dateTimes) return
    const requestId = availabilityRequestId.current + 1
    availabilityRequestId.current = requestId
    setBooking({ status: 'idle' })
    setAvailability({ status: 'checking' })
    try {
      const response = await facilitiesApi.checkSpaceAvailability(space.spaceId, dateTimes.startDateTime, dateTimes.endDateTime)
      if (availabilityRequestId.current !== requestId) return
      setAvailability({ status: response.available ? 'available' : 'unavailable', response })
    } catch (requestError) {
      if (availabilityRequestId.current !== requestId) return
      const message = axios.isAxiosError(requestError) && requestError.response?.status === 404
        ? 'This space could not be found.'
        : apiErrorMessage(requestError)
      setAvailability({ status: 'error', message })
    }
  }

  const currentDateTimes = selectedDateTimes(selection)
  const canBook = availability.status === 'available'
    && currentDateTimes !== null
    && availability.response.startDateTime === currentDateTimes.startDateTime
    && availability.response.endDateTime === currentDateTimes.endDateTime

  async function createBooking() {
    if (!space || !canBook || !currentDateTimes) return
    const request: CreateBookingRequest = {
      spaceId: space.spaceId,
      startDateTime: currentDateTimes.startDateTime,
      endDateTime: currentDateTimes.endDateTime,
    }
    setBooking({ status: 'creating' })
    try {
      const response = await facilitiesApi.createBooking(request)
      setConfirmationOpen(false)
      setAvailability({ status: 'idle' })
      setBooking({ status: 'success', response })
    } catch (requestError) {
      setConfirmationOpen(false)
      if (axios.isAxiosError(requestError) && requestError.response?.status === 409) {
        setAvailability({ status: 'idle' })
        setBooking({
          status: 'error',
          message: 'This space is no longer available for the selected time. Please check availability again.',
        })
        return
      }
      setBooking({ status: 'error', message: apiErrorMessage(requestError) })
    }
  }

  if (error) return <Stack spacing={2}><Alert severity="error">{error}</Alert><Button component={RouterLink} to="/facilities" sx={{ alignSelf: 'flex-start' }}>Back to spaces</Button></Stack>
  if (!space) return <Box sx={{ py: 8, textAlign: 'center' }}><CircularProgress /></Box>

  return (
    <Stack spacing={3}>
      <Button component={RouterLink} to="/facilities" sx={{ alignSelf: 'flex-start' }}>Back to spaces</Button>
      <Stack direction={{ xs: 'column', sm: 'row' }} justifyContent="space-between" gap={2}>
        <Box><Typography component="h2" variant="h5" fontWeight={700}>{space.name}</Typography><Typography color="text.secondary">{space.building} · Floor {space.floor} · Room {space.roomNumber}</Typography></Box>
        <Chip label={displayLabel(space.status)} color={space.status === 'AVAILABLE' ? 'success' : 'default'} sx={{ alignSelf: 'flex-start' }} />
      </Stack>
      <Card variant="outlined"><CardContent>
        <Typography component="h3" variant="h6" fontWeight={700}>Space Details</Typography><Divider sx={{ my: 2 }} />
        <Grid container spacing={2}>
          <Grid size={{ xs: 12, sm: 6 }}><Typography color="text.secondary" variant="body2">Space type</Typography><Typography>{displayLabel(space.spaceType)}</Typography></Grid>
          <Grid size={{ xs: 12, sm: 6 }}><Typography color="text.secondary" variant="body2">Capacity</Typography><Typography>{space.capacity}</Typography></Grid>
          <Grid size={{ xs: 12, sm: 6 }}><Typography color="text.secondary" variant="body2">Opening time</Typography><Typography>{space.openingTime}</Typography></Grid>
          <Grid size={{ xs: 12, sm: 6 }}><Typography color="text.secondary" variant="body2">Closing time</Typography><Typography>{space.closingTime}</Typography></Grid>
          <Grid size={12}><Typography color="text.secondary" variant="body2">Equipment</Typography><Typography>{space.equipment.length ? space.equipment.join(', ') : 'None listed'}</Typography></Grid>
        </Grid>
      </CardContent></Card>

      <Card variant="outlined"><CardContent>
        <Stack spacing={2.5}>
          <Box>
            <Typography component="h3" variant="h6" fontWeight={700}>Availability and Booking</Typography>
            <Typography color="text.secondary" sx={{ mt: 0.5 }}>Choose a date and time, check availability, then confirm your booking.</Typography>
          </Box>
          <Grid container spacing={2}>
            <Grid size={{ xs: 12, sm: 4 }}><TextField fullWidth type="date" label="Date" slotProps={{ inputLabel: { shrink: true } }} value={selection.date} onChange={(event) => updateSelection('date', event.target.value)} /></Grid>
            <Grid size={{ xs: 12, sm: 4 }}><TextField fullWidth type="time" label="Start time" slotProps={{ inputLabel: { shrink: true }, htmlInput: { step: 60 } }} value={selection.startTime} onChange={(event) => updateSelection('startTime', event.target.value)} /></Grid>
            <Grid size={{ xs: 12, sm: 4 }}><TextField fullWidth type="time" label="End time" slotProps={{ inputLabel: { shrink: true }, htmlInput: { step: 60 } }} value={selection.endTime} onChange={(event) => updateSelection('endTime', event.target.value)} /></Grid>
          </Grid>
          {validationError && <Alert severity="warning">{validationError}</Alert>}
          {availability.status === 'available' && <Alert severity="success"><AlertTitle>Available</AlertTitle>This space is available for the selected time.</Alert>}
          {availability.status === 'unavailable' && <Alert severity="warning"><AlertTitle>Unavailable</AlertTitle>{unavailableMessage(availability.response.reasonCode)}</Alert>}
          {availability.status === 'error' && <Alert severity="error">{availability.message}</Alert>}
          {booking.status === 'error' && <Alert severity="error">{booking.message}</Alert>}
          {booking.status === 'success' && (
            <Alert severity="success">
              <AlertTitle>Booking confirmed</AlertTitle>
              <Stack spacing={0.5}>
                <Typography>Booking ID: {booking.response.bookingId}</Typography>
                <Typography>Space: {booking.response.space.name}</Typography>
                <Typography>Date/time: {bookingDateTime(booking.response.startDateTime)} – {booking.response.endDateTime.split('T')[1]?.slice(0, 5)}</Typography>
                <Typography>Status: {displayLabel(booking.response.status)}</Typography>
                <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1} sx={{ alignSelf: 'flex-start', mt: 0.5 }}>
                  <Button component={RouterLink} to={`/facilities/bookings/${booking.response.bookingId}`}>View booking</Button>
                  <Button component={RouterLink} to="/facilities/bookings">View My Bookings</Button>
                </Stack>
              </Stack>
            </Alert>
          )}
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5} justifyContent="flex-end">
            <Button
              variant="outlined"
              onClick={checkAvailability}
              disabled={!selection.date || !selection.startTime || !selection.endTime || availability.status === 'checking' || booking.status === 'creating'}
            >
              {availability.status === 'checking' ? <><CircularProgress size={18} sx={{ mr: 1 }} />Checking...</> : 'Check Availability'}
            </Button>
            <Button variant="contained" onClick={() => setConfirmationOpen(true)} disabled={!canBook || booking.status === 'creating'}>Book this space</Button>
          </Stack>
        </Stack>
      </CardContent></Card>

      <Dialog open={confirmationOpen} onClose={() => booking.status !== 'creating' && setConfirmationOpen(false)} fullWidth maxWidth="xs">
        <DialogTitle>Confirm booking</DialogTitle>
        <DialogContent>
          <Stack spacing={1} sx={{ pt: 0.5 }}>
            <Typography><strong>Space:</strong> {space.name}</Typography>
            <Typography><strong>Date:</strong> {selection.date}</Typography>
            <Typography><strong>Start time:</strong> {selection.startTime}</Typography>
            <Typography><strong>End time:</strong> {selection.endTime}</Typography>
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setConfirmationOpen(false)} disabled={booking.status === 'creating'}>Cancel</Button>
          <Button variant="contained" onClick={createBooking} disabled={booking.status === 'creating'}>{booking.status === 'creating' ? 'Confirming...' : 'Confirm Booking'}</Button>
        </DialogActions>
      </Dialog>
    </Stack>
  )
}
