import {
  Alert,
  Box,
  Button,
  Chip,
  CircularProgress,
  FormControl,
  InputLabel,
  MenuItem,
  Pagination,
  Paper,
  Select,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  TextField,
  Typography,
} from '@mui/material'
import { FormEvent, useEffect, useState } from 'react'
import { searchAdminAuditLogs } from '../../api/adminLostFound'
import { apiErrorMessage } from '../../api/client'
import { auditActionLabels } from '../../labels'
import type { AdminAuditLog, AuditAction, PageResponse } from '../../types'

const auditActions = Object.keys(auditActionLabels) as AuditAction[]

function formatDateTime(value: string) {
  return new Intl.DateTimeFormat('en-SG', {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(new Date(value))
}

interface AuditFilters {
  action: string
  actorEmail: string
  keyword: string
  reportId: string
}

const emptyFilters: AuditFilters = { action: '', actorEmail: '', keyword: '', reportId: '' }

export function AdminAuditLogsSection() {
  const [result, setResult] = useState<PageResponse<AdminAuditLog> | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [filters, setFilters] = useState<AuditFilters>(emptyFilters)

  function load(source: AuditFilters, overrides: Record<string, string | number | undefined> = {}) {
    const params: Record<string, string | number | undefined> = {}
    if (source.action) params.action = source.action
    if (source.actorEmail.trim()) params.actorEmail = source.actorEmail.trim()
    if (source.keyword.trim()) params.keyword = source.keyword.trim()
    if (source.reportId.trim()) params.reportId = Number(source.reportId.trim())
    setLoading(true)
    setError('')
    searchAdminAuditLogs({ ...params, ...overrides, size: 25, sort: 'createdAt,desc' })
      .then((data) => setResult(data))
      .catch((requestError) => setError(apiErrorMessage(requestError)))
      .finally(() => setLoading(false))
  }

  useEffect(() => {
    load(emptyFilters, { page: 0 })
    // 挂载时仅加载一次；过滤由用户显式触发
  }, [])

  function submit(event: FormEvent) {
    event.preventDefault()
    load(filters, { page: 0 })
  }

  function reset() {
    setFilters(emptyFilters)
    load(emptyFilters, { page: 0 })
  }

  return (
    <Stack spacing={2}>
      <Paper component="form" onSubmit={submit} sx={{ p: 2 }}>
        <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: { xs: '1fr', sm: 'repeat(2, 1fr)', lg: 'repeat(4, 1fr)' } }}>
          <FormControl>
            <InputLabel>Action</InputLabel>
            <Select label="Action" value={filters.action} onChange={(event) => setFilters({ ...filters, action: event.target.value })}>
              <MenuItem value="">All</MenuItem>
              {auditActions.map((action) => <MenuItem key={action} value={action}>{auditActionLabels[action]}</MenuItem>)}
            </Select>
          </FormControl>
          <TextField
            label="Reporter email"
            value={filters.actorEmail}
            onChange={(event) => setFilters({ ...filters, actorEmail: event.target.value })}
          />
          <TextField
            label="Keyword"
            value={filters.keyword}
            onChange={(event) => setFilters({ ...filters, keyword: event.target.value })}
          />
          <TextField
            label="Report ID"
            type="number"
            value={filters.reportId}
            onChange={(event) => setFilters({ ...filters, reportId: event.target.value })}
          />
        </Box>
        <Stack direction="row" spacing={1} justifyContent="flex-end" sx={{ mt: 2 }}>
          <Button onClick={reset}>Reset</Button>
          <Button type="submit" variant="contained">Search</Button>
        </Stack>
      </Paper>

      {error && <Alert severity="error">{error}</Alert>}

      {loading ? (
        <Box sx={{ py: 8, display: 'grid', placeItems: 'center' }}><CircularProgress /></Box>
      ) : result?.content.length ? (
        <>
          <TableContainer component={Paper} variant="outlined">
            <Table aria-label="Lost and Found administration audit logs">
              <TableHead>
                <TableRow>
                  <TableCell>Time</TableCell>
                  <TableCell>Report</TableCell>
                  <TableCell>Action</TableCell>
                  <TableCell>Actor</TableCell>
                  <TableCell>Reason</TableCell>
                  <TableCell>Detail</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {result.content.map((log) => (
                  <TableRow key={log.id} hover>
                    <TableCell>{formatDateTime(log.createdAt)}</TableCell>
                    <TableCell>
                      <Typography fontWeight={600}>{log.itemName}</Typography>
                      <Typography variant="caption" color="text.secondary">#{log.reportId}</Typography>
                    </TableCell>
                    <TableCell><Chip size="small" color="secondary" label={auditActionLabels[log.action]} /></TableCell>
                    <TableCell>{log.actorEmail}</TableCell>
                    <TableCell>{log.reason ?? '—'}</TableCell>
                    <TableCell>{log.detail ?? '—'}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableContainer>
          {result.totalPages > 1 && (
            <Pagination
              sx={{ alignSelf: 'center' }}
              page={result.page + 1}
              count={result.totalPages}
              onChange={(_, page) => load(filters, { page: page - 1 })}
            />
          )}
        </>
      ) : !error && (
        <Paper sx={{ p: 6, textAlign: 'center' }}>
          <Typography variant="h6">No audit logs found</Typography>
          <Typography color="text.secondary">Admin write actions are recorded here.</Typography>
        </Paper>
      )}
    </Stack>
  )
}
