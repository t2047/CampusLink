import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  CircularProgress,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Divider,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Typography,
} from '@mui/material'
import { useCallback, useEffect, useRef, useState } from 'react'
import { apiErrorMessage } from '../../../api/client'
import { formatDashboardInstant } from '../dashboardDateTime'
import { dashboardAuditActionLabels } from '../../../labels'
import { loadDashboardReportSnapshot } from './dashboardReportData'
import type { DashboardReportSnapshot } from './dashboardReportTypes'

interface DashboardReportDialogProps {
  open: boolean
  generatedBy: string
  onClose: () => void
}

function formatReportDate(value: string) {
  const match = value.match(/^(\d{4})-(\d{2})-(\d{2})$/)
  if (!match) return value
  return new Intl.DateTimeFormat('en-SG', {
    day: 'numeric',
    month: 'short',
    year: 'numeric',
    timeZone: 'UTC',
  }).format(new Date(Date.UTC(Number(match[1]), Number(match[2]) - 1, Number(match[3]))))
}

function MetricCard({ label, value, detail }: { label: string; value: number; detail?: string }) {
  return (
    <Card variant="outlined" role="group" aria-label={label} sx={{ height: '100%' }}>
      <CardContent>
        <Typography color="text.secondary" variant="body2">{label}</Typography>
        <Typography variant="h4" fontWeight={700} sx={{ my: 1 }}>{value}</Typography>
        {detail && <Typography color="text.secondary" variant="body2">{detail}</Typography>}
      </CardContent>
    </Card>
  )
}

