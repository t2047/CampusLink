import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  Chip,
  CircularProgress,
  Divider,
  MenuItem,
  Select,
  Stack,
  Tab,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TablePagination,
  TableRow,
  Tabs,
  TextField,
  Typography,
} from '@mui/material'
import { Link as RouterLink, useLocation, useParams } from 'react-router-dom'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { apiErrorMessage } from '../../api/client'
import {
  getAdminFacilitiesOverview,
  getAdminFacilityMaintenance,
  searchAdminFacilityBookings,
  searchAdminFacilityMaintenance,
} from '../../api/adminFacilities'
import { facilitiesApi } from '../../api/facilities'
import type {
  AdminFacilitiesOverview,
  AdminFacilityBooking,
  AdminFacilityBookingStatus,
  AdminFacilityMaintenance,
  AdminFacilityMaintenancePriority,
  AdminFacilityMaintenanceStatus,
  PageResponse,
} from '../../types'
import { formatFacilityDateTime } from '../../pages/facilities/bookingDateTime'
import {
  buildFacilityUsageRanking,
  buildReservationTrend,
  countTodayReservations,
  formatAnalyticsDateLabel,
  getSingaporeAnalyticsWindow,
  type SingaporeAnalyticsWindow,
} from './facilitiesAnalytics'

const statusLabel = (value: string) => value.replaceAll('_', ' ')
const statusColor = (status: string) => ['AVAILABLE', 'CONFIRMED', 'COMPLETED', 'RESOLVED'].includes(status) ? 'success' : ['OUT_OF_SERVICE', 'IN_PROGRESS'].includes(status) ? 'warning' : 'default'

function FacilitiesNav() {
  const path = useLocation().pathname
  const value = path.includes('reservations') ? 'reservations' : path.includes('maintenance') ? 'maintenance' : 'dashboard'
  return <Tabs value={value} sx={{ mb: 3 }} aria-label="Facilities sections">
    <Tab label="Dashboard" value="dashboard" component={RouterLink} to="/admin/facilities" />
    <Tab label="Reservations" value="reservations" component={RouterLink} to="/admin/facilities/reservations" />
    <Tab label="Maintenance" value="maintenance" component={RouterLink} to="/admin/facilities/maintenance" />
  </Tabs>
}

function Loading() { return <Box sx={{ display: 'grid', placeItems: 'center', minHeight: 240 }}><CircularProgress /></Box> }
function ErrorState({ message, retry }: { message: string; retry: () => void }) { return <Alert severity="error" action={<Button color="inherit" size="small" onClick={retry}>Retry</Button>}>{message}</Alert> }
function Metric({ label, value, detail }: { label: string; value: number; detail: string }) { return <Card variant="outlined" role="group" aria-label={label}><CardContent><Typography color="text.secondary" variant="body2">{label}</Typography><Typography variant="h4" fontWeight={700} sx={{ my: 1 }}>{value}</Typography><Typography variant="body2" color="text.secondary">{detail}</Typography></CardContent></Card> }
function nextStatuses(status: string) {
  if (status === 'SUBMITTED') return ['SUBMITTED', 'IN_PROGRESS', 'CANCELLED']
  if (status === 'IN_PROGRESS') return ['IN_PROGRESS', 'RESOLVED', 'CANCELLED']
  return [status]
}

interface FacilitiesAnalyticsData {
  overview: AdminFacilitiesOverview
  bookings: AdminFacilityBooking[]
  window: SingaporeAnalyticsWindow
}

const MAX_ANALYTICS_PAGES = 10_000

function validateAnalyticsPage(
  response: PageResponse<AdminFacilityBooking>,
  requestedPage: number,
  expectedTotalPages?: number,
) {
  if (response.page !== requestedPage || !Number.isInteger(response.totalPages)
    || response.totalPages < 0 || response.totalPages > MAX_ANALYTICS_PAGES) {
    throw new Error('Invalid Facilities booking pagination contract.')
  }
  if (expectedTotalPages !== undefined && response.totalPages !== expectedTotalPages) {
    throw new Error('Facilities booking pagination changed while loading analytics.')
  }
  if (response.totalPages === 0 && response.content.length > 0) {
    throw new Error('Invalid Facilities booking pagination contract.')
  }
}

