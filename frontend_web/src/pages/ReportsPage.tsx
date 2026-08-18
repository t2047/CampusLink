import CloseIcon from '@mui/icons-material/Close'
import AddIcon from '@mui/icons-material/Add'
import ImageSearchIcon from '@mui/icons-material/ImageSearch'
import SearchIcon from '@mui/icons-material/Search'
import { Alert, Box, Button, CircularProgress, FormControl, Grid, IconButton, InputLabel, MenuItem, Pagination, Paper, Select, Stack, TextField, Typography } from '@mui/material'
import { FormEvent, useEffect, useState } from 'react'
import { Link as RouterLink, useSearchParams } from 'react-router-dom'
import { apiErrorMessage } from '../api/client'
import { searchByImage, searchReports, type AgentImageSearchResponse, type AgentImageSearchStatus } from '../api/lostFound'
import { uploadAgentImage, type AgentMatchResult, type StagedAgentImage } from '../api/lostFoundAgent'
import { ReportCard } from '../components/ReportCard'
import { LostFoundAgentPanel } from '../components/LostFoundAgentPanel'
import { categoryLabels } from '../labels'
import type { ItemCategory, PageResponse, LostFoundReport, ReportStatus, ReportType } from '../types'

const categories = Object.keys(categoryLabels) as ItemCategory[]
const emptyFilters = { keyword: '', category: '', colour: '', location: '', dateFrom: '', dateTo: '' }
const allowedTypes = ['image/jpeg', 'image/png', 'image/webp']
const maxFileSize = 10 * 1024 * 1024

/** Agent 候选 → ReportCard 兼容对象：item_id 即报告 id，image_urls 即报告图片 URL。 */
function toReportCard(report: AgentMatchResult): LostFoundReport {
  return {
    id: Number(report.item_id),
    reportType: report.report_type,
    itemName: report.item_name,
    category: report.category as ItemCategory,
    description: report.description,
    colour: report.colour ?? null,
    location: report.location,
    eventDate: report.event_date,
    timeDescription: report.time_description ?? null,
    status: report.status as ReportStatus,
    images: report.image_urls.map((url) => ({ id: 0, url, contentType: '', fileSize: 0, sortOrder: 0 })),
    createdByMe: false,
    adminHidden: false,
    createdAt: report.event_date,
    updatedAt: report.event_date,
  }
}

