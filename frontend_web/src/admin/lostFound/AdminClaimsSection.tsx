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
import { FormEvent, useEffect, useMemo, useState } from 'react'
import { Link as RouterLink, useSearchParams } from 'react-router-dom'
import { searchAdminClaims } from '../../api/adminLostFound'
import { apiErrorMessage } from '../../api/client'
import { categoryLabels, claimStatusLabels } from '../../labels'
import type { AdminClaimSummary, PageResponse } from '../../types'
import {
  adminClaimStatuses,
  buildAdminClaimDetailHref,
  buildAdminClaimsListSearchParams,
  parseAdminClaimRouteState,
} from './adminClaimRouteState'
import type { AdminClaimStatusFilter } from './adminClaimRouteState'

interface ClaimFilters {
  status: AdminClaimStatusFilter
  keyword: string
}

const claimStatusOptions: Array<{ value: AdminClaimStatusFilter; label: string }> = [
  ...adminClaimStatuses.map((status) => ({ value: status, label: claimStatusLabels[status] })),
  { value: 'ALL', label: 'All' },
]

function formatDateTime(value: string) {
  return new Intl.DateTimeFormat('en-SG', {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(new Date(value))
}

export function AdminClaimsSection() {
  const [searchParams, setSearchParams] = useSearchParams()
  const { status, keyword, page } = parseAdminClaimRouteState(searchParams)
  const normalizedQuery = useMemo(
    () => buildAdminClaimsListSearchParams({ status, keyword, page }).toString(),
    [keyword, page, status],
  )
  const [filters, setFilters] = useState<ClaimFilters>({ status, keyword })
  const [result, setResult] = useState<PageResponse<AdminClaimSummary> | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [retryCounter, setRetryCounter] = useState(0)

  useEffect(() => {
    if (searchParams.toString() !== normalizedQuery) {
      setSearchParams(new URLSearchParams(normalizedQuery), { replace: true })
    }
  }, [normalizedQuery, searchParams, setSearchParams])

  useEffect(() => {
    setFilters({ status, keyword })
  }, [keyword, status])

  useEffect(() => {
    let active = true
    setLoading(true)
    setError('')
    searchAdminClaims({
      ...(status === 'ALL' ? {} : { status }),
      ...(keyword ? { keyword } : {}),
      page,
      size: 25,
      sort: 'createdAt,desc',
    })
      .then((data) => {
        if (active) setResult(data)
      })
      .catch((requestError) => {
        if (active) setError(apiErrorMessage(requestError))
      })
      .finally(() => {
        if (active) setLoading(false)
      })
    return () => { active = false }
  }, [keyword, page, retryCounter, status])

  function submit(event: FormEvent) {
    event.preventDefault()
    setSearchParams(buildAdminClaimsListSearchParams({
      status: filters.status,
      keyword: filters.keyword,
      page: 0,
    }))
  }

  function reset() {
    setFilters({ status: 'SUBMITTED', keyword: '' })
    setSearchParams(buildAdminClaimsListSearchParams({ status: 'SUBMITTED', keyword: '', page: 0 }))
  }

  function changePage(nextPage: number) {
    setSearchParams(buildAdminClaimsListSearchParams({ status, keyword, page: nextPage }))
  }

  const isDefaultFilter = status === 'SUBMITTED' && !keyword && page === 0

  return (
    <Stack spacing={2}>
      <Paper component="form" onSubmit={submit} sx={{ p: 2 }}>
        <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: { xs: '1fr', sm: 'repeat(2, 1fr)' } }}>
          <FormControl>
            <InputLabel id="admin-claims-status-label">Status</InputLabel>
            <Select
              id="admin-claims-status"
              labelId="admin-claims-status-label"
              label="Status"
              value={filters.status}
              onChange={(event) => setFilters({ ...filters, status: event.target.value as AdminClaimStatusFilter })}
            >
              {claimStatusOptions.map((option) => (
                <MenuItem key={option.value} value={option.value}>{option.label}</MenuItem>
              ))}
            </Select>
          </FormControl>
          <TextField
            label="Keyword"
            value={filters.keyword}
            onChange={(event) => setFilters({ ...filters, keyword: event.target.value })}
          />
        </Box>
        <Stack direction="row" spacing={1} justifyContent="flex-end" sx={{ mt: 2 }}>
          <Button onClick={reset}>Reset</Button>
          <Button type="submit" variant="contained">Search</Button>
        </Stack>
      </Paper>

      {error && (
        <Alert
          severity="error"
          action={<Button color="inherit" size="small" onClick={() => setRetryCounter((counter) => counter + 1)}>Retry</Button>}
        >
          {error}
        </Alert>
      )}

      {loading ? (
        <Box sx={{ py: 8, display: 'grid', placeItems: 'center' }}><CircularProgress /></Box>
      ) : error ? null : result?.content.length ? (
        <>
          <TableContainer component={Paper} variant="outlined">
            <Table aria-label="Lost and Found administration claims">
              <TableHead>
                <TableRow>
                  <TableCell>Claim ID</TableCell>
                  <TableCell>Status</TableCell>
                  <TableCell>Item</TableCell>
                  <TableCell>Claimant</TableCell>
                  <TableCell>Report Owner</TableCell>
                  <TableCell>Location</TableCell>
                  <TableCell>Submitted At</TableCell>
                  <TableCell align="right">Actions</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {result.content.map((claim) => (
                  <TableRow key={claim.id} hover>
                    <TableCell>#{claim.id}</TableCell>
                    <TableCell><Chip size="small" label={claimStatusLabels[claim.status]} /></TableCell>
                    <TableCell>
                      <Typography fontWeight={600}>{claim.report.itemName}</Typography>
                      <Typography variant="caption" color="text.secondary">
                        {categoryLabels[claim.report.category]}
                      </Typography>
                    </TableCell>
                    <TableCell>{claim.claimant.email}</TableCell>
                    <TableCell>{claim.report.owner.email}</TableCell>
                    <TableCell>{claim.report.location}</TableCell>
                    <TableCell>{formatDateTime(claim.createdAt)}</TableCell>
                    <TableCell align="right">
                      <Button
                        component={RouterLink}
                        to={buildAdminClaimDetailHref(claim.id, { status, keyword, page })}
                        size="small"
                      >
                        View
                      </Button>
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
              onChange={(_, nextPage) => changePage(nextPage - 1)}
            />
          )}
        </>
      ) : (
        <Paper sx={{ p: 6, textAlign: 'center' }}>
          <Typography variant="h6">
            {isDefaultFilter
              ? 'No submitted claims require review.'
              : 'No claims match the current filters.'}
          </Typography>
        </Paper>
      )}
    </Stack>
  )
}
