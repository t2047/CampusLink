import ArchiveIcon from '@mui/icons-material/Archive'
import DeleteOutlineIcon from '@mui/icons-material/DeleteOutline'
import MailOutlineIcon from '@mui/icons-material/MailOutline'
import MarkEmailReadIcon from '@mui/icons-material/MarkEmailRead'
import MarkEmailUnreadIcon from '@mui/icons-material/MarkEmailUnread'
import RefreshIcon from '@mui/icons-material/Refresh'
import SendIcon from '@mui/icons-material/Send'
import StarIcon from '@mui/icons-material/Star'
import StarBorderIcon from '@mui/icons-material/StarBorder'
import {
  Alert,
  Box,
  Button,
  Chip,
  CircularProgress,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Divider,
  IconButton,
  List,
  ListItemButton,
  Paper,
  Stack,
  Tab,
  Tabs,
  TextField,
  Tooltip,
  Typography,
} from '@mui/material'
import { FormEvent, useEffect, useState } from 'react'
import { apiErrorMessage } from '../api/client'
import { archiveMail, deleteMail, getMailMessage, listMail, sendMail, updateMail } from '../api/mail'
import type { MailFolder, MailMessage } from '../types'

const folders: Array<{ value: MailFolder; label: string }> = [
  { value: 'inbox', label: 'Inbox' },
  { value: 'sent', label: 'Sent' },
  { value: 'archived', label: 'Archived' },
  { value: 'trash', label: 'Trash' },
]

