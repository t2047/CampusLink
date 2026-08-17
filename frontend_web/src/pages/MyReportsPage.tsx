import ArrowBackIcon from '@mui/icons-material/ArrowBack'
import AddIcon from '@mui/icons-material/Add'
import ArticleIcon from '@mui/icons-material/Article'
import { Alert, Box, Button, Chip, CircularProgress, Grid, Pagination, Paper, Stack, Typography } from '@mui/material'
import { useCallback, useEffect, useState } from 'react'
import { Link as RouterLink, useSearchParams } from 'react-router-dom'
import { apiErrorMessage } from '../api/client'
import { searchReports } from '../api/lostFound'
import { ReportCard } from '../components/ReportCard'
import { reportTypeLabels } from '../labels'
import type { LostFoundReport, PageResponse, ReportStatus, ReportType } from '../types'

type StatusFilter = 'ALL' | ReportStatus

const statusOptions: StatusFilter[] = ['ALL', 'OPEN', 'CLAIMED', 'CLOSED']

/** 我的失物/拾物列表（个人中心需求 §10.1）：owner=me + reportType，复用 ReportCard 与 searchReports。 */
export function MyReportsPage({ reportType }: { reportType: ReportType }) {
  const [searchParams] = useSearchParams()
  const showBack = searchParams.get('from') === 'profile'
  const [status, setStatus] = useState<StatusFilter>('ALL')
  const [page, setPage] = useState(0)
  const [result, setResult] = useState<PageResponse<LostFoundReport> | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')

  const load = useCallback(async () => {
    setLoading(true)
    setError('')
    try {
      const data = await searchReports({
        owner: 'me',
        reportType,
        status: status === 'ALL' ? undefined : status,
        page,
        size: 20,
        sort: 'createdAt,desc',
      })
      setResult(data)
    } catch (requestError) {
      setError(apiErrorMessage(requestError))
    } finally {
      setLoading(false)
    }
  }, [reportType, status, page])

  useEffect(() => { void load() }, [load])

  const title = `My ${reportTypeLabels[reportType]} Items`
  const emptyHint = reportType === 'LOST'
    ? "You haven't posted any lost-item reports yet. Reports you post will appear here."
    : "You haven't posted any found-item reports yet. Reports you post will appear here."
  const createPath = reportType === 'LOST' ? '/lost-found/new/lost' : '/lost-found/new/found'

  return (
    <Stack spacing={3}>
      {showBack && <Button component={RouterLink} to="/lost-found/profile" startIcon={<ArrowBackIcon />} sx={{ textTransform: 'none', alignSelf: 'flex-start' }}>Back to personal center</Button>}
      <Stack direction={{ xs: 'column', sm: 'row' }} justifyContent="space-between" alignItems={{ sm: 'center' }} gap={2}>
        <Stack direction="row" spacing={2} alignItems="center">
          <Box sx={{ width: 46, height: 46, borderRadius: 3, display: 'grid', placeItems: 'center', flexShrink: 0, color: '#fff', background: 'linear-gradient(135deg, #6366f1 0%, #4f46e5 100%)', boxShadow: '0 4px 12px rgba(79, 70, 229, 0.3)' }}>
            <ArticleIcon />
          </Box>
          <Box>
            <Typography variant="h4" fontWeight={700}>{title}</Typography>
            <Typography color="text.secondary">Reports you published are listed here, including any removed by an administrator.</Typography>
          </Box>
        </Stack>
        <Button component={RouterLink} to={createPath} variant="contained" startIcon={<AddIcon />}>
          Report {reportTypeLabels[reportType].toLowerCase()}
        </Button>
      </Stack>

      <Stack direction="row" spacing={1} alignItems="center">
        {statusOptions.map((option) => (
          <Chip
            key={option}
            label={option === 'ALL' ? 'All statuses' : option}
            color={status === option ? 'primary' : 'default'}
            onClick={() => { setStatus(option); setPage(0) }}
          />
        ))}
      </Stack>

      {error && <Alert severity="error">{error}</Alert>}
      {loading ? (
        <Box sx={{ py: 8, textAlign: 'center' }}><CircularProgress /></Box>
      ) : result && result.content.length > 0 ? (
        <>
          <Grid container spacing={3}>
            {result.content.map((report) => (
              <Grid key={report.id} size={{ xs: 12, sm: 6, md: 4 }}>
                <ReportCard report={report} showAdminHidden />
              </Grid>
            ))}
          </Grid>
          {result.totalPages > 1 && (
            <Pagination
              sx={{ alignSelf: 'center' }}
              page={page + 1}
              count={result.totalPages}
              onChange={(_, value) => setPage(value - 1)}
            />
          )}
        </>
      ) : (
        <Paper sx={{ p: 6, textAlign: 'center' }}>
          <Typography variant="h6">No reports here yet</Typography>
          <Typography color="text.secondary" sx={{ mb: 2 }}>{emptyHint}</Typography>
          <Button component={RouterLink} to={createPath} variant="contained" startIcon={<AddIcon />}>
            Report {reportTypeLabels[reportType].toLowerCase()}
          </Button>
        </Paper>
      )}
    </Stack>
  )
}
