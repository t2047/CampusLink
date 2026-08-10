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
  FormControl,
  InputLabel,
  MenuItem,
  Pagination,
  Paper,
  Select,
  Stack,
  Tab,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Tabs,
  TextField,
  Typography,
} from '@mui/material'
import { FormEvent, useEffect, useMemo, useState } from 'react'
import { Link as RouterLink, useSearchParams } from 'react-router-dom'
import {
  deleteAdminReport,
  delistAdminReport,
  getAdminLostFoundOverview,
  restoreAdminReport,
  searchAdminLostFoundReports,
} from '../../api/adminLostFound'
import { apiErrorMessage } from '../../api/client'
import { categoryLabels, reportStatusLabels, reportTypeLabels } from '../../labels'
import type {
  AdminLostFoundOverview,
  AdminLostFoundReport,
  ItemCategory,
  PageResponse,
  ReportStatus,
  ReportType,
} from '../../types'
import { AdminAuditLogsSection } from './AdminAuditLogsSection'
import { AdminClaimsSection } from './AdminClaimsSection'

const categories = Object.keys(categoryLabels) as ItemCategory[]
const reportTypes: ReportType[] = ['LOST', 'FOUND']
const reportStatuses: ReportStatus[] = ['OPEN', 'CLAIMED', 'CLOSED']

type AdminAction = 'delist' | 'restore' | 'delete'
type TabValue = 'reports' | 'claims' | 'audit'

interface MetricCardProps {
  label: string
  value: number
  secondary?: string
}

function MetricCard({ label, value, secondary }: MetricCardProps) {
  return (
    <Card variant="outlined">
      <CardContent>
        <Typography color="text.secondary" variant="body2">{label}</Typography>
        <Typography variant="h4" fontWeight={700}>{value}</Typography>
        {secondary && <Typography color="text.secondary" variant="caption">{secondary}</Typography>}
      </CardContent>
    </Card>
  )
}

