import type { MaintenanceResponse, MaintenanceStatus } from '../../api/facilities'

export const ACTIVE_MAINTENANCE_STATUSES = new Set<MaintenanceStatus>(['SUBMITTED', 'IN_PROGRESS'])

export function maintenanceStatusLabel(status: MaintenanceStatus) {
  return status.replaceAll('_', ' ')
}

export function maintenanceStatusColor(status: MaintenanceStatus): 'default' | 'info' | 'warning' | 'success' {
  if (status === 'SUBMITTED') return 'info'
  if (status === 'IN_PROGRESS') return 'warning'
  if (status === 'RESOLVED') return 'success'
  return 'default'
}

export function maintenanceStatusMessage(status: MaintenanceStatus) {
  if (status === 'SUBMITTED') return 'Your request has been submitted and is awaiting review.'
  if (status === 'IN_PROGRESS') return 'Facilities staff are working on this request.'
  if (status === 'RESOLVED') return 'This maintenance request has been resolved.'
  return 'This maintenance request has been cancelled.'
}

export function sortMaintenanceRequests(requests: MaintenanceResponse[]) {
  return [...requests].sort((left, right) => {
    const groupDifference = Number(!ACTIVE_MAINTENANCE_STATUSES.has(left.status))
      - Number(!ACTIVE_MAINTENANCE_STATUSES.has(right.status))
    if (groupDifference) return groupDifference
    return right.updatedAt.localeCompare(left.updatedAt)
      || right.createdAt.localeCompare(left.createdAt)
  })
}