export function ReportsPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const [result, setResult] = useState<PageResponse<LostFoundReport> | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [refreshKey, setRefreshKey] = useState(0)
  const [form, setForm] = useState(() => ({
    keyword: searchParams.get('keyword') ?? '',
    category: searchParams.get('category') ?? '',
    colour: searchParams.get('colour') ?? '',
    location: searchParams.get('location') ?? '',
    dateFrom: searchParams.get('dateFrom') ?? '',
    dateTo: searchParams.get('dateTo') ?? '',
  }))
  const [stagedImage, setStagedImage] = useState<StagedAgentImage | null>(null)
  const [imageUploading, setImageUploading] = useState(false)
  const [imageSearching, setImageSearching] = useState(false)
  const [imageSearch, setImageSearch] = useState<{ status: AgentImageSearchStatus; matches: AgentMatchResult[]; message?: string | null } | null>(null)
  const [imageError, setImageError] = useState('')
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
  }, [queryKey, refreshKey])

  async function selectSearchImage(files: FileList | null) {
    const file = files?.[0]
    if (!file) return
    if (!allowedTypes.includes(file.type)) {
      setImageError(`${file.name} must be a JPEG, PNG or WebP image.`)
      return
    }
    if (file.size > maxFileSize) {
      setImageError(`${file.name} must be no larger than 10 MB.`)
      return
    }
    setImageUploading(true)
    setImageError('')
    try {
      const staged = await uploadAgentImage(file)
      setStagedImage(staged)
      setImageSearch(null)
    } catch (requestError) {
      setImageError(apiErrorMessage(requestError))
    } finally {
      setImageUploading(false)
    }
  }

  function clearSearchImage() {
    setStagedImage(null)
    setImageSearch(null)
    setImageError('')
  }

  async function runImageSearch() {
    if (!stagedImage) return
    setError('')
    setImageError('')
    setImageSearching(true)
    try {
      const data: AgentImageSearchResponse = await searchByImage({
        reportType: (searchParams.get('reportType') ?? 'FOUND') as ReportType,
        keyword: form.keyword.trim() || undefined,
        category: form.category || undefined,
        colour: form.colour.trim() || undefined,
        location: form.location.trim() || undefined,
        dateFrom: form.dateFrom || undefined,
        dateTo: form.dateTo || undefined,
        images: [{
          objectKey: stagedImage.objectKey,
          visualFingerprint: stagedImage.visualFingerprint,
          url: stagedImage.url,
        }],
      })
      setImageSearch({ status: data.status, matches: data.match_results, message: data.message })
    } catch (requestError) {
      setImageSearch(null)
      setImageError(apiErrorMessage(requestError))
    } finally {
      setImageSearching(false)
    }
  }

  function submit(event: FormEvent) {
    event.preventDefault()
    if (stagedImage) {
      runImageSearch()
      return
    }
    const next = new URLSearchParams()
    next.set('reportType', searchParams.get('reportType') ?? 'FOUND')
    next.set('status', searchParams.get('status') ?? 'OPEN')
    Object.entries(form).forEach(([key, value]) => { if (value.trim()) next.set(key, value.trim()) })
    setSearchParams(next)
  }

  function reset() {
    setForm({ keyword: '', category: '', colour: '', location: '', dateFrom: '', dateTo: '' })
    clearSearchImage()
    setSearchParams({ reportType: 'FOUND', status: 'OPEN' })
  }

  function setView(reportType: 'FOUND' | 'LOST') {
    const next = new URLSearchParams(searchParams)
    next.set('reportType', reportType)
    next.set('status', 'OPEN')
    next.delete('page')
    clearSearchImage()
    setSearchParams(next)
  }

  function handleAgentReportCreated() {
    setForm(emptyFilters)
    clearSearchImage()
    setSearchParams({ reportType: 'LOST', status: 'OPEN' })
    setRefreshKey((value) => value + 1)
  }

  return (
    <Stack spacing={3}>
      <Stack direction="row" spacing={2} alignItems="center">
        <Box sx={{ width: 46, height: 46, borderRadius: 3, display: 'grid', placeItems: 'center', flexShrink: 0, color: '#fff', background: 'linear-gradient(135deg, #0891b2 0%, #0e7490 100%)', boxShadow: '0 4px 12px rgba(14, 116, 144, 0.3)' }}>
          <SearchIcon />
        </Box>
        <Box>
          <Typography variant="h4" fontWeight={700}>Lost & Found</Typography>
          <Typography color="text.secondary">Search current reports or help the community by posting one.</Typography>
        </Box>
      </Stack>

      <LostFoundAgentPanel onReportCreated={handleAgentReportCreated} />

      <Paper component="form" onSubmit={submit} sx={{ p: 2 }}>
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1} justifyContent="space-between" alignItems={{ sm: 'center' }} sx={{ mb: 2 }}>
          <Stack direction="row" spacing={1}>
            <Button variant={(searchParams.get('reportType') ?? 'FOUND') === 'FOUND' ? 'contained' : 'outlined'} onClick={() => setView('FOUND')}>Found items</Button>
            <Button variant={searchParams.get('reportType') === 'LOST' ? 'contained' : 'outlined'} onClick={() => setView('LOST')}>Lost items</Button>
          </Stack>
          <Stack direction="row" spacing={1}>
            <Button component={RouterLink} to="/lost-found/new/lost" variant="outlined" startIcon={<AddIcon />}>Report lost</Button>
            <Button component={RouterLink} to="/lost-found/new/found" variant="contained" startIcon={<AddIcon />}>Report found</Button>
          </Stack>
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
          <Grid size={{ xs: 12, sm: 6, md: 3 }} sx={{ display: 'flex', alignItems: 'center' }}>
            <Stack direction="row" spacing={1} alignItems="center">
              <Button component="label" variant="outlined" size="large" startIcon={<ImageSearchIcon />} sx={{ minHeight: 56 }}>
                Search by image
                <input hidden type="file" accept="image/jpeg,image/png,image/webp" aria-label="Search by image" onChange={(e) => { selectSearchImage(e.target.files); e.target.value = '' }} />
              </Button>
              {imageUploading && <CircularProgress size={20} />}
              {stagedImage && (
                <>
                  <Box component="img" src={stagedImage.url} alt={stagedImage.originalName} sx={{ width: 48, height: 48, objectFit: 'cover', borderRadius: 1 }} />
                  <IconButton size="small" onClick={clearSearchImage} aria-label={`Remove ${stagedImage.originalName}`}><CloseIcon /></IconButton>
                </>
              )}
            </Stack>
          </Grid>
          <Grid size={{ xs: 12, sm: 6, md: 3 }} sx={{ display: 'flex', alignItems: 'center', justifyContent: 'flex-end' }}><Stack direction="row" spacing={1}><Button onClick={reset} size="large" sx={{ minHeight: 56 }}>Reset</Button><Button type="submit" variant="contained" size="large" sx={{ minHeight: 56 }} disabled={imageUploading}>Search</Button></Stack></Grid>
        </Grid>
      </Paper>

      {(error || imageError) && <Alert severity="error">{error || imageError}</Alert>}
      {loading || imageSearching ? <Box sx={{ py: 8, textAlign: 'center' }}><CircularProgress /></Box> : imageSearch ? (
        imageSearch.status === 'failed' ? (
          <Alert severity="error">{imageSearch.message ?? 'Image search failed.'}</Alert>
        ) : imageSearch.matches.length ? (
          <Grid container spacing={3}>{imageSearch.matches.map((match) => <Grid key={match.item_id} size={{ xs: 12, sm: 6, md: 4 }}><ReportCard report={toReportCard(match)} /></Grid>)}</Grid>
        ) : (
          <Paper sx={{ p: 6, textAlign: 'center' }}><Typography variant="h6">无匹配</Typography><Typography color="text.secondary">没有找到与所选图片视觉相似的记录，试试更换图片或放宽筛选条件。</Typography></Paper>
        )
      ) : result?.content.length ? (
        <><Grid container spacing={3}>{result.content.map((report) => <Grid key={report.id} size={{ xs: 12, sm: 6, md: 4 }}><ReportCard report={report} /></Grid>)}</Grid>
          {result.totalPages > 1 && <Pagination sx={{ alignSelf: 'center' }} page={result.page + 1} count={result.totalPages} onChange={(_, page) => { const next = new URLSearchParams(searchParams); next.set('page', String(page - 1)); setSearchParams(next) }} />}
        </>
      ) : !error && !imageError && <Paper sx={{ p: 6, textAlign: 'center' }}><Typography variant="h6">No matching reports</Typography><Typography color="text.secondary">Try clearing one or more filters.</Typography></Paper>}
    </Stack>
  )
}
