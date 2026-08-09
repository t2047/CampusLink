// ──────────────────────────────────────────────
//  Chat Page — Campus AI Chat Interface
//  Tailwind CSS · 深色模式 · 完整 SSE 事件渲染
// ──────────────────────────────────────────────

import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type KeyboardEvent as ReactKeyboardEvent,
  type ReactNode,
} from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import {
  createChatStream,
  clearToken,
  type SseEvent,
} from '../services/api';

// ── Types ────────────────────────────────────

interface AgentStep {
  agent?: string;
  tool?: string;
  action?: string;
  status: 'running' | 'ok' | 'error';
  label: string;
}

interface ChatMessage {
  id: string;
  role: 'user' | 'assistant' | 'system' | 'error';
  content: string;
  steps: AgentStep[];
  timestamp: number;
}

interface PendingConfirm {
  msgId: string;
  agent: string;
  details: Record<string, unknown>;
}

let msgCounter = 0;

const SUGGESTIONS = [
  '帮我找一下最近的邮件',
  '明天下午有没有空的研讨室',
  '现在几点',
  '把 15 美元换算成人民币',
];

export default function ChatPage() {
  const navigate = useNavigate();
  const { logout: authLogout } = useAuth();
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [input, setInput] = useState('');
  const [connected, setConnected] = useState(false);

  // 会话 ID（多轮上下文）：localStorage 持久化，同一会话复用同一 thread_id；登出清除
  const [sessionId] = useState(() => {
    const existing = localStorage.getItem('sessionId');
    if (existing) return existing;
    const id = crypto.randomUUID();
    localStorage.setItem('sessionId', id);
    return id;
  });
  const [streaming, setStreaming] = useState(false);
  const [pendingConfirm, setPendingConfirm] = useState<PendingConfirm | null>(null);
  const [dark, setDark] = useState<boolean>(() => {
    const saved = localStorage.getItem('theme');
    if (saved) return saved === 'dark';
    return window.matchMedia('(prefers-color-scheme: dark)').matches;
  });

  const messagesEndRef = useRef<HTMLDivElement>(null);
  const taRef = useRef<HTMLTextAreaElement>(null);
  const closeRef = useRef<{ close: () => void } | null>(null);
  const streamingRef = useRef(false);

  // ── 深色模式：class 策略 + localStorage 持久化 ──
  useEffect(() => {
    document.documentElement.classList.toggle('dark', dark);
    localStorage.setItem('theme', dark ? 'dark' : 'light');
  }, [dark]);

  // ── 自动滚动到底部 ──
  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

  // ── 状态工具 ────────────────────────────────

  const appendContent = useCallback((msgId: string, content: string) => {
    setMessages((prev) =>
      prev.map((m) => (m.id === msgId ? { ...m, content: m.content + content } : m)),
    );
  }, []);

  const appendStep = useCallback((msgId: string, step: AgentStep) => {
    setMessages((prev) =>
      prev.map((m) => (m.id === msgId ? { ...m, steps: [...m.steps, step] } : m)),
    );
  }, []);

  const markLastStepOk = useCallback((msgId: string) => {
    setMessages((prev) =>
      prev.map((m) => {
        if (m.id !== msgId || m.steps.length === 0) return m;
        const steps = m.steps.map((s, i) =>
          i === m.steps.length - 1 ? { ...s, status: 'ok' as const } : s,
        );
        return { ...m, steps };
      }),
    );
  }, []);

  const finish = useCallback(() => {
    streamingRef.current = false;
    setStreaming(false);
    setConnected(false);
  }, []);

  // ── SSE 事件处理 ────────────────────────────

  const handleEvent = useCallback(
    (evt: SseEvent, msgId: string) => {
      const { type, data } = evt;

      switch (type) {
        case 'token': {
          const content = typeof data.content === 'string' ? data.content : '';
          if (content) appendContent(msgId, content);
          break;
        }

        case 'intent_detected': {
          const targets = Array.isArray(data.targets)
            ? (data.targets as string[]).join('、')
            : '';
          if (targets) appendStep(msgId, { status: 'ok', label: `🎯 ${targets}` });
          break;
        }

        case 'agent_start':
          appendStep(msgId, {
            agent: String(data.agent ?? 'Agent'),
            status: 'running',
            label: `🤖 ${String(data.agent ?? 'Agent')} 处理中…`,
          });
          break;

        case 'agent_step': {
          const action = String(data.action ?? '执行');
          const result = data.result != null ? ` → ${String(data.result)}` : '';
          appendStep(msgId, {
            agent: data.agent as string,
            action,
            status: data.status === 'error' ? 'error' : 'ok',
            label: `▸ ${action}${result}`,
          });
          break;
        }

        case 'agent_done':
          markLastStepOk(msgId);
          break;

        case 'agent_error':
          appendStep(msgId, {
            agent: data.agent as string,
            status: 'error',
            label: `❌ ${String(data.agent ?? 'Agent')} 失败：${String(data.message ?? '未知错误')}`,
          });
          finish();
          break;

        case 'utility_start':
          appendStep(msgId, {
            tool: data.tool as string,
            status: 'running',
            label: `🔧 ${String(data.tool ?? '工具')} 执行中…`,
          });
          break;

        case 'utility_result':
          markLastStepOk(msgId);
          break;

        case 'confirm_required': {
          setPendingConfirm({
            msgId,
            agent: String(data.agent ?? ''),
            details: (data.details as Record<string, unknown>) ?? {},
          });
          appendStep(msgId, { status: 'running', label: '⏳ 需要确认' });
          break;
        }

        case 'error': {
          const msg = typeof data.message === 'string' ? data.message : '未知错误';
          appendContent(msgId, `\n[错误] ${msg}`);
          finish();
          break;
        }

        case 'message': {
          // 兜底：未知/旧协议事件，避免静默丢弃
          const content = typeof data.content === 'string' ? data.content : '';
          const msg = typeof data.message === 'string' ? data.message : '';
          const raw = typeof data.raw === 'string' ? data.raw : '';
          if (content) appendContent(msgId, content);
          else if (msg) appendContent(msgId, `\n[信息] ${msg}`);
          else if (raw) appendContent(msgId, raw);
          break;
        }

        case 'done':
          finish();
          break;
      }
    },
    [appendContent, appendStep, finish, markLastStepOk],
  );

  // ── 发送 ────────────────────────────────────

  const send = useCallback(
    (text?: string) => {
      const msg = (text ?? input).trim();
      if (!msg || streamingRef.current) return;

      const userMsg: ChatMessage = {
        id: `msg-${++msgCounter}`,
        role: 'user',
        content: msg,
        steps: [],
        timestamp: Date.now(),
      };
      const assistantMsg: ChatMessage = {
        id: `msg-${++msgCounter}`,
        role: 'assistant',
        content: '',
        steps: [],
        timestamp: Date.now(),
      };

      setMessages((prev) => [...prev, userMsg, assistantMsg]);
      setInput('');
      if (taRef.current) taRef.current.style.height = 'auto';

      streamingRef.current = true;
      setStreaming(true);
      setConnected(true);

      const assistantId = assistantMsg.id;

      closeRef.current?.close();
      try {
        const stream = createChatStream(
          msg,
          sessionId,
          (evt) => handleEvent(evt, assistantId),
          () => finish(),
        );
        closeRef.current = stream;
      } catch (err) {
        appendContent(
          assistantId,
          `\n[错误] ${err instanceof Error ? err.message : '未知错误'}`,
        );
        finish();
      }
    },
    [input, sessionId, handleEvent, appendContent, finish],
  );

  // ── 停止生成 ────────────────────────────────

  const stop = useCallback(() => {
    closeRef.current?.close();
    closeRef.current = null;
    finish();
  }, [finish]);

  // ── 确认操作（HITL 本地响应）────────────────

  const resolveConfirmation = useCallback(
    (approved: boolean) => {
      if (!pendingConfirm) return;
      appendContent(
        pendingConfirm.msgId,
        `\n${approved ? '✅ 操作已确认。' : '❌ 操作已取消。'}`,
      );
      markLastStepOk(pendingConfirm.msgId);
      setPendingConfirm(null);
    },
    [pendingConfirm, appendContent, markLastStepOk],
  );

  // ── 退出 ────────────────────────────────────

  const handleLogout = useCallback(() => {
    closeRef.current?.close();
    clearToken();
    authLogout(); // 同步清 AuthContext 登录态（sessionStorage user），避免半登出状态
    localStorage.removeItem('sessionId');
    document.documentElement.classList.remove('dark');
    localStorage.removeItem('theme');
    navigate('/login', { replace: true });
  }, [navigate, authLogout]);

  // ── 输入框 ──────────────────────────────────

  const handleKeyDown = useCallback(
    (e: ReactKeyboardEvent<HTMLTextAreaElement>) => {
      if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        send();
      }
    },
    [send],
  );

  const autoResize = useCallback(() => {
    const el = taRef.current;
    if (!el) return;
    el.style.height = 'auto';
    el.style.height = `${Math.min(el.scrollHeight, 160)}px`;
  }, []);

  const lastAssistant = [...messages].reverse().find((m) => m.role === 'assistant');

  // ── Render ──────────────────────────────────

  return (
    <div className="flex h-screen flex-col bg-slate-100 text-slate-800 transition-colors dark:bg-slate-900 dark:text-slate-100">
      {/* ── Header ── */}
      <header className="flex shrink-0 items-center justify-between border-b border-slate-200 bg-white px-4 py-3 dark:border-slate-700 dark:bg-slate-800">
        <div className="flex items-center gap-3">
          <div className="flex h-9 w-9 items-center justify-center rounded-xl bg-gradient-to-br from-indigo-500 to-purple-500 text-lg font-bold text-white shadow-sm">
            C
          </div>
          <div>
            <h1 className="text-base font-bold leading-tight">Campus Link</h1>
            <p className="text-xs text-slate-500 dark:text-slate-400">校园 AI 助手</p>
          </div>
          <span
            className={`ml-2 inline-flex items-center gap-1.5 rounded-full px-2.5 py-0.5 text-xs font-medium ${
              streaming
                ? 'bg-amber-100 text-amber-700 dark:bg-amber-500/10 dark:text-amber-400'
                : connected
                  ? 'bg-emerald-100 text-emerald-700 dark:bg-emerald-500/10 dark:text-emerald-400'
                  : 'bg-slate-100 text-slate-500 dark:bg-slate-700 dark:text-slate-400'
            }`}
          >
            <span
              className={`h-1.5 w-1.5 rounded-full ${
                streaming
                  ? 'animate-pulse bg-amber-500'
                  : connected
                    ? 'bg-emerald-500'
                    : 'bg-slate-400 dark:bg-slate-500'
              }`}
            />
            {streaming ? '回复中' : connected ? '已连接' : '就绪'}
          </span>
        </div>

        <div className="flex items-center gap-2">
          {/* 深色模式切换 */}
          <button
            type="button"
            onClick={() => setDark((d) => !d)}
            title={dark ? '切换到浅色模式' : '切换到深色模式'}
            className="flex h-9 w-9 items-center justify-center rounded-lg border border-slate-200 bg-white text-base text-slate-600 transition-colors hover:bg-slate-100 dark:border-slate-600 dark:bg-slate-700 dark:text-slate-300 dark:hover:bg-slate-600"
          >
            {dark ? '☀️' : '🌙'}
          </button>
          <button
            type="button"
            onClick={handleLogout}
            className="rounded-lg border border-slate-200 bg-white px-3 py-1.5 text-sm text-slate-600 transition-colors hover:bg-slate-100 dark:border-slate-600 dark:bg-slate-700 dark:text-slate-300 dark:hover:bg-slate-600"
          >
            退出
          </button>
        </div>
      </header>

      {/* ── 消息区 ── */}
      <main className="flex-1 overflow-y-auto px-4 py-6">
        <div className="mx-auto flex w-full max-w-3xl flex-col gap-5">
          {messages.length === 0 && (
            <div className="flex flex-1 flex-col items-center justify-center gap-3 py-24 text-center">
              <div className="flex h-20 w-20 items-center justify-center rounded-3xl bg-gradient-to-br from-indigo-500 to-purple-500 text-4xl shadow-lg">
                💬
              </div>
              <p className="text-lg font-medium text-slate-700 dark:text-slate-200">
                开始和校园助手对话吧
              </p>
              <p className="text-sm text-slate-400 dark:text-slate-500">
                可以问我邮件、会议室、失物招领、技能市场相关的问题
              </p>
              <div className="mt-4 flex flex-wrap justify-center gap-2">
                {SUGGESTIONS.map((hint) => (
                  <button
                    key={hint}
                    type="button"
                    onClick={() => send(hint)}
                    className="rounded-full border border-slate-200 bg-white px-4 py-2 text-sm text-slate-600 transition-colors hover:border-indigo-400 hover:bg-indigo-50 hover:text-indigo-600 dark:border-slate-600 dark:bg-slate-800 dark:text-slate-300 dark:hover:border-indigo-500 dark:hover:bg-indigo-500/10 dark:hover:text-indigo-300"
                  >
                    {hint}
                  </button>
                ))}
              </div>
            </div>
          )}

          {messages.map((msg) => (
            <div
              key={msg.id}
              className={`flex flex-col gap-1.5 ${
                msg.role === 'user' ? 'items-end' : 'items-start'
              }`}
            >
              {/* 步骤卡片 */}
              {msg.steps.length > 0 && (
                <div className="flex w-full flex-col gap-1.5">
                  {msg.steps.map((s, i) => (
                    <div
                      key={`${msg.id}-step-${i}`}
                      className={`flex items-center gap-2 rounded-lg border px-3 py-1.5 text-xs transition-colors ${
                        s.status === 'error'
                          ? 'border-red-200 bg-red-50 text-red-700 dark:border-red-500/30 dark:bg-red-500/10 dark:text-red-300'
                          : s.status === 'running'
                            ? 'border-amber-200 bg-amber-50 text-amber-700 dark:border-amber-500/30 dark:bg-amber-500/10 dark:text-amber-300'
                            : 'border-emerald-200 bg-emerald-50 text-emerald-700 dark:border-emerald-500/30 dark:bg-emerald-500/10 dark:text-emerald-300'
                      }`}
                    >
                      <span className="shrink-0">
                        {s.status === 'running' ? '⏳' : s.status === 'error' ? '❌' : '✅'}
                      </span>
                      <span className="truncate">{s.label}</span>
                    </div>
                  ))}
                </div>
              )}

              {/* 消息气泡 */}
              {(msg.content || streaming) && (
                <div
                  className={`max-w-[85%] animate-fade-in whitespace-pre-wrap break-words rounded-2xl px-4 py-2.5 text-sm leading-relaxed shadow-sm ${
                    msg.role === 'user'
                      ? 'rounded-br-md bg-indigo-600 text-white'
                      : msg.role === 'error' || msg.role === 'system'
                        ? 'rounded-xl border border-red-200 bg-red-50 text-xs text-red-600 dark:border-red-500/30 dark:bg-red-500/10 dark:text-red-300'
                        : 'rounded-bl-md border border-slate-200 bg-white text-slate-800 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100'
                  }`}
                >
                  {msg.role === 'assistant' ? renderContent(msg.content) : msg.content}
                  {streaming &&
                    msg.role === 'assistant' &&
                    msg.id === lastAssistant?.id && (
                      <span className="typing-cursor" />
                    )}
                </div>
              )}

              {/* 确认操作栏 */}
              {pendingConfirm?.msgId === msg.id && (
                <div className="flex items-center gap-2">
                  <span className="text-xs text-slate-500 dark:text-slate-400">
                    {pendingConfirm.agent} 需要确认：
                    {typeof pendingConfirm.details === 'object' &&
                      pendingConfirm.details !== null &&
                      'message' in pendingConfirm.details &&
                      String(pendingConfirm.details.message)}
                  </span>
                  <button
                    type="button"
                    onClick={() => resolveConfirmation(true)}
                    className="rounded-lg bg-indigo-600 px-3 py-1 text-xs font-medium text-white transition-colors hover:bg-indigo-700"
                  >
                    确认
                  </button>
                  <button
                    type="button"
                    onClick={() => resolveConfirmation(false)}
                    className="rounded-lg border border-slate-300 px-3 py-1 text-xs text-slate-600 transition-colors hover:bg-slate-100 dark:border-slate-600 dark:text-slate-300 dark:hover:bg-slate-700"
                  >
                    取消
                  </button>
                </div>
              )}
            </div>
          ))}

          {/* 思考中指示器 */}
          {streaming && lastAssistant?.content === '' && (
            <div className="flex items-center gap-2 rounded-2xl rounded-bl-md border border-slate-200 bg-white px-4 py-3 dark:border-slate-700 dark:bg-slate-800">
              <span className="flex gap-1">
                <span className="h-1.5 w-1.5 animate-bounce-dot rounded-full bg-indigo-500" />
                <span className="h-1.5 w-1.5 animate-bounce-dot rounded-full bg-indigo-500 [animation-delay:0.2s]" />
                <span className="h-1.5 w-1.5 animate-bounce-dot rounded-full bg-indigo-500 [animation-delay:0.4s]" />
              </span>
              <span className="text-xs text-slate-400 dark:text-slate-500">思考中…</span>
            </div>
          )}

          <div ref={messagesEndRef} />
        </div>
      </main>

      {/* ── 输入区 ── */}
      <footer className="shrink-0 border-t border-slate-200 bg-white px-4 py-3 dark:border-slate-700 dark:bg-slate-800">
        <div className="mx-auto flex w-full max-w-3xl items-end gap-2">
          <textarea
            ref={taRef}
            rows={1}
            value={input}
            onChange={(e) => {
              setInput(e.target.value);
              autoResize();
            }}
            onKeyDown={handleKeyDown}
            placeholder="输入消息，Enter 发送，Shift+Enter 换行…"
            className="max-h-40 min-h-[44px] flex-1 resize-none rounded-2xl border border-slate-300 bg-slate-50 px-4 py-2.5 text-sm outline-none transition-all placeholder:text-slate-400 focus:border-indigo-500 focus:bg-white focus:ring-4 focus:ring-indigo-500/10 disabled:opacity-60 dark:border-slate-600 dark:bg-slate-900 dark:text-slate-100 dark:placeholder:text-slate-500 dark:focus:border-indigo-500 dark:focus:bg-slate-900"
          />
          {streaming ? (
            <button
              type="button"
              onClick={stop}
              className="flex h-[44px] shrink-0 items-center gap-1.5 rounded-2xl bg-red-500 px-5 text-sm font-semibold text-white shadow-sm transition-colors hover:bg-red-600"
            >
              <svg
                className="h-4 w-4"
                fill="currentColor"
                viewBox="0 0 24 24"
              >
                <rect x="6" y="6" width="12" height="12" rx="1.5" />
              </svg>
              停止
            </button>
          ) : (
            <button
              type="button"
              onClick={() => send()}
              disabled={!input.trim()}
              className="flex h-[44px] shrink-0 items-center gap-1.5 rounded-2xl bg-indigo-600 px-5 text-sm font-semibold text-white shadow-sm transition-colors hover:bg-indigo-700 disabled:cursor-not-allowed disabled:bg-slate-300 dark:disabled:bg-slate-700"
            >
              <svg
                className="h-4 w-4"
                fill="currentColor"
                viewBox="0 0 24 24"
              >
                <path d="M6 12 3.269 3.126A59.768 59.768 0 0 1 21.485 12 59.77 59.77 0 0 1 3.27 20.876L5.999 12zm0 0h7.5" />
              </svg>
              发送
            </button>
          )}
        </div>
        <p className="mx-auto mt-1.5 w-full max-w-3xl text-center text-[11px] text-slate-400 dark:text-slate-600">
          AI 生成内容仅供参考 · Campus Link
        </p>
      </footer>
    </div>
  );
}

