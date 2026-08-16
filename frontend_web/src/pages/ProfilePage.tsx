import AddPhotoAlternateIcon from '@mui/icons-material/AddPhotoAlternate'
import ChevronRightIcon from '@mui/icons-material/ChevronRight'
import EditIcon from '@mui/icons-material/Edit'
import FindInPageIcon from '@mui/icons-material/FindInPage'
import HelpOutlineIcon from '@mui/icons-material/HelpOutline'
import InboxIcon from '@mui/icons-material/Inbox'
import PersonSearchIcon from '@mui/icons-material/PersonSearch'
import {
  Alert, Avatar, Box, Button, Card, CardActionArea, Chip, CircularProgress,
  Dialog, DialogActions, DialogContent, DialogTitle, Stack, TextField, Typography,
} from '@mui/material'
import { useEffect, useState, type ReactNode } from 'react'
import { Link as RouterLink } from 'react-router-dom'
import { apiErrorMessage } from '../api/client'
import { getMyClaims, searchReports } from '../api/lostFound'
import { getMyProfile, updateNickname, uploadAvatar } from '../api/users'
import { displayName } from '../auth/displayName'
import { useAuth } from '../auth/AuthContext'
import type { UserProfile } from '../types'

const avatarTypes = ['image/jpeg', 'image/png', 'image/webp']
const avatarMaxBytes = 2 * 1024 * 1024

interface ServiceEntryProps {
  icon: ReactNode
  title: string
  subtitle: ReactNode
  to: string
}

function ServiceEntry({ icon, title, subtitle, to }: ServiceEntryProps) {
  return (
    <Card sx={{ height: '100%' }}>
      <CardActionArea component={RouterLink} to={to} sx={{ height: '100%', p: 2 }}>
        <Stack direction="row" spacing={2} alignItems="center">
          <Box sx={{ color: '#1e40af', display: 'grid', placeItems: 'center' }}>{icon}</Box>
          <Box sx={{ flexGrow: 1, minWidth: 0 }}>
            <Typography variant="subtitle1" fontWeight={700}>{title}</Typography>
            <Typography variant="body2" color="text.secondary">{subtitle}</Typography>
          </Box>
          <ChevronRightIcon color="disabled" />
        </Stack>
      </CardActionArea>
    </Card>
  )
}

function CountText({ value, suffix }: { value: number | null; suffix: string }) {
  return value === null ? `${suffix} …` : `${value} ${suffix}`
}

/** Lost & Found 个人中心（个人中心需求 §5/§6）：资料区 + 我的服务 + 其他。 */
export function ProfilePage() {
  const { user, updateProfile } = useAuth()
  const [profile, setProfile] = useState<UserProfile | null>(user)
  const [claimsCount, setClaimsCount] = useState<number | null>(null)
  const [submittedCount, setSubmittedCount] = useState<number | null>(null)
  const [lostCount, setLostCount] = useState<number | null>(null)
  const [foundCount, setFoundCount] = useState<number | null>(null)
  const [error, setError] = useState('')
  const [editOpen, setEditOpen] = useState(false)

  useEffect(() => {
    let active = true
    setError('')
    getMyProfile()
      .then((next) => { if (active) { setProfile(next); updateProfile(next) } })
      .catch((requestError) => { if (active) setError(apiErrorMessage(requestError)) })
    getMyClaims()
      .then((claims) => {
        if (!active) return
        setClaimsCount(claims.length)
        setSubmittedCount(claims.filter((claim) => claim.status === 'SUBMITTED').length)
      })
      .catch(() => { if (active) { setClaimsCount(0); setSubmittedCount(0) } })
    searchReports({ owner: 'me', reportType: 'LOST', size: 1 })
      .then((page) => { if (active) setLostCount(page.totalElements) })
      .catch(() => { if (active) setLostCount(0) })
    searchReports({ owner: 'me', reportType: 'FOUND', size: 1 })
      .then((page) => { if (active) setFoundCount(page.totalElements) })
      .catch(() => { if (active) setFoundCount(0) })
    return () => { active = false }
  }, [updateProfile])

  const email = profile?.email ?? user?.email ?? ''
  const role = profile?.role ?? user?.role ?? ''
  const name = displayName(profile?.nickname ?? user?.nickname, email)
  const avatarUrl = profile?.avatarUrl ?? user?.avatarUrl

  return (
    <Stack spacing={3}>
      <Typography variant="h4" fontWeight={700}>Personal Center</Typography>
      {error && <Alert severity="error">{error}</Alert>}

      <Card sx={{ p: 3 }}>
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={3} alignItems={{ xs: 'center', sm: 'center' }}>
          <Avatar
            src={avatarUrl ?? undefined}
            onError={(event) => { (event.currentTarget as HTMLImageElement).style.visibility = 'hidden' }}
            sx={{ width: 88, height: 88, bgcolor: '#1e40af', fontSize: 36, fontWeight: 700 }}
          >
            {name.charAt(0).toUpperCase() || '?'}
          </Avatar>
          <Box sx={{ flexGrow: 1, textAlign: { xs: 'center', sm: 'left' } }}>
            <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1} alignItems={{ xs: 'center', sm: 'center' }}>
              <Typography variant="h5" fontWeight={700}>{name}</Typography>
              {role && <Chip size="small" label={role} variant="outlined" />}
            </Stack>
            <Typography color="text.secondary">{email}</Typography>
          </Box>
          <Button variant="outlined" startIcon={<EditIcon />} onClick={() => setEditOpen(true)}>Edit profile</Button>
        </Stack>
      </Card>

      <Box>
        <Typography variant="h6" fontWeight={700} sx={{ mb: 1 }}>My Services</Typography>
        <Stack spacing={2}>
          <ServiceEntry
            icon={<InboxIcon fontSize="large" />}
            title="My Claims"
            subtitle={
              submittedCount && submittedCount > 0
                ? `${claimsCount} total · ${submittedCount} pending review`
                : `${claimsCount} total`
            }
            to="/claims/mine"
          />
          <ServiceEntry
            icon={<PersonSearchIcon fontSize="large" />}
            title="My Lost Items"
            subtitle={<CountText value={lostCount} suffix="lost-item reports" />}
            to="/lost-found/profile/lost"
          />
          <ServiceEntry
            icon={<FindInPageIcon fontSize="large" />}
            title="My Found Items"
            subtitle={<CountText value={foundCount} suffix="found-item reports" />}
            to="/lost-found/profile/found"
          />
        </Stack>
      </Box>

      <Box>
        <Typography variant="h6" fontWeight={700} sx={{ mb: 1 }}>Other</Typography>
        <ServiceEntry
          icon={<HelpOutlineIcon fontSize="large" />}
          title="FAQ"
          subtitle="Lost & Found usage, claiming rules and common questions"
          to="/lost-found/faq"
        />
      </Box>

      <EditProfileDialog
        open={editOpen}
        onClose={() => setEditOpen(false)}
        name={name}
        avatarUrl={avatarUrl}
        onSaved={(next) => { updateProfile(next); setProfile(next); setEditOpen(false) }}
      />
    </Stack>
  )
}

