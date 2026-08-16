import { Box, Drawer, useMediaQuery, useTheme } from '@mui/material'
import { useState } from 'react'
import { Outlet } from 'react-router-dom'
import { AdminHeader } from './AdminHeader'
import { AdminSidebar } from './AdminSidebar'

const DRAWER_WIDTH = 256

export function AdminLayout() {
  const theme = useTheme()
  const isDesktop = useMediaQuery(theme.breakpoints.up('md'))
  const [mobileOpen, setMobileOpen] = useState(false)

  return (
    <Box sx={{ minHeight: '100vh', display: 'flex', bgcolor: (theme) => (theme.palette.mode === 'dark' ? '#0f172a' : 'grey.50') }}>
      {isDesktop ? (
        <Drawer
          variant="permanent"
          open
          sx={{
            width: DRAWER_WIDTH,
            flexShrink: 0,
            '& .MuiDrawer-paper': { width: DRAWER_WIDTH, boxSizing: 'border-box' },
          }}
        >
          <AdminSidebar />
        </Drawer>
      ) : (
        <Drawer
          variant="temporary"
          open={mobileOpen}
          onClose={() => setMobileOpen(false)}
          sx={{ '& .MuiDrawer-paper': { width: DRAWER_WIDTH, boxSizing: 'border-box' } }}
        >
          <AdminSidebar
            showCloseButton
            onClose={() => setMobileOpen(false)}
            onNavigate={() => setMobileOpen(false)}
          />
        </Drawer>
      )}
      <Box sx={{ minWidth: 0, flexGrow: 1 }}>
        <AdminHeader showMenuButton={!isDesktop} onMenuClick={() => setMobileOpen(true)} />
        <Box component="main" sx={{ p: { xs: 2, md: 4 } }}>
          <Outlet />
        </Box>
      </Box>
    </Box>
  )
}
