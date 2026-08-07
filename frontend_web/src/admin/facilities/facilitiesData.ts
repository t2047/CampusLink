export type FacilityStatus = 'Available' | 'Reserved' | 'Maintenance'
export type ReservationStatus = 'Confirmed' | 'Pending' | 'Cancelled'
export type MaintenanceStatus = 'Pending' | 'In Progress' | 'Completed'

export interface Facility {
  id: string
  name: string
  type: string
  status: FacilityStatus
}

export interface Reservation {
  id: string
  facility: string
  applicant: string
  date: string
  time: string
  status: ReservationStatus
}

export interface MaintenanceRequest {
  id: string
  facility: string
  issue: string
  priority: 'Low' | 'Medium' | 'High'
  status: MaintenanceStatus
  submittedDate: string
  note: string
}

export const facilities: Facility[] = [
  { id: 'FAC-001', name: 'Meeting Room A', type: 'Meeting Room', status: 'Available' },
  { id: 'FAC-002', name: 'Lab 301', type: 'Laboratory', status: 'Reserved' },
  { id: 'FAC-003', name: 'Library Room', type: 'Study Room', status: 'Available' },
  { id: 'FAC-004', name: 'Sports Hall', type: 'Sports', status: 'Maintenance' },
  { id: 'FAC-005', name: 'Seminar Room B', type: 'Seminar Room', status: 'Available' },
]

export const reservations: Reservation[] = [
  { id: 'RES-1001', facility: 'Meeting Room A', applicant: 'Alice Tan', date: '2026-08-07', time: '09:00–11:00', status: 'Confirmed' },
  { id: 'RES-1002', facility: 'Lab 301', applicant: 'Benjamin Lee', date: '2026-08-07', time: '13:00–15:00', status: 'Confirmed' },
  { id: 'RES-1003', facility: 'Library Room', applicant: 'Chloe Lim', date: '2026-08-08', time: '10:00–12:00', status: 'Pending' },
  { id: 'RES-1004', facility: 'Seminar Room B', applicant: 'Daniel Wong', date: '2026-08-09', time: '15:00–17:00', status: 'Cancelled' },
]

export const maintenanceRequests: MaintenanceRequest[] = [
  { id: 'MNT-2001', facility: 'Sports Hall', issue: 'Air-conditioning unit is not cooling.', priority: 'High', status: 'In Progress', submittedDate: '2026-08-06', note: 'Technician visit scheduled for tomorrow.' },
  { id: 'MNT-2002', facility: 'Lab 301', issue: 'Projector lamp needs replacement.', priority: 'Medium', status: 'Pending', submittedDate: '2026-08-05', note: '' },
  { id: 'MNT-2003', facility: 'Library Room', issue: 'One ceiling light is flickering.', priority: 'Low', status: 'Completed', submittedDate: '2026-08-01', note: 'Replacement completed.' },
]

