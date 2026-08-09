import { Box, Card, CardActionArea, CardContent, Chip, Stack, Typography } from '@mui/material'
import { Link as RouterLink } from 'react-router-dom'
import { useAuth } from '../../auth/AuthContext'

interface AdminModuleCardProps {
  title: string
  description: string
  path: string
  available?: boolean
}

function AdminModuleCard({ title, description, path, available = false }: AdminModuleCardProps) {
  return (
    <Card variant="outlined" sx={{ height: '100%' }}>
      <CardActionArea
        component={RouterLink}
        to={path}
        aria-label={title}
        sx={{ height: '100%', alignItems: 'stretch' }}
      >
        <CardContent>
          <Stack spacing={2} alignItems="flex-start">
            <Typography component="h2" variant="h6" fontWeight={700}>{title}</Typography>
            <Typography color="text.secondary">{description}</Typography>
            <Chip
              label={available ? 'Available' : 'Coming Soon'}
              size="small"
              color={available ? 'success' : 'default'}
            />
          </Stack>
        </CardContent>
      </CardActionArea>
    </Card>
  )
}

interface AdminSectionCardProps {
  title: string
  message: string
  status?: string
}

function AdminSectionCard({ title, message, status }: AdminSectionCardProps) {
  return (
    <Card variant="outlined" sx={{ height: '100%' }}>
      <CardContent>
        <Stack spacing={2} alignItems="flex-start">
          <Typography component="h2" variant="h6" fontWeight={700}>{title}</Typography>
          {status && <Chip label={status} size="small" color="default" />}
          <Typography color="text.secondary">{message}</Typography>
        </Stack>
      </CardContent>
    </Card>
  )
}

export function AdminDashboardPage() {
  const { user } = useAuth()

  return (
    <Box sx={{ display: 'grid', gap: 4 }}>
      <Card>
        <CardContent>
          <Stack spacing={1}>
            <Typography component="h1" variant="h4" fontWeight={700}>Dashboard Overview</Typography>
            <Typography variant="h6">Welcome to CampusLink Administration.</Typography>
            <Typography color="text.secondary">Signed in as {user?.email ?? 'Unknown user'}</Typography>
            <Typography color="text.secondary">Role: {user?.role ?? 'Unknown role'}</Typography>
          </Stack>
        </CardContent>
      </Card>

      <Box sx={{ display: 'grid', gap: 3, gridTemplateColumns: { xs: '1fr', md: 'repeat(3, minmax(0, 1fr))' } }}>
        <AdminModuleCard
          title="Lost & Found"
          description="Review report volume, status metrics, and operational records."
          path="/admin/lost-found"
          available
        />
        <AdminModuleCard
          title="Facilities"
          description="Facilities administration will be available here."
          path="/admin/facilities"
        />
        <AdminModuleCard
          title="User Management"
          description="The scope of this module is pending team confirmation."
          path="/admin/users"
        />
      </Box>

      <Box sx={{ display: 'grid', gap: 3, gridTemplateColumns: { xs: '1fr', lg: 'repeat(3, minmax(0, 1fr))' } }}>
        <AdminSectionCard
          title="Overview Metrics"
          status="Data source not connected"
          message="Module metrics will appear here after the corresponding data sources are connected."
        />
        <AdminSectionCard
          title="Action Required"
          message="No operational data is connected yet."
        />
        <AdminSectionCard
          title="Recent Activity"
          message="No activity data is connected yet."
        />
      </Box>
    </Box>
  )
}
