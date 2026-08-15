import AddCircleOutlineIcon from '@mui/icons-material/AddCircleOutline'
import CalendarMonthIcon from '@mui/icons-material/CalendarMonth'
import InboxIcon from '@mui/icons-material/Inbox'
import LogoutIcon from '@mui/icons-material/Logout'
import MeetingRoomIcon from '@mui/icons-material/MeetingRoom'
import MailOutlineIcon from '@mui/icons-material/MailOutline'
import SearchIcon from '@mui/icons-material/Search'
import { AppBar, Box, Button, Container, Toolbar, Typography } from '@mui/material'
import { Link as RouterLink, Outlet, useNavigate } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'

export function AppShell() {
  const { user, logout } = useAuth()
  const navigate = useNavigate()

  return (
    <Box sx={{ minHeight: '100vh', bgcolor: '#f1f5f9' }}>
      <AppBar position="sticky" color="inherit" elevation={0} sx={{ bgcolor: 'white', border: '1px solid', borderColor: '#e2e8f0' }}>
        <Toolbar sx={{ gap: 1, flexWrap: 'wrap' }}>
          <Typography
            variant="h6"
            component={RouterLink}
            to="/chat"
            sx={{ flexGrow: 1, fontWeight: 700, textDecoration: 'none', color: '#172554', '&:hover': { opacity: 0.8 } }}
          >CampusLink</Typography>
          <Button variant="outlined" color="inherit" component={RouterLink} to="/lost-found" startIcon={<SearchIcon />} sx={{ borderColor: '#e2e8f0', color: '#334155' }}>Browse</Button>
          <Button variant="outlined" color="inherit" component={RouterLink} to="/facilities" startIcon={<MeetingRoomIcon />} sx={{ borderColor: '#e2e8f0', color: '#334155' }}>Facilities</Button>
          <Button variant="outlined" color="inherit" component={RouterLink} to="/lost-found/new/lost" startIcon={<AddCircleOutlineIcon />} sx={{ borderColor: '#e2e8f0', color: '#334155' }}>Report</Button>
          <Button variant="outlined" color="inherit" component={RouterLink} to="/claims/mine" startIcon={<InboxIcon />} sx={{ borderColor: '#e2e8f0', color: '#334155' }}>Claims</Button>
          <Button variant="outlined" color="inherit" component={RouterLink} to="/mail" startIcon={<MailOutlineIcon />} sx={{ borderColor: '#e2e8f0', color: '#334155' }}>Mail</Button>
          <Button variant="outlined" color="inherit" component={RouterLink} to="/mail/calendar" startIcon={<CalendarMonthIcon />} sx={{ borderColor: '#e2e8f0', color: '#334155' }}>Calendar</Button>
          <Typography variant="body2" sx={{ display: { xs: 'none', md: 'block' }, mx: 1, color: '#475569' }}>{user?.email}</Typography>
          <Button
            color="inherit"
            variant="outlined"
            startIcon={<LogoutIcon />}
            sx={{ borderColor: '#e2e8f0', color: '#334155' }}
            onClick={() => { logout(); navigate('/login') }}
          >Logout</Button>
        </Toolbar>
      </AppBar>
      <Container maxWidth="lg" sx={{ py: 4 }}><Outlet /></Container>
    </Box>
  )
}
