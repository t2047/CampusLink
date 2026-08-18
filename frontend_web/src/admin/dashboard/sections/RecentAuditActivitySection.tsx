import {
  Alert,
  Box,
  Button,
  Card,
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
import { searchAdminAuditLogs } from '../../../api/adminLostFound'
import { apiErrorMessage } from '../../../api/client'
import type { AdminAuditLog, PageResponse } from '../../../types'
import { formatDashboardInstant } from '../dashboardDateTime'
import { dashboardAuditActionLabels } from '../../../labels'


function summarize(value: string, maximumLength = 120) {
  return value.length > maximumLength ? `${value.slice(0, maximumLength - 3)}...` : value
}

function AuditDetail({ log }: { log: AdminAuditLog }) {
  const primary = log.reason ?? log.detail
  if (!primary) return <>?</>

  return (
    <Stack spacing={0.5}>
      <Typography>{summarize(primary)}</Typography>
      {log.reason && log.detail && (
        <Typography variant="body2" color="text.secondary">{summarize(log.detail)}</Typography>
      )}
    </Stack>
  )
}

export function RecentAuditActivitySection() {
  const [page, setPage] = useState<PageResponse<AdminAuditLog> | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const mountedRef = useRef(false)
  const requestInFlightRef = useRef(false)

  const loadAuditLogs = useCallback(async () => {
    if (requestInFlightRef.current) return

    requestInFlightRef.current = true
    if (mountedRef.current) {
      setLoading(true)
      setError('')
      setPage(null)
    }

    try {
      const data = await searchAdminAuditLogs({
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
    void loadAuditLogs()

    return () => {
      mountedRef.current = false
    }
  }, [loadAuditLogs])

  const logs = page?.content.slice(0, 5) ?? []

  return (
    <Box component="section" aria-labelledby="recent-audit-activity-heading" sx={{ display: 'grid', gap: 3 }}>
      <Stack direction={{ xs: 'column', sm: 'row' }} justifyContent="space-between" alignItems={{ sm: 'center' }} spacing={2}>
        <Typography id="recent-audit-activity-heading" component="h2" variant="h5" fontWeight={700}>
          Recent Administrative Activity
        </Typography>
        <Button component={RouterLink} to="/admin/lost-found?tab=audit" variant="outlined">View All</Button>
      </Stack>

      {loading && (
        <Stack role="status" direction="row" spacing={2} alignItems="center">
          <CircularProgress size={24} aria-label="Loading recent administrative activity" />
          <Typography>Loading recent administrative activity</Typography>
        </Stack>
      )}

      {!loading && error && (
        <Alert
          severity="error"
          action={<Button color="inherit" onClick={() => void loadAuditLogs()}>Retry</Button>}
        >
          {error}
        </Alert>
      )}

      {!loading && page && logs.length === 0 && (
        <Alert severity="info">No administrative activity is currently available.</Alert>
      )}

      {!loading && logs.length > 0 && (
        <Card variant="outlined">
          <TableContainer sx={{ overflowX: 'auto' }}>
            <Table size="small" aria-label="Recent administrative activity">
              <TableHead>
                <TableRow>
                  <TableCell>Time</TableCell>
                  <TableCell>Action</TableCell>
                  <TableCell>Item / Report</TableCell>
                  <TableCell>Administrator</TableCell>
                  <TableCell>Detail</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {logs.map((log) => (
                  <TableRow key={log.id}>
                    <TableCell sx={{ whiteSpace: 'nowrap' }}>{formatDashboardInstant(log.createdAt)}</TableCell>
                    <TableCell>{dashboardAuditActionLabels[log.action]}</TableCell>
                    <TableCell>
                      <Stack spacing={0.25}>
                        <Typography>{log.itemName}</Typography>
                        <Typography variant="body2" color="text.secondary">Report #{log.reportId}</Typography>
                      </Stack>
                    </TableCell>
                    <TableCell>{log.actorEmail}</TableCell>
                    <TableCell sx={{ minWidth: '13.75rem' }}><AuditDetail log={log} /></TableCell>
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



