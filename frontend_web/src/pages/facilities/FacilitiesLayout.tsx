import { Box, Paper, Tab, Tabs, Typography } from '@mui/material'
import { Link as RouterLink, Outlet, useLocation } from 'react-router-dom'

export function FacilitiesLayout() {
  const path = useLocation().pathname
  const activeSection = path === '/facilities/maintenance/new'
    ? 'maintenance-new'
    : path.startsWith('/facilities/maintenance')
      ? 'maintenance'
      : path.startsWith('/facilities/bookings')
        ? 'bookings'
        : path.startsWith('/facilities/spaces') || path === '/facilities' ? 'spaces' : false

  return (
    <Box sx={{ display: 'grid', gap: 3 }}>
      <Paper variant="outlined" sx={{ p: 3, bgcolor: 'background.paper' }}>
        <Typography component="h1" variant="h4" fontWeight={700}>Facilities</Typography>
        <Typography color="text.secondary">Find a space, manage bookings, or report a facility issue.</Typography>
        </Paper>
      <Paper variant="outlined" sx={{ px: 1 }}>
        <Tabs value={activeSection} aria-label="Facilities sections" variant="scrollable" scrollButtons="auto" allowScrollButtonsMobile>
        <Tab label="Search Spaces" value="spaces" component={RouterLink} to="/facilities" />
        <Tab label="My Bookings" value="bookings" component={RouterLink} to="/facilities/bookings" />
        <Tab label="Report Maintenance" value="maintenance-new" component={RouterLink} to="/facilities/maintenance/new" />
        <Tab label="My Maintenance Requests" value="maintenance" component={RouterLink} to="/facilities/maintenance" />
        </Tabs>
      </Paper>
      <Outlet />
    </Box>
  )
}
