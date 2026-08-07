import { Navigate, Outlet, useLocation } from 'react-router-dom'
import { useAuth } from './AuthContext'

const ADMIN_ROLES = new Set(['ADMIN', 'SUPER_ADMIN'])

export function AdminRoute() {
  const { user } = useAuth()
  const location = useLocation()

  if (!user) return <Navigate to="/login" replace state={{ from: location }} />
  if (!ADMIN_ROLES.has(user.role)) return <Navigate to="/forbidden" replace />
  return <Outlet />
}
