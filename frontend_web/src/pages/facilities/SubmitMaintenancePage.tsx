import { Alert, AlertTitle, Box, Button, Card, CardContent, CircularProgress, FormControl, InputLabel, MenuItem, Select, Stack, TextField, Typography } from '@mui/material'
import { FormEvent, useEffect, useRef, useState } from 'react'
import { Link as RouterLink, useSearchParams } from 'react-router-dom'
import { apiErrorMessage } from '../../api/client'
import { facilitiesApi, type MaintenancePriority, type MaintenanceResponse, type Space, type SubmitMaintenanceRequest } from '../../api/facilities'

interface MaintenanceForm {
  spaceId: string
  facilityType: string
  description: string
  priority: MaintenancePriority
}

const emptyForm: MaintenanceForm = { spaceId: '', facilityType: '', description: '', priority: 'MEDIUM' }

function requestedSpaceId(searchParams: URLSearchParams) {
  const value = Number(searchParams.get('spaceId'))
  return Number.isInteger(value) && value > 0 ? value : null
}

export function SubmitMaintenancePage() {
  const [searchParams] = useSearchParams()
  const [spaces, setSpaces] = useState<Space[]>([])
  const [spacesLoading, setSpacesLoading] = useState(true)
  const [spacesError, setSpacesError] = useState('')
  const [form, setForm] = useState<MaintenanceForm>(emptyForm)
  const [validationError, setValidationError] = useState('')
  const [submitError, setSubmitError] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [submitted, setSubmitted] = useState<MaintenanceResponse | null>(null)
  const submittingRef = useRef(false)

  useEffect(() => {
    let active = true
    const preselectedId = requestedSpaceId(searchParams)
    setSpacesLoading(true)
    facilitiesApi.searchSpaces()
      .then((result) => {
        if (!active) return
        setSpaces(result)
        if (preselectedId && result.some((space) => space.spaceId === preselectedId)) {
          setForm((current) => ({ ...current, spaceId: String(preselectedId) }))
        }
      })
      .catch((requestError) => { if (active) setSpacesError(apiErrorMessage(requestError)) })
      .finally(() => { if (active) setSpacesLoading(false) })
    return () => { active = false }
  }, [searchParams])

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (submittingRef.current) return
    if (!form.spaceId || !form.facilityType.trim() || !form.description.trim()) {
      setValidationError('Choose a space and provide both the issue type and description.')
      return
    }
    const request: SubmitMaintenanceRequest = {
      spaceId: Number(form.spaceId),
      facilityType: form.facilityType.trim(),
      description: form.description.trim(),
      priority: form.priority,
    }
    submittingRef.current = true
    setSubmitting(true)
    setValidationError('')
    setSubmitError('')
    try {
      setSubmitted(await facilitiesApi.submitMaintenanceRequest(request))
    } catch (requestError) {
      setSubmitError(apiErrorMessage(requestError))
    } finally {
      submittingRef.current = false
      setSubmitting(false)
    }
  }

  return (
    <Stack spacing={3}>
      <Box>
        <Typography component="h2" variant="h5" fontWeight={700}>Report Maintenance</Typography>
        <Typography color="text.secondary">Tell Facilities about a room or equipment problem.</Typography>
      </Box>

      {spacesError && <Alert severity="error">{spacesError}</Alert>}
      {validationError && <Alert severity="warning">{validationError}</Alert>}
      {submitError && <Alert severity="error">{submitError}</Alert>}
      {submitted && (
        <Alert severity="success">
          <AlertTitle>Maintenance request submitted</AlertTitle>
          <Stack spacing={0.75}>
            <Typography>Request ID: {submitted.ticketId}</Typography>
            <Typography>Status: {submitted.status.replaceAll('_', ' ')}</Typography>
            <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1} sx={{ alignSelf: 'flex-start' }}>
              <Button component={RouterLink} to={`/facilities/maintenance/${submitted.ticketId}`}>View request</Button>
              <Button component={RouterLink} to="/facilities/maintenance">View My Maintenance Requests</Button>
            </Stack>
          </Stack>
        </Alert>
      )}

      <Card variant="outlined"><CardContent component="form" onSubmit={submit}>
        <Stack spacing={2.5}>
          <FormControl fullWidth required disabled={spacesLoading || submitting}>
            <InputLabel id="maintenance-space-label">Space</InputLabel>
            <Select labelId="maintenance-space-label" id="maintenance-space" label="Space" value={form.spaceId} onChange={(event) => { setForm({ ...form, spaceId: event.target.value }); setSubmitted(null) }}>
              {spaces.map((space) => <MenuItem key={space.spaceId} value={String(space.spaceId)}>{space.name} — {space.building} {space.roomNumber}</MenuItem>)}
            </Select>
          </FormControl>
          {spacesLoading && <Stack direction="row" spacing={1} alignItems="center"><CircularProgress size={18} /><Typography color="text.secondary">Loading campus spaces...</Typography></Stack>}
          <TextField required fullWidth label="Issue type" helperText="For example: projector, air conditioning, lighting" value={form.facilityType} onChange={(event) => { setForm({ ...form, facilityType: event.target.value }); setSubmitted(null) }} slotProps={{ htmlInput: { maxLength: 255 } }} disabled={submitting} />
          <TextField required fullWidth multiline minRows={4} label="Description" value={form.description} onChange={(event) => { setForm({ ...form, description: event.target.value }); setSubmitted(null) }} slotProps={{ htmlInput: { maxLength: 2000 } }} disabled={submitting} />
          <FormControl fullWidth disabled={submitting}>
            <InputLabel id="maintenance-priority-label">Priority</InputLabel>
            <Select labelId="maintenance-priority-label" id="maintenance-priority" label="Priority" value={form.priority} onChange={(event) => setForm({ ...form, priority: event.target.value as MaintenancePriority })}>
              <MenuItem value="LOW">Low</MenuItem>
              <MenuItem value="MEDIUM">Medium</MenuItem>
              <MenuItem value="HIGH">High</MenuItem>
            </Select>
          </FormControl>
          <Button type="submit" variant="contained" disabled={spacesLoading || submitting || !!submitted} sx={{ alignSelf: 'flex-start' }}>
            {submitting ? <><CircularProgress size={18} color="inherit" sx={{ mr: 1 }} />Submitting...</> : 'Submit Request'}
          </Button>
        </Stack>
      </CardContent></Card>
    </Stack>
  )
}
