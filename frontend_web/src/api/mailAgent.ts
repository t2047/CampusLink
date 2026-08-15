import { apiClient } from './client'

export interface MailAgentAction {
  tool: string
  args: Record<string, unknown>
}

export interface MailAgentChatResponse {
  response: string
  status: 'completed' | 'failed'
  session_id: string
  actions_taken: MailAgentAction[]
  model: string
}

export interface MailAgentChatRequest {
  message: string
  session_id?: string
}

/** 调用 Mail 模块的 LangChain agent（搜索/阅读/删除/加星/归档/发送邮件）。 */
export async function invokeMailAgent(request: MailAgentChatRequest): Promise<MailAgentChatResponse> {
  const response = await apiClient.post<MailAgentChatResponse>('/mail/agent/chat', request, {
    // LLM + 多轮工具调用（尤其批量删除）可能需要较长时间，放宽超时。
    timeout: 300_000,
  })
  return response.data
}
