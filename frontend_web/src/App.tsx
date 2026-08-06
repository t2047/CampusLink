import { Box, CircularProgress } from '@mui/material'
import { lazy, Suspense } from 'react'
import { Navigate, Route, Routes } from 'react-router-dom'
import { ProtectedRoute } from './auth/ProtectedRoute'
import { AppShell } from './components/AppShell'

const AuthPage = lazy(() => import('./pages/AuthPage').then((module) => ({ default: module.AuthPage })))
const ClaimsPage = lazy(() => import('./pages/ClaimsPage').then((module) => ({ default: module.ClaimsPage })))
const CreateReportPage = lazy(() => import('./pages/CreateReportPage').then((module) => ({ default: module.CreateReportPage })))
const ReportDetailPage = lazy(() => import('./pages/ReportDetailPage').then((module) => ({ default: module.ReportDetailPage })))
const ReportsPage = lazy(() => import('./pages/ReportsPage').then((module) => ({ default: module.ReportsPage })))

export default function App() {
  return (
    <Suspense fallback={<Box sx={{ minHeight: '60vh', display: 'grid', placeItems: 'center' }}><CircularProgress /></Box>}>
      <Routes>
      <Route path="/login" element={<AuthPage mode="login" />} />
      <Route path="/register" element={<AuthPage mode="register" />} />
      <Route element={<ProtectedRoute />}>
        <Route element={<AppShell />}>
          <Route path="/lost-found" element={<ReportsPage />} />
          <Route path="/lost-found/new/lost" element={<CreateReportPage reportType="LOST" />} />
          <Route path="/lost-found/new/found" element={<CreateReportPage reportType="FOUND" />} />
          <Route path="/lost-found/:reportId" element={<ReportDetailPage />} />
          <Route path="/claims/mine" element={<ClaimsPage view="mine" />} />
          <Route path="/claims/received" element={<ClaimsPage view="received" />} />
        </Route>
      </Route>
      <Route path="*" element={<Navigate to="/lost-found" replace />} />
      </Routes>
    </Suspense>
  )
}
