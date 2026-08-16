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
import { displayName } from '../auth/displayName'
import { UserAvatar } from './UserAvatar'

export function AppShell() {
  const { user, logout } = useAuth()
  const navigate = useNavigate()
  const profileName = user ? displayName(user.nickname, user.email) : ''

  return (
    <Box sx={{ minHeight: '100vh', bgcolor: (theme) => (theme.palette.mode === 'dark' ? '#0f172a' : '#f1f5f9') }}>
      <AppBar position="sticky" color="inherit" elevation={0} sx={{ bgcolor: 'background.paper', border: '1px solid', borderColor: 'divider' }}>
        <Toolbar sx={{ gap: 1, flexWrap: 'wrap' }}>
          <Typography
            variant="h6"
            component={RouterLink}
            to="/chat"
            sx={{ flexGrow: 1, fontWeight: 700, textDecoration: 'none', color: 'text.primary', '&:hover': { opacity: 0.8 } }}
          >CampusLink</Typography>
          <Button variant="outlined" color="inherit" component={RouterLink} to="/lost-found" startIcon={<SearchIcon />} sx={{ borderColor: 'divider', color: 'text.secondary' }}>Browse</Button>
          <Button variant="outlined" color="inherit" component={RouterLink} to="/lost-found/new/lost" startIcon={<AddCircleOutlineIcon />} sx={{ borderColor: 'divider', color: 'text.secondary' }}>Report</Button>
          <Button variant="outlined" color="inherit" component={RouterLink} to="/claims/mine" startIcon={<InboxIcon />} sx={{ borderColor: 'divider', color: 'text.secondary' }}>Claims</Button>
          <Button variant="outlined" color="inherit" component={RouterLink} to="/facilities" startIcon={<MeetingRoomIcon />} sx={{ borderColor: 'divider', color: 'text.secondary' }}>Facilities</Button>
          <Button variant="outlined" color="inherit" component={RouterLink} to="/mail" startIcon={<MailOutlineIcon />} sx={{ borderColor: 'divider', color: 'text.secondary' }}>Mail</Button>
          <Button variant="outlined" color="inherit" component={RouterLink} to="/mail/calendar" startIcon={<CalendarMonthIcon />} sx={{ borderColor: 'divider', color: 'text.secondary' }}>Calendar</Button>
          <Button
            component={RouterLink}
            to="/lost-found/profile"
            sx={{ borderColor: 'divider', color: 'text.secondary', textTransform: 'none', px: 1, gap: 1 }}
            aria-label="Personal center"
          >
            <UserAvatar name={profileName} avatarUrl={user?.avatarUrl} size={28} />
            <Typography variant="body2" sx={{ display: { xs: 'none', md: 'block' }, color: 'text.secondary' }}>{profileName}</Typography>
          </Button>
          <Button
            color="inherit"
            variant="outlined"
            startIcon={<LogoutIcon />}
            sx={{ borderColor: 'divider', color: 'text.secondary' }}
            onClick={() => { logout(); navigate('/login') }}
          >Logout</Button>
        </Toolbar>
      </AppBar>
      <Container maxWidth="lg" sx={{ py: 4 }}><Outlet /></Container>
    </Box>
  )
}
