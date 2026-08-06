// ──────────────────────────────────────────────
//  Login / Register Page — Tailwind + Dark mode
// ──────────────────────────────────────────────

import { useState } from 'react';
import {
  login,
  register,
  setToken,
  type LoginRequest,
  type RegisterRequest,
} from '../services/api';

interface LoginPageProps {
  onLogin: () => void;
}

export default function LoginPage({ onLogin }: LoginPageProps) {
  const [mode, setMode] = useState<'login' | 'register'>('login');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [name, setName] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  const toggleMode = () => {
    setMode(mode === 'login' ? 'register' : 'login');
    setError('');
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');
    setLoading(true);

    try {
      let result: { token: string };
      if (mode === 'login') {
        const req: LoginRequest = { email, password };
        result = await login(req);
      } else {
        const req: RegisterRequest = { email, password, name: name || undefined };
        result = await register(req);
      }
      setToken(result.token);
      onLogin();
    } catch (err) {
      setError(err instanceof Error ? err.message : '操作失败');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="flex min-h-screen flex-col items-center justify-center bg-gray-50 px-4 py-10 dark:bg-gray-950">
      <div className="w-full max-w-md animate-fade-in">
        {/* Card */}
        <div className="rounded-3xl border border-gray-200 bg-white p-8 shadow-sm dark:border-gray-800 dark:bg-gray-900">
          {/* Header */}
          <div className="mb-6 text-center">
            <div className="mx-auto mb-3 flex h-14 w-14 items-center justify-center rounded-2xl bg-brand-600 text-2xl font-bold text-white shadow-md">
              C
            </div>
            <h1 className="text-2xl font-bold text-gray-900 dark:text-gray-100">
              Campus Link
            </h1>
            <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">
              校园智能服务平台 · 校园 AI 助手
            </p>
          </div>

          {/* Tabs */}
          <div className="mb-6 grid grid-cols-2 gap-1 rounded-xl bg-gray-100 p-1 dark:bg-gray-800">
            <button
              type="button"
              onClick={() => setMode('login')}
              className={`rounded-lg py-2 text-sm font-medium transition-colors ${
                mode === 'login'
                  ? 'bg-white text-brand-600 shadow-sm dark:bg-gray-700 dark:text-brand-300'
                  : 'text-gray-500 hover:text-gray-700 dark:text-gray-400 dark:hover:text-gray-200'
              }`}
            >
              登录
            </button>
            <button
              type="button"
              onClick={() => setMode('register')}
              className={`rounded-lg py-2 text-sm font-medium transition-colors ${
                mode === 'register'
                  ? 'bg-white text-brand-600 shadow-sm dark:bg-gray-700 dark:text-brand-300'
                  : 'text-gray-500 hover:text-gray-700 dark:text-gray-400 dark:hover:text-gray-200'
              }`}
            >
              注册
            </button>
          </div>

          {/* Form */}
          <form onSubmit={handleSubmit} className="space-y-4">
            {mode === 'register' && (
              <div>
                <label
                  htmlFor="name"
                  className="mb-1.5 block text-sm font-medium text-gray-700 dark:text-gray-300"
                >
                  姓名 <span className="text-gray-400">（选填）</span>
                </label>
                <input
                  id="name"
                  type="text"
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  placeholder="你的姓名"
                  autoComplete="name"
                  className="w-full rounded-xl border border-gray-300 bg-gray-50 px-3.5 py-2.5 text-sm outline-none transition-all placeholder:text-gray-400 focus:border-brand-500 focus:bg-white focus:ring-4 focus:ring-brand-500/10 dark:border-gray-700 dark:bg-gray-800 dark:text-gray-100 dark:focus:border-brand-500 dark:focus:bg-gray-800"
                />
              </div>
            )}

            <div>
              <label
                htmlFor="email"
                className="mb-1.5 block text-sm font-medium text-gray-700 dark:text-gray-300"
              >
                邮箱
              </label>
              <input
                id="email"
                type="email"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                placeholder="your@campus.edu"
                required
                autoComplete="email"
                className="w-full rounded-xl border border-gray-300 bg-gray-50 px-3.5 py-2.5 text-sm outline-none transition-all placeholder:text-gray-400 focus:border-brand-500 focus:bg-white focus:ring-4 focus:ring-brand-500/10 dark:border-gray-700 dark:bg-gray-800 dark:text-gray-100 dark:focus:border-brand-500 dark:focus:bg-gray-800"
              />
            </div>

            <div>
              <label
                htmlFor="password"
                className="mb-1.5 block text-sm font-medium text-gray-700 dark:text-gray-300"
              >
                密码
              </label>
              <input
                id="password"
                type="password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                placeholder="••••••••"
                required
                minLength={6}
                autoComplete={mode === 'login' ? 'current-password' : 'new-password'}
                className="w-full rounded-xl border border-gray-300 bg-gray-50 px-3.5 py-2.5 text-sm outline-none transition-all placeholder:text-gray-400 focus:border-brand-500 focus:bg-white focus:ring-4 focus:ring-brand-500/10 dark:border-gray-700 dark:bg-gray-800 dark:text-gray-100 dark:focus:border-brand-500 dark:focus:bg-gray-800"
              />
            </div>

            {error && (
              <div className="rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-600 dark:border-red-500/30 dark:bg-red-500/10 dark:text-red-300">
                {error}
              </div>
            )}

            <button
              type="submit"
              disabled={loading}
              className="w-full rounded-xl bg-brand-600 py-2.5 text-sm font-semibold text-white shadow-sm transition-colors hover:bg-brand-700 disabled:cursor-not-allowed disabled:opacity-60"
            >
              {loading ? '处理中…' : mode === 'login' ? '登录' : '创建账号'}
            </button>
          </form>

          {/* Switch */}
          <p className="mt-5 text-center text-sm text-gray-500 dark:text-gray-400">
            {mode === 'login' ? '还没有账号？' : '已有账号？'}
            <button
              type="button"
              onClick={toggleMode}
              className="ml-1 font-medium text-brand-600 hover:underline dark:text-brand-400"
            >
              {mode === 'login' ? '立即注册' : '去登录'}
            </button>
          </p>
        </div>

        <footer className="mt-6 text-center text-xs text-gray-400 dark:text-gray-600">
          Campus Link · 校园智能服务平台
        </footer>
      </div>
    </div>
  );
}
