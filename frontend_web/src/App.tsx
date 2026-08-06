// ──────────────────────────────────────────────
//  App Root — Login / Chat routing + Dark mode init
// ──────────────────────────────────────────────

import { useState, useCallback, useEffect } from 'react';
import { isLoggedIn } from './services/api';
import LoginPage from './pages/LoginPage';
import ChatPage from './pages/ChatPage';

export default function App() {
  const [loggedIn, setLoggedIn] = useState(isLoggedIn);

  // 初始化深色模式（默认跟随系统，用户手动切换后存 localStorage）
  useEffect(() => {
    const saved = localStorage.getItem('theme');
    const prefersDark = window.matchMedia('(prefers-color-scheme: dark)').matches;
    const dark = saved ? saved === 'dark' : prefersDark;
    document.documentElement.classList.toggle('dark', dark);
  }, []);

  const handleLogin = useCallback(() => setLoggedIn(true), []);
  const handleLogout = useCallback(() => setLoggedIn(false), []);

  return loggedIn ? (
    <ChatPage onLogout={handleLogout} />
  ) : (
    <LoginPage onLogin={handleLogin} />
  );
}
