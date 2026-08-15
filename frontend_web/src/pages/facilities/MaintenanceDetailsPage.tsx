import { Alert, Box, Button, Card, CardContent, Chip, CircularProgress, Divider, Stack, Typography } from '@mui/material'
import axios from 'axios'
import { useEffect, useState } from 'react'
import { Link as RouterLink, useParams } from 'react-router-dom'
import { apiErrorMessage } from '../../api/client'
import { facilitiesApi, type MaintenanceResponse } from '../../api/facilities'
import { formatFacilityDateTime } from './bookingDateTime'
import { maintenanceStatusColor, maintenanceStatusLabel, maintenanceStatusMessage } from './maintenanceDisplay'

export function MaintenanceDetailsPage() {
  const { requestId } = useParams()
  const [request, setRequest] = useState<MaintenanceResponse | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')

  useEffect(() => {
    let active = true
    const id = Number(requestId)
    if (!Number.isInteger(id) || id < 1) {
      setError('Maintenance request not found.')
      setLoading(false)
      return () => { active = false }
    }
    facilitiesApi.getMaintenanceRequest(id)
      .then((result) => { if (active) setRequest(result) })
      .catch((requestError) => {
        if (!active) return
        setError(axios.isAxiosError(requestError) && requestError.response?.status === 404
          ? 'Maintenance request not found.'
          : apiErrorMessage(requestError))
      })
      .finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [requestId])

  if (loading) return <Box sx={{ py: 8, textAlign: 'center' }}><CircularProgress /></Box>
  if (error || !request) return <Alert severity="error" action={<Button component={RouterLink} to="/facilities/maintenance" color="inherit">Back to My Maintenance Requests</Button>}>{error || 'Maintenance request not found.'}</Alert>

  return (
    <Stack spacing={3}>
      <Box>
        <Button component={RouterLink} to="/facilities/maintenance">← Back to My Maintenance Requests</Button>
        <Stack direction={{ xs: 'column', sm: 'row' }} justifyContent="space-between" alignItems={{ sm: 'center' }} gap={1} sx={{ mt: 1 }}>
          <Typography component="h2" variant="h5" fontWeight={700}>Maintenance Request #{request.ticketId}</Typography>
          <Chip label={maintenanceStatusLabel(request.status)} color={maintenanceStatusColor(request.status)} />
        </Stack>
      </Box>

      <Alert severity={request.status === 'RESOLVED' ? 'success' : request.status === 'CANCELLED' ? 'info' : 'warning'}>
        {maintenanceStatusMessage(request.status)}
      </Alert>

      <Card variant="outlined"><CardContent><Stack spacing={2}>
        <Box>
          <Typography component="h3" variant="h6" fontWeight={700}>{request.spaceName ?? `${request.building} ${request.roomNumber}`}</Typography>
          <Typography color="text.secondary">{request.building} · Room {request.roomNumber}</Typography>
        </Box>
        <Divider />
        <Typography><strong>Issue type:</strong> {request.facilityType}</Typography>
        <Typography><strong>Description:</strong> {request.description}</Typography>
        <Typography><strong>Priority:</strong> {request.priority}</Typography>
        <Divider />
        <Typography><strong>Created:</strong> {formatFacilityDateTime(request.createdAt)}</Typography>
        <Typography><strong>Last updated:</strong> {formatFacilityDateTime(request.updatedAt)}</Typography>
      </Stack></CardContent></Card>
    </Stack>
  )
}
