import { Box, Tab, Tabs, Typography } from '@mui/material'
import { Link as RouterLink, Outlet, useLocation } from 'react-router-dom'

export function FacilitiesLayout() {
  const path = useLocation().pathname
  const activeSection = path.startsWith('/facilities/spaces') || path === '/facilities' ? 'spaces' : false

  return (
    <Box sx={{ display: 'grid', gap: 3 }}>
      <Box>
        <Typography component="h1" variant="h4" fontWeight={700}>Facilities</Typography>
        <Typography color="text.secondary">Find campus spaces that fit your needs.</Typography>
      </Box>
      <Tabs value={activeSection} aria-label="Facilities sections">
        <Tab label="Spaces" value="spaces" component={RouterLink} to="/facilities" />
      </Tabs>
      <Outlet />
    </Box>
  )
}
