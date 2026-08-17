// ──────────────────────────────────────────────
//  Login / Register Page — Tailwind + Dark mode
//  统一登录/注册页（MUI AuthPage 已被本页取代）
// ──────────────────────────────────────────────

import { useState } from 'react';
import { Navigate, useLocation, useNavigate } from 'react-router-dom';
import { apiErrorMessage } from '../api/client';
import { useAuth } from '../auth/AuthContext';
import { PASSWORD_MAX_BYTES, PASSWORD_MIN_LENGTH, isPasswordLengthValid } from '../lib/passwordRules';

export default function LoginPage() {
  const navigate = useNavigate();
  const location = useLocation();
  const { user, login, register } = useAuth();
  // 初始模式跟随路由：/register 显示注册表单（旧 AuthPage 靠 mode prop 区分）
  const [mode, setMode] = useState<'login' | 'register'>(() =>
    location.pathname === '/register' ? 'register' : 'login',
  );
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  const destination = (location.state as { from?: { pathname?: string } } | null)?.from?.pathname;

  // 已登录访问登录页 → 按角色跳转（admin 进管理台，其余进聊天）
  if (user) {
    const next =
      destination ??
      (['ADMIN', 'SUPER_ADMIN'].includes(user.role) ? '/admin/dashboard' : '/chat');
    return <Navigate to={next} replace />;
  }

  const toggleMode = () => {
    setMode(mode === 'login' ? 'register' : 'login');
    setError('');
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');

    // 注册模式复用与后端一致的密码规则（≥6 字符且 ≤72 UTF-8 字节，ChangePassWord.md）
    if (mode === 'register' && !isPasswordLengthValid(password)) {
      setError(`Password must be at least ${PASSWORD_MIN_LENGTH} characters and at most ${PASSWORD_MAX_BYTES} bytes.`);
      return;
    }

    setLoading(true);

    try {
      const nextUser = await (mode === 'login' ? login(email, password) : register(email, password));
      // 跳转优先级与已登录分支一致：destination（原请求路径）> 角色默认页
      const next =
        destination ??
        (['ADMIN', 'SUPER_ADMIN'].includes(nextUser.role) ? '/admin/dashboard' : '/chat');
      navigate(next, { replace: true });
    } catch (err) {
      setError(apiErrorMessage(err));
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
            <img
              src="/campuslink-icon.png"
              alt="CampusLink"
              className="mx-auto mb-3 h-14 w-14 rounded-2xl object-contain p-1.5"
            />
            <h1 className="text-2xl font-bold text-gray-900 dark:text-gray-100">
              Campus Link
            </h1>
            <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">
              Smart Campus Platform · Campus AI Assistant
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
              Log in
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
              Register
            </button>
          </div>

          {/* Form */}
          <form onSubmit={handleSubmit} className="space-y-4">
            <div>
              <label
                htmlFor="email"
                className="mb-1.5 block text-sm font-medium text-gray-700 dark:text-gray-300"
              >
                Email
              </label>
              <input
                id="email"
                type="email"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                placeholder="you@campus.edu"
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
                Password
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
              <div
                role="alert"
                className="rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-600 dark:border-red-500/30 dark:bg-red-500/10 dark:text-red-300"
              >
                {error}
              </div>
            )}

            <button
              type="submit"
              disabled={loading}
              className="w-full rounded-xl bg-brand-600 py-2.5 text-sm font-semibold text-white shadow-sm transition-colors hover:bg-brand-700 disabled:cursor-not-allowed disabled:opacity-60"
            >
              {loading ? 'Processing…' : mode === 'login' ? 'Log in' : 'Create account'}
            </button>
          </form>

          {/* Switch */}
          <p className="mt-5 text-center text-sm text-gray-500 dark:text-gray-400">
            {mode === 'login' ? "Don't have an account?" : 'Already have an account?'}
            <button
              type="button"
              onClick={toggleMode}
              className="ml-1 font-medium text-brand-600 hover:underline dark:text-brand-400"
            >
              {mode === 'login' ? 'Sign up' : 'Log in'}
            </button>
          </p>
        </div>

        <footer className="mt-6 text-center text-xs text-gray-400 dark:text-gray-600">
          Campus Link · Smart Campus Platform
        </footer>
      </div>
    </div>
  );
}
