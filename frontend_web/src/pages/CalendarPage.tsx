import AddIcon from '@mui/icons-material/Add'
import ChevronLeftIcon from '@mui/icons-material/ChevronLeft'
import ChevronRightIcon from '@mui/icons-material/ChevronRight'
import DeleteOutlineIcon from '@mui/icons-material/DeleteOutline'
import EditOutlinedIcon from '@mui/icons-material/EditOutlined'
import EventAvailableIcon from '@mui/icons-material/EventAvailable'
import EventNoteIcon from '@mui/icons-material/EventNote'
import MailOutlineIcon from '@mui/icons-material/MailOutline'
import TodayIcon from '@mui/icons-material/Today'
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
  FormControlLabel,
  IconButton,
  Paper,
  Stack,
  TextField,
  Tooltip,
  Typography,
} from '@mui/material'
import { FormEvent, useEffect, useMemo, useState } from 'react'
import { apiErrorMessage } from '../api/client'
import {
  createCalendarEvent,
  deleteCalendarEvent,
  listCalendarEvents,
  updateCalendarEvent,
} from '../api/mailCalendar'
import { ScheduleImportDialog } from '../components/ScheduleImportDialog'
import type { CalendarEvent, CalendarEventInput } from '../types'

const WEEKDAY_LABELS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat']

