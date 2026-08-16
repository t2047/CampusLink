import { Box, CircularProgress } from '@mui/material'
import { lazy, Suspense } from 'react'
import { Navigate, Route, Routes } from 'react-router-dom'
import { AdminLayout } from './admin/layout/AdminLayout'
import { AdminRoute } from './auth/AdminRoute'
import { ProtectedRoute } from './auth/ProtectedRoute'
import { WorkspaceShell } from './components/WorkspaceShell'
import ChatProtectedRoute from './components/ProtectedRoute'
import ChatPage from './pages/ChatPage'

const AdminDashboardPage = lazy(() => import('./admin/dashboard/AdminDashboardPage').then((module) => ({ default: module.AdminDashboardPage })))
const FacilitiesDashboardPage = lazy(() => import('./admin/facilities/FacilitiesPage').then((module) => ({ default: module.FacilitiesDashboardPage })))
const ReservationsPage = lazy(() => import('./admin/facilities/FacilitiesPage').then((module) => ({ default: module.ReservationsPage })))
const ReservationDetailPage = lazy(() => import('./admin/facilities/FacilitiesPage').then((module) => ({ default: module.ReservationDetailPage })))
const FacilityReservationsPage = lazy(() => import('./admin/facilities/FacilitiesPage').then((module) => ({ default: module.FacilityReservationsPage })))
const MaintenancePage = lazy(() => import('./admin/facilities/FacilitiesPage').then((module) => ({ default: module.MaintenancePage })))
const MaintenanceDetailPage = lazy(() => import('./admin/facilities/FacilitiesPage').then((module) => ({ default: module.MaintenanceDetailPage })))
const AdminLostFoundPage = lazy(() => import('./admin/lostFound/AdminLostFoundPage').then((module) => ({ default: module.AdminLostFoundPage })))
const AdminClaimDetailPage = lazy(() => import('./admin/lostFound/AdminClaimDetailPage').then((module) => ({ default: module.AdminClaimDetailPage })))
const AdminNotFoundPage = lazy(() => import('./admin/shared/AdminNotFoundPage').then((module) => ({ default: module.AdminNotFoundPage })))
const AdminUserManagementPage = lazy(() => import('./admin/users/AdminUserManagementPage').then((module) => ({ default: module.AdminUserManagementPage })))
const AdminForbiddenPage = lazy(() => import('./admin/shared/AdminForbiddenPage').then((module) => ({ default: module.AdminForbiddenPage })))
const LoginPage = lazy(() => import('./pages/LoginPage'))
const CalendarPage = lazy(() => import('./pages/CalendarPage').then((module) => ({ default: module.CalendarPage })))
const ClaimsPage = lazy(() => import('./pages/ClaimsPage').then((module) => ({ default: module.ClaimsPage })))
const CreateReportPage = lazy(() => import('./pages/CreateReportPage').then((module) => ({ default: module.CreateReportPage })))
const FacilitiesLayout = lazy(() => import('./pages/facilities/FacilitiesLayout').then((module) => ({ default: module.FacilitiesLayout })))
const SpacesPage = lazy(() => import('./pages/facilities/SpacesPage').then((module) => ({ default: module.SpacesPage })))
const SpaceDetailsPage = lazy(() => import('./pages/facilities/SpaceDetailsPage').then((module) => ({ default: module.SpaceDetailsPage })))
const MyBookingsPage = lazy(() => import('./pages/facilities/MyBookingsPage').then((module) => ({ default: module.MyBookingsPage })))
const BookingDetailsPage = lazy(() => import('./pages/facilities/BookingDetailsPage').then((module) => ({ default: module.BookingDetailsPage })))
const SubmitMaintenancePage = lazy(() => import('./pages/facilities/SubmitMaintenancePage').then((module) => ({ default: module.SubmitMaintenancePage })))
const MyMaintenancePage = lazy(() => import('./pages/facilities/MyMaintenancePage').then((module) => ({ default: module.MyMaintenancePage })))
const MaintenanceDetailsPage = lazy(() => import('./pages/facilities/MaintenanceDetailsPage').then((module) => ({ default: module.MaintenanceDetailsPage })))
const MailPage = lazy(() => import('./pages/MailPage').then((module) => ({ default: module.MailPage })))
const ProfilePage = lazy(() => import('./pages/ProfilePage').then((module) => ({ default: module.ProfilePage })))
const SettingsPage = lazy(() => import('./pages/SettingsPage').then((module) => ({ default: module.SettingsPage })))
const MyReportsPage = lazy(() => import('./pages/MyReportsPage').then((module) => ({ default: module.MyReportsPage })))
const LostFoundFaqPage = lazy(() => import('./pages/LostFoundFaqPage').then((module) => ({ default: module.LostFoundFaqPage })))
const ReportDetailPage = lazy(() => import('./pages/ReportDetailPage').then((module) => ({ default: module.ReportDetailPage })))
const ReportsPage = lazy(() => import('./pages/ReportsPage').then((module) => ({ default: module.ReportsPage })))
const NotFoundPage = lazy(() => import('./pages/NotFoundPage').then((module) => ({ default: module.NotFoundPage })))

