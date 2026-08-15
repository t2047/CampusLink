import EventAvailableIcon from '@mui/icons-material/EventAvailable'
import EventBusyIcon from '@mui/icons-material/EventBusy'
import {
  Alert,
  Box,
  Button,
  Checkbox,
  Chip,
  CircularProgress,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Divider,
  FormControl,
  InputLabel,
  List,
  ListItem,
  ListItemButton,
  ListItemIcon,
  ListItemText,
  MenuItem,
  Select,
  Stack,
  Tooltip,
  Typography,
} from '@mui/material'
import { useState } from 'react'
import { apiErrorMessage } from '../api/client'
import {
  extractCalendarSchedules,
  importCalendarSchedules,
} from '../api/mailCalendar'
import type { ExtractedSchedule } from '../types'

export interface ScheduleImportDialogProps {
  open: boolean
  onClose: () => void
  /** Called after a successful import so the parent can refresh its calendar. */
  onImported?: (imported: number) => void
}

const dayOptions = [
  { value: 0, label: 'Today only' },
  { value: 1, label: 'Today + yesterday' },
  { value: 2, label: 'Last 3 days' },
  { value: 3, label: 'Last 4 days' },
  { value: 7, label: 'Last 8 days' },
]

function formatWhen(schedule: ExtractedSchedule): string {
  if (schedule.all_day) {
    return schedule.start_time.slice(0, 10)
  }
  const start = schedule.start_time.replace('T', ' ').slice(0, 16)
  const end = schedule.end_time.replace('T', ' ').slice(11, 16)
  return `${start} – ${end}`
}

/**
 * Extract schedules from recent emails and import them into the calendar.
 * Nothing is written until the user reviews the proposals and confirms.
 */
