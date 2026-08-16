import { Alert, Box, Button, Card, CardActionArea, CardContent, Chip, CircularProgress, FormControl, Grid, InputLabel, MenuItem, Paper, Select, Stack, TextField, Typography } from '@mui/material'
import { FormEvent, useEffect, useState } from 'react'
import { Link as RouterLink, useSearchParams } from 'react-router-dom'
import { apiErrorMessage } from '../../api/client'
import { facilitiesApi, type Space, type SpaceSearchFilters } from '../../api/facilities'

const spaceTypes = ['STUDY_ROOM', 'SEMINAR_ROOM', 'SPORTS_VENUE', 'LAB', 'LECTURE_ROOM']

interface SearchForm {
  query: string
  building: string
  spaceType: string
  minimumCapacity: string
  equipment: string
  startDateTime: string
  endDateTime: string
}

const emptyForm: SearchForm = {
  query: '', building: '', spaceType: '', minimumCapacity: '', equipment: '', startDateTime: '', endDateTime: '',
}

function formFromParams(params: URLSearchParams): SearchForm {
  return {
    query: params.get('query') ?? '',
    building: params.get('building') ?? '',
    spaceType: params.get('spaceType') ?? '',
    minimumCapacity: params.get('minimumCapacity') ?? '',
    equipment: params.getAll('equipment').join(', '),
    startDateTime: params.get('startDateTime') ?? '',
    endDateTime: params.get('endDateTime') ?? '',
  }
}

function filtersFromParams(params: URLSearchParams): SpaceSearchFilters {
  const minimumCapacity = params.get('minimumCapacity')
  return {
    query: params.get('query') || undefined,
    building: params.get('building') || undefined,
    spaceType: params.get('spaceType') || undefined,
    minimumCapacity: minimumCapacity ? Number(minimumCapacity) : undefined,
    equipment: params.getAll('equipment').filter(Boolean),
    startDateTime: params.get('startDateTime') || undefined,
    endDateTime: params.get('endDateTime') || undefined,
  }
}

const displayLabel = (value: string) => value.replaceAll('_', ' ')

