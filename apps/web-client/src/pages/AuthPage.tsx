import { Alert, Box, Button, Card, CardContent, CircularProgress, Link, Stack, TextField, Typography } from '@mui/material'
import { FormEvent, useState } from 'react'
import { Link as RouterLink, Navigate, useLocation, useNavigate } from 'react-router-dom'
import { apiErrorMessage } from '../api/client'
import { useAuth } from '../auth/AuthContext'

export function AuthPage({ mode }: { mode: 'login' | 'register' }) {
  const { user, login, register } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  if (user) return <Navigate to="/lost-found" replace />

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    if (mode === 'register' && password !== confirmPassword) {
      setError('Passwords do not match.')
      return
    }
    if (mode === 'register' && password.length < 6) {
      setError('Password must be at least 6 characters.')
      return
    }
    setLoading(true)
    setError('')
    try {
      await (mode === 'login' ? login(email, password) : register(email, password))
      const destination = (location.state as { from?: { pathname?: string } } | null)?.from?.pathname
      navigate(destination ?? '/lost-found', { replace: true })
    } catch (requestError) {
      setError(apiErrorMessage(requestError))
    } finally {
      setLoading(false)
    }
  }

  const isRegister = mode === 'register'
  return (
    <Box sx={{ minHeight: '100vh', bgcolor: 'grey.100', display: 'grid', placeItems: 'center', p: 2 }}>
      <Card sx={{ width: '100%', maxWidth: 440 }}>
        <CardContent sx={{ p: 4 }}>
          <Typography variant="h4" fontWeight={700} gutterBottom>CampusLink</Typography>
          <Typography variant="h6" gutterBottom>{isRegister ? 'Create an account' : 'Welcome back'}</Typography>
          <Typography color="text.secondary" sx={{ mb: 3 }}>Sign in to use the campus Lost & Found service.</Typography>
          <Stack component="form" spacing={2} onSubmit={handleSubmit}>
            {error && <Alert severity="error">{error}</Alert>}
            <TextField label="Email" type="email" required autoComplete="email" value={email} onChange={(e) => setEmail(e.target.value)} />
            <TextField label="Password" type="password" required autoComplete={isRegister ? 'new-password' : 'current-password'} value={password} onChange={(e) => setPassword(e.target.value)} />
            {isRegister && <TextField label="Confirm password" type="password" required autoComplete="new-password" value={confirmPassword} onChange={(e) => setConfirmPassword(e.target.value)} />}
            <Button type="submit" size="large" variant="contained" disabled={loading}>
              {loading ? <CircularProgress size={24} /> : isRegister ? 'Register' : 'Login'}
            </Button>
            <Typography variant="body2" textAlign="center">
              {isRegister ? 'Already have an account? ' : 'New to CampusLink? '}
              <Link component={RouterLink} to={isRegister ? '/login' : '/register'}>{isRegister ? 'Login' : 'Register'}</Link>
            </Typography>
          </Stack>
        </CardContent>
      </Card>
    </Box>
  )
}
