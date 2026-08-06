import CloseIcon from '@mui/icons-material/Close'
import { Box, Divider, IconButton, List, ListItemButton, ListItemIcon, ListItemText, Toolbar, Typography } from '@mui/material'
import { Link as RouterLink, useLocation } from 'react-router-dom'
import { adminNavigation } from '../config/adminNavigation'

function isNavigationItemSelected(currentPath: string, itemPath: string) {
  if (currentPath === itemPath) return true
  if (itemPath === '/admin/dashboard') return false
  return currentPath.startsWith(`${itemPath}/`)
}

interface AdminSidebarProps {
  onClose?: () => void
  onNavigate?: () => void
  showCloseButton?: boolean
}

export function AdminSidebar({ onClose, onNavigate, showCloseButton = false }: AdminSidebarProps) {
  const location = useLocation()

  return (
    <Box component="nav" aria-label="Administration" sx={{ width: 256 }}>
      <Toolbar sx={{ justifyContent: 'space-between' }}>
        <Typography variant="subtitle1" fontWeight={700}>Administration</Typography>
        {showCloseButton && (
          <IconButton aria-label="Close administration menu" onClick={onClose}>
            <CloseIcon />
          </IconButton>
        )}
      </Toolbar>
      <Divider />
      <List>
        {adminNavigation.map((item) => {
          const selected = isNavigationItemSelected(location.pathname, item.path)
          const Icon = item.icon
          return (
            <ListItemButton
              key={item.path}
              component={RouterLink}
              to={item.path}
              selected={selected}
              aria-current={selected ? 'page' : undefined}
              onClick={onNavigate}
            >
              <ListItemIcon><Icon /></ListItemIcon>
              <ListItemText primary={item.label} />
            </ListItemButton>
          )
        })}
      </List>
    </Box>
  )
}