function DistributionTable({
  title,
  rows,
}: {
  title: string
  rows: { label: string; count: number }[]
}) {
  return (
    <Card variant="outlined">
      <CardContent>
        <Typography component="h3" variant="h6" fontWeight={700} sx={{ mb: 2 }}>{title}</Typography>
        <TableContainer>
          <Table size="small" aria-label={title}>
            <TableBody>
              {rows.map((row) => (
                <TableRow key={row.label}>
                  <TableCell>{row.label}</TableCell>
                  <TableCell align="right">{row.count}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      </CardContent>
    </Card>
  )
}

function UserManagementSection({ snapshot }: { snapshot: DashboardReportSnapshot }) {
  const users = snapshot.userManagement
  return (
    <Box component="section" aria-labelledby="usage-report-users-heading" sx={{ display: 'grid', gap: 2 }}>
      <Typography id="usage-report-users-heading" component="h2" variant="h5" fontWeight={700}>User Management</Typography>
      <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: { xs: '1fr 1fr', lg: 'repeat(3, 1fr)' } }}>
        <MetricCard label="Total Users" value={users.totalUsers} detail="Current registered accounts" />
        <MetricCard label="Administrators" value={users.administratorCount} detail="Current ADMIN accounts" />
        <MetricCard label="Super Administrators" value={users.superAdministratorCount} detail="Current SUPER_ADMIN accounts" />
      </Box>
      <DistributionTable
        title="Current Role Distribution"
        rows={[
          { label: 'Students', count: users.roleDistribution.STUDENT },
          { label: 'Administrators', count: users.roleDistribution.ADMIN },
          { label: 'Super Administrators', count: users.roleDistribution.SUPER_ADMIN },
        ]}
      />
      {users.unavailableMetrics.map((message) => <Alert key={message} severity="info">{message}</Alert>)}
    </Box>
  )
}

function FacilitiesSection({ snapshot }: { snapshot: DashboardReportSnapshot }) {
  const facilities = snapshot.facilities
  return (
    <Box component="section" aria-labelledby="usage-report-facilities-heading" sx={{ display: 'grid', gap: 2 }}>
      <Typography id="usage-report-facilities-heading" component="h2" variant="h5" fontWeight={700}>Facilities</Typography>
      <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: { xs: '1fr 1fr', lg: 'repeat(3, 1fr)' } }}>
        <MetricCard label="Bookings in Period" value={facilities.bookingsInPeriod} />
        <MetricCard label="Maintenance Requests in Period" value={facilities.maintenanceRequestsInPeriod} />
        <MetricCard label="Current Unresolved Maintenance Requests" value={facilities.unresolvedMaintenanceRequests} />
      </Box>
      <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: { xs: '1fr', md: '1fr 1fr' } }}>
        <DistributionTable
          title="Booking Status Distribution"
          rows={[
            { label: 'Confirmed', count: facilities.bookingStatusDistribution.CONFIRMED },
            { label: 'Completed', count: facilities.bookingStatusDistribution.COMPLETED },
            { label: 'Cancelled', count: facilities.bookingStatusDistribution.CANCELLED },
          ]}
        />
        <DistributionTable
          title="Maintenance Status Distribution"
          rows={[
            { label: 'Submitted', count: facilities.maintenanceStatusDistribution.SUBMITTED },
            { label: 'In Progress', count: facilities.maintenanceStatusDistribution.IN_PROGRESS },
            { label: 'Resolved', count: facilities.maintenanceStatusDistribution.RESOLVED },
            { label: 'Cancelled', count: facilities.maintenanceStatusDistribution.CANCELLED },
          ]}
        />
      </Box>
      <Card variant="outlined">
        <CardContent>
          <Typography component="h3" variant="h6" fontWeight={700} sx={{ mb: 2 }}>Most Used Facilities</Typography>
          {facilities.topFacilities.length === 0 ? (
            <Alert severity="info">No active facility reservations were recorded in this reporting period.</Alert>
          ) : (
            <TableContainer>
              <Table size="small" aria-label="Most used facilities">
                <TableHead>
                  <TableRow><TableCell>Facility</TableCell><TableCell align="right">Reservations</TableCell></TableRow>
                </TableHead>
                <TableBody>
                  {facilities.topFacilities.map((facility) => (
                    <TableRow key={facility.facilityId}>
                      <TableCell>{facility.facilityName}</TableCell>
                      <TableCell align="right">{facility.reservationCount}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </TableContainer>
          )}
        </CardContent>
      </Card>
    </Box>
  )
}

function LostFoundSection({ snapshot }: { snapshot: DashboardReportSnapshot }) {
  const lostFound = snapshot.lostFound
  return (
    <Box component="section" aria-labelledby="usage-report-lost-found-heading" sx={{ display: 'grid', gap: 2 }}>
      <Typography id="usage-report-lost-found-heading" component="h2" variant="h5" fontWeight={700}>Lost &amp; Found</Typography>
      <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: { xs: '1fr 1fr', lg: 'repeat(3, 1fr)' } }}>
        <MetricCard label="Lost & Found Reports in Period" value={lostFound.reportsInPeriod} />
        <MetricCard label="Current Pending Claims" value={lostFound.pendingClaims} />
        <MetricCard label="Current Processed Claims" value={lostFound.processedClaims} />
      </Box>
      <Alert severity="info">{lostFound.claimMetricNote}</Alert>
      <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: { xs: '1fr', md: '1fr 1fr' } }}>
        <DistributionTable
          title="Lost / Found Type Distribution"
          rows={[
            { label: 'Lost', count: lostFound.reportTypeDistribution.LOST },
            { label: 'Found', count: lostFound.reportTypeDistribution.FOUND },
          ]}
        />
        <DistributionTable
          title="Report Status Distribution"
          rows={[
            { label: 'Open', count: lostFound.reportStatusDistribution.OPEN },
            { label: 'Claimed', count: lostFound.reportStatusDistribution.CLAIMED },
            { label: 'Closed', count: lostFound.reportStatusDistribution.CLOSED },
          ]}
        />
      </Box>
    </Box>
  )
}