async function loadAllAnalyticsBookings(window: SingaporeAnalyticsWindow) {
  const fetchPage = (page: number) => searchAdminFacilityBookings({
    startFrom: window.startFrom,
    startTo: window.startTo,
    page,
    size: 100,
    sort: 'startDateTime,asc',
  })
  const firstPage = await fetchPage(0)
  validateAnalyticsPage(firstPage, 0)
  if (firstPage.totalPages === 0) return []

  const bookings = [...firstPage.content]
  for (let page = 1; page < firstPage.totalPages; page += 1) {
    const response = await fetchPage(page)
    validateAnalyticsPage(response, page, firstPage.totalPages)
    bookings.push(...response.content)
  }
  return bookings
}

const SPACE_STATUS_LABELS: Record<string, string> = {
  AVAILABLE: 'Available',
  OUT_OF_SERVICE: 'Out of Service',
  INACTIVE: 'Inactive',
}

export function FacilitiesDashboardPage() {
  const [data, setData] = useState<FacilitiesAnalyticsData | null>(null)
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(true)
  const mountedRef = useRef(false)
  const requestInFlightRef = useRef(false)

  const loadAnalytics = useCallback(async () => {
    if (requestInFlightRef.current) return
    requestInFlightRef.current = true
    if (mountedRef.current) {
      setLoading(true)
      setError('')
      setData(null)
    }

    try {
      const window = getSingaporeAnalyticsWindow()
      const [overview, bookings] = await Promise.all([
        getAdminFacilitiesOverview(),
        loadAllAnalyticsBookings(window),
      ])
      if (mountedRef.current) {
        setData({ overview, bookings, window })
        setError('')
      }
    } catch (requestError) {
      if (mountedRef.current) {
        setData(null)
        setError(apiErrorMessage(requestError))
      }
    } finally {
      requestInFlightRef.current = false
      if (mountedRef.current) setLoading(false)
    }
  }, [])

  useEffect(() => {
    mountedRef.current = true
    void loadAnalytics()
    return () => {
      mountedRef.current = false
    }
  }, [loadAnalytics])

  const analytics = useMemo(() => {
    if (!data) return null
    const trend = buildReservationTrend(data.bookings, data.window.trendDates)
    const usage = buildFacilityUsageRanking(data.bookings)
    return {
      trend,
      usage,
      todayReservations: countTodayReservations(data.bookings, data.window.today),
      maximumTrendCount: Math.max(0, ...trend.map(({ count }) => count)),
      maximumUsageCount: Math.max(0, ...usage.map(({ reservationCount }) => reservationCount)),
    }
  }, [data])

  return (
    <Box sx={{ display: 'grid', gap: 3 }}>
      <Typography component="h1" variant="h4" fontWeight={700}>Facilities Dashboard</Typography>
      <FacilitiesNav />

      {loading && (
        <Stack role="status" direction="row" spacing={2} alignItems="center">
          <CircularProgress size={24} aria-label="Loading Facilities analytics" />
          <Typography>Loading Facilities analytics</Typography>
        </Stack>
      )}
      {!loading && error && <ErrorState message={error} retry={() => void loadAnalytics()} />}

      {!loading && data && analytics && (
        <>
          <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: { xs: '1fr 1fr', lg: 'repeat(4, 1fr)' } }}>
            <Metric label="Total Facilities" value={data.overview.summary.totalSpaces} detail="Registered facilities" />
            <Metric label="Available Facilities" value={data.overview.summary.availableSpaces} detail="Ready for booking" />
            <Metric label="Today's Reservations" value={analytics.todayReservations} detail="System-wide active reservations" />
            <Metric label="Open Maintenance Requests" value={data.overview.summary.openMaintenanceRequests} detail="Submitted or in progress" />
          </Box>

          <Box sx={{ display: 'grid', gap: 3, gridTemplateColumns: { xs: '1fr', lg: 'minmax(280px, 0.7fr) minmax(0, 1.3fr)' } }}>
            <Card variant="outlined">
              <CardContent>
                <Typography component="h2" variant="h6" fontWeight={700}>Facility Status</Typography>
                <Stack spacing={1.5} sx={{ mt: 3 }}>
                  {data.overview.spaceStatusBreakdown.map((item) => (
                    <Stack direction="row" justifyContent="space-between" key={item.status} role="group" aria-label={`${SPACE_STATUS_LABELS[item.status] ?? statusLabel(item.status)}: ${item.count}`}>
                      <Typography>{SPACE_STATUS_LABELS[item.status] ?? statusLabel(item.status)}</Typography>
                      <Chip label={item.count} color={statusColor(item.status)} size="small" />
                    </Stack>
                  ))}
                </Stack>
              </CardContent>
            </Card>

            <Card variant="outlined">
              <CardContent>
                <Typography component="h2" variant="h6" fontWeight={700}>Reservation Trend</Typography>
                <Stack direction="row" alignItems="end" spacing={1} sx={{ height: 190, mt: 3 }}>
                  {analytics.trend.map((item) => {
                    const label = formatAnalyticsDateLabel(item.date)
                    const height = analytics.maximumTrendCount === 0 ? 0 : (item.count / analytics.maximumTrendCount) * 100
                    return (
                      <Stack key={item.date} role="group" aria-label={`${label}: ${item.count} reservations`} alignItems="center" justifyContent="end" sx={{ flex: 1, height: '100%', minWidth: 0 }}>
                        <Typography variant="caption" fontWeight={600}>{item.count}</Typography>
                        <Box sx={{ display: 'flex', alignItems: 'end', width: '70%', height: 120, bgcolor: 'action.hover', borderRadius: '4px 4px 0 0', overflow: 'hidden' }}>
                          <Box role="img" aria-label={`${label} reservations: ${item.count}`} sx={{ width: '100%', height: `${height}%`, minHeight: item.count > 0 ? 4 : 0, bgcolor: 'primary.main' }} />
                        </Box>
                        <Typography variant="caption" sx={{ mt: 0.75, whiteSpace: 'nowrap' }}>{label}</Typography>
                      </Stack>
                    )
                  })}
                </Stack>
              </CardContent>
            </Card>
          </Box>

          <Card variant="outlined">
            <CardContent>
              <Typography component="h2" variant="h6" fontWeight={700}>Facility Usage Analysis</Typography>
              {analytics.usage.length === 0 ? (
                <Alert severity="info" sx={{ mt: 2 }}>No active facility reservations in the last 30 days.</Alert>
              ) : (
                <Stack spacing={2} sx={{ mt: 3 }}>
                  {analytics.usage.map((item) => {
                    const width = analytics.maximumUsageCount === 0 ? 0 : (item.reservationCount / analytics.maximumUsageCount) * 100
                    return (
                      <Box key={item.facilityId} role="group" aria-label={`${item.facilityName} facility usage`}>
                        <Stack direction="row" justifyContent="space-between" spacing={2}>
                          <Typography>{item.facilityName}</Typography>
                          <Typography color="text.secondary">{item.reservationCount} reservations</Typography>
                        </Stack>
                        <Box sx={{ height: 10, bgcolor: 'action.hover', borderRadius: 5, mt: 0.75, overflow: 'hidden' }}>
                          <Box role="img" aria-label={`${item.facilityName} usage: ${item.reservationCount} reservations`} sx={{ height: '100%', width: `${width}%`, bgcolor: 'primary.main', borderRadius: 5 }} />
                        </Box>
                      </Box>
                    )
                  })}
                </Stack>
              )}
            </CardContent>
          </Card>
        </>
      )}
    </Box>
  )
}

