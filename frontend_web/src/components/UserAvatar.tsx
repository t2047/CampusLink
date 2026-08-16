import { Avatar } from '@mui/material'
import { useState } from 'react'

interface UserAvatarProps {
  /** 展示名（昵称或 email 前缀），用于无头像时的默认首字母头像。 */
  name: string
  avatarUrl?: string | null
  size?: number
}

/**
 * 用户头像：有 avatarUrl 显示图片，加载失败或无头像时回退为展示名首字母。
 * 头像代理端点 /api/users/avatar/{key} 公开回显，<img> 可直接加载。
 */
export function UserAvatar({ name, avatarUrl, size = 40 }: UserAvatarProps) {
  const [broken, setBroken] = useState(false)
  const initial = name.trim().charAt(0).toUpperCase() || '?'
  const showImage = Boolean(avatarUrl) && !broken
  return (
    <Avatar
      src={showImage ? (avatarUrl ?? undefined) : undefined}
      onError={() => setBroken(true)}
      alt={name}
      sx={{
        width: size,
        height: size,
        bgcolor: '#1e40af',
        fontSize: Math.round(size * 0.45),
        fontWeight: 700,
      }}
    >
      {initial}
    </Avatar>
  )
}
