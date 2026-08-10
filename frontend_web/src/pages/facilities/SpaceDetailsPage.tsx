import { Alert, Box, Card, CardContent, Chip, CircularProgress, Divider, Grid, Stack, Typography } from '@mui/material'
import axios from 'axios'
import { useEffect, useState } from 'react'
import { Link as RouterLink, useParams } from 'react-router-dom'
import { Button } from '@mui/material'
import { apiErrorMessage } from '../../api/client'
import { facilitiesApi, type Space } from '../../api/facilities'

const displayLabel = (value: string) => value.replaceAll('_', ' ')

export function SpaceDetailsPage() {
  const { spaceId } = useParams()
  const [space, setSpace] = useState<Space | null>(null)
  const [error, setError] = useState('')

  useEffect(() => {
    let active = true
    const id = Number(spaceId)
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
      <Card variant="outlined"><CardContent><Typography component="h3" variant="h6" fontWeight={700}>Availability and Booking</Typography><Typography color="text.secondary" sx={{ mt: 1 }}>Availability checking and booking will be added in the next phase.</Typography></CardContent></Card>
    </Stack>
  )
}
