import { Box, Card, CardActionArea, CardContent, Stack, Typography } from '@mui/material'
import { Link as RouterLink } from 'react-router-dom'
import { useEffect, useState } from 'react'
import { useAuth } from '../../auth/AuthContext'
import { apiErrorMessage } from '../../api/client'
import { facilitiesApi, FacilitiesDashboard } from '../../api/facilities'
import { LostFoundOverviewSection } from './sections/LostFoundOverviewSection'

function FacilityMetric({ label, value }: { label: string; value: number }) {
  return <Card variant="outlined"><CardContent><Stack spacing={1}><Typography color="text.secondary">{label}</Typography><Typography variant="h4" fontWeight={700}>{value}</Typography></Stack></CardContent></Card>
}

function FacilitiesOverview() {
  const [data, setData] = useState<FacilitiesDashboard | null>(null)
  const [error, setError] = useState('')
  useEffect(() => { facilitiesApi.getDashboard().then(setData).catch((e) => setError(apiErrorMessage(e))) }, [])
  return <Card variant="outlined"><CardActionArea component={RouterLink} to="/admin/facilities"><CardContent><Stack spacing={3}><Stack direction="row" justifyContent="space-between" alignItems="center"><Typography component="h2" variant="h5" fontWeight={700}>Facilities Overview</Typography><Typography color="primary" variant="body2">View details →</Typography></Stack>{error ? <Typography color="error">{error}</Typography> : !data ? <Typography color="text.secondary">Loading facility KPIs…</Typography> : <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: 'repeat(2, 1fr)' }}><FacilityMetric label="Total Facilities" value={data.summary.totalFacilities} /><FacilityMetric label="Available" value={data.summary.availableFacilities} /><FacilityMetric label="Today's Reservations" value={data.summary.todayReservations} /><FacilityMetric label="Under Maintenance" value={data.summary.underMaintenance} /></Box>}</Stack></CardContent></CardActionArea></Card>
}

function RecentActivity() {
  return <Card variant="outlined"><CardContent><Stack spacing={1}><Typography component="h2" variant="h6" fontWeight={700}>Recent Activity</Typography><Typography color="text.secondary">Activity data is not available yet.</Typography></Stack></CardContent></Card>
}

export function AdminDashboardPage() {
  const { user } = useAuth()
  return <Box sx={{ display: 'grid', gap: 4 }}>
    <Card><CardContent><Stack spacing={1}><Typography component="h1" variant="h4" fontWeight={700}>Dashboard Overview</Typography><Typography variant="h6">Welcome to CampusLink Administration.</Typography><Typography color="text.secondary">Signed in as {user?.email ?? 'Unknown user'}</Typography><Typography color="text.secondary">Role: {user?.role ?? 'Unknown role'}</Typography></Stack></CardContent></Card>
    <Box sx={{ display: 'grid', gap: 3, gridTemplateColumns: 'repeat(2, minmax(0, 1fr))', alignItems: 'start' }}><Card variant="outlined"><CardContent><LostFoundOverviewSection /></CardContent></Card><FacilitiesOverview /></Box>
    <RecentActivity />
  </Box>
}
