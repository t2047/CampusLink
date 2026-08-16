import ArchiveIcon from '@mui/icons-material/Archive'
import ChatBubbleOutlineIcon from '@mui/icons-material/ChatBubbleOutline'
import ChevronLeftIcon from '@mui/icons-material/ChevronLeft'
import ChevronRightIcon from '@mui/icons-material/ChevronRight'
import CloseIcon from '@mui/icons-material/Close'
import DeleteOutlineIcon from '@mui/icons-material/DeleteOutline'
import EventAvailableIcon from '@mui/icons-material/EventAvailable'
import MailOutlineIcon from '@mui/icons-material/MailOutline'
import MarkEmailReadIcon from '@mui/icons-material/MarkEmailRead'
import MarkEmailUnreadIcon from '@mui/icons-material/MarkEmailUnread'
import RefreshIcon from '@mui/icons-material/Refresh'
import SearchIcon from '@mui/icons-material/Search'
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
  Drawer,
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
import { archiveMail, deleteMail, disconnectMail, getMailMessage, getMailOAuthStatus, getMailOAuthUrl, listMail, sendMail, updateMail } from '../api/mail'
import { MailAgentPanel } from '../components/MailAgentPanel'
import { ScheduleImportDialog } from '../components/ScheduleImportDialog'
import type { MailCategory, MailFolder, MailMessage } from '../types'

const folders: Array<{ value: MailFolder; label: string }> = [
  { value: 'inbox', label: 'Inbox' },
  { value: 'sent', label: 'Sent' },
  { value: 'archived', label: 'Archived' },
  { value: 'trash', label: 'Trash' },
  { value: 'spam', label: 'Spam' },
]

const categoryMeta: Record<MailCategory, { label: string; color: 'success' | 'info' | 'warning' | 'default' }> = {
  campus: { label: 'Campus', color: 'success' },
  career: { label: 'Career', color: 'info' },
  finance: { label: 'Finance', color: 'warning' },
  other: { label: 'Other', color: 'default' },
}

function formatMailDate(value: string): string {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return value
  }
  const now = new Date()
  const sameDay = date.toDateString() === now.toDateString()
  const yesterday = new Date(now)
  yesterday.setDate(now.getDate() - 1)
  const isYesterday = date.toDateString() === yesterday.toDateString()
  if (sameDay) {
    return date.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' })
  }
  if (isYesterday) {
    return `Yesterday ${date.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' })}`
  }
  return date.toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' })
}

function formatMailDateTime(value: string): string {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return value
  }
  return date.toLocaleString(undefined, {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    weekday: 'short',
    hour: '2-digit',
    minute: '2-digit',
  })
}