// ── Helpers ──────────────────────────────────

/** Markdown 渲染：标题 / 代码块 / 列表（有序·无序）/ 引用 / 分隔线 / 表格 + 行内样式 */
function renderContent(text: string): ReactNode {
  if (!text) return null;

  const blocks: ReactNode[] = [];
  const lines = text.split(/\r?\n/);
  let key = 0;

  let para: string[] = [];
  let list: { ordered: boolean; items: string[] } | null = null;
  let inCode = false;
  let codeLang = '';
  let codeLines: string[] = [];
  let table: { header: string[]; rows: string[][] } | null = null;

  const flushPara = () => {
    if (para.length > 0) {
      blocks.push(
        <p key={key++} className="min-h-[1em]">
          {renderInline(para.join('\n'))}
        </p>,
      );
      para = [];
    }
  };

  const flushList = () => {
    if (list) {
      const Tag = list.ordered ? 'ol' : 'ul';
      blocks.push(
        <Tag
          key={key++}
          className={`my-1 ml-4 ${list.ordered ? 'list-decimal' : 'list-disc'} space-y-0.5`}
        >
          {list.items.map((it, i) => (
            <li key={i}>{renderInline(it)}</li>
          ))}
        </Tag>,
      );
      list = null;
    }
  };

  const flushCode = () => {
    if (inCode) {
      blocks.push(
        <pre
          key={key++}
          className="my-1.5 overflow-x-auto rounded-lg bg-slate-900 p-3 font-mono text-xs leading-relaxed text-slate-100 dark:bg-slate-900"
        >
          <code className={codeLang ? `language-${codeLang}` : ''}>{codeLines.join('\n')}</code>
        </pre>,
      );
      inCode = false;
      codeLang = '';
      codeLines = [];
    }
  };

  const flushTable = () => {
    if (table) {
      blocks.push(
        <div key={key++} className="my-1.5 overflow-x-auto">
          <table className="w-full border-collapse text-xs">
            <thead>
              <tr>
                {table.header.map((h, i) => (
                  <th
                    key={i}
                    className="border border-slate-300 bg-slate-100 px-2 py-1 text-left font-medium dark:border-slate-600 dark:bg-slate-700"
                  >
                    {renderInline(h)}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {table.rows.map((row, ri) => (
                <tr key={ri}>
                  {row.map((c, ci) => (
                    <td key={ci} className="border border-slate-300 px-2 py-1 dark:border-slate-600">
                      {renderInline(c)}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>,
      );
      table = null;
    }
  };

  const flushAll = () => {
    flushPara();
    flushList();
    flushCode();
    flushTable();
  };

  const isTableRow = (line: string) =>
    /^\s*\|/.test(line) && line.replace(/\|/g, '').trim() !== '';
  const isTableSep = (line: string) => /^[\s|:\-]+$/.test(line) && line.includes('-');

  for (const line of lines) {
    const trimmed = line.trim();

    // 代码块围栏（```lang）
    const fence = line.match(/^```(\w*)\s*$/);
    if (fence) {
      flushAll();
      if (inCode) flushCode();
      else {
        inCode = true;
        codeLang = fence[1];
      }
      continue;
    }
    if (inCode) {
      codeLines.push(line);
      continue;
    }

    if (trimmed === '') {
      flushAll();
      continue;
    }

    // 标题（# ~ ######）
    const heading = line.match(/^(#{1,6})\s+(.*)$/);
    if (heading) {
      flushAll();
      const level = Math.min(heading[1].length, 6);
      const Tag = `h${level}` as 'h1' | 'h2' | 'h3' | 'h4' | 'h5' | 'h6';
      const size =
        level === 1
          ? 'text-xl font-bold'
          : level === 2
            ? 'text-lg font-bold'
            : level === 3
              ? 'text-base font-semibold'
              : 'text-sm font-semibold';
      blocks.push(
        <Tag key={key++} className={`mt-2 mb-1 ${size}`}>
          {renderInline(heading[2])}
        </Tag>,
      );
      continue;
    }

    // 引用（>）
    if (/^>\s?/.test(line)) {
      flushAll();
      blocks.push(
        <blockquote
          key={key++}
          className="my-1 border-l-4 border-slate-300 pl-3 text-slate-500 dark:border-slate-600 dark:text-slate-400"
        >
          {renderInline(line.replace(/^>\s?/, ''))}
        </blockquote>,
      );
      continue;
    }

    // 分隔线（--- / *** / ___）
    if (/^\s*([-*_])\1{2,}\s*$/.test(line)) {
      flushAll();
      blocks.push(<hr key={key++} className="my-2 border-slate-300 dark:border-slate-600" />);
      continue;
    }

    // 表格：连续 | 行，第二行是 ---/:-: 分隔行时视为表头
    if (isTableRow(line) || (table && isTableSep(line))) {
      flushPara();
      flushList();
      if (!table) {
        table = { header: splitTableRow(line), rows: [] };
        continue;
      }
      if (isTableSep(line)) continue;
      table.rows.push(splitTableRow(line));
      continue;
    }
    flushTable();

    // 列表（无序 - * • / 有序 1. 1)）
    const ul = line.match(/^\s*[-*•]\s+(.*)$/);
    const ol = line.match(/^\s*\d+[.)]\s+(.*)$/);
    if (ul || ol) {
      flushPara();
      const ordered = !!ol;
      const content = (ul ?? ol)![1];
      if (!list || list.ordered !== ordered) {
        flushList();
        list = { ordered, items: [] };
      }
      list.items.push(content);
      continue;
    }

    // 普通段落行
    flushList();
    para.push(line);
  }
  flushAll();

  return <>{blocks}</>;
}

/** 按 | 切分表格行（去掉首尾管道符）。 */
function splitTableRow(line: string): string[] {
  return line
    .replace(/^\s*\|/, '')
    .replace(/\|\s*$/, '')
    .split('|')
    .map((c) => c.trim());
}

function renderInline(text: string): ReactNode {
  const parts: ReactNode[] = [];
  // 顺序：**粗体** → *斜体* → ~~删除线~~ → `代码` → URL
  const regex = /(\*\*(.+?)\*\*|\*([^*\n]+)\*|~~(.+?)~~|`([^`]+)`|(https?:\/\/[^\s]+))/g;
  let last = 0;
  let match: RegExpExecArray | null;
  let key = 0;

  while ((match = regex.exec(text)) !== null) {
    if (match.index > last) parts.push(text.slice(last, match.index));
    if (match[2]) {
      parts.push(
        <strong key={key++} className="font-semibold">
          {match[2]}
        </strong>,
      );
    } else if (match[3]) {
      parts.push(<em key={key++}>{match[3]}</em>);
    } else if (match[4]) {
      parts.push(
        <del key={key++} className="text-slate-400 dark:text-slate-500">
          {match[4]}
        </del>,
      );
    } else if (match[5]) {
      parts.push(
        <code
          key={key++}
          className="rounded bg-slate-200 px-1.5 py-0.5 font-mono text-[0.85em] text-rose-600 dark:bg-slate-700 dark:text-rose-300"
        >
          {match[5]}
        </code>,
      );
    } else if (match[6]) {
      parts.push(
        <a
          key={key++}
          href={match[6]}
          target="_blank"
          rel="noreferrer"
          className="text-indigo-600 underline dark:text-indigo-400"
        >
          {match[6]}
        </a>,
      );
    }
    last = regex.lastIndex;
  }

  if (last < text.length) parts.push(text.slice(last));
  return parts.length > 0 ? parts : text;
}
