import AddIcon from '@mui/icons-material/Add'
import { Alert, Box, Button, CircularProgress, FormControl, Grid, InputLabel, MenuItem, Pagination, Paper, Select, Stack, TextField, Typography } from '@mui/material'
import { FormEvent, useEffect, useState } from 'react'
import { Link as RouterLink, useSearchParams } from 'react-router-dom'
import { apiErrorMessage } from '../api/client'
import { searchReports } from '../api/lostFound'
import { ReportCard } from '../components/ReportCard'
import { categoryLabels } from '../labels'
import type { ItemCategory, PageResponse, LostFoundReport } from '../types'

const categories = Object.keys(categoryLabels) as ItemCategory[]

export function ReportsPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const [result, setResult] = useState<PageResponse<LostFoundReport> | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [form, setForm] = useState(() => ({
    keyword: searchParams.get('keyword') ?? '',
    category: searchParams.get('category') ?? '',
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
      category: params.category ?? '',
      colour: params.colour ?? '',
      location: params.location ?? '',
      dateFrom: params.dateFrom ?? '',
      dateTo: params.dateTo ?? '',
    })
    searchReports({
      reportType: params.reportType ?? 'FOUND',
      status: params.status ?? 'OPEN',
      keyword: params.keyword,
      category: params.category,
      colour: params.colour,
      location: params.location,
      dateFrom: params.dateFrom,
      dateTo: params.dateTo,
      page: params.page ?? 0,
      size: 12,
      sort: 'createdAt,desc',
    })
      .then((data) => { if (active) setResult(data) })
      .catch((requestError) => { if (active) setError(apiErrorMessage(requestError)) })
      .finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [queryKey])

  function submit(event: FormEvent) {
    event.preventDefault()
    const next = new URLSearchParams()
    next.set('reportType', searchParams.get('reportType') ?? 'FOUND')
    next.set('status', searchParams.get('status') ?? 'OPEN')
    Object.entries(form).forEach(([key, value]) => { if (value.trim()) next.set(key, value.trim()) })
    setSearchParams(next)
  }

  function reset() {
    setForm({ keyword: '', category: '', colour: '', location: '', dateFrom: '', dateTo: '' })
    setSearchParams({ reportType: 'FOUND', status: 'OPEN' })
  }

  function setView(reportType: 'FOUND' | 'LOST') {
    const next = new URLSearchParams(searchParams)
    next.set('reportType', reportType)
    next.set('status', 'OPEN')
    next.delete('page')
    setSearchParams(next)
  }

  return (
    <Stack spacing={3}>
      <Stack direction={{ xs: 'column', sm: 'row' }} justifyContent="space-between" alignItems={{ sm: 'center' }} gap={2}>
        <Box><Typography variant="h4" fontWeight={700}>Lost & Found</Typography><Typography color="text.secondary">Search current reports or help the community by posting one.</Typography></Box>
        <Stack direction="row" spacing={1}>
          <Button component={RouterLink} to="/lost-found/new/lost" variant="outlined" startIcon={<AddIcon />}>Report lost</Button>
          <Button component={RouterLink} to="/lost-found/new/found" variant="contained" startIcon={<AddIcon />}>Report found</Button>
        </Stack>
      </Stack>

      <Paper component="form" onSubmit={submit} sx={{ p: 2 }}>
        <Stack direction="row" spacing={1} sx={{ mb: 2 }}>
          <Button variant={(searchParams.get('reportType') ?? 'FOUND') === 'FOUND' ? 'contained' : 'outlined'} onClick={() => setView('FOUND')}>Found items</Button>
          <Button variant={searchParams.get('reportType') === 'LOST' ? 'contained' : 'outlined'} onClick={() => setView('LOST')}>Lost items</Button>
        </Stack>
        <Grid container spacing={2}>
          <Grid size={{ xs: 12, md: 4 }}><TextField fullWidth label="Keyword" value={form.keyword} onChange={(e) => setForm({ ...form, keyword: e.target.value })} /></Grid>
          <Grid size={{ xs: 12, md: 2 }}>
            <FormControl fullWidth><InputLabel>Category</InputLabel><Select label="Category" value={form.category} onChange={(e) => setForm({ ...form, category: e.target.value })}>
              <MenuItem value="">All</MenuItem>{categories.map((category) => <MenuItem key={category} value={category}>{categoryLabels[category]}</MenuItem>)}
            </Select></FormControl>
          </Grid>
          <Grid size={{ xs: 12, md: 3 }}><TextField fullWidth label="Colour" value={form.colour} onChange={(e) => setForm({ ...form, colour: e.target.value })} /></Grid>
          <Grid size={{ xs: 12, md: 3 }}><TextField fullWidth label="Location" value={form.location} onChange={(e) => setForm({ ...form, location: e.target.value })} /></Grid>
          <Grid size={{ xs: 12, sm: 6, md: 3 }}><TextField fullWidth type="date" label="From date" slotProps={{ inputLabel: { shrink: true } }} value={form.dateFrom} onChange={(e) => setForm({ ...form, dateFrom: e.target.value })} /></Grid>
          <Grid size={{ xs: 12, sm: 6, md: 3 }}><TextField fullWidth type="date" label="To date" slotProps={{ inputLabel: { shrink: true } }} value={form.dateTo} onChange={(e) => setForm({ ...form, dateTo: e.target.value })} /></Grid>
          <Grid size={{ xs: 12, md: 6 }}><Stack direction="row" spacing={1} justifyContent="flex-end"><Button onClick={reset}>Reset</Button><Button type="submit" variant="contained">Search</Button></Stack></Grid>
        </Grid>
      </Paper>

      {error && <Alert severity="error">{error}</Alert>}
      {loading ? <Box sx={{ py: 8, textAlign: 'center' }}><CircularProgress /></Box> : result?.content.length ? (
        <><Grid container spacing={3}>{result.content.map((report) => <Grid key={report.id} size={{ xs: 12, sm: 6, md: 4 }}><ReportCard report={report} /></Grid>)}</Grid>
          {result.totalPages > 1 && <Pagination sx={{ alignSelf: 'center' }} page={result.page + 1} count={result.totalPages} onChange={(_, page) => { const next = new URLSearchParams(searchParams); next.set('page', String(page - 1)); setSearchParams(next) }} />}
        </>
      ) : !error && <Paper sx={{ p: 6, textAlign: 'center' }}><Typography variant="h6">No matching reports</Typography><Typography color="text.secondary">Try clearing one or more filters.</Typography></Paper>}
    </Stack>
  )
}