export function ReservationsPage() {
  const [draftStatus, setDraftStatus] = useState<AdminFacilityBookingStatus | 'ALL'>('ALL')
  const [draftUserEmail, setDraftUserEmail] = useState('')
  const [appliedFilters, setAppliedFilters] = useState<{
    status: AdminFacilityBookingStatus | 'ALL'
    userEmail: string
  }>({ status: 'ALL', userEmail: '' })
  const [pageIndex, setPageIndex] = useState(0)
  const [pageSize, setPageSize] = useState(25)
  const [result, setResult] = useState<PageResponse<AdminFacilityBooking> | null>(null)
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(true)
  const mountedRef = useRef(true)
  const requestIdRef = useRef(0)

  useEffect(() => () => {
    mountedRef.current = false
  }, [])

  const loadReservations = useCallback(async () => {
    const requestId = ++requestIdRef.current
    if (mountedRef.current) {
      setLoading(true)
      setError('')
      setResult(null)
    }

    try {
      const data = await searchAdminFacilityBookings({
        status: appliedFilters.status === 'ALL' ? undefined : appliedFilters.status,
        userEmail: appliedFilters.userEmail || undefined,
        page: pageIndex,
        size: pageSize,
        sort: 'startDateTime,asc',
      })
      if (mountedRef.current && requestId === requestIdRef.current) {
        setResult(data)
      }
    } catch (requestError) {
      if (mountedRef.current && requestId === requestIdRef.current) {
        setError(apiErrorMessage(requestError))
      }
    } finally {
      if (mountedRef.current && requestId === requestIdRef.current) {
        setLoading(false)
      }
    }
  }, [appliedFilters, pageIndex, pageSize])

  useEffect(() => {
    void loadReservations()
  }, [loadReservations])

  const applyFilters = () => {
    setAppliedFilters({
      status: draftStatus,
      userEmail: draftUserEmail.trim(),
    })
    setPageIndex(0)
  }

  return (
    <Box sx={{ display: 'grid', gap: 3 }}>
      <Typography component="h1" variant="h4" fontWeight={700}>Reservations</Typography>
      <FacilitiesNav />

      <Card variant="outlined">
        <CardContent>
          <Stack
            component="form"
            onSubmit={(event) => {
              event.preventDefault()
              applyFilters()
            }}
            direction={{ xs: 'column', md: 'row' }}
            spacing={2}
            alignItems={{ md: 'center' }}
          >
            <Select
              native
              value={draftStatus}
              onChange={(event) => setDraftStatus(event.target.value as AdminFacilityBookingStatus | 'ALL')}
              inputProps={{ 'aria-label': 'Status filter' }}
              size="small"
              sx={{ minWidth: 180 }}
            >
              <option value="ALL">All statuses</option>
              <option value="CONFIRMED">Confirmed</option>
              <option value="COMPLETED">Completed</option>
              <option value="CANCELLED">Cancelled</option>
            </Select>
            <TextField
              label="User Email"
              value={draftUserEmail}
              onChange={(event) => setDraftUserEmail(event.target.value)}
              size="small"
              fullWidth
            />
            <Button type="submit" variant="contained">Apply Filters</Button>
          </Stack>
        </CardContent>
      </Card>

      {loading && (
        <Stack role="status" direction="row" spacing={2} alignItems="center">
          <CircularProgress size={24} aria-label="Loading reservations" />
          <Typography>Loading reservations</Typography>
        </Stack>
      )}

      {!loading && error && <ErrorState message={error} retry={() => void loadReservations()} />}

      {!loading && result && result.content.length === 0 && (
        <Alert severity="info">No reservations found.</Alert>
      )}

      {!loading && result && result.content.length > 0 && (
        <Card variant="outlined">
          <TableContainer sx={{ overflowX: 'auto' }}>
            <Table size="small" aria-label="System reservations">
              <TableHead>
                <TableRow>
                  <TableCell>Reservation ID</TableCell>
                  <TableCell>Facility</TableCell>
                  <TableCell>Location</TableCell>
                  <TableCell>User</TableCell>
                  <TableCell>Start</TableCell>
                  <TableCell>End</TableCell>
                  <TableCell>Status</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {result.content.map((reservation) => (
                  <TableRow key={reservation.bookingId}>
                    <TableCell>{reservation.bookingId}</TableCell>
                    <TableCell>{reservation.spaceName}</TableCell>
                    <TableCell>{reservation.building} / {reservation.roomNumber}</TableCell>
                    <TableCell>{reservation.userEmail ?? 'Unknown user'}</TableCell>
                    <TableCell sx={{ whiteSpace: 'nowrap' }}>
                      {formatFacilityDateTime(reservation.startDateTime)}
                    </TableCell>
                    <TableCell sx={{ whiteSpace: 'nowrap' }}>
                      {formatFacilityDateTime(reservation.endDateTime)}
                    </TableCell>
                    <TableCell>
                      <Chip
                        label={reservation.status.charAt(0) + reservation.status.slice(1).toLowerCase()}
                        size="small"
                        color={statusColor(reservation.status)}
                      />
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableContainer>
          <TablePagination
            component="div"
            count={result.totalElements}
            page={result.page}
            rowsPerPage={result.size}
            rowsPerPageOptions={[10, 25, 50]}
            onPageChange={(_, nextPage) => setPageIndex(nextPage)}
            onRowsPerPageChange={(event) => {
              setPageSize(Number(event.target.value))
              setPageIndex(0)
            }}
          />
        </Card>
      )}
    </Box>
  )
}

export function MaintenancePage() {
  const [draftStatus, setDraftStatus] = useState<AdminFacilityMaintenanceStatus | 'ALL'>('ALL')
  const [draftPriority, setDraftPriority] = useState<AdminFacilityMaintenancePriority | 'ALL'>('ALL')
  const [draftUserEmail, setDraftUserEmail] = useState('')
  const [appliedFilters, setAppliedFilters] = useState<{
    status: AdminFacilityMaintenanceStatus | 'ALL'
    priority: AdminFacilityMaintenancePriority | 'ALL'
    userEmail: string
  }>({ status: 'ALL', priority: 'ALL', userEmail: '' })
  const [pageIndex, setPageIndex] = useState(0)
  const [pageSize, setPageSize] = useState(25)
  const [result, setResult] = useState<PageResponse<AdminFacilityMaintenance> | null>(null)
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(true)
  const mountedRef = useRef(true)
  const requestIdRef = useRef(0)

  useEffect(() => () => {
    mountedRef.current = false
  }, [])

  const loadMaintenance = useCallback(async () => {
    const requestId = ++requestIdRef.current
    if (mountedRef.current) {
      setLoading(true)
      setError('')
      setResult(null)
    }

    try {
      const data = await searchAdminFacilityMaintenance({
        statuses: appliedFilters.status === 'ALL' ? undefined : [appliedFilters.status],
        priority: appliedFilters.priority === 'ALL' ? undefined : appliedFilters.priority,
        userEmail: appliedFilters.userEmail || undefined,
        page: pageIndex,
        size: pageSize,
        sort: 'createdAt,desc',
      })
      if (mountedRef.current && requestId === requestIdRef.current) {
        setResult(data)
      }
    } catch (requestError) {
      if (mountedRef.current && requestId === requestIdRef.current) {
        setError(apiErrorMessage(requestError))
      }
    } finally {
      if (mountedRef.current && requestId === requestIdRef.current) {
        setLoading(false)
      }
    }
  }, [appliedFilters, pageIndex, pageSize])

  useEffect(() => {
    void loadMaintenance()
  }, [loadMaintenance])

  const applyFilters = () => {
    setAppliedFilters({
      status: draftStatus,
      priority: draftPriority,
      userEmail: draftUserEmail.trim(),
    })
    setPageIndex(0)
  }

  return (
    <Box sx={{ display: 'grid', gap: 3 }}>
      <Typography component="h1" variant="h4" fontWeight={700}>Maintenance</Typography>
      <FacilitiesNav />

      <Card variant="outlined">
        <CardContent>
          <Stack
            component="form"
            onSubmit={(event) => {
              event.preventDefault()
              applyFilters()
            }}
            direction={{ xs: 'column', md: 'row' }}
            spacing={2}
            alignItems={{ md: 'center' }}
          >
            <Select
              native
              value={draftStatus}
              onChange={(event) => setDraftStatus(event.target.value as AdminFacilityMaintenanceStatus | 'ALL')}
              inputProps={{ 'aria-label': 'Status filter' }}
              size="small"
              sx={{ minWidth: 180 }}
            >
              <option value="ALL">All statuses</option>
              <option value="SUBMITTED">Submitted</option>
              <option value="IN_PROGRESS">In Progress</option>
              <option value="RESOLVED">Resolved</option>
              <option value="CANCELLED">Cancelled</option>
            </Select>
            <Select
              native
              value={draftPriority}
              onChange={(event) => setDraftPriority(event.target.value as AdminFacilityMaintenancePriority | 'ALL')}
              inputProps={{ 'aria-label': 'Priority filter' }}
              size="small"
              sx={{ minWidth: 180 }}
            >
              <option value="ALL">All priorities</option>
              <option value="LOW">Low</option>
              <option value="MEDIUM">Medium</option>
              <option value="HIGH">High</option>
            </Select>
            <TextField
              label="User Email"
              value={draftUserEmail}
              onChange={(event) => setDraftUserEmail(event.target.value)}
              size="small"
              fullWidth
            />
            <Button type="submit" variant="contained">Apply Filters</Button>
          </Stack>
        </CardContent>
      </Card>

      {loading && (
        <Stack role="status" direction="row" spacing={2} alignItems="center">
          <CircularProgress size={24} aria-label="Loading maintenance requests" />
          <Typography>Loading maintenance requests</Typography>
        </Stack>
      )}

      {!loading && error && <ErrorState message={error} retry={() => void loadMaintenance()} />}

      {!loading && result && result.content.length === 0 && (
        <Alert severity="info">No maintenance requests found.</Alert>
      )}

      {!loading && result && result.content.length > 0 && (
        <Card variant="outlined">
          <TableContainer sx={{ overflowX: 'auto' }}>
            <Table size="small" aria-label="System maintenance requests">
              <TableHead>
                <TableRow>
                  <TableCell>Request ID</TableCell>
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
                {result.content.map((ticket) => (
                  <TableRow key={ticket.ticketId}>
                    <TableCell>{ticket.ticketId}</TableCell>
                    <TableCell>{ticket.spaceName ?? `${ticket.building} / ${ticket.roomNumber}`}</TableCell>
                    <TableCell sx={{ maxWidth: 360 }}>{ticket.description}</TableCell>
                    <TableCell>{ticket.userEmail ?? 'Unknown user'}</TableCell>
                    <TableCell>
                      <Chip label={statusLabel(ticket.priority)} size="small" color={ticket.priority === 'HIGH' ? 'error' : ticket.priority === 'MEDIUM' ? 'warning' : 'default'} />
                    </TableCell>
                    <TableCell>
                      <Chip label={statusLabel(ticket.status)} size="small" color={statusColor(ticket.status)} />
                    </TableCell>
                    <TableCell sx={{ whiteSpace: 'nowrap' }}>{formatFacilityDateTime(ticket.createdAt)}</TableCell>
                    <TableCell>
                      <Button component={RouterLink} to={`/admin/facilities/maintenance/${ticket.ticketId}`} size="small">View</Button>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableContainer>
          <TablePagination
            component="div"
            count={result.totalElements}
            page={result.page}
            rowsPerPage={result.size}
            rowsPerPageOptions={[10, 25, 50]}
            onPageChange={(_, nextPage) => setPageIndex(nextPage)}
            onRowsPerPageChange={(event) => {
              setPageSize(Math.min(Number(event.target.value), 100))
              setPageIndex(0)
            }}
          />
        </Card>
      )}
    </Box>
  )
}

export function MaintenanceDetailPage() {
  const { id } = useParams()
  const [request, setRequest] = useState<AdminFacilityMaintenance | null>(null)
  const [status, setStatus] = useState<AdminFacilityMaintenanceStatus>('SUBMITTED')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const mountedRef = useRef(true)

  const loadDetail = useCallback(async () => {
    if (!id) {
      setError('Maintenance request not found.')
      setLoading(false)
      return
    }
    setLoading(true)
    setError('')
    try {
      const data = await getAdminFacilityMaintenance(Number(id))
      if (mountedRef.current) {
        setRequest(data)
        setStatus(data.status)
      }
    } catch (requestError) {
      if (mountedRef.current) setError(apiErrorMessage(requestError))
    } finally {
      if (mountedRef.current) setLoading(false)
    }
  }, [id])

  useEffect(() => {
    void loadDetail()
    return () => {
      mountedRef.current = false
    }
  }, [loadDetail])

  const save = async () => {
    if (!id || !request || status === request.status) return
    setSaving(true)
    setError('')
    try {
      await facilitiesApi.updateMaintenance(Number(id), status)
      await loadDetail()
    } catch (requestError) {
      if (mountedRef.current) setError(apiErrorMessage(requestError))
    } finally {
      if (mountedRef.current) setSaving(false)
    }
  }

  if (loading) return <Loading />
  if (error) return <ErrorState message={error} retry={() => void loadDetail()} />
  if (!request) return <Alert severity="info">Maintenance request not found.</Alert>

  const facility = request.spaceName ?? `${request.building} / ${request.roomNumber}`
  return (
    <Box sx={{ display: 'grid', gap: 3, maxWidth: 760 }}>
      <Typography component="h1" variant="h4" fontWeight={700}>Maintenance Detail</Typography>
      <FacilitiesNav />
      <Card variant="outlined">
        <CardContent>
          <Stack spacing={2}>
            <Typography variant="h6">Ticket ID: {request.ticketId}</Typography>
            <Divider />
            <Box component="div"><strong>Submitter:</strong> {request.userEmail ?? 'Unknown user'}</Box>
            <Box component="div"><strong>Facility:</strong> {facility}</Box>
            <Box component="div"><strong>Building / Room:</strong> {request.building} / {request.roomNumber}</Box>
            <Box component="div"><strong>Facility Type:</strong> {request.facilityType}</Box>
            <Box component="div"><strong>Description:</strong> {request.description}</Box>
            <Stack direction="row" spacing={1} alignItems="center">
              <strong>Priority:</strong>
              <Chip label={statusLabel(request.priority)} size="small" color={request.priority === 'HIGH' ? 'error' : request.priority === 'MEDIUM' ? 'warning' : 'default'} />
            </Stack>
            <Stack direction="row" spacing={1} alignItems="center">
              <strong>Status:</strong>
              <Chip label={statusLabel(request.status)} size="small" color={statusColor(request.status)} />
            </Stack>
            <Box component="div"><strong>Created:</strong> {formatFacilityDateTime(request.createdAt)}</Box>
            <Box component="div"><strong>Updated:</strong> {formatFacilityDateTime(request.updatedAt)}</Box>
            <Select
              value={status}
              onChange={(event) => setStatus(event.target.value as AdminFacilityMaintenanceStatus)}
              aria-label="Maintenance status"
              inputProps={{ 'aria-label': 'Maintenance status' }}
            >
              {nextStatuses(request.status).map((value) => <MenuItem key={value} value={value}>{statusLabel(value)}</MenuItem>)}
            </Select>
            <Stack direction="row" spacing={2}>
              <Button variant="contained" onClick={() => void save()} disabled={saving || status === request.status}>
                {saving ? 'Saving?' : 'Save Changes'}
              </Button>
              <Button component={RouterLink} to="/admin/facilities/maintenance">Back to Maintenance</Button>
            </Stack>
          </Stack>
        </CardContent>
      </Card>
    </Box>
  )
}