export function MailPage() {
  const [folder, setFolder] = useState<MailFolder>('inbox')
  const [query, setQuery] = useState('')
  const [messages, setMessages] = useState<MailMessage[]>([])
  const [selected, setSelected] = useState<MailMessage | null>(null)
  const [loading, setLoading] = useState(true)
  const [detailLoading, setDetailLoading] = useState(false)
  const [error, setError] = useState('')
  const [composeOpen, setComposeOpen] = useState(false)
  const [sending, setSending] = useState(false)
  const [draft, setDraft] = useState({ recipients: '', subject: '', body: '' })

  async function loadMessages(nextFolder = folder, nextQuery = query) {
    setLoading(true)
    setError('')
    try {
      const result = await listMail({ folder: nextFolder, q: nextQuery || undefined, size: 50 })
      setMessages(result.content)
      if (!result.content.some((message) => message.id === selected?.id)) {
        setSelected(null)
      }
    } catch (requestError) {
      setError(apiErrorMessage(requestError))
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    void loadMessages(folder, query)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [folder])

  async function openMessage(message: MailMessage) {
    setDetailLoading(true)
    setError('')
    try {
      const detail = await getMailMessage(message.id)
      setSelected(detail)
      setMessages((current) => current.map((item) => (item.id === detail.id ? detail : item)))
    } catch (requestError) {
      setError(apiErrorMessage(requestError))
    } finally {
      setDetailLoading(false)
    }
  }

  async function patchSelected(patch: Partial<Pick<MailMessage, 'read' | 'starred' | 'folder'>>) {
    if (!selected) return
    try {
      const updated = await updateMail(selected.id, patch)
      setSelected(updated.folder === folder ? updated : null)
      await loadMessages()
    } catch (requestError) {
      setError(apiErrorMessage(requestError))
    }
  }

  async function archiveSelected() {
    if (!selected) return
    try {
      await archiveMail(selected.id)
      setSelected(null)
      await loadMessages()
    } catch (requestError) {
      setError(apiErrorMessage(requestError))
    }
  }

  async function deleteSelected() {
    if (!selected) return
    try {
      await deleteMail(selected.id)
      setSelected(null)
      await loadMessages()
    } catch (requestError) {
      setError(apiErrorMessage(requestError))
    }
  }

  async function submitSearch(event: FormEvent) {
    event.preventDefault()
    await loadMessages(folder, query)
  }

  async function submitDraft() {
    const recipients = draft.recipients.split(',').map((item) => item.trim()).filter(Boolean)
    if (!recipients.length || !draft.subject.trim() || !draft.body.trim()) {
      setError('Please fill recipients, subject, and body.')
      return
    }
    setSending(true)
    setError('')
    try {
      await sendMail({ recipients, subject: draft.subject.trim(), body: draft.body.trim() })
      setDraft({ recipients: '', subject: '', body: '' })
      setComposeOpen(false)
      setFolder('sent')
      await loadMessages('sent', '')
    } catch (requestError) {
      setError(apiErrorMessage(requestError))
    } finally {
      setSending(false)
    }
  }

  return (
    <Stack spacing={3}>
      <Stack direction={{ xs: 'column', md: 'row' }} justifyContent="space-between" gap={2}>
        <Box>
          <Typography variant="h4" fontWeight={700}>Mail</Typography>
          <Typography color="text.secondary">Read, search, send, and organize campus mail.</Typography>
        </Box>
        <Stack direction="row" spacing={1}>
          <Button variant="outlined" startIcon={<RefreshIcon />} onClick={() => loadMessages()}>Refresh</Button>
          <Button variant="contained" startIcon={<SendIcon />} onClick={() => setComposeOpen(true)}>Compose</Button>
        </Stack>
      </Stack>

      {error && <Alert severity="error" onClose={() => setError('')}>{error}</Alert>}

      <Paper sx={{ overflow: 'hidden' }}>
        <Stack direction={{ xs: 'column', md: 'row' }} sx={{ minHeight: 620 }}>
          <Box sx={{ width: { xs: '100%', md: 380 }, borderRight: { md: 1 }, borderColor: 'divider' }}>
            <Tabs
              value={folder}
              onChange={(_, value: MailFolder) => setFolder(value)}
              variant="scrollable"
              scrollButtons="auto"
              sx={{ px: 2, borderBottom: 1, borderColor: 'divider' }}
            >
              {folders.map((item) => <Tab key={item.value} value={item.value} label={item.label} />)}
            </Tabs>
            <Box component="form" onSubmit={submitSearch} sx={{ p: 2 }}>
              <TextField
                fullWidth
                size="small"
                label="Search mail"
                value={query}
                onChange={(event) => setQuery(event.target.value)}
              />
            </Box>
            <Divider />
            {loading ? (
              <Box sx={{ p: 4, textAlign: 'center' }}><CircularProgress /></Box>
            ) : messages.length ? (
              <List disablePadding>
                {messages.map((message) => (
                  <ListItemButton
                    key={message.id}
                    selected={selected?.id === message.id}
                    onClick={() => openMessage(message)}
                    sx={{ alignItems: 'flex-start', gap: 1, py: 1.5 }}
                  >
                    <MailOutlineIcon color={message.read ? 'disabled' : 'primary'} sx={{ mt: 0.4 }} />
                    <Box sx={{ minWidth: 0, flex: 1 }}>
                      <Stack direction="row" alignItems="center" gap={1}>
                        <Typography noWrap fontWeight={message.read ? 500 : 800} sx={{ flex: 1 }}>
                          {message.subject}
                        </Typography>
                        {message.starred && <StarIcon color="warning" fontSize="small" />}
                      </Stack>
                      <Typography noWrap variant="body2" color="text.secondary">{message.sender}</Typography>
                      <Typography noWrap variant="body2">{message.preview}</Typography>
                    </Box>
                  </ListItemButton>
                ))}
              </List>
            ) : (
              <Box sx={{ p: 4, textAlign: 'center' }}>
                <Typography variant="h6">No messages</Typography>
                <Typography color="text.secondary">Try another folder or search term.</Typography>
              </Box>
            )}
          </Box>

          <Box sx={{ flex: 1, minWidth: 0 }}>
            {detailLoading ? (
              <Box sx={{ p: 6, textAlign: 'center' }}><CircularProgress /></Box>
            ) : selected ? (
              <Stack spacing={2} sx={{ p: 3 }}>
                <Stack direction="row" alignItems="center" justifyContent="space-between" gap={2}>
                  <Box sx={{ minWidth: 0 }}>
                    <Typography variant="h5" fontWeight={700}>{selected.subject}</Typography>
                    <Typography color="text.secondary">From {selected.sender}</Typography>
                    <Typography color="text.secondary">To {selected.recipients.join(', ')}</Typography>
                  </Box>
                  <Chip label={selected.folder} />
                </Stack>
                <Stack direction="row" spacing={1}>
                  <Tooltip title={selected.starred ? 'Unstar' : 'Star'}>
                    <IconButton onClick={() => patchSelected({ starred: !selected.starred })}>
                      {selected.starred ? <StarIcon color="warning" /> : <StarBorderIcon />}
                    </IconButton>
                  </Tooltip>
                  <Tooltip title={selected.read ? 'Mark unread' : 'Mark read'}>
                    <IconButton onClick={() => patchSelected({ read: !selected.read })}>
                      {selected.read ? <MarkEmailUnreadIcon /> : <MarkEmailReadIcon />}
                    </IconButton>
                  </Tooltip>
                  {selected.folder !== 'archived' && selected.folder !== 'trash' && (
                    <Tooltip title="Archive">
                      <IconButton onClick={archiveSelected}><ArchiveIcon /></IconButton>
                    </Tooltip>
                  )}
                  {selected.folder !== 'trash' && (
                    <Tooltip title="Move to trash">
                      <IconButton onClick={deleteSelected}><DeleteOutlineIcon /></IconButton>
                    </Tooltip>
                  )}
                </Stack>
                <Divider />
                <Typography sx={{ whiteSpace: 'pre-wrap', lineHeight: 1.8 }}>{selected.body}</Typography>
              </Stack>
            ) : (
              <Box sx={{ minHeight: 520, display: 'grid', placeItems: 'center', p: 4, textAlign: 'center' }}>
                <Box>
                  <MailOutlineIcon color="disabled" sx={{ fontSize: 56 }} />
                  <Typography variant="h6">Select a message</Typography>
                  <Typography color="text.secondary">Choose an email from the list to read it here.</Typography>
                </Box>
              </Box>
            )}
          </Box>
        </Stack>
      </Paper>

      <Dialog open={composeOpen} onClose={() => !sending && setComposeOpen(false)} fullWidth maxWidth="sm">
        <DialogTitle>Compose mail</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ mt: 1 }}>
            <TextField
              label="Recipients"
              placeholder="name@campus.edu, team@campus.edu"
              value={draft.recipients}
              onChange={(event) => setDraft({ ...draft, recipients: event.target.value })}
            />
            <TextField
              label="Subject"
              value={draft.subject}
              onChange={(event) => setDraft({ ...draft, subject: event.target.value })}
            />
            <TextField
              label="Body"
              multiline
              minRows={8}
              value={draft.body}
              onChange={(event) => setDraft({ ...draft, body: event.target.value })}
            />
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setComposeOpen(false)} disabled={sending}>Cancel</Button>
          <Button variant="contained" startIcon={<SendIcon />} onClick={submitDraft} disabled={sending}>Send</Button>
        </DialogActions>
      </Dialog>
    </Stack>
  )
}