function formatDateTime(value: string) {
  return new Intl.DateTimeFormat('en-SG', {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(new Date(value))
}

const reportQueryKeys = [
  'keyword', 'reportType', 'category', 'status', 'colour', 'location',
  'dateFrom', 'dateTo', 'adminHidden', 'page',
] as const

function normalizeReportStatus(value: string | null) {
  return reportStatuses.includes(value as ReportStatus) ? value ?? '' : ''
}

function normalizeReportSearchParams(source: URLSearchParams) {
  const normalized = new URLSearchParams()
  reportQueryKeys.forEach((key) => {
    const value = source.get(key)
    if (!value) return
    if (key === 'status' && !reportStatuses.includes(value as ReportStatus)) return
    normalized.set(key, value)
  })
  return normalized
}

export function AdminLostFoundPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const tabParam = searchParams.get('tab')
  const tab: TabValue = tabParam === 'claims' ? 'claims' : tabParam === 'audit' ? 'audit' : 'reports'
  const [overview, setOverview] = useState<AdminLostFoundOverview | null>(null)
  const [result, setResult] = useState<PageResponse<AdminLostFoundReport> | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [refreshCounter, setRefreshCounter] = useState(0)
  const [form, setForm] = useState(() => ({
    keyword: searchParams.get('keyword') ?? '',
    reportType: searchParams.get('reportType') ?? '',
    category: searchParams.get('category') ?? '',
    status: normalizeReportStatus(searchParams.get('status')),
    colour: searchParams.get('colour') ?? '',
    location: searchParams.get('location') ?? '',
    dateFrom: searchParams.get('dateFrom') ?? '',
    dateTo: searchParams.get('dateTo') ?? '',
    adminHidden: searchParams.get('adminHidden') ?? '',
  }))
  const [pendingAction, setPendingAction] = useState<{ report: AdminLostFoundReport; action: AdminAction } | null>(null)
  const [actionReason, setActionReason] = useState('')
  const [actionBusy, setActionBusy] = useState(false)
  const [actionError, setActionError] = useState('')

  // Reports read only their whitelisted query parameters.
  const reportsQueryKey = useMemo(
    () => tab === 'reports' ? normalizeReportSearchParams(searchParams).toString() : '',
    [searchParams, tab],
  )

  useEffect(() => {
    if (tab === 'reports' && searchParams.toString() !== reportsQueryKey) {
      setSearchParams(new URLSearchParams(reportsQueryKey), { replace: true })
    }
  }, [reportsQueryKey, searchParams, setSearchParams, tab])

  useEffect(() => {
    if (tab !== 'reports') return
    let active = true
    setLoading(true)
    setError('')
    const params = Object.fromEntries(new URLSearchParams(reportsQueryKey).entries())
    setForm({
      keyword: params.keyword ?? '',
      reportType: params.reportType ?? '',
      category: params.category ?? '',
      status: params.status ?? '',
      colour: params.colour ?? '',
      location: params.location ?? '',
      dateFrom: params.dateFrom ?? '',
      dateTo: params.dateTo ?? '',
      adminHidden: params.adminHidden ?? '',
    })

    Promise.all([
      getAdminLostFoundOverview(),
      searchAdminLostFoundReports({
        keyword: params.keyword,
        reportType: params.reportType,
        category: params.category,
        status: params.status,
        adminHidden: params.adminHidden,
        colour: params.colour,
        location: params.location,
        dateFrom: params.dateFrom,
        dateTo: params.dateTo,
        page: params.page ?? 0,
        size: 25,
        sort: 'createdAt,desc',
      }),
    ])
      .then(([overviewData, reportsData]) => {
        if (active) {
          setOverview(overviewData)
          setResult(reportsData)
        }
      })
      .catch((requestError) => {
        if (active) setError(apiErrorMessage(requestError))
      })
      .finally(() => {
        if (active) setLoading(false)
      })

    return () => { active = false }
  }, [reportsQueryKey, refreshCounter, tab])

  // Claims and Audit load overview metrics without loading the reports list.
  useEffect(() => {
    if (tab === 'reports') return
    setError('')
    let active = true
    getAdminLostFoundOverview()
      .then((overviewData) => {
        if (active) setOverview(overviewData)
      })
      .catch((requestError) => {
        if (active) setError(apiErrorMessage(requestError))
      })
    return () => { active = false }
  }, [tab, refreshCounter])

  function changeTab(next: TabValue) {
    if (next === 'claims') {
      setSearchParams({ tab: 'claims', status: 'SUBMITTED' })
    } else if (next === 'audit') {
      setSearchParams({ tab: 'audit' })
    } else {
      setSearchParams({})
    }
  }

  function submit(event: FormEvent) {
    event.preventDefault()
    const next = new URLSearchParams()
    Object.entries(form).forEach(([key, value]) => {
      if (value.trim()) next.set(key, value.trim())
    })
    setSearchParams(next)
  }

  function reset() {
    setForm({
      keyword: '',
      reportType: '',
      category: '',
      status: '',
      colour: '',
      location: '',
      dateFrom: '',
      dateTo: '',
      adminHidden: '',
    })
    setSearchParams({})
  }

  function openAction(report: AdminLostFoundReport, action: AdminAction) {
    setPendingAction({ report, action })
    setActionReason('')
    setActionError('')
  }

  function closeAction() {
    setPendingAction(null)
    setActionBusy(false)
    setActionError('')
  }

  async function confirmAction() {
    if (!pendingAction || !actionReason.trim()) return
    setActionBusy(true)
    setActionError('')
    const { report, action } = pendingAction
    try {
      if (action === 'delist') await delistAdminReport(report.id, actionReason.trim())
      else if (action === 'restore') await restoreAdminReport(report.id, actionReason.trim())
      else await deleteAdminReport(report.id, actionReason.trim())
      closeAction()
      setRefreshCounter((counter) => counter + 1)
    } catch (requestError) {
      setActionError(apiErrorMessage(requestError))
      setActionBusy(false)
    }
  }

  const actionTitle = pendingAction
    ? `${pendingAction.action === 'delete' ? 'Delete' : pendingAction.action === 'delist' ? 'Delist' : 'Restore'} report`
    : ''

  return (
    <Stack spacing={3}>
      <Box>
        <Typography component="h1" variant="h4" fontWeight={700}>Lost & Found</Typography>
        <Typography color="text.secondary">
          Review operational data, delist or restore reports, and trace every write operation.
        </Typography>
      </Box>

      <Tabs value={tab} onChange={(_, value: TabValue) => changeTab(value)}>
        <Tab label="Reports" value="reports" />
        <Tab label="Claims" value="claims" />
        <Tab label="Audit Logs" value="audit" />
      </Tabs>

      {error && <Alert severity="error">{error}</Alert>}

      {overview && (
        <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: { xs: '1fr', sm: 'repeat(2, 1fr)', lg: 'repeat(6, 1fr)' } }}>
          <MetricCard label="Total reports" value={overview.totalReports} secondary={`${overview.lostReports} lost · ${overview.foundReports} found`} />
          <MetricCard label="Open reports" value={overview.openReports} />
          <MetricCard label="Claimed reports" value={overview.claimedReports} />
          <MetricCard label="Closed reports" value={overview.closedReports} />
          <MetricCard label="Pending claims" value={overview.submittedClaims} />
          <MetricCard label="Hidden reports" value={overview.hiddenReports} />
        </Box>
      )}

      {tab === 'reports' ? (
        <>
          <Paper component="form" onSubmit={submit} sx={{ p: 2 }}>
            <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: { xs: '1fr', sm: 'repeat(2, 1fr)', lg: 'repeat(4, 1fr)' } }}>
              <TextField
                label="Keyword"
                value={form.keyword}
                onChange={(event) => setForm({ ...form, keyword: event.target.value })}
                sx={{ gridColumn: { lg: 'span 2' } }}
              />
              <FormControl>
                <InputLabel>Type</InputLabel>
                <Select label="Type" value={form.reportType} onChange={(event) => setForm({ ...form, reportType: event.target.value })}>
                  <MenuItem value="">All</MenuItem>
                  {reportTypes.map((type) => <MenuItem key={type} value={type}>{reportTypeLabels[type]}</MenuItem>)}
                </Select>
              </FormControl>
              <FormControl>
                <InputLabel>Category</InputLabel>
                <Select label="Category" value={form.category} onChange={(event) => setForm({ ...form, category: event.target.value })}>
                  <MenuItem value="">All</MenuItem>
                  {categories.map((category) => <MenuItem key={category} value={category}>{categoryLabels[category]}</MenuItem>)}
                </Select>
              </FormControl>
              <FormControl>
                <InputLabel>Status</InputLabel>
                <Select label="Status" value={form.status} onChange={(event) => setForm({ ...form, status: event.target.value })}>
                  <MenuItem value="">All</MenuItem>
                  {reportStatuses.map((status) => <MenuItem key={status} value={status}>{reportStatusLabels[status]}</MenuItem>)}
                </Select>
              </FormControl>
              <FormControl>
                <InputLabel>Hidden</InputLabel>
                <Select label="Hidden" value={form.adminHidden} onChange={(event) => setForm({ ...form, adminHidden: event.target.value })}>
                  <MenuItem value="">All</MenuItem>
                  <MenuItem value="true">Hidden</MenuItem>
                  <MenuItem value="false">Visible</MenuItem>
                </Select>
              </FormControl>
              <TextField
                label="Colour"
                value={form.colour}
                onChange={(event) => setForm({ ...form, colour: event.target.value })}
              />
              <TextField
                label="Location"
                value={form.location}
                onChange={(event) => setForm({ ...form, location: event.target.value })}
              />
              <TextField
                label="From date"
                type="date"
                value={form.dateFrom}
                onChange={(event) => setForm({ ...form, dateFrom: event.target.value })}
                slotProps={{ inputLabel: { shrink: true } }}
              />
              <TextField
                label="To date"
                type="date"
                value={form.dateTo}
                onChange={(event) => setForm({ ...form, dateTo: event.target.value })}
                slotProps={{ inputLabel: { shrink: true } }}
              />
            </Box>
            <Stack direction="row" spacing={1} justifyContent="flex-end" sx={{ mt: 2 }}>
              <Button onClick={reset}>Reset</Button>
              <Button type="submit" variant="contained">Search</Button>
            </Stack>
          </Paper>

          {loading ? (
            <Box sx={{ py: 8, display: 'grid', placeItems: 'center' }}><CircularProgress /></Box>
          ) : result?.content.length ? (
            <>
              <TableContainer component={Paper} variant="outlined">
                <Table aria-label="Lost and Found administration reports">
                  <TableHead>
                    <TableRow>
                      <TableCell>Item</TableCell>
                      <TableCell>Type</TableCell>
                      <TableCell>Category</TableCell>
                      <TableCell>Status</TableCell>
                      <TableCell>Location</TableCell>
                      <TableCell>Event date</TableCell>
                      <TableCell>Reporter</TableCell>
                      <TableCell>Created</TableCell>
                      <TableCell align="right">Action</TableCell>
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {result.content.map((report) => (
                      <TableRow key={report.id} hover>
                        <TableCell>
                          <Typography fontWeight={600}>{report.itemName}</Typography>
                          <Stack direction="row" spacing={1}>
                            {report.colour && <Typography variant="caption" color="text.secondary">{report.colour}</Typography>}
                            {report.adminHidden && <Chip size="small" color="warning" label="Hidden" />}
                          </Stack>
                        </TableCell>
                        <TableCell>{reportTypeLabels[report.reportType]}</TableCell>
                        <TableCell>{categoryLabels[report.category]}</TableCell>
                        <TableCell><Chip size="small" label={reportStatusLabels[report.status]} /></TableCell>
                        <TableCell>{report.location}</TableCell>
                        <TableCell>{report.eventDate}</TableCell>
                        <TableCell>{report.createdByEmail}</TableCell>
                        <TableCell>{formatDateTime(report.createdAt)}</TableCell>
                        <TableCell align="right">
                          <Stack direction="row" spacing={1} justifyContent="flex-end">
                            <Button component={RouterLink} to={`/lost-found/${report.id}`} size="small">View report</Button>
                            {report.adminHidden
                              ? <Button size="small" color="success" onClick={() => openAction(report, 'restore')}>Restore</Button>
                              : <Button size="small" color="warning" onClick={() => openAction(report, 'delist')}>Delist</Button>}
                            <Button size="small" color="error" onClick={() => openAction(report, 'delete')}>Delete</Button>
                          </Stack>
                        </TableCell>
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
                  onChange={(_, page) => {
                    const next = new URLSearchParams(searchParams)
                    next.set('page', String(page - 1))
                    setSearchParams(next)
                  }}
                />
              )}
            </>
          ) : !error && (
            <Paper sx={{ p: 6, textAlign: 'center' }}>
              <Typography variant="h6">No reports found</Typography>
              <Typography color="text.secondary">Try clearing one or more filters.</Typography>
            </Paper>
          )}
        </>
      ) : tab === 'claims' ? (
        <AdminClaimsSection />
      ) : (
        <AdminAuditLogsSection />
      )}

      <Dialog open={pendingAction !== null} onClose={closeAction} fullWidth maxWidth="sm">
        <DialogTitle>{actionTitle}</DialogTitle>
        <DialogContent>
          {pendingAction && (
            <Typography color="text.secondary" sx={{ mb: 2 }}>
              {pendingAction.report.itemName} (#{pendingAction.report.id})
            </Typography>
          )}
          <TextField
            label="Reason"
            multiline
            minRows={2}
            fullWidth
            required
            value={actionReason}
            onChange={(event) => setActionReason(event.target.value)}
            helperText={actionError || undefined}
            error={Boolean(actionError)}
          />
        </DialogContent>
        <DialogActions>
          <Button onClick={closeAction}>Cancel</Button>
          <Button
            variant="contained"
            color={pendingAction?.action === 'delete' ? 'error' : 'primary'}
            disabled={!actionReason.trim() || actionBusy}
            onClick={confirmAction}
          >
            {actionBusy ? 'Saving…' : 'Confirm'}
          </Button>
        </DialogActions>
      </Dialog>
    </Stack>
  )
}
