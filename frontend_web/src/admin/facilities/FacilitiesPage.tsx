import { Box, Button, Card, CardContent, Chip, Divider, MenuItem, Select, Stack, Tab, Tabs, TextField, Typography } from '@mui/material'
import { Link as RouterLink, useNavigate, useParams, useLocation } from 'react-router-dom'
import { useMemo, useState } from 'react'
import { facilities, maintenanceRequests, reservations, MaintenanceStatus, ReservationStatus } from './facilitiesData'

const statusColor = (status: string) => status === 'Available' || status === 'Completed' || status === 'Confirmed' ? 'success' : status === 'Maintenance' || status === 'In Progress' ? 'warning' : 'default'

function FacilitiesNav() {
  const location = useLocation()
  const value = location.pathname.includes('reservations') ? 'reservations' : location.pathname.includes('maintenance') ? 'maintenance' : 'dashboard'
  return <Tabs value={value} sx={{ mb: 3 }} aria-label="Facilities sections">
    <Tab label="Dashboard" value="dashboard" component={RouterLink} to="/admin/facilities" />
    <Tab label="Reservations" value="reservations" component={RouterLink} to="/admin/facilities/reservations" />
    <Tab label="Maintenance" value="maintenance" component={RouterLink} to="/admin/facilities/maintenance" />
  </Tabs>
}

function Metric({ label, value, detail }: { label: string; value: number; detail: string }) {
  return <Card variant="outlined"><CardContent><Typography color="text.secondary" variant="body2">{label}</Typography><Typography variant="h4" fontWeight={700} sx={{ my: 1 }}>{value}</Typography><Typography variant="body2" color="text.secondary">{detail}</Typography></CardContent></Card>
}

export function FacilitiesDashboardPage() {
  const trend = [22, 31, 27, 39, 34, 45, 41]
  return <Box sx={{ display: 'grid', gap: 3 }}><Typography component="h1" variant="h4" fontWeight={700}>Facilities Dashboard</Typography><FacilitiesNav />
    <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: { xs: '1fr 1fr', lg: 'repeat(4, 1fr)' } }}>
      <Metric label="Total Facilities" value={facilities.length} detail="Registered facilities" /><Metric label="Available Facilities" value={facilities.filter((f) => f.status === 'Available').length} detail="Ready for booking" /><Metric label="Today's Reservations" value={reservations.filter((r) => r.date === '2026-08-07').length} detail="Across all facilities" /><Metric label="Under Maintenance" value={facilities.filter((f) => f.status === 'Maintenance').length} detail="Currently unavailable" />
    </Box>
    <Box sx={{ display: 'grid', gap: 3, gridTemplateColumns: { xs: '1fr', lg: '1fr 1fr' } }}>
      <Card variant="outlined"><CardContent><Typography component="h2" variant="h6" fontWeight={700}>Facility Status</Typography><Stack spacing={1.5} sx={{ mt: 3 }}>{(['Available', 'Reserved', 'Maintenance'] as const).map((status) => <Stack direction="row" justifyContent="space-between" key={status}><Typography>{status}</Typography><Chip label={facilities.filter((f) => f.status === status).length} color={statusColor(status)} size="small" /></Stack>)}</Stack></CardContent></Card>
      <Card variant="outlined"><CardContent><Typography component="h2" variant="h6" fontWeight={700}>Reservation Trend</Typography><Stack direction="row" alignItems="end" spacing={1} sx={{ height: 150, mt: 3 }}>{trend.map((amount, index) => <Box key={index} sx={{ flex: 1, bgcolor: 'primary.main', height: `${amount * 2.2}%`, borderRadius: '4px 4px 0 0' }} title={`${amount} reservations`} />)}</Stack><Stack direction="row" justifyContent="space-between"><Typography variant="caption">Mon</Typography><Typography variant="caption">Sun</Typography></Stack></CardContent></Card>
    </Box>
    <Card variant="outlined"><CardContent><Typography component="h2" variant="h6" fontWeight={700}>Facility Usage Analysis</Typography><Stack spacing={2} sx={{ mt: 3 }}>{facilities.slice(0, 4).map((facility, index) => <Box key={facility.id}><Stack direction="row" justifyContent="space-between"><Typography>{facility.name}</Typography><Typography color="text.secondary">{[52, 43, 38, 26][index]} reservations</Typography></Stack><Box sx={{ height: 8, bgcolor: 'grey.200', borderRadius: 4, mt: 0.5 }}><Box sx={{ height: '100%', width: `${[90, 74, 65, 45][index]}%`, bgcolor: 'primary.main', borderRadius: 4 }} /></Box></Box>)}</Stack></CardContent></Card>
  </Box>
}

