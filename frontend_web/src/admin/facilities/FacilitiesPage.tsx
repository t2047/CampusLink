import { Alert, Box, Button, Card, CardContent, Chip, CircularProgress, Divider, MenuItem, Select, Stack, Tab, Tabs, TextField, Typography } from '@mui/material'
import { Link as RouterLink, useLocation, useNavigate, useParams } from 'react-router-dom'
import { useEffect, useMemo, useState } from 'react'
import { apiErrorMessage } from '../../api/client'
import { Booking, facilitiesApi, FacilitiesDashboard, MaintenanceRequest } from '../../api/facilities'

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
function Metric({ label, value, detail }: { label: string; value: number; detail: string }) { return <Card variant="outlined"><CardContent><Typography color="text.secondary" variant="body2">{label}</Typography><Typography variant="h4" fontWeight={700} sx={{ my: 1 }}>{value}</Typography><Typography variant="body2" color="text.secondary">{detail}</Typography></CardContent></Card> }
function nextStatuses(status: string) {
  if (status === 'SUBMITTED') return ['SUBMITTED', 'IN_PROGRESS', 'CANCELLED']
  if (status === 'IN_PROGRESS') return ['IN_PROGRESS', 'RESOLVED', 'CANCELLED']
  return [status]
}

export function FacilitiesDashboardPage() {
  const [data, setData] = useState<FacilitiesDashboard | null>(null); const [error, setError] = useState('')
  const load = () => { setError(''); facilitiesApi.getDashboard().then(setData).catch((e) => setError(apiErrorMessage(e))) }
  useEffect(load, [])
  return <Box sx={{ display: 'grid', gap: 3 }}><Typography component="h1" variant="h4" fontWeight={700}>Facilities Dashboard</Typography><FacilitiesNav />{error ? <ErrorState message={error} retry={load} /> : !data ? <Loading /> : <>
    <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: { xs: '1fr 1fr', lg: 'repeat(4, 1fr)' } }}><Metric label="Total Facilities" value={data.summary.totalFacilities} detail="Registered facilities" /><Metric label="Available Facilities" value={data.summary.availableFacilities} detail="Ready for booking" /><Metric label="Today's Reservations" value={data.summary.todayReservations} detail="Across all facilities" /><Metric label="Under Maintenance" value={data.summary.underMaintenance} detail="Currently unavailable" /></Box>
    <Box sx={{ display: 'grid', gap: 3, gridTemplateColumns: { xs: '1fr', lg: '1fr 1fr' } }}><Card variant="outlined"><CardContent><Typography component="h2" variant="h6" fontWeight={700}>Facility Status</Typography><Stack spacing={1.5} sx={{ mt: 3 }}>{data.statusBreakdown.map((item) => <Stack direction="row" justifyContent="space-between" key={item.status}><Typography>{statusLabel(item.status)}</Typography><Chip label={item.count} color={statusColor(item.status)} size="small" /></Stack>)}</Stack></CardContent></Card><Card variant="outlined"><CardContent><Typography component="h2" variant="h6" fontWeight={700}>Reservation Trend</Typography><Stack direction="row" alignItems="end" spacing={1} sx={{ height: 150, mt: 3 }}>{data.reservationTrend.map((item) => <Box key={item.date} sx={{ flex: 1, bgcolor: 'primary.main', height: `${Math.max(item.count * 2, 4)}%`, borderRadius: '4px 4px 0 0' }} title={`${item.date}: ${item.count}`} />)}</Stack></CardContent></Card></Box>
    <Card variant="outlined"><CardContent><Typography component="h2" variant="h6" fontWeight={700}>Facility Usage Analysis</Typography><Stack spacing={2} sx={{ mt: 3 }}>{data.facilityUsage.slice(0, 8).map((item) => <Box key={item.facilityId}><Stack direction="row" justifyContent="space-between"><Typography>{item.facilityName}</Typography><Typography color="text.secondary">{item.reservationCount} reservations</Typography></Stack><Box sx={{ height: 8, bgcolor: 'grey.200', borderRadius: 4, mt: 0.5 }}><Box sx={{ height: '100%', width: `${Math.min(item.reservationCount * 5, 100)}%`, bgcolor: 'primary.main', borderRadius: 4 }} /></Box></Box>)}</Stack></CardContent></Card>
  </>}</Box>
}

