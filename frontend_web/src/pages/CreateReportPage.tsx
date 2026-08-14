import CloudUploadIcon from '@mui/icons-material/CloudUpload'
import DeleteOutlineIcon from '@mui/icons-material/DeleteOutline'
import { Alert, Box, Button, Card, CardMedia, FormControl, Grid, IconButton, InputLabel, LinearProgress, MenuItem, Select, Stack, TextField, Typography } from '@mui/material'
import { FormEvent, useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { apiErrorMessage } from '../api/client'
import { createReport, suggestCategory } from '../api/lostFound'
import { categoryLabels } from '../labels'
import type { CreateReportInput, ItemCategory, ReportType } from '../types'

const categories = Object.keys(categoryLabels) as ItemCategory[]
const allowedTypes = ['image/jpeg', 'image/png', 'image/webp']
const maxFileSize = 10 * 1024 * 1024

interface SelectedImage { file: File; preview: string }

export function CreateReportPage({ reportType }: { reportType: ReportType }) {
  const navigate = useNavigate()
  const [form, setForm] = useState<CreateReportInput>({
    reportType,
    itemName: '',
    category: 'ELECTRONICS',
    description: '',
    colour: '',
    location: '',
    eventDate: new Date().toISOString().slice(0, 10),
    timeDescription: '',
  })
  const [images, setImages] = useState<SelectedImage[]>([])
  const [error, setError] = useState('')
  const [progress, setProgress] = useState(0)
  const [submitting, setSubmitting] = useState(false)
  const imagesRef = useRef<SelectedImage[]>([])
  // ref 供 await 后读取最新值，防止异步返回时覆盖用户刚手动选过的分类
  const categoryTouchedRef = useRef(false)

  async function autoSuggestCategory() {
    const name = form.itemName.trim()
    if (!name || categoryTouchedRef.current) return
    try {
      const suggested = await suggestCategory(name)
      if (suggested && !categoryTouchedRef.current) {
        setForm((current) => ({ ...current, category: suggested }))
      }
    } catch {
      /* 静默失败：分类建议失败不影响创建报告 */
    }
  }

  useEffect(() => { imagesRef.current = images }, [images])
  useEffect(() => () => { imagesRef.current.forEach((image) => URL.revokeObjectURL(image.preview)) }, [])

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

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (form.itemName.trim().length < 3 || form.description.trim().length < 10) {
      setError('Item name must contain at least 3 characters and description at least 10 characters.')
      return
    }
    setSubmitting(true)
    setProgress(0)
    setError('')
    try {
      const report = await createReport(form, images.map((image) => image.file), setProgress)
      navigate(`/lost-found/${report.id}`)
    } catch (requestError) {
      setError(apiErrorMessage(requestError))
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <Stack spacing={3} component="form" onSubmit={submit}>
      <Box><Typography variant="h4" fontWeight={700}>Report a {reportType === 'LOST' ? 'lost' : 'found'} item</Typography><Typography color="text.secondary">Provide details that will help another campus member identify the item.</Typography></Box>
      {error && <Alert severity="error">{error}</Alert>}
      <Card sx={{ p: 3 }}>
        <Grid container spacing={2}>
          <Grid size={{ xs: 12, md: 6 }}><TextField fullWidth required label="Item name" inputProps={{ minLength: 3, maxLength: 100 }} value={form.itemName} onChange={(e) => setForm({ ...form, itemName: e.target.value })} onBlur={autoSuggestCategory} /></Grid>
          <Grid size={{ xs: 12, md: 6 }}><FormControl fullWidth required><InputLabel>Category</InputLabel><Select label="Category" value={form.category} onChange={(e) => { categoryTouchedRef.current = true; setForm({ ...form, category: e.target.value as ItemCategory }) }}>{categories.map((category) => <MenuItem key={category} value={category}>{categoryLabels[category]}</MenuItem>)}</Select></FormControl></Grid>
          <Grid size={12}><TextField fullWidth required multiline minRows={4} label="Description" helperText="Include brand, distinguishing marks and other identifying details (10–2000 characters)." inputProps={{ minLength: 10, maxLength: 2000 }} value={form.description} onChange={(e) => setForm({ ...form, description: e.target.value })} /></Grid>
          <Grid size={{ xs: 12, md: 6 }}><TextField fullWidth label="Colour" inputProps={{ maxLength: 50 }} value={form.colour} onChange={(e) => setForm({ ...form, colour: e.target.value })} /></Grid>
          <Grid size={{ xs: 12, md: 6 }}><TextField fullWidth required label="Location" inputProps={{ maxLength: 200 }} value={form.location} onChange={(e) => setForm({ ...form, location: e.target.value })} /></Grid>
          <Grid size={{ xs: 12, md: 6 }}><TextField fullWidth required type="date" label={reportType === 'LOST' ? 'Date lost' : 'Date found'} slotProps={{ inputLabel: { shrink: true }, htmlInput: { max: new Date().toISOString().slice(0, 10) } }} value={form.eventDate} onChange={(e) => setForm({ ...form, eventDate: e.target.value })} /></Grid>
          <Grid size={{ xs: 12, md: 6 }}><TextField fullWidth label="Approximate time" placeholder="e.g. Around 3 pm" inputProps={{ maxLength: 100 }} value={form.timeDescription} onChange={(e) => setForm({ ...form, timeDescription: e.target.value })} /></Grid>
        </Grid>
      </Card>

      <Card sx={{ p: 3 }}>
        <Typography variant="h6">Images <Typography component="span" color="text.secondary" variant="body2">(optional, up to 5)</Typography></Typography>
        <Button sx={{ mt: 2 }} component="label" variant="outlined" startIcon={<CloudUploadIcon />} disabled={images.length >= 5 || submitting}>
          Select images<input hidden type="file" multiple accept="image/jpeg,image/png,image/webp" onChange={(e) => { selectImages(e.target.files); e.target.value = '' }} />
        </Button>
        {images.length > 0 && <Grid container spacing={2} sx={{ mt: 1 }}>{images.map((image, index) => <Grid key={`${image.file.name}-${image.file.lastModified}`} size={{ xs: 6, sm: 4, md: 2.4 }}><Box sx={{ position: 'relative' }}><CardMedia component="img" image={image.preview} alt={image.file.name} sx={{ height: 140, borderRadius: 1, objectFit: 'cover' }} /><IconButton aria-label={`Remove ${image.file.name}`} onClick={() => removeImage(index)} sx={{ position: 'absolute', top: 4, right: 4, bgcolor: 'background.paper' }}><DeleteOutlineIcon /></IconButton></Box><Typography variant="caption" noWrap display="block">{image.file.name}</Typography></Grid>)}</Grid>}
      </Card>
      {submitting && <Box><LinearProgress variant={progress ? 'determinate' : 'indeterminate'} value={progress} /><Typography variant="caption">Uploading {progress ? `${progress}%` : '…'}</Typography></Box>}
      <Stack direction="row" spacing={1} justifyContent="flex-end"><Button onClick={() => navigate(-1)} disabled={submitting}>Cancel</Button><Button type="submit" variant="contained" disabled={submitting}>Publish report</Button></Stack>
    </Stack>
  )
}