export function ReservationsPage() {
  const [query, setQuery] = useState(''); const [filter, setFilter] = useState<'All' | ReservationStatus>('All')
  const rows = useMemo(() => reservations.filter((r) => (filter === 'All' || r.status === filter) && `${r.id} ${r.facility} ${r.applicant}`.toLowerCase().includes(query.toLowerCase())), [filter, query])
  return <Box sx={{ display: 'grid', gap: 3 }}><Typography component="h1" variant="h4" fontWeight={700}>Reservations</Typography><FacilitiesNav /><Card variant="outlined"><CardContent><Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} sx={{ mb: 2 }}><TextField label="Search reservations" value={query} onChange={(e) => setQuery(e.target.value)} size="small" fullWidth /><Select value={filter} onChange={(e) => setFilter(e.target.value as typeof filter)} size="small" aria-label="Filter by reservation status"><MenuItem value="All">All statuses</MenuItem><MenuItem value="Confirmed">Confirmed</MenuItem><MenuItem value="Pending">Pending</MenuItem><MenuItem value="Cancelled">Cancelled</MenuItem></Select></Stack><Box sx={{ overflowX: 'auto' }}><Box component="table" sx={{ width: '100%', borderCollapse: 'collapse', '& th, & td': { textAlign: 'left', p: 1.5, borderBottom: '1px solid', borderColor: 'divider', whiteSpace: 'nowrap' } }}><thead><tr>{['Reservation ID', 'Facility', 'Applicant', 'Date', 'Time Slot', 'Status'].map((h) => <th key={h}>{h}</th>)}</tr></thead><tbody>{rows.map((r) => <tr key={r.id}><td>{r.id}</td><td>{r.facility}</td><td>{r.applicant}</td><td>{r.date}</td><td>{r.time}</td><td><Chip label={r.status} size="small" color={statusColor(r.status)} /></td></tr>)}</tbody></Box></Box>{rows.length === 0 && <Typography color="text.secondary" sx={{ mt: 2 }}>No reservations found.</Typography>}</CardContent></Card></Box>
}

export function MaintenancePage() {
  return <Box sx={{ display: 'grid', gap: 3 }}><Typography component="h1" variant="h4" fontWeight={700}>Maintenance Requests</Typography><FacilitiesNav /><Card variant="outlined"><CardContent><Box sx={{ overflowX: 'auto' }}><Box component="table" sx={{ width: '100%', borderCollapse: 'collapse', '& th, & td': { textAlign: 'left', p: 1.5, borderBottom: '1px solid', borderColor: 'divider', whiteSpace: 'nowrap' } }}><thead><tr>{['Request ID', 'Facility', 'Issue', 'Priority', 'Status', 'Submitted Date', ''].map((h) => <th key={h}>{h}</th>)}</tr></thead><tbody>{maintenanceRequests.map((r) => <tr key={r.id}><td>{r.id}</td><td>{r.facility}</td><td>{r.issue}</td><td><Chip label={r.priority} size="small" color={r.priority === 'High' ? 'error' : 'default'} /></td><td><Chip label={r.status} size="small" color={statusColor(r.status)} /></td><td>{r.submittedDate}</td><td><Button component={RouterLink} to={`/admin/facilities/maintenance/${r.id}`} size="small">View</Button></td></tr>)}</tbody></Box></Box></CardContent></Card></Box>
}

export function MaintenanceDetailPage() {
  const { id } = useParams(); const navigate = useNavigate(); const request = maintenanceRequests.find((item) => item.id === id); const [status, setStatus] = useState<MaintenanceStatus>(request?.status ?? 'Pending'); const [note, setNote] = useState(request?.note ?? '')
  if (!request) return <Card variant="outlined"><CardContent><Typography component="h1" variant="h5" fontWeight={700}>Maintenance request not found</Typography><Button onClick={() => navigate('/admin/facilities/maintenance')} sx={{ mt: 2 }}>Back to Maintenance</Button></CardContent></Card>
  return <Box sx={{ display: 'grid', gap: 3, maxWidth: 760 }}><Typography component="h1" variant="h4" fontWeight={700}>Maintenance Detail</Typography><Card variant="outlined"><CardContent><Stack spacing={2}><Typography variant="h6">{request.id} · {request.facility}</Typography><Divider /><Typography><strong>Problem:</strong> {request.issue}</Typography><Typography><strong>Request time:</strong> {request.submittedDate}</Typography><Typography><strong>Current status:</strong> {request.status}</Typography><Select label="Status" value={status} onChange={(e) => setStatus(e.target.value as MaintenanceStatus)} aria-label="Maintenance status"><MenuItem value="Pending">Pending</MenuItem><MenuItem value="In Progress">In Progress</MenuItem><MenuItem value="Completed">Completed</MenuItem></Select><TextField label="Admin note" multiline minRows={4} value={note} onChange={(e) => setNote(e.target.value)} placeholder="Add an internal processing note" /><Stack direction="row" spacing={2}><Button variant="contained" onClick={() => navigate('/admin/facilities/maintenance')}>Save Changes</Button><Button component={RouterLink} to="/admin/facilities/maintenance">Cancel</Button></Stack></Stack></CardContent></Card></Box>
}