export function ReservationsPage() {
  const [rows, setRows] = useState<Booking[]>([]); const [query, setQuery] = useState(''); const [filter, setFilter] = useState('All'); const [error, setError] = useState(''); const [loading, setLoading] = useState(true)
  const load = () => { setError(''); setLoading(true); facilitiesApi.getReservations().then(setRows).catch((e) => setError(apiErrorMessage(e))).finally(() => setLoading(false)) }; useEffect(load, [])
  const filtered = useMemo(() => rows.filter((row) => (filter === 'All' || row.status === filter) && `${row.bookingId} ${row.space.name}`.toLowerCase().includes(query.toLowerCase())), [filter, query, rows])
  return <Box sx={{ display: 'grid', gap: 3 }}><Typography component="h1" variant="h4" fontWeight={700}>Reservations</Typography><FacilitiesNav />{error ? <ErrorState message={error} retry={load} /> : loading ? <Loading /> : rows.length === 0 ? <Alert severity="info">No reservations found for the current account.</Alert> : <Card variant="outlined"><CardContent><Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} sx={{ mb: 2 }}><TextField label="Search reservations" value={query} onChange={(e) => setQuery(e.target.value)} size="small" fullWidth /><Select value={filter} onChange={(e) => setFilter(e.target.value)} size="small" aria-label="Filter by reservation status"><MenuItem value="All">All statuses</MenuItem><MenuItem value="CONFIRMED">Confirmed</MenuItem><MenuItem value="COMPLETED">Completed</MenuItem><MenuItem value="CANCELLED">Cancelled</MenuItem></Select></Stack><Box sx={{ overflowX: 'auto' }}><Box component="table" sx={{ width: '100%', borderCollapse: 'collapse', '& th, & td': { textAlign: 'left', p: 1.5, borderBottom: '1px solid', borderColor: 'divider', whiteSpace: 'nowrap' } }}><thead><tr>{['Reservation ID', 'Facility', 'Applicant', 'Reservation Date', 'Time Slot', 'Status'].map((h) => <th key={h}>{h}</th>)}</tr></thead><tbody>{filtered.map((row) => <tr key={row.bookingId}><td>{row.bookingId}</td><td>{row.space.name}</td><td>User #{row.bookingId}</td><td>{new Date(row.startDateTime).toLocaleDateString()}</td><td>{new Date(row.startDateTime).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}–{new Date(row.endDateTime).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}</td><td><Chip label={statusLabel(row.status)} size="small" color={statusColor(row.status)} /></td></tr>)}</tbody></Box></Box></CardContent></Card>}</Box>
}

export function MaintenancePage() {
  const [rows, setRows] = useState<MaintenanceRequest[]>([]); const [error, setError] = useState(''); const [loading, setLoading] = useState(true); const load = () => { setError(''); setLoading(true); facilitiesApi.getMaintenance().then(setRows).catch((e) => setError(apiErrorMessage(e))).finally(() => setLoading(false)) }; useEffect(load, [])
  return <Box sx={{ display: 'grid', gap: 3 }}><Typography component="h1" variant="h4" fontWeight={700}>Maintenance Requests</Typography><FacilitiesNav />{error ? <ErrorState message={error} retry={load} /> : loading ? <Loading /> : rows.length === 0 ? <Alert severity="info">No maintenance requests found for the current account.</Alert> : <Card variant="outlined"><CardContent><Box sx={{ overflowX: 'auto' }}><Box component="table" sx={{ width: '100%', borderCollapse: 'collapse', '& th, & td': { textAlign: 'left', p: 1.5, borderBottom: '1px solid', borderColor: 'divider', whiteSpace: 'nowrap' } }}><thead><tr>{['Request ID', 'Facility', 'Issue', 'Priority', 'Status', 'Submitted Date', ''].map((h) => <th key={h}>{h}</th>)}</tr></thead><tbody>{rows.map((row) => <tr key={row.ticketId}><td>{row.ticketId}</td><td>{row.spaceName ?? `${row.building} ${row.roomNumber}`}</td><td>{row.description}</td><td><Chip label={row.priority} size="small" color={row.priority === 'HIGH' ? 'error' : 'default'} /></td><td><Chip label={statusLabel(row.status)} size="small" color={statusColor(row.status)} /></td><td>{new Date(row.createdAt).toLocaleDateString()}</td><td><Button component={RouterLink} to={`/admin/facilities/maintenance/${row.ticketId}`} size="small">View</Button></td></tr>)}</tbody></Box></Box></CardContent></Card>}</Box>
}

export function MaintenanceDetailPage() {
  const { id } = useParams(); const navigate = useNavigate(); const [request, setRequest] = useState<MaintenanceRequest | null>(null); const [status, setStatus] = useState('SUBMITTED'); const [error, setError] = useState('')
  useEffect(() => { if (id) facilitiesApi.getMaintenanceDetail(Number(id)).then((data) => { setRequest(data); setStatus(data.status) }).catch((e) => setError(apiErrorMessage(e))) }, [id])
  const save = () => { if (!id) return; facilitiesApi.updateMaintenance(Number(id), status).then(() => navigate('/admin/facilities/maintenance')).catch((e) => setError(apiErrorMessage(e))) }
  if (error) return <ErrorState message={error} retry={() => window.location.reload()} />; if (!request) return <Loading />
  return <Box sx={{ display: 'grid', gap: 3, maxWidth: 760 }}><Typography component="h1" variant="h4" fontWeight={700}>Maintenance Detail</Typography><Card variant="outlined"><CardContent><Stack spacing={2}><Typography variant="h6">#{request.ticketId} · {request.spaceName ?? `${request.building} ${request.roomNumber}`}</Typography><Divider /><Typography><strong>Problem:</strong> {request.description}</Typography><Typography><strong>Request time:</strong> {new Date(request.createdAt).toLocaleString()}</Typography><Typography><strong>Current status:</strong> {statusLabel(request.status)}</Typography><Select value={status} onChange={(e) => setStatus(e.target.value)} aria-label="Maintenance status">{nextStatuses(request.status).map((value) => <MenuItem key={value} value={value}>{statusLabel(value)}</MenuItem>)}</Select><Stack direction="row" spacing={2}><Button variant="contained" onClick={save} disabled={status === request.status}>Save Changes</Button><Button component={RouterLink} to="/admin/facilities/maintenance">Cancel</Button></Stack></Stack></CardContent></Card></Box>
}
