import CloudUploadIcon from '@mui/icons-material/CloudUpload'
import DeleteOutlineIcon from '@mui/icons-material/DeleteOutline'
import { Alert, Box, Button, CardMedia, Dialog, DialogActions, DialogContent, DialogTitle, FormControl, Grid, IconButton, InputLabel, LinearProgress, MenuItem, Select, TextField, Typography } from '@mui/material'
import { useEffect, useRef, useState } from 'react'
import { apiErrorMessage } from '../api/client'
import { updateReport } from '../api/lostFound'
import { categoryLabels } from '../labels'
import type { ItemCategory, LostFoundReport, UpdateReportInput } from '../types'

const categories = Object.keys(categoryLabels) as ItemCategory[]
const allowedTypes = ['image/jpeg', 'image/png', 'image/webp']
const maxFileSize = 10 * 1024 * 1024

interface SelectedImage { file: File; preview: string }

interface EditReportDialogProps {
  report: LostFoundReport
  open: boolean
  onClose: () => void
  onUpdated: (report: LostFoundReport) => void
}

export function EditReportDialog({ report, open, onClose, onUpdated }: EditReportDialogProps) {
  const [form, setForm] = useState<UpdateReportInput>(() => initialForm(report))
  const [images, setImages] = useState<SelectedImage[]>([])
  const [error, setError] = useState('')
  const [progress, setProgress] = useState(0)
  const [submitting, setSubmitting] = useState(false)
  const imagesRef = useRef<SelectedImage[]>([])

  useEffect(() => { imagesRef.current = images }, [images])
  useEffect(() => {
    if (open) {
      setForm(initialForm(report))
      setImages([])
      setError('')
      setProgress(0)
    }
    return () => { imagesRef.current.forEach((image) => URL.revokeObjectURL(image.preview)) }
  }, [open, report])

  function selectImages(files: FileList | null) {
    if (!files) return
    const incoming = Array.from(files)
    if (images.length + incoming.length > 5) {
      setError('You can upload at most 5 images.')
      return
    }
    const invalid = incoming.find((file) => !allowedTypes.includes(file.type) || file.size > maxFileSize)
    if (invalid) {
      setError(`${invalid.name} must be a JPEG, PNG or WebP image no larger than 10 MB.`)
      return
    }
    setError('')
    setImages((current) => [...current, ...incoming.map((file) => ({ file, preview: URL.createObjectURL(file) }))])
  }

  function removeImage(index: number) {
    setImages((current) => {
      URL.revokeObjectURL(current[index].preview)
      return current.filter((_, itemIndex) => itemIndex !== index)
    })
  }

  async function submit() {
    if (form.itemName.trim().length < 3 || form.description.trim().length < 10) {
      setError('Item name must contain at least 3 characters and description at least 10 characters.')
      return
    }
    setSubmitting(true)
    setProgress(0)
    setError('')
    try {
      const updated = await updateReport(report.id, form, images.map((image) => image.file), setProgress)
      onUpdated(updated)
      onClose()
    } catch (requestError) {
      setError(apiErrorMessage(requestError))
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <Dialog open={open} onClose={() => !submitting && onClose()} fullWidth maxWidth="md">
      <DialogTitle>Edit report</DialogTitle>
      <DialogContent>
        {error && <Alert severity="error" sx={{ mb: 2 }}>{error}</Alert>}
        <Grid container spacing={2} sx={{ mt: 0 }}>
          <Grid size={{ xs: 12, md: 6 }}><TextField fullWidth required label="Item name" inputProps={{ minLength: 3, maxLength: 100 }} value={form.itemName} onChange={(e) => setForm({ ...form, itemName: e.target.value })} /></Grid>
          <Grid size={{ xs: 12, md: 6 }}><FormControl fullWidth required><InputLabel>Category</InputLabel><Select label="Category" value={form.category} onChange={(e) => setForm({ ...form, category: e.target.value as ItemCategory })}>{categories.map((category) => <MenuItem key={category} value={category}>{categoryLabels[category]}</MenuItem>)}</Select></FormControl></Grid>
          <Grid size={12}><TextField fullWidth required multiline minRows={3} label="Description" helperText="Include brand, distinguishing marks and other identifying details (10–2000 characters)." inputProps={{ minLength: 10, maxLength: 2000 }} value={form.description} onChange={(e) => setForm({ ...form, description: e.target.value })} /></Grid>
          <Grid size={{ xs: 12, md: 6 }}><TextField fullWidth label="Colour" inputProps={{ maxLength: 50 }} value={form.colour} onChange={(e) => setForm({ ...form, colour: e.target.value })} /></Grid>
          <Grid size={{ xs: 12, md: 6 }}><TextField fullWidth required label="Location" inputProps={{ maxLength: 200 }} value={form.location} onChange={(e) => setForm({ ...form, location: e.target.value })} /></Grid>
          <Grid size={{ xs: 12, md: 6 }}><TextField fullWidth required type="date" label={report.reportType === 'LOST' ? 'Date lost' : 'Date found'} slotProps={{ inputLabel: { shrink: true }, htmlInput: { max: new Date().toISOString().slice(0, 10) } }} value={form.eventDate} onChange={(e) => setForm({ ...form, eventDate: e.target.value })} /></Grid>
          <Grid size={{ xs: 12, md: 6 }}><TextField fullWidth label="Approximate time" placeholder="e.g. Around 3 pm" inputProps={{ maxLength: 100 }} value={form.timeDescription} onChange={(e) => setForm({ ...form, timeDescription: e.target.value })} /></Grid>
        </Grid>
        <Typography variant="subtitle2" sx={{ mt: 3 }}>Replace images <Typography component="span" color="text.secondary" variant="body2">(optional, up to 5; leave empty to keep current images)</Typography></Typography>
        <Button sx={{ mt: 1 }} component="label" variant="outlined" startIcon={<CloudUploadIcon />} disabled={images.length >= 5 || submitting}>
          Select images<input hidden type="file" multiple accept="image/jpeg,image/png,image/webp" onChange={(e) => { selectImages(e.target.files); e.target.value = '' }} />
        </Button>
        {images.length > 0 && <Grid container spacing={2} sx={{ mt: 1 }}>{images.map((image, index) => <Grid key={`${image.file.name}-${image.file.lastModified}`} size={{ xs: 6, sm: 4, md: 2.4 }}><Box sx={{ position: 'relative' }}><CardMedia component="img" image={image.preview} alt={image.file.name} sx={{ height: 140, borderRadius: 1, objectFit: 'cover' }} /><IconButton aria-label={`Remove ${image.file.name}`} onClick={() => removeImage(index)} sx={{ position: 'absolute', top: 4, right: 4, bgcolor: 'background.paper' }}><DeleteOutlineIcon /></IconButton></Box><Typography variant="caption" noWrap display="block">{image.file.name}</Typography></Grid>)}</Grid>}
        {submitting && <Box sx={{ mt: 2 }}><LinearProgress variant={progress ? 'determinate' : 'indeterminate'} value={progress} /><Typography variant="caption">Uploading {progress ? `${progress}%` : '…'}</Typography></Box>}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} disabled={submitting}>Cancel</Button>
        <Button variant="contained" onClick={submit} disabled={submitting}>Save changes</Button>
      </DialogActions>
    </Dialog>
  )
}

function initialForm(report: LostFoundReport): UpdateReportInput {
  return {
    itemName: report.itemName,
    category: report.category,
    description: report.description,
    colour: report.colour ?? '',
    location: report.location,
    eventDate: report.eventDate,
    timeDescription: report.timeDescription ?? '',
  }
}
