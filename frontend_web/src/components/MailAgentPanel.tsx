import ArchiveIcon from '@mui/icons-material/Archive'
import BuildIcon from '@mui/icons-material/Build'
import DeleteOutlineIcon from '@mui/icons-material/DeleteOutline'
import EmailOutlinedIcon from '@mui/icons-material/EmailOutlined'
import SearchIcon from '@mui/icons-material/Search'
import SendIcon from '@mui/icons-material/Send'
import SmartToyOutlinedIcon from '@mui/icons-material/SmartToyOutlined'
import StarBorderIcon from '@mui/icons-material/StarBorder'
import {
  Alert,
  Box,
  Button,
  Chip,
  CircularProgress,
  Divider,
  Stack,
  TextField,
  Typography,
} from '@mui/material'
import { FormEvent, useState, type ReactNode } from 'react'
import { apiErrorMessage } from '../api/client'
import { invokeMailAgent, type MailAgentAction } from '../api/mailAgent'
import { MarkdownContent } from './MarkdownContent'

interface ConversationMessage {
  id: string
  role: 'user' | 'agent'
  text: string
  actions?: MailAgentAction[]
}

function newSessionId() {
  return `mail-${Date.now()}-${Math.random().toString(36).slice(2)}`
}

const suggestions = [
  '帮我找最近的未读邮件',
  '找一封关于考试的邮件并读给我',
  '把最新一封邮件加星',
]

const toolMeta: Record<string, { label: string; icon: ReactNode }> = {
  search_mail: { label: '搜索邮件', icon: <SearchIcon fontSize="small" /> },
  read_mail: { label: '阅读邮件', icon: <EmailOutlinedIcon fontSize="small" /> },
  delete_mail: { label: '删除邮件', icon: <DeleteOutlineIcon fontSize="small" /> },
  star_mail: { label: '加星标', icon: <StarBorderIcon fontSize="small" /> },
  archive_mail: { label: '归档邮件', icon: <ArchiveIcon fontSize="small" /> },
  send_mail: { label: '发送邮件', icon: <SendIcon fontSize="small" /> },
}

function summarizeArgs(args: Record<string, unknown>): string {
  try {
    const text = JSON.stringify(args)
    if (!text) return ''
    return text.length > 72 ? `${text.slice(0, 69)}…` : text
  } catch {
    return ''
  }
}