export function ScheduleImportDialog({ open, onClose, onImported }: ScheduleImportDialogProps) {
  const [days, setDays] = useState(0)
  const [extracting, setExtracting] = useState(false)
  const [importing, setImporting] = useState(false)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [proposals, setProposals] = useState<ExtractedSchedule[]>([])
  const [selectedKeys, setSelectedKeys] = useState<Set<string>>(new Set())
  const [done, setDone] = useState(false)
  const [mode, setMode] = useState<'llm' | 'rules' | null>(null)

  function reset() {
    setError('')
    setNotice('')
    setProposals([])
    setSelectedKeys(new Set())
    setDone(false)
    setMode(null)
  }

  function close() {
    if (extracting || importing) return
    reset()
    onClose()
  }

  async function runExtract() {
    setExtracting(true)
    setError('')
    setNotice('')
    setDone(false)
    try {
      const result = await extractCalendarSchedules(days)
      setProposals(result.events)
      setMode(result.mode)
      setSelectedKeys(new Set(result.events.map((event) => event.key)))
      if (!result.events.length) {
        setNotice(
          days === 0
            ? 'No schedules found in today\'s mail.'
            : `No schedules found in the last ${days + 1} days of mail.`,
        )
      }
    } catch (requestError) {
      const message = apiErrorMessage(requestError)
      if (requestError && typeof requestError === 'object' && 'response' in requestError) {
        const body = (requestError as { response?: { data?: { code?: string; auth_url?: string } } }).response?.data
        if (body?.code === 'GMAIL_NOT_CONNECTED') {
          setError('Gmail is not connected. Connect it from the Mail page first, then try again.')
          setDone(true)
          return
        }
      }
      setError(message)
    } finally {
      setExtracting(false)
    }
  }

  function toggle(key: string) {
    setSelectedKeys((current) => {
      const next = new Set(current)
      if (next.has(key)) {
        next.delete(key)
      } else {
        next.add(key)
      }
      return next
    })
  }

  async function confirmImport() {
    const chosen = proposals.filter((proposal) => selectedKeys.has(proposal.key))
    if (!chosen.length) {
      setError('Select at least one schedule to import.')
      return
    }
    setImporting(true)
    setError('')
    try {
      const result = await importCalendarSchedules(chosen)
      setProposals([])
      setSelectedKeys(new Set())
      setDone(true)
      setNotice(`Imported ${result.imported} schedule${result.imported === 1 ? '' : 's'}.`)
      if (result.skipped > 0) {
        setNotice((current) => `${current} Skipped ${result.skipped} duplicate${result.skipped === 1 ? '' : 's'} already in your calendar.`)
      }
      onImported?.(result.imported)
    } catch (requestError) {
      setError(apiErrorMessage(requestError))
    } finally {
      setImporting(false)
    }
  }

  return (
    <Dialog open={open} onClose={close} fullWidth maxWidth="sm">
      <DialogTitle>Import schedules from mail</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          <Typography variant="body2" color="text.secondary">
            Scan your recent emails for date and time mentions, then review and confirm
            what gets added to your calendar.
          </Typography>

          <FormControl fullWidth size="small">
            <InputLabel id="extract-days-label">Mail window</InputLabel>
            <Select
              labelId="extract-days-label"
              label="Mail window"
              value={days}
              disabled={extracting || importing}
              onChange={(event) => setDays(Number(event.target.value))}
            >
              {dayOptions.map((option) => (
                <MenuItem key={option.value} value={option.value}>{option.label}</MenuItem>
              ))}
            </Select>
          </FormControl>

          <Button
            variant="outlined"
            startIcon={extracting ? <CircularProgress size={18} /> : <EventAvailableIcon />}
            onClick={() => void runExtract()}
            disabled={extracting || importing}
            sx={{ alignSelf: 'flex-start' }}
          >
            {extracting ? 'Scanning mail…' : 'Scan mail for schedules'}
          </Button>

          {notice && <Alert severity="success" onClose={() => setNotice('')}>{notice}</Alert>}
          {error && <Alert severity="error" onClose={() => setError('')}>{error}</Alert>}

          {proposals.length > 0 && (
            <>
              <Divider />
              <Stack direction="row" alignItems="center" justifyContent="space-between">
                <Typography variant="subtitle1" fontWeight={700}>
                  {proposals.length} schedule{proposals.length === 1 ? '' : 's'} found
                </Typography>
                <Stack direction="row" spacing={1} alignItems="center">
                  {mode === 'llm' && (
                    <Tooltip title="Schedules were recognised by the DeepSeek model.">
                      <Chip size="small" color="secondary" variant="outlined" label="AI extracted" />
                    </Tooltip>
                  )}
                  {mode === 'rules' && (
                    <Tooltip title="Schedules were recognised by the built-in pattern parser.">
                      <Chip size="small" variant="outlined" label="Rule parser" />
                    </Tooltip>
                  )}
                  <Typography variant="caption" color="text.secondary">
                    {selectedKeys.size} selected
                  </Typography>
                </Stack>
              </Stack>
              <List dense disablePadding sx={{ maxHeight: 320, overflowY: 'auto' }}>
                {proposals.map((proposal) => (
                  <ListItem key={proposal.key} disablePadding>
                    <ListItemButton onClick={() => toggle(proposal.key)}>
                      <ListItemIcon>
                        <Checkbox
                          edge="start"
                          checked={selectedKeys.has(proposal.key)}
                          tabIndex={-1}
                          disableRipple
                        />
                      </ListItemIcon>
                      <ListItemText
                        primary={
                          <Stack direction="row" alignItems="center" gap={1}>
                            <Typography variant="body2" fontWeight={600}>{proposal.title}</Typography>
                            {proposal.source_email_id && (
                              <Chip size="small" variant="outlined" color="primary" label="from mail" />
                            )}
                          </Stack>
                        }
                        secondary={
                          <>
                            <Typography variant="caption" component="span" display="block">
                              {formatWhen(proposal)}
                            </Typography>
                            {proposal.location && (
                              <Typography variant="caption" component="span" display="block" color="text.secondary">
                                📍 {proposal.location}
                              </Typography>
                            )}
                            {proposal.email_subject && (
                              <Typography variant="caption" component="span" display="block" color="text.secondary" noWrap>
                                Email: {proposal.email_subject}
                              </Typography>
                            )}
                          </>
                        }
                      />
                    </ListItemButton>
                  </ListItem>
                ))}
              </List>
            </>
          )}

          {done && !proposals.length && (
            <Box sx={{ textAlign: 'center', py: 2, color: 'text.secondary' }}>
              <EventBusyIcon sx={{ fontSize: 44 }} color="disabled" />
              <Typography variant="body2">You can close this dialog now.</Typography>
            </Box>
          )}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={close} disabled={extracting || importing}>Close</Button>
        {proposals.length > 0 && !done && (
          <Button
            variant="contained"
            startIcon={importing ? <CircularProgress size={18} color="inherit" /> : <EventAvailableIcon />}
            onClick={() => void confirmImport()}
            disabled={importing || selectedKeys.size === 0}
          >
            {importing ? 'Importing…' : `Import ${selectedKeys.size} to calendar`}
          </Button>
        )}
      </DialogActions>
    </Dialog>
  )
}
