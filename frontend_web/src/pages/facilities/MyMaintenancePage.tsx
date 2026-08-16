import { Alert, Box, Button, Card, CardContent, Chip, CircularProgress, Paper, Stack, Typography } from '@mui/material'
import { useEffect, useState } from 'react'
import { Link as RouterLink } from 'react-router-dom'
import { apiErrorMessage } from '../../api/client'
import { facilitiesApi, type MaintenanceResponse } from '../../api/facilities'
import { formatFacilityDateTime } from './bookingDateTime'
import { maintenanceStatusColor, maintenanceStatusLabel, sortMaintenanceRequests } from './maintenanceDisplay'

export function MyMaintenancePage() {
  const [requests, setRequests] = useState<MaintenanceResponse[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')

  useEffect(() => {
    let active = true
    facilitiesApi.listMaintenanceRequests()
      .then((result) => { if (active) setRequests(sortMaintenanceRequests(result)) })
      .catch((requestError) => { if (active) setError(apiErrorMessage(requestError)) })
      .finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [])

  return (
    <Stack spacing={3}>
      <Stack direction={{ xs: 'column', sm: 'row' }} justifyContent="space-between" alignItems={{ sm: 'center' }} gap={2}>
        <Box>
          <Typography component="h2" variant="h5" fontWeight={700}>My Maintenance Requests</Typography>
          <Typography color="text.secondary">Track problems you have reported to Facilities.</Typography>
          {!loading && !error && <Typography variant="body2" color="primary" sx={{ mt: 1 }}>{requests.length} {requests.length === 1 ? 'request' : 'requests'} total</Typography>}
        </Box>
        <Button component={RouterLink} to="/facilities/maintenance/new" variant="contained">Report Maintenance</Button>
      </Stack>

      {error && <Alert severity="error">{error}</Alert>}
      {loading ? <Box sx={{ py: 8, textAlign: 'center' }}><CircularProgress /></Box> : requests.length ? (
        <Stack spacing={2}>
          {requests.map((request) => (
            <Card key={request.ticketId} variant="outlined"><CardContent>
              <Stack direction={{ xs: 'column', sm: 'row' }} justifyContent="space-between" gap={2}>
                <Stack spacing={0.75}>
                  <Stack direction="row" alignItems="center" spacing={1} flexWrap="wrap">
                    <Typography component="h3" variant="h6" fontWeight={700}>Request #{request.ticketId} · {request.facilityType}</Typography>
                    <Chip size="small" label={maintenanceStatusLabel(request.status)} color={maintenanceStatusColor(request.status)} />
                    <Chip size="small" label={`${request.priority} PRIORITY`} variant="outlined" />
                  </Stack>
                  <Typography>{request.spaceName ?? `${request.building} ${request.roomNumber}`} · {request.building} Room {request.roomNumber}</Typography>
                  <Typography color="text.secondary">{request.description}</Typography>
                  <Typography variant="body2" color="text.secondary">Submitted {formatFacilityDateTime(request.createdAt)} · Updated {formatFacilityDateTime(request.updatedAt)}</Typography>
                </Stack>
                <Button component={RouterLink} to={`/facilities/maintenance/${request.ticketId}`} sx={{ alignSelf: { xs: 'flex-start', sm: 'center' } }}>View details</Button>
              </Stack>
            </CardContent></Card>
          ))}
        </Stack>
      ) : !error && (
        <Paper sx={{ p: 6, textAlign: 'center' }}>
          <Typography variant="h6">No maintenance requests yet</Typography>
          <Typography color="text.secondary" sx={{ mb: 2 }}>Report a room or equipment problem when you find one.</Typography>
          <Button component={RouterLink} to="/facilities/maintenance/new" variant="contained">Report Maintenance</Button>
        </Paper>
      )}
    </Stack>
  )
}
