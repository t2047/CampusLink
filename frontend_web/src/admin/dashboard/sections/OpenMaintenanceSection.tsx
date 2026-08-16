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
import { searchAdminFacilityMaintenance } from '../../../api/adminFacilities'
import { apiErrorMessage } from '../../../api/client'
import { formatFacilityDateTime } from '../../../pages/facilities/bookingDateTime'
import type { AdminFacilityMaintenance, PageResponse } from '../../../types'

function statusLabel(status: AdminFacilityMaintenance['status']) {
  return status.replaceAll('_', ' ').replace(/^./, (value) => value.toUpperCase()).toLowerCase()
    .replace(/(^| )\w/g, (value) => value.toUpperCase())
}

function priorityColor(priority: AdminFacilityMaintenance['priority']): 'error' | 'warning' | 'default' {
  if (priority === 'HIGH') return 'error'
  if (priority === 'MEDIUM') return 'warning'
  return 'default'
}

function statusColor(status: AdminFacilityMaintenance['status']): 'success' | 'info' | 'default' {
  if (status === 'RESOLVED') return 'success'
  if (status === 'IN_PROGRESS') return 'info'
  return 'default'
}

function descriptionSummary(description: string) {
  return description.length > 100 ? `${description.slice(0, 97)}...` : description
}

function facilityLabel(ticket: AdminFacilityMaintenance) {
  return ticket.spaceName ?? `${ticket.building} / ${ticket.roomNumber}`
}

export function OpenMaintenanceSection() {
  const [page, setPage] = useState<PageResponse<AdminFacilityMaintenance> | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const mountedRef = useRef(false)
  const requestInFlightRef = useRef(false)

  const loadMaintenance = useCallback(async () => {
    if (requestInFlightRef.current) return

    requestInFlightRef.current = true
    if (mountedRef.current) {
      setLoading(true)
      setError('')
      setPage(null)
    }

    try {
      const data = await searchAdminFacilityMaintenance({
        statuses: ['SUBMITTED', 'IN_PROGRESS'],
        page: 0,
        size: 5,
        sort: 'createdAt,asc',
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
    void loadMaintenance()

    return () => {
      mountedRef.current = false
    }
  }, [loadMaintenance])

  const tickets = page?.content.slice(0, 5) ?? []

  return (
    <Box component="section" aria-labelledby="open-maintenance-heading" sx={{ display: 'grid', gap: 3 }}>
      <Stack direction={{ xs: 'column', sm: 'row' }} justifyContent="space-between" alignItems={{ sm: 'center' }} spacing={2}>
        <Typography id="open-maintenance-heading" component="h2" variant="h5" fontWeight={700}>
          Open Maintenance
        </Typography>
        <Button component={RouterLink} to="/admin/facilities/maintenance" variant="outlined">
          View All
        </Button>
      </Stack>

      {loading && (
        <Stack role="status" direction="row" spacing={2} alignItems="center">
          <CircularProgress size={24} aria-label="Loading open maintenance" />
          <Typography>Loading open maintenance</Typography>
        </Stack>
      )}

      {!loading && error && (
        <Alert
          severity="error"
          action={<Button color="inherit" onClick={() => void loadMaintenance()}>Retry</Button>}
        >
          {error}
        </Alert>
      )}

      {!loading && page && tickets.length === 0 && (
        <Alert severity="info">No open maintenance requests.</Alert>
      )}

      {!loading && tickets.length > 0 && (
        <Card variant="outlined">
          <TableContainer sx={{ overflowX: 'auto' }}>
            <Table size="small" aria-label="Open maintenance requests">
              <TableHead>
                <TableRow>
                  <TableCell>Facility</TableCell>
                  <TableCell>Issue</TableCell>
                  <TableCell>Submitter</TableCell>
                  <TableCell>Priority</TableCell>
                  <TableCell>Status</TableCell>
                  <TableCell>Submitted</TableCell>
                  <TableCell>Action</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {tickets.map((ticket) => (
                  <TableRow key={ticket.ticketId}>
                    <TableCell>{facilityLabel(ticket)}</TableCell>
                    <TableCell>{descriptionSummary(ticket.description)}</TableCell>
                    <TableCell>{ticket.userEmail ?? 'Unknown user'}</TableCell>
                    <TableCell>
                      <Chip label={ticket.priority} color={priorityColor(ticket.priority)} size="small" />
                    </TableCell>
                    <TableCell>
                      <Chip label={statusLabel(ticket.status)} color={statusColor(ticket.status)} size="small" />
                    </TableCell>
                    <TableCell sx={{ whiteSpace: 'nowrap' }}>
                      {formatFacilityDateTime(ticket.createdAt)}
                    </TableCell>
                    <TableCell>
                      <Button component={RouterLink} to={`/admin/facilities/maintenance/${ticket.ticketId}`} size="small">
                        View
                      </Button>
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
