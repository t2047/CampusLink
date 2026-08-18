import ArrowBackIcon from '@mui/icons-material/ArrowBack'
import BusinessIcon from '@mui/icons-material/Business'
import { Box, Button, Paper, Stack, Tab, Tabs, Typography } from '@mui/material'
import { Link as RouterLink, Outlet, useLocation, useSearchParams } from 'react-router-dom'

export function FacilitiesLayout() {
  const path = useLocation().pathname
  const [searchParams] = useSearchParams()
  const showBack = searchParams.get('from') === 'profile'
  const activeSection = path === '/facilities/maintenance/new'
    ? 'maintenance-new'
    : path.startsWith('/facilities/maintenance')
      ? 'maintenance'
      : path.startsWith('/facilities/bookings')
        ? 'bookings'
        : path.startsWith('/facilities/spaces') || path === '/facilities' ? 'spaces' : false

  return (
    <Box sx={{ display: 'grid', gap: 3 }}>
      {showBack && <Button component={RouterLink} to="/lost-found/profile" startIcon={<ArrowBackIcon />} sx={{ textTransform: 'none', justifySelf: 'start' }}>Back to personal center</Button>}
      <Paper variant="outlined" sx={{ p: 3, bgcolor: 'background.paper' }}>
        <Stack direction="row" spacing={2} alignItems="center">
          <Box sx={{ width: 46, height: 46, borderRadius: 3, display: 'grid', placeItems: 'center', flexShrink: 0, color: '#fff', background: 'linear-gradient(135deg, #ec4899 0%, #db2777 100%)', boxShadow: '0 4px 12px rgba(219, 39, 119, 0.3)' }}>
            <BusinessIcon />
          </Box>
          <Box>
            <Typography component="h1" variant="h4" fontWeight={700}>Facilities</Typography>
            <Typography color="text.secondary">Find a space, manage bookings, or report a facility issue.</Typography>
          </Box>
        </Stack>
        </Paper>
      <Paper variant="outlined" sx={{ px: 1 }}>
        <Tabs value={activeSection} aria-label="Facilities sections" variant="scrollable" scrollButtons="auto" allowScrollButtonsMobile>
        <Tab label="Search Spaces" value="spaces" component={RouterLink} to="/facilities" />
        <Tab label="My Bookings" value="bookings" component={RouterLink} to="/facilities/bookings" />
        <Tab label="My Maintenance Requests" value="maintenance" component={RouterLink} to="/facilities/maintenance" />
        </Tabs>
      </Paper>
      <Outlet />
    </Box>
  )
}
