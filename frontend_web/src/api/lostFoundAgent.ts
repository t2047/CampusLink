import { apiClient } from './client'

export interface AgentConversationContext {
  sessionId: string
  sharedData: Record<string, unknown>
}

/** Agent 面板选中并已由后端暂存的一张图片。 */
export interface StagedAgentImage {
  objectKey: string
  visualFingerprint: string
  url: string
  contentType: string
  originalName: string
  fileSize: number
  embeddingStatus?: 'READY' | 'PENDING' | 'BASELINE'
}

export interface AgentMatchResult {
  item_id: string
  report_type: 'LOST' | 'FOUND'
  item_name: string
  category: string
  description: string
  colour?: string | null
  location: string
  event_date: string
  time_description?: string | null
  image_urls: string[]
  status: string
  match_score: number
  match_reason: string[]
  score_breakdown?: Record<string, number>
  matching_mode?: 'pretrained_multimodal' | 'pretrained_image' | 'pretrained_text' | 'baseline'
}

export interface AgentConfirmationRequired {
  confirmation_id: string
  action: 'report_lost' | 'report_found' | 'claim_item'
  summary: string
  expires_at: string
}

export interface AgentActionTaken {
  action: 'report_lost' | 'report_found' | 'search_found_items' | 'search_lost_items' | 'get_item_detail' | 'claim_item'
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
  /** 本轮携带的已暂存图片（多轮共享；确认创建时由 Agent 关联落库） */
  images?: StagedAgentImage[]
}

export async function invokeLostFoundAgent(
  request: AgentInvokeRequest,
): Promise<AgentInvokeResponse> {
  const response = await apiClient.post<AgentInvokeResponse>('/lost-found/agent/invoke', {
    ...request,
    confirmed: request.confirmed ?? false,
  }, {
    // Agent 内部模型最多等待 15 秒，额外预留规则降级和后端工具调用时间。
    timeout: 25_000,
  })
  return response.data
}

/** 单张图片暂存：登录用户上传后由后端算好指纹并返回可回显的代理 URL。 */
export async function uploadAgentImage(file: File): Promise<StagedAgentImage> {
  const form = new FormData()
  form.append('image', file)
  const response = await apiClient.post<StagedAgentImage>('/lost-found/agent/upload-image', form)
  return response.data
}
