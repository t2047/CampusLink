import { CssBaseline, ThemeProvider, createTheme } from '@mui/material'
import { StrictMode } from 'react'
import { useEffect, useMemo, useState } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import './index.css' // Tailwind base（Chat 页面样式）
import App from './App'
import { AuthProvider } from './auth/AuthContext'

function buildTheme(dark: boolean) {
  return createTheme({
    palette: {
      // 跟随 html.dark（Tailwind darkMode: class 由 ChatPage 切换）
      mode: dark ? 'dark' : 'light',
      primary: { main: '#2356a8', light: '#3f6fc4', dark: '#1b4385' },
      secondary: { main: '#0f766e', light: '#14958b', dark: '#0b5d57' },
      ...(dark
        ? { background: { default: '#0f172a', paper: '#1e293b' } }
        : {}),
    },
  typography: {
    fontFamily: 'Inter, ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif',
    button: { textTransform: 'none', fontWeight: 600, letterSpacing: '0.01em' },
  },
  shape: { borderRadius: 10 },
  components: {
    MuiButton: {
      styleOverrides: {
        root: {
          borderRadius: 10,
          boxShadow: 'none',
          transition: 'all .18s ease',
        },
        containedPrimary: {
          backgroundImage: 'linear-gradient(135deg, #3366b8 0%, #1d4f9e 100%)',
          boxShadow: '0 4px 14px rgba(35, 86, 168, 0.25)',
          '&:hover': {
            backgroundImage: 'linear-gradient(135deg, #3f74c4 0%, #1d4f9e 100%)',
            boxShadow: '0 6px 20px rgba(35, 86, 168, 0.35)',
            transform: 'translateY(-1px)',
          },
          '&:active': { transform: 'translateY(0px)' },
          '&.Mui-disabled': { backgroundImage: 'none', boxShadow: 'none' },
        },
        containedSecondary: {
          backgroundImage: 'linear-gradient(135deg, #14958b 0%, #0f766e 100%)',
          boxShadow: '0 4px 14px rgba(15, 118, 110, 0.25)',
          '&:hover': {
            backgroundImage: 'linear-gradient(135deg, #1aa397 0%, #0f766e 100%)',
            boxShadow: '0 6px 20px rgba(15, 118, 110, 0.35)',
            transform: 'translateY(-1px)',
          },
          '&:active': { transform: 'translateY(0px)' },
          '&.Mui-disabled': { backgroundImage: 'none', boxShadow: 'none' },
        },
        containedSuccess: {
          backgroundImage: 'linear-gradient(135deg, #22c55e 0%, #16a34a 100%)',
          boxShadow: '0 4px 14px rgba(34, 197, 94, 0.3)',
          '&:hover': {
            backgroundImage: 'linear-gradient(135deg, #2fce6b 0%, #16a34a 100%)',
            boxShadow: '0 6px 20px rgba(34, 197, 94, 0.4)',
            transform: 'translateY(-1px)',
          },
          '&:active': { transform: 'translateY(0px)' },
          '&.Mui-disabled': { backgroundImage: 'none', boxShadow: 'none' },
        },
        containedError: {
          backgroundImage: 'linear-gradient(135deg, #ef4444 0%, #dc2626 100%)',
          boxShadow: '0 4px 14px rgba(239, 68, 68, 0.28)',
          '&:hover': {
            backgroundImage: 'linear-gradient(135deg, #f25555 0%, #dc2626 100%)',
            boxShadow: '0 6px 20px rgba(239, 68, 68, 0.36)',
            transform: 'translateY(-1px)',
          },
          '&:active': { transform: 'translateY(0px)' },
          '&.Mui-disabled': { backgroundImage: 'none', boxShadow: 'none' },
        },
        outlined: {
          borderColor: '#d6deea',
          backgroundColor: dark ? 'rgba(255, 255, 255, 0.06)' : 'rgba(255, 255, 255, 0.6)',
          '&:hover': {
            borderColor: '#b9c8dd',
            backgroundColor: 'rgba(35, 86, 168, 0.06)',
          },
        },
        outlinedError: {
          borderColor: 'rgba(239, 68, 68, 0.45)',
          '&:hover': { borderColor: '#ef4444', backgroundColor: 'rgba(239, 68, 68, 0.06)' },
        },
        text: {
          '&:hover': { backgroundColor: 'rgba(35, 86, 168, 0.08)' },
        },
        sizeSmall: {
          borderRadius: 8,
          paddingLeft: 12,
          paddingRight: 12,
        },
      },
    },
    MuiIconButton: {
      styleOverrides: {
        root: {
          borderRadius: 10,
          transition: 'background-color .15s ease, transform .15s ease',
          '&:hover': { backgroundColor: 'rgba(35, 86, 168, 0.08)' },
        },
      },
    },
    MuiTabs: {
      styleOverrides: {
        indicator: { height: 3, borderRadius: 3, backgroundColor: '#2356a8' },
      },
    },
    MuiTab: {
      styleOverrides: {
        root: {
          textTransform: 'none',
          fontWeight: 600,
          '&.Mui-selected': { color: '#2356a8' },
        },
      },
    },
    MuiChip: {
      styleOverrides: {
        root: { borderRadius: 8, fontWeight: 600 },
        sizeSmall: { height: 22 },
      },
    },
    MuiOutlinedInput: {
      styleOverrides: {
        root: {
          borderRadius: 10,
          transition: 'box-shadow .18s ease',
          '&.Mui-focused': { boxShadow: '0 0 0 4px rgba(35, 86, 168, 0.12)' },
        },
      },
    },
    MuiDialog: {
      styleOverrides: {
        paper: { borderRadius: 16 },
      },
    },
    MuiDialogTitle: {
      styleOverrides: {
        root: { fontWeight: 700, fontSize: '1.125rem' },
      },
    },
    MuiAlert: {
      styleOverrides: {
        root: { borderRadius: 10 },
      },
    },
    MuiTooltip: {
      styleOverrides: {
        tooltip: { borderRadius: 8 },
      },
    },
    MuiListItemButton: {
      styleOverrides: {
        root: {
          '&.Mui-selected': {
            backgroundColor: 'rgba(35, 86, 168, 0.10)',
            '&:hover': { backgroundColor: 'rgba(35, 86, 168, 0.14)' },
          },
        },
      },
    },
  },
  })
}

