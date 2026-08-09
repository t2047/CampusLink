import SmartToyOutlinedIcon from '@mui/icons-material/SmartToyOutlined'
import SendIcon from '@mui/icons-material/Send'
import {
  Alert,
  Box,
  Button,
  Chip,
  CircularProgress,
  Divider,
  Link,
  Paper,
  Stack,
  TextField,
  Typography,
} from '@mui/material'
import { FormEvent, useState } from 'react'
import { Link as RouterLink } from 'react-router-dom'
import { apiErrorMessage } from '../api/client'
import {
  invokeLostFoundAgent,
  type AgentConfirmationRequired,
  type AgentInvokeResponse,
  type AgentMatchResult,
} from '../api/lostFoundAgent'

interface ConversationMessage {
  id: string
  role: 'user' | 'agent'
  text: string
  status?: AgentInvokeResponse['status']
  matches?: AgentMatchResult[]
}

function newSessionId() {
  return `web-${Date.now()}-${Math.random().toString(36).slice(2)}`
}

function createdReportId(response: AgentInvokeResponse) {
  const summary = response.actions_taken.find(
    (action) => action.action === 'report_lost' && action.status === 'success',
  )?.result_summary
  const match = summary?.match(/^report_id=(\d+)$/)
  return match ? Number(match[1]) : undefined
}

export function LostFoundAgentPanel({ onReportCreated }: { onReportCreated?: (reportId?: number) => void }) {
  const [input, setInput] = useState('')
  const [messages, setMessages] = useState<ConversationMessage[]>([])
  const [sessionId] = useState(newSessionId)
  const [sharedData, setSharedData] = useState<Record<string, unknown>>({})
  const [pendingConfirmation, setPendingConfirmation] = useState<AgentConfirmationRequired | null>(null)
  const [latestCreatedReportId, setLatestCreatedReportId] = useState<number | undefined>()
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  function appendAgentResponse(response: AgentInvokeResponse) {
    setSharedData(response.shared_context ?? {})
    setPendingConfirmation(response.confirmation_required)
    setMessages((current) => [
      ...current,
      {
        id: response.request_id,
        role: 'agent',
        text: response.response,
        status: response.status,
        matches: response.match_results,
      },
    ])
    if (response.actions_taken.some((action) => action.action === 'report_lost' && action.status === 'success')) {
      const reportId = createdReportId(response)
      setLatestCreatedReportId(reportId)
      onReportCreated?.(reportId)
    }
  }

  async function send(event: FormEvent) {
    event.preventDefault()
    const message = input.trim()
    if (!message || loading) return
    setInput('')
    setError('')
    setMessages((current) => [
      ...current,
      { id: `user-${Date.now()}`, role: 'user', text: message },
    ])
    setLoading(true)
    try {
      appendAgentResponse(await invokeLostFoundAgent({
        message,
        conversationContext: { sessionId, sharedData },
      }))
    } catch (requestError) {
      setError(apiErrorMessage(requestError))
    } finally {
      setLoading(false)
    }
  }

  async function confirmAction() {
    if (!pendingConfirmation || loading) return
    setError('')
    setLoading(true)
    try {
      appendAgentResponse(await invokeLostFoundAgent({
        message: '确认',
        conversationContext: { sessionId, sharedData },
        confirmed: true,
        confirmationId: pendingConfirmation.confirmation_id,
      }))
    } catch (requestError) {
      setError(apiErrorMessage(requestError))
    } finally {
      setLoading(false)
    }
  }

  function cancelAction() {
    setPendingConfirmation(null)
    setMessages((current) => [
      ...current,
      {
        id: `cancelled-${Date.now()}`,
        role: 'agent',
        text: 'Action cancelled. No record was created. / 操作已取消，本次不会创建记录。',
        status: 'completed',
      },
    ])
  }

  return (
    <Paper component="section" aria-labelledby="agent-test-heading" sx={{ p: 2.5 }}>
      <Stack spacing={2}>
        <Stack direction="row" spacing={1.5} alignItems="center">
          <SmartToyOutlinedIcon color="primary" />
          <Box>
            <Typography id="agent-test-heading" variant="h6" fontWeight={700}>Try the Lost & Found Agent</Typography>
            <Typography variant="body2" color="text.secondary">
              Describe an item naturally, for example: “我昨天在图书馆丢了一副黑色耳机”。
            </Typography>
          </Box>
          <Chip label="Test" size="small" color="primary" variant="outlined" sx={{ ml: 'auto' }} />
        </Stack>

        {messages.length > 0 && (
          <Stack spacing={1} sx={{ maxHeight: 360, overflowY: 'auto', pr: 0.5 }}>
            {messages.map((message) => (
              <Box
                key={message.id}
                sx={{
                  alignSelf: message.role === 'user' ? 'flex-end' : 'flex-start',
                  maxWidth: { xs: '100%', md: '80%' },
                  bgcolor: message.role === 'user' ? 'primary.main' : 'grey.100',
                  color: message.role === 'user' ? 'primary.contrastText' : 'text.primary',
                  borderRadius: 2,
                  px: 2,
                  py: 1.25,
                }}
              >
                <Typography sx={{ whiteSpace: 'pre-wrap' }}>{message.text}</Typography>
                {message.status && (
                  <Typography variant="caption" sx={{ opacity: 0.72 }}>{message.status}</Typography>
                )}
                {message.matches?.map((match) => (
                  <Box key={match.item_id} sx={{ mt: 1, pt: 1, borderTop: '1px solid', borderColor: 'divider' }}>
                    <Stack direction="row" justifyContent="space-between" gap={1}>
                      <Link component={RouterLink} to={`/lost-found/${match.item_id}`} fontWeight={700}>
                        #{match.item_id} {match.item_name}
                      </Link>
                      <Typography variant="body2">{Math.round(match.match_score * 100)}%</Typography>
                    </Stack>
                    <Typography variant="body2">{match.found_location} · {match.found_date}</Typography>
                    <Typography variant="caption">{match.match_reason.join('；')}</Typography>
                  </Box>
                ))}
              </Box>
            ))}
          </Stack>
        )}

        {pendingConfirmation && (
          <Alert
            severity="warning"
            action={(
              <Stack direction="row" spacing={1}>
                <Button color="inherit" size="small" onClick={cancelAction}>Cancel</Button>
                <Button color="warning" variant="contained" size="small" onClick={confirmAction} disabled={loading}>Confirm</Button>
              </Stack>
            )}
          >
            {pendingConfirmation.summary}
          </Alert>
        )}
        {latestCreatedReportId && (
          <Alert severity="success">
            Report #{latestCreatedReportId} was created. Filters below were cleared so the record is visible.{' '}
            <Link component={RouterLink} to={`/lost-found/${latestCreatedReportId}`} fontWeight={700}>
              View report
            </Link>
          </Alert>
        )}
        {error && <Alert severity="error">{error}</Alert>}
        <Divider />
        <Stack component="form" direction={{ xs: 'column', sm: 'row' }} spacing={1} onSubmit={send}>
          <TextField
            fullWidth
            multiline
            maxRows={4}
            label="Describe what you lost or want to find"
            value={input}
            onChange={(event) => setInput(event.target.value)}
            slotProps={{ htmlInput: { maxLength: 4000 } }}
          />
          <Button
            type="submit"
            variant="contained"
            endIcon={loading ? <CircularProgress color="inherit" size={18} /> : <SendIcon />}
            disabled={loading || !input.trim()}
            sx={{ minWidth: 112 }}
          >
            Send
          </Button>
        </Stack>
      </Stack>
    </Paper>
  )
}
