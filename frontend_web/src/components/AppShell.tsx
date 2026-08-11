import AddCircleOutlineIcon from '@mui/icons-material/AddCircleOutline'
import InboxIcon from '@mui/icons-material/Inbox'
import LogoutIcon from '@mui/icons-material/Logout'
import MailOutlineIcon from '@mui/icons-material/MailOutline'
import MeetingRoomIcon from '@mui/icons-material/MeetingRoom'
import SearchIcon from '@mui/icons-material/Search'
import { AppBar, Box, Button, Container, Toolbar, Typography } from '@mui/material'
import { Link as RouterLink, Outlet, useNavigate } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'

export function AppShell() {
  const { user, logout } = useAuth()
  const navigate = useNavigate()

  return (
    <Box sx={{ minHeight: '100vh', bgcolor: 'grey.50' }}>
      <AppBar position="sticky">
        <Toolbar sx={{ gap: 1, flexWrap: 'wrap' }}>
          <Typography
            variant="h6"
            component={RouterLink}
            to="/chat"
            sx={{ flexGrow: 1, fontWeight: 700, textDecoration: 'none', color: 'inherit', '&:hover': { opacity: 0.8 } }}
          >CampusLink</Typography>
          <Button color="inherit" component={RouterLink} to="/facilities" startIcon={<MeetingRoomIcon />}>Facilities</Button>
          <Button color="inherit" component={RouterLink} to="/lost-found" startIcon={<SearchIcon />}>Browse</Button>
          <Button color="inherit" component={RouterLink} to="/lost-found/new/lost" startIcon={<AddCircleOutlineIcon />}>Report</Button>
          <Button color="inherit" component={RouterLink} to="/claims/mine" startIcon={<InboxIcon />}>Claims</Button>
          <Button color="inherit" component={RouterLink} to="/mail" startIcon={<MailOutlineIcon />}>Mail</Button>
          <Typography variant="body2" sx={{ display: { xs: 'none', md: 'block' }, mx: 1 }}>{user?.email}</Typography>
          <Button
            color="inherit"
            startIcon={<LogoutIcon />}
            onClick={() => { logout(); navigate('/login') }}
          >Logout</Button>
        </Toolbar>
      </AppBar>
      <Container maxWidth="lg" sx={{ py: 4 }}><Outlet /></Container>
    </Box>
  )
}
