// ──────────────────────────────────────────────
//  NotFoundPage — 404
// ──────────────────────────────────────────────

import { Link } from 'react-router-dom';

export function NotFoundPage() {
  return (
    <div className="flex min-h-screen flex-col items-center justify-center bg-gray-50 px-4 dark:bg-gray-950">
      <div className="text-center">
        <p className="text-7xl font-bold text-gray-200 dark:text-gray-800">404</p>
        <h1 className="mt-3 text-xl font-semibold text-gray-800 dark:text-gray-200">
          页面不存在
        </h1>
        <p className="mt-2 text-sm text-gray-500 dark:text-gray-400">
          你访问的地址可能已移动或拼写有误
        </p>
        <Link
          to="/chat"
          className="mt-6 inline-block rounded-xl bg-brand-600 px-6 py-2.5 text-sm font-semibold text-white shadow-sm transition-colors hover:bg-brand-700"
        >
          返回聊天
        </Link>
      </div>
    </div>
  );
}
