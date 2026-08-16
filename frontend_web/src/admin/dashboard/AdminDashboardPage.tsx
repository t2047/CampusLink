import { Box, Button, Card, CardActionArea, CardContent, Chip, Divider, Stack, Typography } from '@mui/material'
import { useState, type ReactNode } from 'react'
import { Link as RouterLink } from 'react-router-dom'
import { useEffect, useState } from 'react'
import { useAuth } from '../../auth/AuthContext'
import { DashboardReportDialog } from './reports/DashboardReportDialog'
import { FacilitiesOverviewSection } from './sections/FacilitiesOverviewSection'
import { LostFoundOverviewSection } from './sections/LostFoundOverviewSection'
import { OpenMaintenanceSection } from './sections/OpenMaintenanceSection'
import { PendingClaimsSection } from './sections/PendingClaimsSection'
import { RecentAuditActivitySection } from './sections/RecentAuditActivitySection'
import { RecentLostFoundReportsSection } from './sections/RecentLostFoundReportsSection'
import { UpcomingReservationsSection } from './sections/UpcomingReservationsSection'
import { UserOverviewSection } from './sections/UserOverviewSection'

function FacilityMetric({ label, value }: { label: string; value: number }) {
  return <Card variant="outlined"><CardContent><Stack spacing={1}><Typography color="text.secondary">{label}</Typography><Typography variant="h4" fontWeight={700}>{value}</Typography></Stack></CardContent></Card>
}

function AdminModuleCard({ title, description, path, available = false }: AdminModuleCardProps) {
  return (
    <Card variant="outlined" sx={{ height: '100%' }}>
      <CardActionArea
        component={RouterLink}
        to={path}
        aria-label={title}
        sx={{ height: '100%', alignItems: 'stretch' }}
      >
        <CardContent>
          <Stack spacing={2} alignItems="flex-start">
            <Typography component="h3" variant="h6" fontWeight={700}>{title}</Typography>
            <Typography color="text.secondary">{description}</Typography>
            <Chip
              label={available ? 'Available' : 'Coming Soon'}
              size="small"
              color={available ? 'success' : 'default'}
            />
          </Stack>
        </CardContent>
      </CardActionArea>
    </Card>
  )
}

function DashboardGroup({ id, title, children }: { id: string; title: string; children: ReactNode }) {
  const headingId = `${id}-heading`
  return (
    <Box component="section" role="region" aria-labelledby={headingId} sx={{ display: 'grid', gap: 3 }}>
      <Stack spacing={1}>
        <Typography id={headingId} component="h2" variant="h5" fontWeight={700}>{title}</Typography>
        <Divider />
      </Stack>
      {children}
    </Box>
  )
}

export function AdminDashboardPage() {
  const { user } = useAuth()
  const [reportOpen, setReportOpen] = useState(false)

  return (
    <Box sx={{ display: 'grid', gap: 5 }}>
      <Card>
        <CardContent>
          <Stack spacing={2}>
            <Stack
              direction={{ xs: 'column', sm: 'row' }}
              justifyContent="space-between"
              alignItems={{ sm: 'center' }}
              spacing={2}
            >
              <Typography component="h1" variant="h4" fontWeight={700}>Dashboard Overview</Typography>
              <Button variant="contained" onClick={() => setReportOpen(true)}>Generate Usage Report</Button>
            </Stack>
            <Typography variant="h6">Welcome to CampusLink Administration.</Typography>
            <Typography color="text.secondary">Signed in as {user?.email ?? 'Unknown user'}</Typography>
            <Typography color="text.secondary">Role: {user?.role ?? 'Unknown role'}</Typography>
          </Stack>
        </CardContent>
      </Card>

      <DashboardGroup id="system-summary" title="System Summary">
        <Box sx={{ display: 'grid', gap: 3, gridTemplateColumns: { xs: '1fr', md: 'repeat(3, minmax(0, 1fr))' } }}>
          <AdminModuleCard
            title="Lost & Found"
            description="Review report volume, status metrics, and operational records."
            path="/admin/lost-found"
            available
          />
          <AdminModuleCard
            title="Facilities"
            description="Monitor campus facilities, reservations, availability, and maintenance requests."
            path="/admin/facilities"
            available
          />
          <AdminModuleCard
            title="User Management"
            description="View system users, monitor role distribution, and manage account roles where authorized."
            path="/admin/users"
            available
          />
        </Box>
      </DashboardGroup>

      <DashboardGroup id="user-management" title="User Management">
        <UserOverviewSection />
      </DashboardGroup>

      <DashboardGroup id="facilities" title="Facilities">
        <FacilitiesOverviewSection />
        <UpcomingReservationsSection />
        <OpenMaintenanceSection />
      </DashboardGroup>

      <DashboardGroup id="lost-found" title="Lost & Found">
        <LostFoundOverviewSection />
        <PendingClaimsSection />
        <RecentLostFoundReportsSection />
      </DashboardGroup>

      <DashboardGroup id="administration" title="Administration">
        <RecentAuditActivitySection />
      </DashboardGroup>

      <DashboardReportDialog
        open={reportOpen}
        generatedBy={user?.email ?? 'Unknown administrator'}
        onClose={() => setReportOpen(false)}
      />
    </Box>
  )
}
