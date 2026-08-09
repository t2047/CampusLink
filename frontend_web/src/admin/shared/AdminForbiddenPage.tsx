import { Box, Button, Card, CardContent, Stack, Typography } from '@mui/material'
import { useNavigate } from 'react-router-dom'
import { useAuth } from '../../auth/AuthContext'

export function AdminForbiddenPage() {
  const { user, logout } = useAuth()
  const navigate = useNavigate()

  return (
    <Box sx={{ minHeight: '100vh', bgcolor: 'grey.100', display: 'grid', placeItems: 'center', p: 2 }}>
      <Card sx={{ width: '100%', maxWidth: 520 }}>
        <CardContent sx={{ p: 4 }}>
          <Stack spacing={3}>
            <Box>
              <Typography component="h1" variant="h4" fontWeight={700} gutterBottom>Access Denied</Typography>
              <Typography color="text.secondary">You do not have permission to access this page.</Typography>
            </Box>
            <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
              <Button variant="contained" onClick={() => navigate('/lost-found')}>Return to CampusLink</Button>
              {user && (
                <Button
                  variant="outlined"
                  onClick={() => { logout(); navigate('/login') }}
                >
                  Sign Out
                </Button>
              )}
            </Stack>
          </Stack>
        </CardContent>
      </Card>
    </Box>
  )
}
