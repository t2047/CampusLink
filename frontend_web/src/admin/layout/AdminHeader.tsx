import LogoutIcon from '@mui/icons-material/Logout'
import MenuIcon from '@mui/icons-material/Menu'
import { AppBar, Button, IconButton, Stack, Toolbar, Typography } from '@mui/material'
import { useNavigate } from 'react-router-dom'
import { useAuth } from '../../auth/AuthContext'

interface AdminHeaderProps {
  onMenuClick: () => void
  showMenuButton: boolean
}

export function AdminHeader({ onMenuClick, showMenuButton }: AdminHeaderProps) {
  const { user, logout } = useAuth()
  const navigate = useNavigate()

  return (
    <AppBar position="sticky">
      <Toolbar sx={{ gap: 2, flexWrap: 'wrap' }}>
        {showMenuButton && (
          <IconButton color="inherit" aria-label="Open administration menu" onClick={onMenuClick}>
            <MenuIcon />
          </IconButton>
        )}
        <Typography component="div" variant="h6" fontWeight={700} sx={{ flexGrow: 1 }}>
          CampusLink Administration
        </Typography>
        <Stack direction="row" spacing={1} alignItems="center">
          <Typography variant="body2">{user?.email ?? 'Unknown user'}</Typography>
          <Typography variant="body2" fontWeight={700}>{user?.role ?? 'Unknown role'}</Typography>
        </Stack>
        <Button
          color="inherit"
          startIcon={<LogoutIcon />}
          onClick={() => { logout(); navigate('/login') }}
        >
          Sign Out
        </Button>
      </Toolbar>
    </AppBar>
  )
}