function AdministrationSection({ snapshot }: { snapshot: DashboardReportSnapshot }) {
  const administration = snapshot.administration
  const actionRows = Object.entries(administration.actionDistribution)
    .filter(([, count]) => count > 0)
    .map(([action, count]) => ({ label: dashboardAuditActionLabels[action as keyof typeof dashboardAuditActionLabels], count }))

  return (
    <Box component="section" aria-labelledby="usage-report-administration-heading" sx={{ display: 'grid', gap: 2 }}>
      <Typography id="usage-report-administration-heading" component="h2" variant="h5" fontWeight={700}>Administration</Typography>
      <Alert severity="info">{administration.scopeNote}</Alert>
      <MetricCard label="Administrative Actions in Period" value={administration.actionsInPeriod} />
      <DistributionTable title="Audit Action Distribution" rows={actionRows.length ? actionRows : [{ label: 'No actions', count: 0 }]} />
      <Card variant="outlined">
        <CardContent>
          <Typography component="h3" variant="h6" fontWeight={700} sx={{ mb: 2 }}>Recent Administrative Activity Summary</Typography>
          {administration.recentActivity.length === 0 ? (
            <Alert severity="info">No administrative activity was recorded in this reporting period.</Alert>
          ) : (
            <TableContainer>
              <Table size="small" aria-label="Recent administrative activity summary">
                <TableHead>
                  <TableRow>
                    <TableCell>Time</TableCell><TableCell>Action</TableCell><TableCell>Item</TableCell><TableCell>Administrator</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {administration.recentActivity.map((log) => (
                    <TableRow key={log.auditId}>
                      <TableCell sx={{ whiteSpace: 'nowrap' }}>{formatDashboardInstant(log.createdAt)}</TableCell>
                      <TableCell>{dashboardAuditActionLabels[log.action]}</TableCell>
                      <TableCell>{log.itemName} (Report #{log.reportId})</TableCell>
                      <TableCell>{log.actorEmail}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </TableContainer>
          )}
        </CardContent>
      </Card>
    </Box>
  )
}

function ReportPreview({ snapshot }: { snapshot: DashboardReportSnapshot }) {
  const period = snapshot.metadata.reportingPeriod
  return (
    <Stack spacing={4}>
      <Box>
        <Typography component="h2" variant="h5" fontWeight={700}>{snapshot.metadata.title}</Typography>
        <Typography color="text.secondary">Administrative Usage Report</Typography>
        <Typography>
          <strong>Reporting Period:</strong> {formatReportDate(period.startDate)} – {formatReportDate(period.endDate)}
        </Typography>
        <Typography color="text.secondary">{period.days} Singapore calendar days</Typography>
        <Typography><strong>Generated At:</strong> {formatDashboardInstant(snapshot.metadata.generatedAt)}</Typography>
        <Typography><strong>Generated By:</strong> {snapshot.metadata.generatedBy}</Typography>
      </Box>
      <Divider />
      <UserManagementSection snapshot={snapshot} />
      <Divider />
      <FacilitiesSection snapshot={snapshot} />
      <Divider />
      <LostFoundSection snapshot={snapshot} />
      <Divider />
      <AdministrationSection snapshot={snapshot} />
    </Stack>
  )
}

export function DashboardReportDialog({ open, generatedBy, onClose }: DashboardReportDialogProps) {
  const [snapshot, setSnapshot] = useState<DashboardReportSnapshot | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const mountedRef = useRef(true)
  const requestIdRef = useRef(0)
  const generatedForOpenRef = useRef(false)

  const generateReport = useCallback(async () => {
    const requestId = ++requestIdRef.current
    setSnapshot(null)
    setError('')
    setLoading(true)
    try {
      const nextSnapshot = await loadDashboardReportSnapshot(generatedBy)
      if (mountedRef.current && requestId === requestIdRef.current) setSnapshot(nextSnapshot)
    } catch (requestError) {
      if (mountedRef.current && requestId === requestIdRef.current) {
        setSnapshot(null)
        setError(apiErrorMessage(requestError))
      }
    } finally {
      if (mountedRef.current && requestId === requestIdRef.current) setLoading(false)
    }
  }, [generatedBy])

  useEffect(() => {
    mountedRef.current = true
    return () => {
      mountedRef.current = false
    }
  }, [])

  useEffect(() => {
    if (open && !generatedForOpenRef.current) {
      generatedForOpenRef.current = true
      void generateReport()
    }
    if (!open) {
      generatedForOpenRef.current = false
      requestIdRef.current += 1
    }
  }, [open, generateReport])

  const closeDialog = () => {
    generatedForOpenRef.current = false
    requestIdRef.current += 1
    setSnapshot(null)
    setError('')
    setLoading(false)
    onClose()
  }

  return (
    <Dialog open={open} onClose={closeDialog} fullWidth maxWidth="xl" scroll="paper" aria-labelledby="dashboard-report-dialog-title">
      <DialogTitle id="dashboard-report-dialog-title">Administrative Usage Report Preview</DialogTitle>
      <DialogContent dividers>
        {loading && (
          <Stack role="status" direction="row" spacing={2} alignItems="center" sx={{ py: 4 }}>
            <CircularProgress size={28} aria-label="Generating administrative usage report" />
            <Typography>Generating administrative usage report...</Typography>
          </Stack>
        )}
        {!loading && error && (
          <Alert severity="error">
            <Typography fontWeight={700}>Failed to generate the complete administrative usage report.</Typography>
            <Typography>{error}</Typography>
          </Alert>
        )}
        {!loading && snapshot && <ReportPreview snapshot={snapshot} />}
      </DialogContent>
      <DialogActions>
        {!loading && error && <Button onClick={() => void generateReport()}>Retry</Button>}
        {!loading && snapshot && <Button onClick={() => void generateReport()}>Regenerate</Button>}
        <Button onClick={closeDialog}>Close</Button>
      </DialogActions>
    </Dialog>
  )
}