function useMuiTheme() {
  // 跟随 html.dark class（Tailwind darkMode: class，由 ChatPage 主题切换驱动）
  const [dark, setDark] = useState<boolean>(() =>
    typeof document !== 'undefined' && document.documentElement.classList.contains('dark'),
  )
  useEffect(() => {
    const el = document.documentElement
    const observer = new MutationObserver(() =>
      setDark(el.classList.contains('dark')),
    )
    observer.observe(el, { attributes: true, attributeFilter: ['class'] })
    return () => observer.disconnect()
  }, [])
  return useMemo(() => buildTheme(dark), [dark])
}

function useThemeSync() {
  // 全局深色主题：用户未手动设置（localStorage 无 theme）时跟随系统
  // prefers-color-scheme；手动设置由 ChatPage 主题切换写入 localStorage。
  useEffect(() => {
    const apply = () => {
      if (!localStorage.getItem('theme')) {
        document.documentElement.classList.toggle(
          'dark',
          window.matchMedia('(prefers-color-scheme: dark)').matches,
        )
      }
    }
    apply()
    const mq = window.matchMedia('(prefers-color-scheme: dark)')
    mq.addEventListener('change', apply)
    return () => mq.removeEventListener('change', apply)
  }, [])
}

function Root() {
  useThemeSync()
  const theme = useMuiTheme()
  return (
    <ThemeProvider theme={theme}>
      <CssBaseline />
      <BrowserRouter>
        <AuthProvider><App /></AuthProvider>
      </BrowserRouter>
    </ThemeProvider>
  )
}

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <Root />
  </StrictMode>,
)
