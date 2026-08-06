import { createContext, useContext, useMemo, useState, type ReactNode } from 'react'
import { login as loginRequest, register as registerRequest } from '../api/auth'
import { TOKEN_KEY, USER_KEY } from '../api/client'

interface SessionUser {
  email: string
  role: string
}

interface AuthContextValue {
  user: SessionUser | null
  login: (email: string, password: string) => Promise<void>
  register: (email: string, password: string) => Promise<void>
  logout: () => void
}

const AuthContext = createContext<AuthContextValue | undefined>(undefined)

function storedUser(): SessionUser | null {
  if (!sessionStorage.getItem(TOKEN_KEY)) return null
  const raw = sessionStorage.getItem(USER_KEY)
  if (!raw) return null
  try {
    return JSON.parse(raw) as SessionUser
  } catch {
    sessionStorage.removeItem(TOKEN_KEY)
    sessionStorage.removeItem(USER_KEY)
    return null
  }
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<SessionUser | null>(storedUser)

  async function authenticate(action: typeof loginRequest, email: string, password: string) {
    const response = await action(email, password)
    const nextUser = { email: response.email, role: response.role }
    sessionStorage.setItem(TOKEN_KEY, response.token)
    sessionStorage.setItem(USER_KEY, JSON.stringify(nextUser))
    setUser(nextUser)
  }

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
