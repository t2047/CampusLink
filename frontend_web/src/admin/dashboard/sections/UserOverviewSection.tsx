import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  Chip,
  CircularProgress,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Typography,
} from '@mui/material'
import { Link as RouterLink } from 'react-router-dom'
import { useCallback, useEffect, useRef, useState } from 'react'
import { listAdminUsers, type AdminUser, type UserRole } from '../../../api/adminUsers'
import { apiErrorMessage } from '../../../api/client'

const ROLES: UserRole[] = ['STUDENT', 'ADMIN', 'SUPER_ADMIN']

const ROLE_LABELS: Record<UserRole, string> = {
  STUDENT: 'Student',
  ADMIN: 'Administrator',
  SUPER_ADMIN: 'Super Administrator',
}

const ROLE_COLORS: Record<UserRole, string> = {
  STUDENT: 'primary.main',
  ADMIN: 'warning.main',
  SUPER_ADMIN: 'secondary.main',
}

function roleLabel(role: UserRole) {
  return ROLE_LABELS[role]
}

interface MetricCardProps {
  label: string
  value: number
}

function MetricCard({ label, value }: MetricCardProps) {
  return (
    <Card variant="outlined" role="group" aria-label={label} sx={{ height: '100%' }}>
      <CardContent>
        <Stack spacing={1}>
          <Typography color="text.secondary">{label}</Typography>
          <Typography variant="h4" fontWeight={700}>{value}</Typography>
        </Stack>
      </CardContent>
    </Card>
  )
}

interface RoleDistributionChartProps {
  counts: Record<UserRole, number>
}

function RoleDistributionChart({ counts }: RoleDistributionChartProps) {
  const maxCount = Math.max(0, ...ROLES.map((role) => counts[role]))

  return (
    <Card component="section" variant="outlined" aria-labelledby="user-role-distribution-heading">
      <CardContent>
        <Stack spacing={3}>
          <Typography id="user-role-distribution-heading" component="h3" variant="h6" fontWeight={700}>
            User Role Distribution
          </Typography>
          <Stack spacing={2.5}>
            {ROLES.map((role) => {
              const label = roleLabel(role)
              const count = counts[role]
              const width = maxCount === 0 ? 0 : (count / maxCount) * 100

              return (
                <Box key={role} role="group" aria-label={`${label}: ${count}`}>
                  <Stack direction="row" justifyContent="space-between" spacing={2} sx={{ mb: 0.75 }}>
                    <Typography fontWeight={600}>{label}</Typography>
                    <Typography aria-label={`${label} count`}>{count}</Typography>
                  </Stack>
                  <Box sx={{ height: 12, borderRadius: 999, bgcolor: 'action.hover', overflow: 'hidden' }}>
                    <Box
                      role="img"
                      aria-label={`${label} users: ${count}`}
                      sx={{
                        height: '100%',
                        width: `${width}%`,
                        minWidth: count > 0 ? 4 : 0,
                        borderRadius: 999,
                        bgcolor: ROLE_COLORS[role],
                      }}
                    />
                  </Box>
                </Box>
              )
            })}
          </Stack>
        </Stack>
      </CardContent>
    </Card>
  )
}

interface UserAccountsTableProps {
  users: AdminUser[]
}

function UserAccountsTable({ users }: UserAccountsTableProps) {
  const displayedUsers = [...users].sort((left, right) => right.id - left.id).slice(0, 5)

  return (
    <Card component="section" variant="outlined" aria-labelledby="user-accounts-heading">
      <CardContent>
        <Stack spacing={2}>
          <Stack direction={{ xs: 'column', sm: 'row' }} justifyContent="space-between" alignItems={{ sm: 'center' }} spacing={2}>
            <Typography id="user-accounts-heading" component="h3" variant="h6" fontWeight={700}>
              User Accounts
            </Typography>
            <Button component={RouterLink} to="/admin/users" variant="outlined">View All</Button>
          </Stack>

          {displayedUsers.length === 0 ? (
            <Alert severity="info">No user accounts are currently available.</Alert>
          ) : (
            <TableContainer sx={{ overflowX: 'auto' }}>
              <Table size="small" aria-label="User accounts overview">
                <TableHead>
                  <TableRow>
                    <TableCell>User ID</TableCell>
                    <TableCell>Email</TableCell>
                    <TableCell>Role</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {displayedUsers.map((user) => (
                    <TableRow key={user.id}>
                      <TableCell>{user.id}</TableCell>
                      <TableCell>{user.email}</TableCell>
                      <TableCell>
                        <Chip label={roleLabel(user.role)} aria-label={`${roleLabel(user.role)} role`} size="small" variant="outlined" />
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </TableContainer>
          )}
        </Stack>
      </CardContent>
    </Card>
  )
}

export function UserOverviewSection() {
  const [users, setUsers] = useState<AdminUser[] | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const mountedRef = useRef(false)
  const requestInFlightRef = useRef(false)

  const loadUsers = useCallback(async () => {
    if (requestInFlightRef.current) return

    requestInFlightRef.current = true
    if (mountedRef.current) {
      setLoading(true)
      setError('')
      setUsers(null)
    }

    try {
      const data = await listAdminUsers()
      if (mountedRef.current) {
        setUsers(data)
        setError('')
      }
    } catch (requestError) {
      if (mountedRef.current) {
        setUsers(null)
        setError(apiErrorMessage(requestError))
      }
    } finally {
      requestInFlightRef.current = false
      if (mountedRef.current) setLoading(false)
    }
  }, [])

  useEffect(() => {
    mountedRef.current = true
    void loadUsers()

    return () => {
      mountedRef.current = false
    }
  }, [loadUsers])

  const counts: Record<UserRole, number> = {
    STUDENT: users?.filter(({ role }) => role === 'STUDENT').length ?? 0,
    ADMIN: users?.filter(({ role }) => role === 'ADMIN').length ?? 0,
    SUPER_ADMIN: users?.filter(({ role }) => role === 'SUPER_ADMIN').length ?? 0,
  }

  return (
    <Box component="section" aria-labelledby="user-overview-heading" sx={{ display: 'grid', gap: 3 }}>
      <Typography id="user-overview-heading" component="h2" variant="h5" fontWeight={700}>
        User Management Overview
      </Typography>

      {loading && (
        <Stack role="status" direction="row" spacing={2} alignItems="center">
          <CircularProgress size={24} aria-label="Loading User Management overview" />
          <Typography>Loading User Management overview</Typography>
        </Stack>
      )}

      {!loading && error && (
        <Alert
          severity="error"
          action={<Button color="inherit" onClick={() => void loadUsers()}>Retry</Button>}
        >
          {error}
        </Alert>
      )}

      {!loading && users && (
        <>
          <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: { xs: '1fr 1fr', lg: 'repeat(4, 1fr)' } }}>
            <MetricCard label="Total Users" value={users.length} />
            <MetricCard label="Students" value={counts.STUDENT} />
            <MetricCard label="Administrators" value={counts.ADMIN} />
            <MetricCard label="Super Administrators" value={counts.SUPER_ADMIN} />
          </Box>

          <Box sx={{ display: 'grid', gap: 3, gridTemplateColumns: { xs: '1fr', lg: 'minmax(280px, 0.8fr) minmax(0, 1.2fr)' } }}>
            <RoleDistributionChart counts={counts} />
            <UserAccountsTable users={users} />
          </Box>
        </>
      )}
    </Box>
  )
}
