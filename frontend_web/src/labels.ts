import type { ClaimStatus, ItemCategory, ReportStatus, ReportType } from './types'

export const categoryLabels: Record<ItemCategory, string> = {
  ELECTRONICS: 'Electronics',
  CLOTHING: 'Clothing',
  BOOKS_STATIONERY: 'Books & Stationery',
  KEYS: 'Keys',
  WALLET_PURSE: 'Wallet / Purse',
  ID_CARD: 'ID Card',
  BAG: 'Bag',
  UMBRELLA: 'Umbrella',
  OTHER: 'Other',
}

export const reportTypeLabels: Record<ReportType, string> = { LOST: 'Lost', FOUND: 'Found' }
export const reportStatusLabels: Record<ReportStatus, string> = {
  OPEN: 'Open',
  CLAIMED: 'Claimed',
  CLOSED: 'Closed',
}
export const claimStatusLabels: Record<ClaimStatus, string> = {
  SUBMITTED: 'Submitted',
  APPROVED: 'Approved',
  REJECTED: 'Rejected',
}
