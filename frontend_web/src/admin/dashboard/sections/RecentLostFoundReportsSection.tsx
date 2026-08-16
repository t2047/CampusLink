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
import { Link as RouterLink } from 'react-router-dom'
import { useCallback, useEffect, useRef, useState } from 'react'
import { searchAdminLostFoundReports } from '../../../api/adminLostFound'
import { apiErrorMessage } from '../../../api/client'
import type { AdminLostFoundReport, PageResponse, ReportStatus, ReportType } from '../../../types'
import { formatDashboardInstant } from '../dashboardDateTime'

const REPORT_TYPE_LABELS: Record<ReportType, string> = {
  LOST: 'Lost',
  FOUND: 'Found',
}

const REPORT_STATUS_LABELS: Record<ReportStatus, string> = {
  OPEN: 'Open',
  CLAIMED: 'Claimed',
  CLOSED: 'Closed',
}

function statusColor(status: ReportStatus): 'primary' | 'warning' | 'success' {
  if (status === 'CLAIMED') return 'warning'
  if (status === 'CLOSED') return 'success'
  return 'primary'
}

export function RecentLostFoundReportsSection() {
  const [page, setPage] = useState<PageResponse<AdminLostFoundReport> | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const mountedRef = useRef(false)
  const requestInFlightRef = useRef(false)

  const loadReports = useCallback(async () => {
    if (requestInFlightRef.current) return

    requestInFlightRef.current = true
    if (mountedRef.current) {
      setLoading(true)
      setError('')
      setPage(null)
    }

    try {
      const data = await searchAdminLostFoundReports({
        page: 0,
        size: 5,
        sort: 'createdAt,desc',
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
    void loadReports()

    return () => {
      mountedRef.current = false
    }
  }, [loadReports])

  const reports = page?.content.slice(0, 5) ?? []

  return (
    <Box component="section" aria-labelledby="recent-lost-found-reports-heading" sx={{ display: 'grid', gap: 3 }}>
      <Stack direction={{ xs: 'column', sm: 'row' }} justifyContent="space-between" alignItems={{ sm: 'center' }} spacing={2}>
        <Typography id="recent-lost-found-reports-heading" component="h2" variant="h5" fontWeight={700}>
          Recent Lost & Found Reports
        </Typography>
        <Button component={RouterLink} to="/admin/lost-found" variant="outlined">View All</Button>
      </Stack>

      {loading && (
        <Stack role="status" direction="row" spacing={2} alignItems="center">
          <CircularProgress size={24} aria-label="Loading recent Lost & Found reports" />
          <Typography>Loading recent Lost & Found reports</Typography>
        </Stack>
      )}

      {!loading && error && (
        <Alert
          severity="error"
          action={<Button color="inherit" onClick={() => void loadReports()}>Retry</Button>}
        >
          {error}
        </Alert>
      )}

      {!loading && page && reports.length === 0 && (
        <Alert severity="info">No Lost & Found reports are currently available.</Alert>
      )}

      {!loading && reports.length > 0 && (
        <Card variant="outlined">
          <TableContainer sx={{ overflowX: 'auto' }}>
            <Table size="small" aria-label="Recent Lost & Found reports">
              <TableHead>
                <TableRow>
                  <TableCell>Item</TableCell>
                  <TableCell>Type</TableCell>
                  <TableCell>Status</TableCell>
                  <TableCell>Location</TableCell>
                  <TableCell>Reporter</TableCell>
                  <TableCell>Created</TableCell>
                  <TableCell>Action</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {reports.map((report) => (
                  <TableRow key={report.id}>
                    <TableCell>
                      <Stack direction="row" spacing={1} alignItems="center">
                        <Typography>{report.itemName}</Typography>
                        {report.adminHidden && <Chip label="Hidden" size="small" color="default" />}
                      </Stack>
                    </TableCell>
                    <TableCell><Chip label={REPORT_TYPE_LABELS[report.reportType]} size="small" variant="outlined" /></TableCell>
                    <TableCell><Chip label={REPORT_STATUS_LABELS[report.status]} size="small" color={statusColor(report.status)} /></TableCell>
                    <TableCell>{report.location}</TableCell>
                    <TableCell>{report.createdByEmail}</TableCell>
                    <TableCell sx={{ whiteSpace: 'nowrap' }}>{formatDashboardInstant(report.createdAt)}</TableCell>
                    <TableCell>
                      <Button component={RouterLink} to={`/lost-found/${report.id}`} size="small">View Report</Button>
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