export function MailPage() {
  const [folder, setFolder] = useState<MailFolder>('inbox')
  const [query, setQuery] = useState('')
  const [page, setPage] = useState(0)
  const [totalPages, setTotalPages] = useState(0)
  const [messages, setMessages] = useState<MailMessage[]>([])
  const [selected, setSelected] = useState<MailMessage | null>(null)
  const [loading, setLoading] = useState(true)
  const [detailLoading, setDetailLoading] = useState(false)
  const [error, setError] = useState('')
  const [composeOpen, setComposeOpen] = useState(false)
  const [sending, setSending] = useState(false)
  const [draft, setDraft] = useState({ recipients: '', subject: '', body: '' })
  const [connected, setConnected] = useState<boolean | null>(null)
  const [connectUrl, setConnectUrl] = useState('')
  const [notice, setNotice] = useState('')
  const [assistantOpen, setAssistantOpen] = useState(false)
  const [importOpen, setImportOpen] = useState(false)

  async function loadMessages(nextFolder = folder, nextQuery = query, nextPage = page) {
    setLoading(true)
    setError('')
    try {
      const result = await listMail({ folder: nextFolder, q: nextQuery || undefined, page: nextPage, size: 5 })
      setPage(nextPage)
      setTotalPages(result.total_pages)
      setMessages(result.content)
      if (!result.content.some((message) => message.id === selected?.id)) {
        setSelected(null)
      }
    } catch (requestError) {
      if (
        requestError &&
        typeof requestError === 'object' &&
        'response' in requestError &&
        (requestError as { response?: { status?: number } }).response?.status === 409
      ) {
        setConnected(false)
        const data = (requestError as { response?: { data?: { auth_url?: string } } }).response?.data
        setConnectUrl(data?.auth_url ?? '')
        setMessages([])
      } else {
        setError(apiErrorMessage(requestError))
      }
    } finally {
      setLoading(false)
    }
  }

  async function refreshConnection(): Promise<boolean> {
    try {
      const status = await getMailOAuthStatus()
      setConnected(status.connected)
      if (!status.connected) {
        setConnectUrl((await getMailOAuthUrl()).auth_url)
      }
      return status.connected
    } catch {
      setConnected(true)
      return true
    }
  }

  async function disconnectGmail() {
    if (!window.confirm('Disconnect Gmail? You will need to re-authorize to use mail again.')) {
      return
    }
    try {
      await disconnectMail()
      setConnected(false)
      setMessages([])
      setSelected(null)
      setTotalPages(0)
      setPage(0)
      setConnectUrl((await getMailOAuthUrl()).auth_url)
    } catch (requestError) {
      setError(apiErrorMessage(requestError))
    }
  }

  useEffect(() => {
    const params = new URLSearchParams(window.location.search)
    if (params.get('connected') === '1') {
      setNotice('Gmail connected successfully.')
      window.history.replaceState({}, '', window.location.pathname)
    }
    void (async () => {
      const ok = await refreshConnection()
      if (ok) {
        await loadMessages(folder, query, 0)
      } else {
        setLoading(false)
        setMessages([])
      }
    })()
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
    await loadMessages(folder, query, 0)
  }

  async function goToPreviousPage() {
    if (page <= 0) return
    await loadMessages(folder, query, page - 1)
  }

  async function goToNextPage() {
    if (page + 1 >= totalPages) return
    await loadMessages(folder, query, page + 1)
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
    <Stack spacing={2} sx={{ height: 'calc(100vh - 3rem)', overflow: 'hidden' }}>
      <Stack direction={{ xs: 'column', md: 'row' }} justifyContent="space-between" gap={2}>
        <Stack direction="row" spacing={2} alignItems="center">
          <Box
            sx={{
              width: 46,
              height: 46,
              borderRadius: 3,
              display: 'grid',
              placeItems: 'center',
              flexShrink: 0,
              color: '#fff',
              background: 'linear-gradient(135deg, #3366b8 0%, #1d4f9e 100%)',
              boxShadow: '0 4px 12px rgba(35, 86, 168, 0.3)',
            }}
          >
            <MailOutlineIcon />
          </Box>
          <Box>
            <Typography variant="h4" fontWeight={700}>Mail</Typography>
            <Typography color="text.secondary">Read, search, send, and organize campus mail.</Typography>
          </Box>
        </Stack>
        <Stack direction="row" spacing={1}>
          {connected === true && (
            <Button variant="outlined" color="error" onClick={disconnectGmail}>
              Disconnect
            </Button>
          )}
          {connected === false && (
            <Button
              variant="contained"
              color="success"
              onClick={() => connectUrl && window.location.assign(connectUrl)}
            >
              Connect Gmail
            </Button>
          )}
          {connected === true && (
            <Button
              variant="outlined"
              startIcon={<ChatBubbleOutlineIcon />}
              onClick={() => setAssistantOpen(true)}
            >
              Assistant
            </Button>
          )}
          {connected === true && (
            <Button
              variant="outlined"
              startIcon={<EventAvailableIcon />}
              onClick={() => setImportOpen(true)}
            >
              Import to Calendar
            </Button>
          )}
          <Button variant="outlined" startIcon={<RefreshIcon />} onClick={() => loadMessages()}>Refresh</Button>
          <Button variant="contained" startIcon={<SendIcon />} onClick={() => setComposeOpen(true)}>Compose</Button>
        </Stack>
      </Stack>

      {notice && <Alert severity="success" onClose={() => setNotice('')}>{notice}</Alert>}
      {error && <Alert severity="error" onClose={() => setError('')}>{error}</Alert>}

      <Paper
        elevation={0}
        sx={{
          minHeight: 0,
          flex: 1,
          overflow: 'hidden',
          border: 1,
          borderColor: '#e2e8f0',
          borderRadius: 3,
          boxShadow: '0 1px 3px rgba(15, 23, 42, 0.06)',
        }}
      >
        <Stack direction={{ xs: 'column', md: 'row' }} sx={{ height: '100%', minHeight: '100%' }}>
          <Box sx={{ display: 'flex', width: { xs: '100%', md: 380 }, flexDirection: 'column', borderRight: { md: 1 }, borderColor: 'divider' }}>
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
                slotProps={{
                  input: {
                    startAdornment: (
                      <SearchIcon fontSize="small" sx={{ color: 'text.secondary', mr: 1 }} />
                    ),
                  },
                }}
              />
            </Box>
            <Divider />
            {loading ? (
              <Box sx={{ p: 4, textAlign: 'center' }}><CircularProgress /></Box>
            ) : messages.length ? (
              <List disablePadding sx={{ px: 1, py: 1 }}>
                {messages.map((message) => {
                  const unread = !message.read
                  return (
                    <ListItemButton
                      key={message.id}
                      selected={selected?.id === message.id}
                      onClick={() => openMessage(message)}
                      sx={{
                        alignItems: 'flex-start',
                        gap: 1.25,
                        py: 1.5,
                        px: 1.75,
                        mb: 0.5,
                        borderRadius: 2,
                        border: 1,
                        borderColor: 'transparent',
                        transition: 'background-color .15s ease, border-color .15s ease',
                        '&:hover': { bgcolor: 'action.hover' },
                        '&.Mui-selected': {
                          bgcolor: 'rgba(35, 86, 168, 0.08)',
                          borderColor: 'rgba(35, 86, 168, 0.25)',
                        },
                      }}
                    >
                      <MailOutlineIcon color={unread ? 'primary' : 'disabled'} sx={{ mt: 0.4 }} />
                      <Box sx={{ minWidth: 0, flex: 1 }}>
                        <Stack direction="row" alignItems="center" gap={1}>
                          {unread && (
                            <Box
                              sx={{
                                width: 8,
                                height: 8,
                                borderRadius: '50%',
                                bgcolor: 'primary.main',
                                flexShrink: 0,
                                boxShadow: '0 0 0 2px rgba(35, 86, 168, 0.15)',
                              }}
                            />
                          )}
                          <Typography noWrap fontWeight={unread ? 800 : 500} sx={{ flex: 1 }}>
                            {message.subject}
                          </Typography>
                          {message.starred && <StarIcon color="warning" fontSize="small" />}
                          <Chip
                            size="small"
                            variant="outlined"
                            color={categoryMeta[message.category].color}
                            label={categoryMeta[message.category].label}
                          />
                        </Stack>
                        <Typography noWrap variant="body2" color="text.secondary" fontWeight={unread ? 600 : 400}>
                          {message.sender}
                        </Typography>
                        <Stack direction="row" alignItems="center" justifyContent="space-between" gap={1}>
                          <Typography noWrap variant="body2">{message.preview}</Typography>
                          <Typography noWrap variant="caption" color="text.secondary" sx={{ flexShrink: 0 }}>
                            {formatMailDate(message.created_at)}
                          </Typography>
                        </Stack>
                      </Box>
                    </ListItemButton>
                  )
                })}
              </List>
            ) : (
              <Box sx={{ display: 'grid', flex: 1, placeItems: 'center', p: 4, textAlign: 'center' }}>
                <Box
                  sx={{
                    width: 84,
                    height: 84,
                    borderRadius: '50%',
                    display: 'grid',
                    placeItems: 'center',
                    bgcolor: 'rgba(35, 86, 168, 0.08)',
                    mx: 'auto',
                    mb: 1.5,
                  }}
                >
                  <MailOutlineIcon color="disabled" sx={{ fontSize: 40 }} />
                </Box>
                <Typography variant="h6">No messages</Typography>
                <Typography color="text.secondary">Try another folder or search term.</Typography>
              </Box>
            )}
            {totalPages > 0 && (
              <Stack
                direction="row"
                alignItems="center"
                justifyContent="center"
                spacing={1}
                sx={{ p: 2, borderTop: 1, borderColor: 'divider' }}
              >
                <Button size="small" disabled={page <= 0} onClick={goToPreviousPage} startIcon={<ChevronLeftIcon />}>
                  Prev
                </Button>
                <Typography variant="body2" sx={{ minWidth: 90, textAlign: 'center' }}>
                  Page {page + 1} / {totalPages}
                </Typography>
                <Button size="small" disabled={page + 1 >= totalPages} onClick={goToNextPage} endIcon={<ChevronRightIcon />}>
                  Next
                </Button>
              </Stack>
            )}
          </Box>

          <Box sx={{ display: 'flex', minWidth: 0, flex: 1, flexDirection: 'column' }}>
            {detailLoading ? (
              <Box sx={{ p: 6, textAlign: 'center' }}><CircularProgress /></Box>
            ) : selected ? (
              <Stack spacing={2} sx={{ p: 3 }}>
                <Stack direction="row" alignItems="center" justifyContent="space-between" gap={2}>
                  <Box sx={{ minWidth: 0 }}>
                    <Typography variant="h5" fontWeight={700}>{selected.subject}</Typography>
                    <Typography color="text.secondary">From {selected.sender}</Typography>
                    <Typography color="text.secondary">To {selected.recipients.join(', ')}</Typography>
                    <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>
                      {formatMailDateTime(selected.created_at)}
                    </Typography>
                  </Box>
                  <Stack direction="row" spacing={1} alignItems="center">
                    <Chip label={selected.folder} />
                    <Chip
                      color={categoryMeta[selected.category].color}
                      label={categoryMeta[selected.category].label}
                    />
                  </Stack>
                </Stack>
                <Stack
                  direction="row"
                  spacing={0.5}
                  alignItems="center"
                  sx={{
                    alignSelf: 'flex-start',
                    borderRadius: 2,
                    border: 1,
                    borderColor: 'divider',
                    p: 0.5,
                    bgcolor: (theme) => (theme.palette.mode === 'dark' ? '#0f172a' : 'grey.50'),
                  }}
                >
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
                {selected.body_html ? (
                  <Box
                    sx={{
                      border: 1,
                      borderColor: 'divider',
                      borderRadius: 2,
                      overflow: 'hidden',
                      boxShadow: '0 1px 3px rgba(15, 23, 42, 0.06)',
                    }}
                  >
                    <iframe
                      title="Email body"
                      sandbox="allow-popups"
                      srcDoc={selected.body_html}
                      style={{ width: '100%', height: '60vh', border: 'none', background: '#fff', display: 'block' }}
                    />
                  </Box>
                ) : (
                  <Typography sx={{ whiteSpace: 'pre-wrap', lineHeight: 1.8 }}>
                    {selected.body}
                  </Typography>
                )}
              </Stack>
            ) : (
              <Box sx={{ display: 'grid', minHeight: 0, flex: 1, placeItems: 'center', p: 4, textAlign: 'center' }}>
                <Box>
                  <Box
                    sx={{
                      width: 96,
                      height: 96,
                      borderRadius: '50%',
                      display: 'grid',
                      placeItems: 'center',
                      bgcolor: 'rgba(35, 86, 168, 0.08)',
                      mx: 'auto',
                      mb: 2,
                    }}
                  >
                    <MailOutlineIcon color="disabled" sx={{ fontSize: 52 }} />
                  </Box>
                  <Typography variant="h6">Select a message</Typography>
                  <Typography color="text.secondary">Choose an email from the list to read it here.</Typography>
                </Box>
              </Box>
            )}
          </Box>
        </Stack>
      </Paper>

      <Dialog open={composeOpen} onClose={() => !sending && setComposeOpen(false)} fullWidth maxWidth="sm">
        <DialogTitle sx={{ display: 'flex', alignItems: 'center', gap: 1.5 }}>
          <Box
            sx={{
              width: 34,
              height: 34,
              borderRadius: 2,
              display: 'grid',
              placeItems: 'center',
              color: '#fff',
              background: 'linear-gradient(135deg, #3366b8 0%, #1d4f9e 100%)',
              boxShadow: '0 2px 8px rgba(35, 86, 168, 0.3)',
            }}
          >
            <SendIcon fontSize="small" />
          </Box>
          Compose mail
        </DialogTitle>
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
        <DialogActions sx={{ px: 3, pb: 2 }}>
          <Button onClick={() => setComposeOpen(false)} disabled={sending}>Cancel</Button>
          <Button variant="contained" startIcon={<SendIcon />} onClick={submitDraft} disabled={sending}>Send</Button>
        </DialogActions>
      </Dialog>

      <Drawer
        anchor="right"
        open={assistantOpen}
        onClose={() => setAssistantOpen(false)}
        sx={{ '& .MuiDrawer-paper': { width: { xs: '100%', sm: 420 } } }}
      >
        <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', px: 2, py: 1.5, borderBottom: 1, borderColor: 'divider' }}>
          <Typography variant="h6" fontWeight={700}>Mail Assistant</Typography>
          <IconButton aria-label="Close assistant" onClick={() => setAssistantOpen(false)}>
            <CloseIcon />
          </IconButton>
        </Box>
        <Box sx={{ flex: 1, minHeight: 0, p: 2 }}>
          <MailAgentPanel />
        </Box>
      </Drawer>

      <ScheduleImportDialog
        open={importOpen}
        onClose={() => setImportOpen(false)}
        onImported={(count) => {
          setNotice(`Imported ${count} schedule${count === 1 ? '' : 's'} into your calendar.`)
        }}
      />
    </Stack>
  )
}