/**
 * 融合路由（Sprint 4 merge 决议，2026-08-09）：
 * - 认证统一：组员 AuthProvider（sessionStorage）+ /login /register LoginPage（Tailwind）
 * - Chat：/chat（我们的聊天入口，登录后可用；根路径重定向到 /chat）
 * - Admin：/admin/*（组员，AdminRoute 角色保护）
 * - Lost & Found：/lost-found/* /claims/*（组员业务页）
 */
export default function App() {
  return (
    <Suspense fallback={<Box sx={{ minHeight: '60vh', display: 'grid', placeItems: 'center' }}><CircularProgress /></Box>}>
      <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/register" element={<LoginPage />} />
      <Route path="/forbidden" element={<AdminForbiddenPage />} />
      <Route element={<AdminRoute />}>
        <Route element={<AdminLayout />}>
          <Route path="/admin" element={<Navigate to="/admin/dashboard" replace />} />
          <Route path="/admin/dashboard" element={<AdminDashboardPage />} />
          <Route path="/admin/lost-found" element={<AdminLostFoundPage />} />
          <Route path="/admin/lost-found/claims/:claimId" element={<AdminClaimDetailPage />} />
          <Route path="/admin/facilities" element={<FacilitiesDashboardPage />} />
          <Route path="/admin/facilities/reservations" element={<ReservationsPage />} />
          <Route path="/admin/facilities/reservations/:id" element={<ReservationDetailPage />} />
          <Route path="/admin/facilities/spaces/:spaceId/reservations" element={<FacilityReservationsPage />} />
          <Route path="/admin/facilities/maintenance" element={<MaintenancePage />} />
          <Route path="/admin/facilities/maintenance/:id" element={<MaintenanceDetailPage />} />
          <Route path="/admin/users" element={<AdminUserManagementPage />} />
          <Route path="/admin/*" element={<AdminNotFoundPage />} />
        </Route>
      </Route>
      <Route path="/" element={<Navigate to="/chat" replace />} />
      <Route element={<ProtectedRoute />}>
        <Route element={<WorkspaceShell />}>
          <Route path="/chat" element={<ChatProtectedRoute><ChatPage /></ChatProtectedRoute>} />
          <Route path="/facilities" element={<FacilitiesLayout />}>
            <Route index element={<SpacesPage />} />
            <Route path="spaces/:spaceId" element={<SpaceDetailsPage />} />
            <Route path="bookings" element={<MyBookingsPage />} />
            <Route path="bookings/:bookingId" element={<BookingDetailsPage />} />
            <Route path="maintenance" element={<MyMaintenancePage />} />
            <Route path="maintenance/new" element={<SubmitMaintenancePage />} />
            <Route path="maintenance/:requestId" element={<MaintenanceDetailsPage />} />
          </Route>
          <Route path="/lost-found" element={<ReportsPage />} />
          <Route path="/lost-found/new/lost" element={<CreateReportPage reportType="LOST" />} />
          <Route path="/lost-found/new/found" element={<CreateReportPage reportType="FOUND" />} />
          <Route path="/lost-found/profile" element={<ProfilePage />} />
          <Route path="/lost-found/profile/lost" element={<MyReportsPage reportType="LOST" />} />
          <Route path="/lost-found/profile/found" element={<MyReportsPage reportType="FOUND" />} />
          <Route path="/lost-found/faq" element={<LostFoundFaqPage />} />
          <Route path="/lost-found/:reportId" element={<ReportDetailPage />} />
          <Route path="/claims/mine" element={<ClaimsPage view="mine" />} />
          <Route path="/claims/received" element={<ClaimsPage view="received" />} />
          <Route path="/mail" element={<MailPage />} />
          <Route path="/mail/calendar" element={<CalendarPage />} />
          <Route path="/settings" element={<SettingsPage />} />
        </Route>
      </Route>
      <Route path="*" element={<NotFoundPage />} />
      </Routes>
    </Suspense>
  )
}
