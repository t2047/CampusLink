import { apiClient } from './client'

export interface AgentConversationContext {
  sessionId: string
  sharedData: Record<string, unknown>
}

export interface AgentMatchResult {
  item_id: string
  item_name: string
  category: string
  description: string
  found_location: string
  found_date: string
  status: string
  match_score: number
  match_reason: string[]
}

export interface AgentConfirmationRequired {
  confirmation_id: string
  action: 'report_lost' | 'claim_item'
  summary: string
  expires_at: string
}

export interface AgentActionTaken {
  action: 'report_lost' | 'search_found_items' | 'get_item_detail' | 'claim_item'
  status: 'success' | 'failed' | 'skipped'
  params_summary?: string | null
  result_summary?: string | null
}

export interface AgentInvokeResponse {
  response: string
  status: 'completed' | 'needs_more_info' | 'match_found' | 'no_match' | 'needs_confirmation' | 'failed'
  match_results: AgentMatchResult[]
  confirmation_required: AgentConfirmationRequired | null
  shared_context: Record<string, unknown>
  actions_taken: AgentActionTaken[]
  request_id: string
}

export interface AgentInvokeRequest {
  message: string
  conversationContext: AgentConversationContext
  confirmed?: boolean
  confirmationId?: string
}

export async function invokeLostFoundAgent(
  request: AgentInvokeRequest,
): Promise<AgentInvokeResponse> {
  const response = await apiClient.post<AgentInvokeResponse>('/lost-found/agent/invoke', {
    ...request,
    confirmed: request.confirmed ?? false,
  })
  return response.data
}
