import AddPhotoAlternateOutlinedIcon from '@mui/icons-material/AddPhotoAlternateOutlined'
import DeleteOutlineIcon from '@mui/icons-material/DeleteOutline'
import SendIcon from '@mui/icons-material/Send'
import SmartToyOutlinedIcon from '@mui/icons-material/SmartToyOutlined'
import {
  Alert,
  Box,
  Button,
  Chip,
  CircularProgress,
  Divider,
  IconButton,
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
  uploadAgentImage,
  type AgentConfirmationRequired,
  type AgentInvokeResponse,
  type AgentMatchResult,
  type StagedAgentImage,
} from '../api/lostFoundAgent'

const allowedTypes = ['image/jpeg', 'image/png', 'image/webp']
const maxFileSize = 10 * 1024 * 1024

interface ConversationMessage {
  id: string
  role: 'user' | 'agent'
  text: string
  status?: AgentInvokeResponse['status']
  matches?: AgentMatchResult[]
  images?: StagedAgentImage[]
}

function newSessionId() {
  return `web-${Date.now()}-${Math.random().toString(36).slice(2)}`
}

function createdReportId(response: AgentInvokeResponse) {
  const summary = response.actions_taken.find(
    (action) => ['report_lost', 'report_found'].includes(action.action) && action.status === 'success',
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
  const [uploading, setUploading] = useState(false)
  const [error, setError] = useState('')
  const [stagedImages, setStagedImages] = useState<StagedAgentImage[]>([])

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
    if (response.actions_taken.some((action) => ['report_lost', 'report_found'].includes(action.action) && action.status === 'success')) {
      const reportId = createdReportId(response)
      setLatestCreatedReportId(reportId)
      setStagedImages([]) // 已关联落库，清空面板暂存
      onReportCreated?.(reportId)
    }
  }

  async function selectImages(files: FileList | null) {
    if (!files || uploading || loading) return
    const incoming = Array.from(files)
    if (stagedImages.length + incoming.length > 5) {
      setError('You can attach at most 5 images.')
      return
    }
    const invalid = incoming.find((file) => !allowedTypes.includes(file.type) || file.size > maxFileSize)
    if (invalid) {
      setError(`${invalid.name} must be a JPEG, PNG or WebP image no larger than 10 MB.`)
      return
    }
    setError('')
    setUploading(true)
    try {
      for (const file of incoming) {
        const staged = await uploadAgentImage(file)
        setStagedImages((current) => [...current, staged])
      }
    } catch (requestError) {
      setError(apiErrorMessage(requestError))
    } finally {
      setUploading(false)
    }
  }

  function removeImage(objectKey: string) {
    setStagedImages((current) => current.filter((image) => image.objectKey !== objectKey))
  }

  async function send(event: FormEvent) {
    event.preventDefault()
    const trimmed = input.trim()
    // 仅发图（无文字）时用占位语触发按图检索；Agent 契约要求 message 非空
    const message = trimmed || (stagedImages.length > 0 ? '帮我找这个' : '')
    if ((!message || loading) && stagedImages.length === 0) return
    setInput('')
    setError('')
    setMessages((current) => [
      ...current,
      { id: `user-${Date.now()}`, role: 'user', text: message, images: stagedImages },
    ])
    setLoading(true)
    try {
      appendAgentResponse(await invokeLostFoundAgent({
        message,
        conversationContext: { sessionId, sharedData },
        images: stagedImages,
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
        images: stagedImages,
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
              Describe an item naturally
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
                {message.images && message.images.length > 0 && (
                  <Stack direction="row" spacing={1} sx={{ mt: 1 }}>
                    {message.images.map((image) => (
                      <Box
                        key={image.objectKey}
                        component="img"
                        src={image.url}
                        alt={image.originalName}
                        sx={{ width: 64, height: 64, objectFit: 'cover', borderRadius: 1 }}
                      />
                    ))}
                  </Stack>
                )}
                {message.status && (
                  <Typography variant="caption" sx={{ opacity: 0.72 }}>{message.status}</Typography>
                )}
                {message.matches?.map((match) => (
                  <Box key={match.item_id} sx={{ mt: 1.5, pt: 1.5, borderTop: '1px solid', borderColor: 'divider' }}>
                    {match.image_urls[0] && (
                      <Box
                        component="img"
                        src={match.image_urls[0]}
                        alt={match.item_name}
                        sx={{ width: '100%', maxHeight: 180, objectFit: 'cover', borderRadius: 1, mb: 1 }}
                      />
                    )}
                    <Stack direction="row" justifyContent="space-between" gap={1}>
                      <Link component={RouterLink} to={`/lost-found/${match.item_id}`} fontWeight={700}>
                        #{match.item_id} [{match.report_type}] {match.item_name}
                      </Link>
                      <Typography variant="body2">{Math.round(match.match_score * 100)}%</Typography>
                    </Stack>
                    <Typography variant="body2">
                      {match.category}{match.colour ? ` · ${match.colour}` : ''} · {match.location} · {match.event_date}
                    </Typography>
                    <Typography variant="body2" color="text.secondary">{match.description}</Typography>
                    <Typography variant="caption">{match.match_reason.join('；')}</Typography>
                    {match.matching_mode === 'baseline' && (
                      <Alert severity="info" sx={{ mt: 1, py: 0 }}>
                        智能模型暂不可用，当前使用基础匹配。
                      </Alert>
                    )}
                    {match.score_breakdown && (
                      <Stack direction="row" spacing={0.5} flexWrap="wrap" useFlexGap sx={{ mt: 1 }}>
                        {Object.entries(match.score_breakdown).map(([name, value]) => (
                          <Chip key={name} size="small" variant="outlined" label={`${name} ${Math.round(value * 100)}%`} />
                        ))}
                      </Stack>
                    )}
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
        {(stagedImages.length > 0 || uploading) && (
          <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
            {stagedImages.map((image) => (
              <Box key={image.objectKey} sx={{ position: 'relative' }}>
                <Box
                  component="img"
                  src={image.url}
                  alt={image.originalName}
                  sx={{ width: 64, height: 64, objectFit: 'cover', borderRadius: 1 }}
                />
                <IconButton
                  aria-label={`Remove ${image.originalName}`}
                  size="small"
                  onClick={() => removeImage(image.objectKey)}
                  sx={{ position: 'absolute', top: 2, right: 2, bgcolor: 'background.paper' }}
                >
                  <DeleteOutlineIcon sx={{ fontSize: 16 }} />
                </IconButton>
              </Box>
            ))}
            {uploading && <CircularProgress size={24} />}
          </Stack>
        )}
        <Stack component="form" direction={{ xs: 'column', sm: 'row' }} spacing={1} onSubmit={send}>
          <TextField
            fullWidth
            multiline
            maxRows={4}
            label="Describe what you lost or want to find"
            value={input}
            onChange={(event) => setInput(event.target.value)}
            slotProps={{ htmlInput: { maxLength: 4000 } }}
            sx={{ flexGrow: 1 }}
          />
          <Button
            component="label"
            variant="outlined"
            startIcon={uploading ? <CircularProgress color="inherit" size={18} /> : <AddPhotoAlternateOutlinedIcon />}
            disabled={stagedImages.length >= 5 || loading || uploading}
            sx={{ minWidth: 112 }}
          >
            Images
            <input
              hidden
              type="file"
              multiple
              accept="image/jpeg,image/png,image/webp"
              onChange={(e) => { selectImages(e.target.files); e.target.value = '' }}
            />
          </Button>
          <Button
            type="submit"
            variant="contained"
            endIcon={loading ? <CircularProgress color="inherit" size={18} /> : <SendIcon />}
            disabled={loading || uploading || (!input.trim() && stagedImages.length === 0)}
            sx={{ minWidth: 112 }}
          >
            Send
          </Button>
        </Stack>
      </Stack>
    </Paper>
  )
}
