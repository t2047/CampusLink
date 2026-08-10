export type ReportType = 'LOST' | 'FOUND'
export type ReportStatus = 'OPEN' | 'CLAIMED' | 'CLOSED'
export type ClaimStatus = 'SUBMITTED' | 'APPROVED' | 'REJECTED'

export type ItemCategory =
  | 'ELECTRONICS'
  | 'CLOTHING'
  | 'BOOKS_STATIONERY'
  | 'KEYS'
  | 'WALLET_PURSE'
  | 'ID_CARD'
  | 'BAG'
  | 'UMBRELLA'
  | 'OTHER'

export interface AuthResponse {
  token: string
  email: string
  role: string
}

export interface LostFoundImage {
  id: number
  url: string
  contentType: string
  fileSize: number
  sortOrder: number
}

export interface LostFoundReport {
  id: number
  reportType: ReportType
  itemName: string
  category: ItemCategory
  description: string
  colour: string | null
  location: string
  eventDate: string
  timeDescription: string | null
  status: ReportStatus
  images: LostFoundImage[]
  createdByMe: boolean
  createdAt: string
  updatedAt: string
}

export interface PageResponse<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
  first: boolean
  last: boolean
}

export interface LostFoundMetadata {
  reportTypes: ReportType[]
  categories: ItemCategory[]
  reportStatuses: ReportStatus[]
  claimStatuses: ClaimStatus[]
}

export interface ClaimReportSummary {
  id: number
  itemName: string
  category: ItemCategory
  location: string
  status: ReportStatus
}

export interface LostFoundClaim {
  id: number
  report: ClaimReportSummary
  proofDescription: string
  status: ClaimStatus
  decisionNote: string | null
  submittedByMe: boolean
  createdAt: string
  updatedAt: string
}

export interface AdminLostFoundOverview {
  totalReports: number
  openReports: number
  claimedReports: number
  closedReports: number
  lostReports: number
  foundReports: number
  submittedClaims: number
}

export interface AdminLostFoundReport {
  id: number
  reportType: ReportType
  itemName: string
  category: ItemCategory
  colour: string | null
  location: string
  eventDate: string
  status: ReportStatus
  createdByEmail: string
  createdAt: string
  updatedAt: string
}

export interface CreateReportInput {
  reportType: ReportType
  itemName: string
  category: ItemCategory
  description: string
  colour: string
  location: string
  eventDate: string
  timeDescription: string
}

export interface ApiErrorBody {
  code?: string
  error?: string
  message?: string
  fieldErrors?: Record<string, string>
}

export type MailFolder = 'inbox' | 'sent' | 'archived' | 'trash'

export interface MailMessage {
  id: string
  subject: string
  sender: string
  recipients: string[]
  preview: string
  body: string
  folder: MailFolder
  read: boolean
  starred: boolean
  created_at: string
  updated_at: string
}

export interface MailPageResponse {
  content: MailMessage[]
  page: number
  size: number
  total_elements: number
  total_pages: number
  first: boolean
  last: boolean
}

export interface SendMailInput {
  recipients: string[]
  subject: string
  body: string
}