export function SpacesPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const [form, setForm] = useState(() => formFromParams(searchParams))
  const [spaces, setSpaces] = useState<Space[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const queryKey = searchParams.toString()

  useEffect(() => {
    let active = true
    const params = new URLSearchParams(queryKey)
    setForm(formFromParams(params))
    setLoading(true)
    setError('')
    facilitiesApi.searchSpaces(filtersFromParams(params))
      .then((result) => { if (active) setSpaces(result) })
      .catch((requestError) => { if (active) setError(apiErrorMessage(requestError)) })
      .finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [queryKey])

  function submit(event: FormEvent) {
    event.preventDefault()
    const next = new URLSearchParams()
    const setIfPresent = (key: string, value: string) => { if (value.trim()) next.set(key, value.trim()) }
    setIfPresent('query', form.query)
    setIfPresent('building', form.building)
    setIfPresent('spaceType', form.spaceType)
    setIfPresent('minimumCapacity', form.minimumCapacity)
    form.equipment.split(',').map((item) => item.trim()).filter(Boolean).forEach((item) => next.append('equipment', item))
    setIfPresent('startDateTime', form.startDateTime)
    setIfPresent('endDateTime', form.endDateTime)
    setSearchParams(next)
  }

  function reset() {
    setForm(emptyForm)
    setSearchParams(new URLSearchParams())
  }

  return (
    <Stack spacing={3}>
      <Box>
        <Typography component="h2" variant="h5" fontWeight={700}>Search Spaces</Typography>
        <Typography color="text.secondary">Search by location, capacity, equipment, or an optional time window.</Typography>
      </Box>

      <Paper component="form" onSubmit={submit} sx={{ p: 2 }}>
        <Grid container spacing={2}>
          <Grid size={{ xs: 12, md: 4 }}><TextField fullWidth label="Keyword" value={form.query} onChange={(event) => setForm({ ...form, query: event.target.value })} /></Grid>
          <Grid size={{ xs: 12, sm: 6, md: 4 }}><TextField fullWidth label="Building" value={form.building} onChange={(event) => setForm({ ...form, building: event.target.value })} /></Grid>
          <Grid size={{ xs: 12, sm: 6, md: 4 }}>
            <FormControl fullWidth><InputLabel>Space Type</InputLabel><Select label="Space Type" value={form.spaceType} onChange={(event) => setForm({ ...form, spaceType: event.target.value })}>
              <MenuItem value="">Any</MenuItem>{spaceTypes.map((type) => <MenuItem key={type} value={type}>{displayLabel(type)}</MenuItem>)}
            </Select></FormControl>
          </Grid>
          <Grid size={{ xs: 12, sm: 6, md: 3 }}><TextField fullWidth type="number" label="Minimum Capacity" slotProps={{ htmlInput: { min: 1 } }} value={form.minimumCapacity} onChange={(event) => setForm({ ...form, minimumCapacity: event.target.value })} /></Grid>
          <Grid size={{ xs: 12, sm: 6, md: 5 }}><TextField fullWidth label="Equipment" helperText="Separate multiple items with commas" value={form.equipment} onChange={(event) => setForm({ ...form, equipment: event.target.value })} /></Grid>
          <Grid size={{ xs: 12, sm: 6, md: 2 }}><TextField fullWidth type="datetime-local" label="Start datetime" slotProps={{ inputLabel: { shrink: true } }} value={form.startDateTime} onChange={(event) => setForm({ ...form, startDateTime: event.target.value })} /></Grid>
          <Grid size={{ xs: 12, sm: 6, md: 2 }}><TextField fullWidth type="datetime-local" label="End datetime" slotProps={{ inputLabel: { shrink: true } }} value={form.endDateTime} onChange={(event) => setForm({ ...form, endDateTime: event.target.value })} /></Grid>
          <Grid size={12}><Stack direction="row" spacing={1} justifyContent="flex-end"><Button type="button" onClick={reset}>Reset</Button><Button type="submit" variant="contained">Search</Button></Stack></Grid>
        </Grid>
      </Paper>

      {error && <Alert severity="error">{error}</Alert>}
      {!loading && !error && <Stack direction="row" justifyContent="space-between" alignItems="center"><Typography color="text.secondary">{spaces.length} {spaces.length === 1 ? 'space' : 'spaces'} found</Typography>{searchParams.toString() && <Button size="small" onClick={reset}>Clear filters</Button>}</Stack>}
      {loading ? <Box sx={{ py: 8, textAlign: 'center' }}><CircularProgress /></Box> : spaces.length ? (
        <Grid container spacing={3}>
          {spaces.map((space) => (
            <Grid key={space.spaceId} size={{ xs: 12, sm: 6, md: 4 }}>
              <Card variant="outlined" sx={{ height: '100%' }}>
                <CardActionArea component={RouterLink} to={`/facilities/spaces/${space.spaceId}`} sx={{ height: '100%', alignItems: 'stretch' }} aria-label={`View ${space.name}`}>
                  <CardContent><Stack spacing={1.5}>
                    <Stack direction="row" justifyContent="space-between" alignItems="flex-start" gap={1}><Typography component="h3" variant="h6" fontWeight={700}>{space.name}</Typography><Chip size="small" label={displayLabel(space.status)} color={space.status === 'AVAILABLE' ? 'success' : 'default'} /></Stack>
                    <Typography>{space.building} · Floor {space.floor} · Room {space.roomNumber}</Typography>
                    <Typography color="text.secondary">{displayLabel(space.spaceType)} · Capacity {space.capacity}</Typography>
                    <Typography variant="body2" color="text.secondary">Equipment: {space.equipment.length ? space.equipment.join(', ') : 'None listed'}</Typography>
                  </Stack></CardContent>
                </CardActionArea>
              </Card>
            </Grid>
          ))}
        </Grid>
      ) : !error && <Paper sx={{ p: 6, textAlign: 'center' }}><Typography variant="h6">No matching spaces</Typography><Typography color="text.secondary">Try clearing one or more filters.</Typography></Paper>}
    </Stack>
  )
}
