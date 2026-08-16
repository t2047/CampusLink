import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from 'react'
import { login as loginRequest, register as registerRequest } from '../api/auth'
import { TOKEN_KEY, USER_KEY } from '../api/client'
import type { UserProfile } from '../types'

interface SessionUser {
  email: string
  role: string
  nickname: string | null
  avatarUrl: string | null
}

interface AuthContextValue {
  user: SessionUser | null
  login: (email: string, password: string) => Promise<SessionUser>
  register: (email: string, password: string) => Promise<SessionUser>
  logout: () => void
  /** 个人中心编辑昵称/头像后同步全站（顶部导航、个人中心，个人中心需求 §10.3）。 */
  updateProfile: (profile: UserProfile) => void
}

const AuthContext = createContext<AuthContextValue | undefined>(undefined)

function storedUser(): SessionUser | null {
  if (!sessionStorage.getItem(TOKEN_KEY)) return null
  const raw = sessionStorage.getItem(USER_KEY)
  if (!raw) return null
  try {
    const parsed = JSON.parse(raw) as SessionUser
    return { ...parsed, nickname: parsed.nickname ?? null, avatarUrl: parsed.avatarUrl ?? null }
  } catch {
    sessionStorage.removeItem(TOKEN_KEY)
    sessionStorage.removeItem(USER_KEY)
    return null
  }
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<SessionUser | null>(storedUser)

  async function authenticate(
    action: typeof loginRequest,
    email: string,
    password: string,
  ): Promise<SessionUser> {
    const response = await action(email, password)
    const nextUser: SessionUser = {
      email: response.email,
      role: response.role,
      nickname: response.nickname ?? null,
      avatarUrl: response.avatarUrl ?? null,
    }
    sessionStorage.setItem(TOKEN_KEY, response.token)
    sessionStorage.setItem(USER_KEY, JSON.stringify(nextUser))
    setUser(nextUser)
    return nextUser
  }

  const updateProfile = useCallback((profile: UserProfile) => {
    setUser((current) => {
      if (!current) return current
      const nextUser: SessionUser = {
        email: profile.email,
        role: profile.role,
        nickname: profile.nickname ?? null,
        avatarUrl: profile.avatarUrl ?? null,
      }
      sessionStorage.setItem(USER_KEY, JSON.stringify(nextUser))
      return nextUser
    })
  }, [])

  const value = useMemo<AuthContextValue>(
    () => ({
      user,
      login: (email, password) => authenticate(loginRequest, email, password),
      register: (email, password) => authenticate(registerRequest, email, password),
      logout: () => {
        sessionStorage.removeItem(TOKEN_KEY)
        sessionStorage.removeItem(USER_KEY)
        setUser(null)
      },
      updateProfile,
    }),
    [user],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth() {
  const context = useContext(AuthContext)
  if (!context) throw new Error('useAuth must be used inside AuthProvider')
  return context
}
