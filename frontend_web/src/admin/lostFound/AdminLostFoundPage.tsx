import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
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
import { Link as RouterLink, useSearchParams } from 'react-router-dom'
import { getAdminLostFoundOverview, searchAdminLostFoundReports } from '../../api/adminLostFound'
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

const categories = Object.keys(categoryLabels) as ItemCategory[]
const reportTypes: ReportType[] = ['LOST', 'FOUND']
const reportStatuses: ReportStatus[] = ['OPEN', 'CLAIMED', 'CLOSED']

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

export function AdminLostFoundPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const [overview, setOverview] = useState<AdminLostFoundOverview | null>(null)
  const [result, setResult] = useState<PageResponse<AdminLostFoundReport> | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [form, setForm] = useState(() => ({
    keyword: searchParams.get('keyword') ?? '',
    reportType: searchParams.get('reportType') ?? '',
    category: searchParams.get('category') ?? '',
    status: searchParams.get('status') ?? '',
    colour: searchParams.get('colour') ?? '',
    location: searchParams.get('location') ?? '',
    dateFrom: searchParams.get('dateFrom') ?? '',
    dateTo: searchParams.get('dateTo') ?? '',
  }))
  const queryKey = searchParams.toString()

  useEffect(() => {
    let active = true
    setLoading(true)
    setError('')
    const params = Object.fromEntries(new URLSearchParams(queryKey).entries())
    setForm({
      keyword: params.keyword ?? '',
      reportType: params.reportType ?? '',
      category: params.category ?? '',
      status: params.status ?? '',
      colour: params.colour ?? '',
      location: params.location ?? '',
      dateFrom: params.dateFrom ?? '',
      dateTo: params.dateTo ?? '',
    })

    Promise.all([
      getAdminLostFoundOverview(),
      searchAdminLostFoundReports({
        keyword: params.keyword,
        reportType: params.reportType,
        category: params.category,
        status: params.status,
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
  }, [queryKey])

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
    })
    setSearchParams({})
  }

  return (
    <Stack spacing={3}>
      <Box>
        <Typography component="h1" variant="h4" fontWeight={700}>Lost & Found</Typography>
        <Typography color="text.secondary">
          Review operational data across all Lost & Found reports. This administration view is read-only.
        </Typography>
      </Box>

      {error && <Alert severity="error">{error}</Alert>}

      {overview && (
        <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: { xs: '1fr', sm: 'repeat(2, 1fr)', lg: 'repeat(5, 1fr)' } }}>
          <MetricCard label="Total reports" value={overview.totalReports} secondary={`${overview.lostReports} lost · ${overview.foundReports} found`} />
          <MetricCard label="Open reports" value={overview.openReports} />
          <MetricCard label="Claimed reports" value={overview.claimedReports} />
          <MetricCard label="Closed reports" value={overview.closedReports} />
          <MetricCard label="Pending claims" value={overview.submittedClaims} />
        </Box>
      )}

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
                      {report.colour && <Typography variant="caption" color="text.secondary">{report.colour}</Typography>}
                    </TableCell>
                    <TableCell>{reportTypeLabels[report.reportType]}</TableCell>
                    <TableCell>{categoryLabels[report.category]}</TableCell>
                    <TableCell><Chip size="small" label={reportStatusLabels[report.status]} /></TableCell>
                    <TableCell>{report.location}</TableCell>
                    <TableCell>{report.eventDate}</TableCell>
                    <TableCell>{report.createdByEmail}</TableCell>
                    <TableCell>{formatDateTime(report.createdAt)}</TableCell>
                    <TableCell align="right">
                      <Button component={RouterLink} to={`/lost-found/${report.id}`} size="small">View report</Button>
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
    </Stack>
  )
}