function toDateKey(date: Date): string {
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

function fromDateKey(key: string): Date {
  const [year, month, day] = key.split('-').map(Number)
  return new Date(year, month - 1, day)
}

function toInputDateTime(date: Date, time: string): string {
  const base = toDateKey(date)
  return `${base}T${time || '00:00'}`
}

function formatEventWhen(event: CalendarEvent): string {
  if (event.all_day) {
    return event.start_time.slice(0, 10)
  }
  const start = event.start_time.replace('T', ' ').slice(0, 16)
  const end = event.end_time.replace('T', ' ').slice(11, 16)
  return `${start} – ${end}`
}

interface EventDialogState {
  open: boolean
  editing: CalendarEvent | null
  date: string
  title: string
  location: string
  description: string
  allDay: boolean
  startTime: string
  endTime: string
}

const emptyDialog = (date: string): EventDialogState => ({
  open: true,
  editing: null,
  date,
  title: '',
  location: '',
  description: '',
  allDay: false,
  startTime: '09:00',
  endTime: '10:00',
})

export function CalendarPage() {
  const today = new Date()
  const [cursor, setCursor] = useState(() => new Date(today.getFullYear(), today.getMonth(), 1))
  const [events, setEvents] = useState<CalendarEvent[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [importOpen, setImportOpen] = useState(false)
  const [dialog, setDialog] = useState<EventDialogState | null>(null)
  const [saving, setSaving] = useState(false)

  const rangeStart = useMemo(() => {
    const first = new Date(cursor.getFullYear(), cursor.getMonth(), 1)
    const start = new Date(first)
    start.setDate(first.getDate() - first.getDay())
    return start
  }, [cursor])

  const rangeEnd = useMemo(() => {
    const end = new Date(rangeStart)
    end.setDate(rangeStart.getDate() + 42)
    return end
  }, [rangeStart])

  const cells = useMemo(() => {
    const list: Date[] = []
    for (let index = 0; index < 42; index += 1) {
      const cell = new Date(rangeStart)
      cell.setDate(rangeStart.getDate() + index)
      list.push(cell)
    }
    return list
  }, [rangeStart])

  const eventsByDay = useMemo(() => {
    const map = new Map<string, CalendarEvent[]>()
    for (const event of events) {
      const key = event.start_time.slice(0, 10)
      const bucket = map.get(key) ?? []
      bucket.push(event)
      map.set(key, bucket)
    }
    for (const bucket of map.values()) {
      bucket.sort((a, b) => a.start_time.localeCompare(b.start_time))
    }
    return map
  }, [events])

  async function loadEvents() {
    setLoading(true)
    setError('')
    try {
      const result = await listCalendarEvents(
        toInputDateTime(rangeStart, '00:00'),
        toInputDateTime(rangeEnd, '00:00'),
      )
      setEvents(result)
    } catch (requestError) {
      setError(apiErrorMessage(requestError))
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    void loadEvents()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [rangeStart, rangeEnd])

  function moveMonth(delta: number) {
    setCursor((current) => new Date(current.getFullYear(), current.getMonth() + delta, 1))
  }

  function openCreate(date: Date) {
    setDialog(emptyDialog(toDateKey(date)))
  }

  function openEdit(event: CalendarEvent) {
    setDialog({
      open: true,
      editing: event,
      date: event.start_time.slice(0, 10),
      title: event.title,
      location: event.location,
      description: event.description,
      allDay: event.all_day,
      startTime: event.all_day ? '09:00' : event.start_time.slice(11, 16),
      endTime: event.all_day ? '10:00' : event.end_time.slice(11, 16),
    })
  }

  async function submitEvent(event: FormEvent) {
    event.preventDefault()
    if (!dialog) return
    const title = dialog.title.trim()
    if (!title) {
      setError('Please enter a title.')
      return
    }
    const input: CalendarEventInput = {
      title,
      location: dialog.location.trim(),
      description: dialog.description.trim(),
      all_day: dialog.allDay,
      start_time: dialog.allDay
        ? `${dialog.date}T00:00:00`
        : toInputDateTime(fromDateKey(dialog.date), dialog.startTime),
      end_time: dialog.allDay
        ? `${dialog.date}T23:59:59`
        : toInputDateTime(fromDateKey(dialog.date), dialog.endTime),
    }
    setSaving(true)
    setError('')
    try {
      if (dialog.editing) {
        await updateCalendarEvent(dialog.editing.id, input)
      } else {
        await createCalendarEvent(input)
      }
      setDialog(null)
      await loadEvents()
    } catch (requestError) {
      setError(apiErrorMessage(requestError))
    } finally {
      setSaving(false)
    }
  }

  async function removeEvent() {
    if (!dialog?.editing) return
    if (!window.confirm(`Delete "${dialog.editing.title}"?`)) return
    setSaving(true)
    setError('')
    try {
      await deleteCalendarEvent(dialog.editing.id)
      setDialog(null)
      await loadEvents()
    } catch (requestError) {
      setError(apiErrorMessage(requestError))
    } finally {
      setSaving(false)
    }
  }

  const monthLabel = cursor.toLocaleDateString(undefined, { year: 'numeric', month: 'long' })
  const todayKey = toDateKey(today)
  const isCurrentMonth = cursor.getFullYear() === today.getFullYear() && cursor.getMonth() === today.getMonth()
  const hasEvents = events.length > 0

  return (
    <Stack spacing={2} sx={{ minHeight: 'calc(100vh - 3rem)' }}>
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
              background: 'linear-gradient(135deg, #14958b 0%, #0f766e 100%)',
              boxShadow: '0 4px 12px rgba(15, 118, 110, 0.3)',
            }}
          >
            <EventNoteIcon />
          </Box>
          <Box>
            <Typography variant="h4" fontWeight={700}>Calendar</Typography>
            <Typography color="text.secondary">
              Manage your schedules and import events found in your mail.
            </Typography>
          </Box>
        </Stack>
        <Stack direction="row" spacing={1} flexWrap="wrap" alignItems="center">
          <Button
            variant="outlined"
            startIcon={<EventAvailableIcon />}
            onClick={() => setImportOpen(true)}
            sx={{ height: 45, minHeight: 45 }}
          >
            Import from mail
          </Button>
          <Button
            variant="contained"
            startIcon={<AddIcon />}
            onClick={() => openCreate(today)}
            sx={{ height: 45, minHeight: 45 }}
          >
            New event
          </Button>
        </Stack>
      </Stack>

      {notice && <Alert severity="success" onClose={() => setNotice('')}>{notice}</Alert>}
      {error && <Alert severity="error" onClose={() => setError('')}>{error}</Alert>}

      <Paper
        elevation={0}
        sx={{
          display: 'flex',
          flexGrow: 1,
          minHeight: 520,
          flexDirection: 'column',
          border: 1,
          borderColor: '#e2e8f0',
          borderRadius: 3,
          overflow: 'hidden',
          boxShadow: '0 1px 3px rgba(15, 23, 42, 0.06)',
        }}
      >
        <Stack
          direction="row"
          alignItems="center"
          justifyContent="space-between"
          sx={{ px: 2, py: 1.5, borderBottom: 1, borderColor: 'divider' }}
        >
          <Stack direction="row" alignItems="center" spacing={1}>
            <Tooltip title="Previous month">
              <IconButton onClick={() => moveMonth(-1)} aria-label="Previous month">
                <ChevronLeftIcon />
              </IconButton>
            </Tooltip>
            <Tooltip title="Next month">
              <IconButton onClick={() => moveMonth(1)} aria-label="Next month">
                <ChevronRightIcon />
              </IconButton>
            </Tooltip>
            <Button
              size="small"
              variant={isCurrentMonth ? 'contained' : 'outlined'}
              startIcon={<TodayIcon />}
              onClick={() => setCursor(new Date(today.getFullYear(), today.getMonth(), 1))}
            >
              Today
            </Button>
          </Stack>
          <Typography variant="h6" fontWeight={700}>{monthLabel}</Typography>
          {loading ? <CircularProgress size={20} /> : <Box sx={{ width: 20 }} />}
        </Stack>

        <Box sx={{ display: 'grid', flex: 1, gridTemplateColumns: 'repeat(7, 1fr)', gridAutoRows: 'minmax(0, 1fr)' }}>
          {WEEKDAY_LABELS.map((label, index) => (
            <Box
              key={label}
              sx={{
                py: 1,
                textAlign: 'center',
                typography: 'caption',
                fontWeight: 700,
                color: 'text.secondary',
                borderBottom: 1,
                borderColor: 'divider',
                bgcolor: index === 0 || index === 6 ? 'rgba(35, 86, 168, 0.04)' : 'transparent',
              }}
            >
              {label}
            </Box>
          ))}
          {cells.map((cell, index) => {
            const key = toDateKey(cell)
            const column = index % 7
            const isWeekend = column === 0 || column === 6
            const inMonth = cell.getMonth() === cursor.getMonth()
            const isToday = key === todayKey
            const dayEvents = eventsByDay.get(key) ?? []
            return (
              <Box
                key={key}
                onClick={() => openCreate(cell)}
                sx={{
                  minHeight: { xs: 84, md: 0 },
                  p: 0.75,
                  borderRight: 1,
                  borderBottom: 1,
                  borderColor: 'divider',
                  bgcolor: inMonth
                    ? isWeekend ? 'rgba(35, 86, 168, 0.03)' : 'background.paper'
                    : 'action.hover',
                  cursor: 'pointer',
                  transition: 'background-color .15s ease',
                  '&:hover': { bgcolor: 'rgba(35, 86, 168, 0.08)' },
                  overflow: 'hidden',
                }}
              >
                <Stack spacing={0.5}>
                  <Box
                    sx={{
                      width: 26,
                      height: 26,
                      display: 'grid',
                      placeItems: 'center',
                      borderRadius: '50%',
                      typography: 'body2',
                      fontWeight: isToday ? 800 : inMonth ? 600 : 400,
                      color: isToday ? 'primary.contrastText' : inMonth ? 'text.primary' : 'text.disabled',
                      bgcolor: isToday ? 'primary.main' : 'transparent',
                      boxShadow: isToday ? '0 2px 8px rgba(35, 86, 168, 0.45)' : 'none',
                    }}
                  >
                    {cell.getDate()}
                  </Box>
                  {dayEvents.slice(0, 3).map((event) => (
                    <Chip
                      key={event.id}
                      size="small"
                      color={event.source === 'mail' ? 'primary' : 'secondary'}
                      variant={event.source === 'mail' ? 'filled' : 'outlined'}
                      icon={event.source === 'mail' ? <MailOutlineIcon /> : <EventNoteIcon />}
                      label={event.all_day ? event.title : `${event.start_time.slice(11, 16)} ${event.title}`}
                      onClick={(click) => {
                        click.stopPropagation()
                        openEdit(event)
                      }}
                      sx={{
                        justifyContent: 'flex-start',
                        width: '100%',
                        fontWeight: 600,
                        boxShadow: event.source === 'mail' ? '0 2px 6px rgba(35, 86, 168, 0.25)' : 'none',
                        '& .MuiChip-label': { flex: 1, overflow: 'hidden', textOverflow: 'ellipsis' },
                      }}
                    />
                  ))}
                  {dayEvents.length > 3 && (
                    <Typography variant="caption" color="text.secondary" sx={{ px: 0.5, fontWeight: 600 }}>
                      +{dayEvents.length - 3} more
                    </Typography>
                  )}
                </Stack>
              </Box>
            )
          })}
        </Box>
      </Paper>

      <Dialog open={Boolean(dialog?.open)} onClose={() => !saving && setDialog(null)} fullWidth maxWidth="sm">
        <DialogTitle sx={{ display: 'flex', alignItems: 'center', gap: 1.5 }}>
          <Box
            sx={{
              width: 34,
              height: 34,
              borderRadius: 2,
              display: 'grid',
              placeItems: 'center',
              color: '#fff',
              background: 'linear-gradient(135deg, #14958b 0%, #0f766e 100%)',
              boxShadow: '0 2px 8px rgba(15, 118, 110, 0.3)',
            }}
          >
            <EventNoteIcon fontSize="small" />
          </Box>
          {dialog?.editing ? 'Edit event' : 'New event'}
        </DialogTitle>
        <DialogContent>
          <Stack component="form" id="calendar-event-form" onSubmit={(event) => void submitEvent(event)} spacing={2} sx={{ mt: 1 }}>
            <TextField
              label="Title"
              required
              value={dialog?.title ?? ''}
              onChange={(event) => setDialog((current) => current && { ...current, title: event.target.value })}
            />
            <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
              <TextField
                label="Date"
                type="date"
                fullWidth
                value={dialog?.date ?? ''}
                onChange={(event) => setDialog((current) => current && { ...current, date: event.target.value })}
                slotProps={{ inputLabel: { shrink: true } }}
              />
            </Stack>
            <FormControlLabel
              control={
                <Checkbox
                  checked={dialog?.allDay ?? false}
                  onChange={(event) => setDialog((current) => current && { ...current, allDay: event.target.checked })}
                />
              }
              label="All-day event"
            />
            {!dialog?.allDay && (
              <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
                <TextField
                  label="Start time"
                  type="time"
                  fullWidth
                  value={dialog?.startTime ?? ''}
                  onChange={(event) => setDialog((current) => current && { ...current, startTime: event.target.value })}
                  slotProps={{ inputLabel: { shrink: true } }}
                />
                <TextField
                  label="End time"
                  type="time"
                  fullWidth
                  value={dialog?.endTime ?? ''}
                  onChange={(event) => setDialog((current) => current && { ...current, endTime: event.target.value })}
                  slotProps={{ inputLabel: { shrink: true } }}
                />
              </Stack>
            )}
            <TextField
              label="Location"
              value={dialog?.location ?? ''}
              onChange={(event) => setDialog((current) => current && { ...current, location: event.target.value })}
            />
            <TextField
              label="Description"
              multiline
              minRows={3}
              value={dialog?.description ?? ''}
              onChange={(event) => setDialog((current) => current && { ...current, description: event.target.value })}
            />
            {dialog?.editing && (
              <>
                <Divider />
                <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, color: 'text.secondary' }}>
                  <EventNoteIcon fontSize="small" />
                  <Typography variant="caption">
                    {formatEventWhen(dialog.editing)}
                  </Typography>
                  {dialog.editing.source === 'mail' && (
                    <Chip size="small" color="primary" variant="outlined" icon={<MailOutlineIcon />} label="from mail" />
                  )}
                </Box>
              </>
            )}
          </Stack>
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2 }}>
          {dialog?.editing && (
            <Button
              color="error"
              variant="outlined"
              startIcon={<DeleteOutlineIcon />}
              onClick={() => void removeEvent()}
              disabled={saving}
              sx={{ mr: 'auto' }}
            >
              Delete
            </Button>
          )}
          <Button onClick={() => setDialog(null)} disabled={saving}>Cancel</Button>
          <Button
            type="submit"
            form="calendar-event-form"
            variant="contained"
            startIcon={saving ? <CircularProgress size={18} color="inherit" /> : <EditOutlinedIcon />}
            disabled={saving}
          >
            {dialog?.editing ? 'Save changes' : 'Create event'}
          </Button>
        </DialogActions>
      </Dialog>

      <ScheduleImportDialog
        open={importOpen}
        onClose={() => setImportOpen(false)}
        onImported={(count) => {
          setNotice(`Imported ${count} schedule${count === 1 ? '' : 's'} from mail.`)
          void loadEvents()
        }}
      />

      {!hasEvents && !loading && (
        <Typography variant="body2" color="text.secondary">
          No events in this month. Click a day or “New event” to add one, or import schedules from your mail.
        </Typography>
      )}
    </Stack>
  )
}
