import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  Chip,
  CircularProgress,
  FormControl,
  InputLabel,
  MenuItem,
  Paper,
  Select,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  TextField,
  Typography,
} from '@mui/material'
import { FormEvent, useCallback, useEffect, useState } from 'react'
import { useAuth } from '../../auth/AuthContext'
import {
  createAdminUser,
  listAdminUsers,
  updateUserRole,
  type AdminUser,
  type UserRole,
} from '../../api/adminUsers'
import { apiErrorMessage } from '../../api/client'

const ROLE_COLORS: Record<UserRole, 'primary' | 'warning' | 'secondary'> = {
  STUDENT: 'primary',
  ADMIN: 'warning',
  SUPER_ADMIN: 'secondary',
}

export function AdminUserManagementPage() {
  const { user: currentUser } = useAuth()
  const isSuperAdmin = currentUser?.role === 'SUPER_ADMIN'

  const [users, setUsers] = useState<AdminUser[]>([])
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState('')

  // 创建用户表单
  const [createEmail, setCreateEmail] = useState('')
  const [createPassword, setCreatePassword] = useState('')
  const [createRole, setCreateRole] = useState<'STUDENT' | 'ADMIN'>('STUDENT')
  const [creating, setCreating] = useState(false)
  const [createMsg, setCreateMsg] = useState<{ type: 'success' | 'error'; text: string } | null>(null)

  // 行内改角色
  const [draftRoles, setDraftRoles] = useState<Record<number, UserRole>>({})
  const [savingId, setSavingId] = useState<number | null>(null)
  const [roleMsg, setRoleMsg] = useState<{ type: 'success' | 'error'; text: string } | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    setLoadError('')
    try {
      setUsers(await listAdminUsers())
    } catch (error) {
      setLoadError(apiErrorMessage(error))
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    void load()
  }, [load])

  async function handleCreate(event: FormEvent) {
    event.preventDefault()
    setCreateMsg(null)
    if (createPassword.length < 6) {
      setCreateMsg({ type: 'error', text: '密码至少 6 位' })
      return
    }
    setCreating(true)
    try {
      const created = await createAdminUser({
        email: createEmail.trim(),
        password: createPassword,
        role: createRole,
      })
      setCreateMsg({ type: 'success', text: `已创建：${created.email}（${created.role}）` })
      setCreateEmail('')
      setCreatePassword('')
      void load()
    } catch (error) {
      setCreateMsg({ type: 'error', text: apiErrorMessage(error) })
    } finally {
      setCreating(false)
    }
  }

  async function handleSaveRole(id: number) {
    const role = draftRoles[id]
    if (!role) return
    setRoleMsg(null)
    setSavingId(id)
    try {
      const updated = await updateUserRole(id, role)
      setRoleMsg({ type: 'success', text: `已将 ${updated.email} 的角色改为 ${updated.role}` })
      void load()
    } catch (error) {
      setRoleMsg({ type: 'error', text: apiErrorMessage(error) })
    } finally {
      setSavingId(null)
    }
  }

  return (
    <Stack spacing={3}>
      <Box>
        <Typography variant="h5" fontWeight={700}>User Management</Typography>
        <Typography color="text.secondary" variant="body2">
          三角色体系：STUDENT · ADMIN · SUPER_ADMIN
          {!isSuperAdmin && '（仅 SUPER_ADMIN 可创建用户 / 修改角色）'}
        </Typography>
      </Box>

      {roleMsg && <Alert severity={roleMsg.type}>{roleMsg.text}</Alert>}

      {/* ── 用户列表 ── */}
      <Card variant="outlined">
        <CardContent>
          <Stack direction="row" alignItems="center" justifyContent="space-between" sx={{ mb: 2 }}>
            <Typography variant="h6">用户列表</Typography>
            <Button variant="outlined" size="small" onClick={() => void load()} disabled={loading}>
              🔄 刷新
            </Button>
          </Stack>

          {loadError && <Alert severity="error" sx={{ mb: 2 }}>{loadError}</Alert>}

          {loading ? (
            <Box sx={{ display: 'flex', justifyContent: 'center', py: 4 }}>
              <CircularProgress />
            </Box>
          ) : users.length === 0 ? (
            <Typography color="text.secondary" align="center" sx={{ py: 4 }}>暂无用户</Typography>
          ) : (
            <TableContainer component={Paper} variant="outlined">
              <Table size="small">
                <TableHead>
                  <TableRow>
                    <TableCell>ID</TableCell>
                    <TableCell>邮箱</TableCell>
                    <TableCell>角色</TableCell>
                    {isSuperAdmin && <TableCell align="right">操作</TableCell>}
                  </TableRow>
                </TableHead>
                <TableBody>
                  {users.map((user) => {
                    const draft = draftRoles[user.id] ?? user.role
                    const dirty = draft !== user.role
                    return (
                      <TableRow key={user.id} hover>
                        <TableCell>{user.id}</TableCell>
                        <TableCell>{user.email}</TableCell>
                        <TableCell>
                          {isSuperAdmin && user.role !== 'SUPER_ADMIN' ? (
                            <FormControl size="small" sx={{ minWidth: 140 }}>
                              <Select
                                value={draft}
                                onChange={(event) =>
                                  setDraftRoles((prev) => ({ ...prev, [user.id]: event.target.value as UserRole }))
                                }
                              >
                                <MenuItem value="STUDENT">STUDENT</MenuItem>
                                <MenuItem value="ADMIN">ADMIN</MenuItem>
                                <MenuItem value="SUPER_ADMIN">SUPER_ADMIN</MenuItem>
                              </Select>
                            </FormControl>
                          ) : (
                            <Chip
                              size="small"
                              label={user.role}
                              color={ROLE_COLORS[user.role] ?? 'default'}
                              variant="outlined"
                            />
                          )}
                        </TableCell>
                        {isSuperAdmin && (
                          <TableCell align="right">
                            {dirty && (
                              <Button
                                size="small"
                                variant="contained"
                                disabled={savingId === user.id}
                                onClick={() => void handleSaveRole(user.id)}
                              >
                                {savingId === user.id ? '保存中…' : '保存'}
                              </Button>
                            )}
                          </TableCell>
                        )}
                      </TableRow>
                    )
                  })}
                </TableBody>
              </Table>
            </TableContainer>
          )}
        </CardContent>
      </Card>

      {/* ── 创建用户（指定角色，仅 SUPER_ADMIN）── */}
      {isSuperAdmin && (
        <Card variant="outlined">
          <CardContent>
            <Typography variant="h6" sx={{ mb: 2 }}>
              创建用户（指定角色）
              <Typography component="span" color="text.secondary" variant="caption" sx={{ ml: 1 }}>仅 SUPER_ADMIN</Typography>
            </Typography>
            <Box component="form" onSubmit={handleCreate} sx={{ display: 'flex', gap: 1.5, flexWrap: 'wrap', alignItems: 'flex-start' }}>
              <TextField
                label="邮箱"
                type="email"
                required
                value={createEmail}
                onChange={(e) => setCreateEmail(e.target.value)}
                size="small"
                sx={{ flex: 1, minWidth: 200 }}
              />
              <TextField
                label="密码（≥6位）"
                type="password"
                required
                value={createPassword}
                onChange={(e) => setCreatePassword(e.target.value)}
                size="small"
                sx={{ flex: 1, minWidth: 160 }}
              />
              <FormControl size="small" sx={{ minWidth: 130 }}>
                <InputLabel>角色</InputLabel>
                <Select
                  value={createRole}
                  label="角色"
                  onChange={(e) => setCreateRole(e.target.value as 'STUDENT' | 'ADMIN')}
                >
                  <MenuItem value="STUDENT">STUDENT</MenuItem>
                  <MenuItem value="ADMIN">ADMIN</MenuItem>
                </Select>
              </FormControl>
              <Button type="submit" variant="contained" disabled={creating}>
                {creating ? '创建中…' : '创建'}
              </Button>
            </Box>
            {createMsg && <Alert severity={createMsg.type} sx={{ mt: 1.5 }}>{createMsg.text}</Alert>}
          </CardContent>
        </Card>
      )}
    </Stack>
  )
}