function EditProfileDialog({
  open, onClose, name, avatarUrl, onSaved,
}: {
  open: boolean
  onClose: () => void
  name: string
  avatarUrl: string | null | undefined
  onSaved: (profile: UserProfile) => void
}) {
  const [nickname, setNickname] = useState(name)
  const [file, setFile] = useState<File | null>(null)
  const [preview, setPreview] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState('')

  useEffect(() => { if (open) { setNickname(name); setFile(null); setPreview(null); setError('') } }, [open, name])

  async function save() {
    if (!nickname.trim()) { setError('Nickname must be 1–30 characters.'); return }
    if (nickname.trim().length > 30) { setError('Nickname must be 1–30 characters.'); return }
    setSubmitting(true)
    setError('')
    try {
      let next = await updateNickname(nickname.trim())
      if (file) {
        next = await uploadAvatar(file)
      }
      onSaved(next)
    } catch (requestError) {
      setError(apiErrorMessage(requestError))
    } finally {
      setSubmitting(false)
    }
  }

  function selectFile(selected: FileList | null) {
    const next = selected?.[0]
    if (!next) return
    if (!avatarTypes.includes(next.type)) {
      setError('Avatar must be a JPEG, PNG or WebP image.')
      return
    }
    if (next.size > avatarMaxBytes) {
      setError('Avatar must be 2 MB or smaller.')
      return
    }
    setError('')
    setFile(next)
    setPreview(URL.createObjectURL(next))
  }

  return (
    <Dialog open={open} onClose={() => !submitting && onClose()} fullWidth maxWidth="sm">
      <DialogTitle>Edit profile</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          <TextField
            fullWidth
            label="Nickname"
            value={nickname}
            onChange={(e) => setNickname(e.target.value)}
            helperText="1–30 characters. Leave empty to show your email prefix."
            inputProps={{ maxLength: 30 }}
          />
          <Stack direction="row" spacing={2} alignItems="center">
            <Avatar src={preview ?? avatarUrl ?? undefined} sx={{ width: 64, height: 64, bgcolor: '#1e40af', fontSize: 26, fontWeight: 700 }}>
              {nickname.trim().charAt(0).toUpperCase() || '?'}
            </Avatar>
            <Button component="label" variant="outlined" startIcon={<AddPhotoAlternateIcon />}>
              Upload avatar
              <input hidden type="file" accept={avatarTypes.join(',')} onChange={(e) => { selectFile(e.target.files); e.target.value = '' }} />
            </Button>
          </Stack>
          {error && <Alert severity="error">{error}</Alert>}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} disabled={submitting}>Cancel</Button>
        <Button variant="contained" onClick={save} disabled={submitting}>{submitting ? <CircularProgress size={20} /> : 'Save'}</Button>
      </DialogActions>
    </Dialog>
  )
}
