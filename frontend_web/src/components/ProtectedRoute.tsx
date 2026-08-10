// ──────────────────────────────────────────────
//  ProtectedRoute — 未登录访问受保护页时重定向到 /login
//  登录态统一来自 AuthContext（组员认证体系，sessionStorage）
// ──────────────────────────────────────────────

import type { ReactNode } from 'react';
import { Navigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';

interface ProtectedRouteProps {
  children: ReactNode;
}

export default function ProtectedRoute({ children }: ProtectedRouteProps) {
  const { user } = useAuth();
  if (!user) {
    return <Navigate to="/login" replace />;
  }
  return <>{children}</>;
}
