import { apiClient } from './client'

export interface FacilitiesDashboard {
  summary: { totalFacilities: number; availableFacilities: number; todayReservations: number; underMaintenance: number }
  statusBreakdown: { status: string; count: number }[]
  reservationTrend: { date: string; count: number }[]
  facilityUsage: { facilityId: number; facilityName: string; reservationCount: number }[]
}

export interface Space {
  spaceId: number
  name: string
  building: string
  floor: string
  roomNumber: string
  spaceType: string
  capacity: number
  equipment: string[]
  openingTime: string
  closingTime: string
  status: 'AVAILABLE' | 'OUT_OF_SERVICE' | 'INACTIVE' | string
}

export interface SpaceSearchFilters {
  query?: string
  building?: string
  spaceType?: string
  minimumCapacity?: number
  equipment?: string[]
  startDateTime?: string
  endDateTime?: string
}

export interface AvailabilityResponse {
  available: boolean
  reasonCode: string | null
  space: Space
  startDateTime: string
  endDateTime: string
}

export interface CreateBookingRequest {
  spaceId: number
  startDateTime: string
  endDateTime: string
}

export interface BookingResponse {
  success: boolean
  bookingId: number
  space: Space
  startDateTime: string
  endDateTime: string
  status: 'CONFIRMED' | 'CANCELLED' | 'COMPLETED' | string
  createdAt: string
  updatedAt: string
}

export type Booking = BookingResponse

export type MaintenancePriority = 'LOW' | 'MEDIUM' | 'HIGH'
export type MaintenanceStatus = 'SUBMITTED' | 'IN_PROGRESS' | 'RESOLVED' | 'CANCELLED'

export interface SubmitMaintenanceRequest {
  spaceId?: number
  building?: string
  roomNumber?: string
  facilityType: string
  description: string
  priority?: MaintenancePriority
}

export interface MaintenanceResponse {
  success: boolean
  ticketId: number
  spaceId: number | null
  spaceName: string | null
  building: string
  roomNumber: string
  facilityType: string
  description: string
  priority: MaintenancePriority
  status: MaintenanceStatus
  createdAt: string
  updatedAt: string
}

export type MaintenanceRequest = MaintenanceResponse

const localDate = (date: Date) => {
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

const activeBookings = (bookings: Booking[]) => bookings.filter((booking) => booking.status !== 'CANCELLED')

export const facilitiesApi = {
  searchSpaces: (filters: SpaceSearchFilters = {}) => {
    const params = new URLSearchParams()
    const append = (key: string, value: string | number | undefined) => {
      if (value === undefined || String(value).trim() === '') return
      params.append(key, String(value).trim())
    }
    append('query', filters.query)
    append('building', filters.building)
    append('spaceType', filters.spaceType)
    append('minimumCapacity', filters.minimumCapacity)
    filters.equipment?.forEach((item) => append('equipment', item))
    append('startDateTime', filters.startDateTime)
    append('endDateTime', filters.endDateTime)
    return apiClient.get<Space[]>('/facilities/spaces', { params }).then((response) => response.data)
  },
  getSpace: (spaceId: number) => apiClient.get<Space>(`/facilities/spaces/${spaceId}`).then((response) => response.data),
  checkSpaceAvailability: (spaceId: number, startDateTime: string, endDateTime: string) => {
    const params = new URLSearchParams({ startDateTime, endDateTime })
    return apiClient.get<AvailabilityResponse>(`/facilities/spaces/${spaceId}/availability`, { params }).then((response) => response.data)
  },
  createBooking: (request: CreateBookingRequest) => apiClient.post<BookingResponse>('/facilities/bookings', request).then((response) => response.data),
  listBookings: () => apiClient.get<BookingResponse[]>('/facilities/bookings').then((response) => response.data),
  getBooking: (bookingId: number) => apiClient.get<BookingResponse>(`/facilities/bookings/${bookingId}`).then((response) => response.data),
  cancelBooking: (bookingId: number) => apiClient.patch<BookingResponse>(`/facilities/bookings/${bookingId}/cancel`).then((response) => response.data),
  submitMaintenanceRequest: (request: SubmitMaintenanceRequest) => apiClient.post<MaintenanceResponse>('/facilities/maintenance', request).then((response) => response.data),
  listMaintenanceRequests: () => apiClient.get<MaintenanceResponse[]>('/facilities/maintenance').then((response) => response.data),
  getMaintenanceRequest: (ticketId: number) => apiClient.get<MaintenanceResponse>(`/facilities/maintenance/${ticketId}`).then((response) => response.data),
  getDashboard: async () => {
    const [spacesResponse, bookingsResponse, maintenanceResponse] = await Promise.all([
      apiClient.get<Space[]>('/facilities/spaces'),
      apiClient.get<Booking[]>('/facilities/bookings'),
      apiClient.get<MaintenanceRequest[]>('/facilities/maintenance'),
    ])
    const spaces = spacesResponse.data
    const bookings = bookingsResponse.data
    const maintenance = maintenanceResponse.data
    const today = localDate(new Date())
    const reservationTrend = Array.from({ length: 7 }, (_, index) => {
      const date = new Date()
      date.setDate(date.getDate() - 6 + index)
      const day = localDate(date)
      return { date: day, count: activeBookings(bookings).filter((booking) => booking.startDateTime.slice(0, 10) === day).length }
    })
    const currentBookings = activeBookings(bookings)
    return {
      summary: {
        totalFacilities: spaces.length,
        availableFacilities: spaces.filter((space) => space.status === 'AVAILABLE').length,
        todayReservations: currentBookings.filter((booking) => booking.startDateTime.slice(0, 10) === today).length,
        underMaintenance: new Set(maintenance.filter((ticket) => ['SUBMITTED', 'IN_PROGRESS'].includes(ticket.status)).map((ticket) => ticket.spaceId ?? `${ticket.building}-${ticket.roomNumber}`)).size,
      },
      statusBreakdown: ['AVAILABLE', 'OUT_OF_SERVICE', 'INACTIVE'].map((status) => ({ status, count: spaces.filter((space) => space.status === status).length })),
      reservationTrend,
      facilityUsage: spaces.map((space) => ({ facilityId: space.spaceId, facilityName: space.name, reservationCount: currentBookings.filter((booking) => booking.space.spaceId === space.spaceId).length })).sort((a, b) => b.reservationCount - a.reservationCount),
    } satisfies FacilitiesDashboard
  },
  getReservations: () => apiClient.get<Booking[]>('/facilities/bookings').then((response) => response.data),
  getMaintenance: () => apiClient.get<MaintenanceRequest[]>('/facilities/maintenance').then((response) => response.data),
  getMaintenanceDetail: (id: number) => apiClient.get<MaintenanceRequest>(`/facilities/maintenance/${id}`).then((response) => response.data),
  updateMaintenance: (id: number, status: string) => apiClient.patch<MaintenanceRequest>(`/facilities/maintenance/${id}/status`, { status }).then((response) => response.data),
}