export function MailAgentPanel() {
  const [input, setInput] = useState('')
  const [messages, setMessages] = useState<ConversationMessage[]>([])
  const [sessionId] = useState(newSessionId)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  async function send(text: string) {
    const message = text.trim()
    if (!message || loading) return
    setInput('')
    setError('')
    setMessages((current) => [
      ...current,
      { id: `user-${Date.now()}`, role: 'user', text: message },
    ])
    setLoading(true)
    try {
      const result = await invokeMailAgent({ message, session_id: sessionId })
      setMessages((current) => [
        ...current,
        {
          id: `agent-${Date.now()}`,
          role: 'agent',
          text: result.response,
          actions: result.actions_taken,
        },
      ])
    } catch (requestError) {
      setError(apiErrorMessage(requestError))
    } finally {
      setLoading(false)
    }
  }

  function submit(event: FormEvent) {
    event.preventDefault()
    void send(input)
  }

  return (
    <Stack component="section" spacing={2} sx={{ height: '100%' }}>
      <Stack direction="row" spacing={1.5} alignItems="center">
        <SmartToyOutlinedIcon color="primary" />
        <Box>
          <Typography variant="subtitle1" fontWeight={700}>Mail Assistant</Typography>
          <Typography variant="body2" color="text.secondary">
            Search, read, delete, star, archive or send mail in plain language.
          </Typography>
        </Box>
        <Chip label="Agent" size="small" color="primary" variant="outlined" sx={{ ml: 'auto' }} />
      </Stack>

      <Divider />

      {messages.length === 0 && !loading && (
        <Stack spacing={1}>
          <Typography variant="body2" color="text.secondary">Try asking:</Typography>
          {suggestions.map((suggestion) => (
            <Chip
              key={suggestion}
              label={suggestion}
              color="secondary"
              variant="outlined"
              onClick={() => void send(suggestion)}
              sx={{
                justifyContent: 'flex-start',
                width: 'fit-content',
                transition: 'all .15s ease',
                '&:hover': {
                  borderColor: 'secondary.main',
                  bgcolor: 'rgba(15, 118, 110, 0.08)',
                  transform: 'translateX(2px)',
                },
              }}
            />
          ))}
        </Stack>
      )}

      <Stack
        spacing={1}
        sx={{
          flex: 1,
          minHeight: 220,
          overflowY: 'auto',
          pr: 0.5,
        }}
      >
        {messages.map((message) => (
          <Box
            key={message.id}
            sx={{
              alignSelf: message.role === 'user' ? 'flex-end' : 'flex-start',
              maxWidth: { xs: '100%', md: '88%' },
              background: message.role === 'user'
                ? 'linear-gradient(135deg, #3366b8 0%, #1d4f9e 100%)'
                : '#eef2f7',
              color: message.role === 'user' ? 'primary.contrastText' : 'text.primary',
              borderRadius: 2,
              boxShadow: message.role === 'user' ? '0 2px 8px rgba(35, 86, 168, 0.25)' : 'none',
              px: 2,
              py: 1.25,
              whiteSpace: 'pre-wrap',
            }}
          >
            {message.actions && message.actions.length > 0 && (
              <Stack spacing={0.5} sx={{ mb: 1 }}>
                {message.actions.map((action, index) => {
                  const meta = toolMeta[action.tool] ?? {
                    label: action.tool,
                    icon: <BuildIcon fontSize="small" />,
                  }
                  return (
                    <Box
                      key={`${action.tool}-${index}`}
                      sx={{
                        display: 'flex',
                        alignItems: 'center',
                        gap: 0.75,
                        borderRadius: 1,
                        bgcolor: 'rgba(0,0,0,0.06)',
                        px: 1,
                        py: 0.5,
                        minWidth: 0,
                      }}
                    >
                      <Box sx={{ display: 'flex', opacity: 0.85 }}>{meta.icon}</Box>
                      <Typography variant="caption" fontWeight={700} noWrap>
                        {meta.label}
                      </Typography>
                      <Typography
                        variant="caption"
                        noWrap
                        sx={{ flex: 1, minWidth: 0, opacity: 0.75, fontFamily: 'monospace' }}
                      >
                        {summarizeArgs(action.args)}
                      </Typography>
                    </Box>
                  )
                })}
              </Stack>
            )}
            {message.role === 'user' ? (
              <Typography sx={{ whiteSpace: 'pre-wrap' }}>{message.text}</Typography>
            ) : (
              <MarkdownContent text={message.text} />
            )}
          </Box>
        ))}
        {loading && (
          <Box sx={{ alignSelf: 'flex-start', display: 'flex', alignItems: 'center', gap: 1, color: 'text.secondary' }}>
            <CircularProgress size={16} />
            <Typography variant="body2">Mail Assistant is working…</Typography>
          </Box>
        )}
      </Stack>

      {error && <Alert severity="error" onClose={() => setError('')}>{error}</Alert>}

      <Stack component="form" direction="row" spacing={1} onSubmit={submit}>
        <TextField
          fullWidth
          size="small"
          multiline
          maxRows={4}
          label="Ask about your mail…"
          value={input}
          onChange={(event) => setInput(event.target.value)}
          slotProps={{ htmlInput: { maxLength: 8000 } }}
          sx={{ flexGrow: 1 }}
        />
        <Button
          type="submit"
          variant="contained"
          aria-label="Send"
          startIcon={loading ? <CircularProgress color="inherit" size={18} /> : <SendIcon />}
          disabled={loading || !input.trim()}
          sx={{ alignSelf: 'center', minWidth: 96 }}
        >
          Send
        </Button>
      </Stack>
    </Stack>
  )
}
