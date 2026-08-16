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
import { Link, useNavigate } from 'react-router-dom';
import ForumOutlinedIcon from '@mui/icons-material/ForumOutlined';
import LanguageOutlinedIcon from '@mui/icons-material/LanguageOutlined';
import AddCommentOutlinedIcon from '@mui/icons-material/AddCommentOutlined';
import AppsOutlinedIcon from '@mui/icons-material/AppsOutlined';
import ExpandMoreIcon from '@mui/icons-material/ExpandMore';
import DarkModeOutlinedIcon from '@mui/icons-material/DarkModeOutlined';
import MailOutlineIcon from '@mui/icons-material/MailOutline';
import SearchOutlinedIcon from '@mui/icons-material/SearchOutlined';
import MeetingRoomOutlinedIcon from '@mui/icons-material/MeetingRoomOutlined';
import AdminPanelSettingsOutlinedIcon from '@mui/icons-material/AdminPanelSettingsOutlined';
import { useAuth } from '../auth/AuthContext';
import type { AgentMatchResult } from '../api/lostFoundAgent';
import {
  createChatStream,
  createChatResumeStream,
  clearToken,
  type SseEvent,
} from '../services/api';
import { useSpeechRecognition } from '../hooks/useSpeechRecognition';

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
  matches: AgentMatchResult[];
  timestamp: number;
}

// crypto.randomUUID 仅在 secure context（HTTPS / localhost）可用；
// HTTP 部署（如 http://<vm-ip>）下不存在，需兜底生成 UUID v4
function randomUUID(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  const hex = (chars: number) =>
    Array.from({ length: chars }, () =>
      Math.floor(Math.random() * 16).toString(16),
    ).join('');
  return `${hex(8)}-${hex(4)}-4${hex(3)}-${((Math.random() * 4) | 8).toString(16)}${hex(3)}-${hex(12)}`;
}

interface PendingConfirm {
  msgId: string;
  agent: string;
  details: Record<string, unknown>;
  /** interrupt 顶层确认提示（human_approval）；details 无 message 时回退 summary */
  message?: string;
}

let msgCounter = 0;

// ── i18n（en / zh；范围：Chat 页。组员页面已英文，保持不动）──
type Lang = 'en' | 'zh';

const TEXTS: Record<Lang, Record<string, string>> = {
  en: {
    subtitle: 'Campus AI Assistant',
    status_streaming: 'Replying',
    status_connected: 'Connected',
    status_idle: 'Ready',
    newChat: 'New Chat',
    newChatTitle: 'Start a new conversation (clears current session)',
    services: 'Services',
    servicesTitle: 'Open subsystem entry list',
    lostFound: 'Lost & Found',
    mail: 'Mail',
    facilities: 'Facilities',
    admin: 'Admin',
    welcomeTitle: 'Start a conversation with the campus assistant',
    welcomeSub: 'Ask about email, meeting rooms, or lost & found',
    placeholder: 'Type a message, Enter to send, Shift+Enter for newline…',
    stop: 'Stop',
    send: 'Send',
    micStart: 'Voice input',
    micStop: 'Stop voice input',
    micUnsupported: 'Voice input requires Chrome or Edge',
    micError: 'Voice recognition failed',
    micErrNoSpeech: 'No speech detected',
    micErrNotAllowed: 'Microphone permission denied',
    micErrAudioCapture: 'Cannot access the microphone',
    micErrNetwork: 'Network error',
    needConfirm: 'needs confirmation:',
    confirm: 'Confirm',
    cancel: 'Cancel',
    thinking: 'Thinking…',
    errPrefix: '[Error]',
    confirmedNote: '\n✅ Confirmed.',
    cancelledNote: '\n❌ Cancelled.',
    confirmStep: '⏳ Waiting for confirmation',
    completed: 'completed',
    unknownError: 'Unknown error',
    langToggle: '中文',
  },
  zh: {
    subtitle: '校园 AI 助手',
    status_streaming: '回复中',
    status_connected: '已连接',
    status_idle: '就绪',
    newChat: '新对话',
    newChatTitle: '开启新对话（清空当前会话）',
    services: '子系统',
    servicesTitle: '打开子系统入口列表',
    lostFound: '失物招领',
    mail: '邮件',
    facilities: '设施',
    admin: '管理后台',
    welcomeTitle: '开始和校园助手对话吧',
    welcomeSub: '可以问我邮件、会议室、失物招领相关的问题',
    placeholder: '输入消息，Enter 发送，Shift+Enter 换行…',
    stop: '停止',
    send: '发送',
    micStart: '语音输入',
    micStop: '停止语音输入',
    micUnsupported: '语音输入需要 Chrome 或 Edge 浏览器',
    micError: '语音识别失败',
    micErrNoSpeech: '未检测到语音',
    micErrNotAllowed: '麦克风权限被拒绝',
    micErrAudioCapture: '无法访问麦克风',
    micErrNetwork: '网络错误',
    needConfirm: '需要确认：',
    confirm: '确认',
    cancel: '取消',
    thinking: '思考中…',
    errPrefix: '[错误]',
    confirmedNote: '\n✅ 操作已确认。',
    cancelledNote: '\n❌ 操作已取消。',
    confirmStep: '⏳ 需要确认',
    completed: '已完成',
    unknownError: '未知错误',
    langToggle: 'English',
  },
};

const SUGGESTIONS: Record<Lang, string[]> = {
  en: ['Find my recent emails', 'Any free meeting rooms tomorrow afternoon?', 'What time is it now?'],
  zh: ['帮我找一下最近的邮件', '明天下午有没有空的研讨室', '现在几点'],
};

export default function ChatPage({ compact = false }: { compact?: boolean }) {
  const navigate = useNavigate();
  const { user, logout: authLogout } = useAuth();
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [input, setInput] = useState('');
  const [connected, setConnected] = useState(false);

  // 会话 ID（多轮上下文）：localStorage 持久化，同一会话复用同一 thread_id；
  // 「新对话」按钮换新 ID 开新会话；登出清除
  const [sessionId, setSessionId] = useState<string>(() => {
    const existing = localStorage.getItem('sessionId');
    if (existing) return existing;
    const id = randomUUID();
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
  // 是否手动设置过主题：未手动设置时跟随系统（main.tsx useThemeSync 全局同步）
  const manualThemeRef = useRef(localStorage.getItem('theme') !== null);
  // ── 界面语言（en/zh）：localStorage 持久化，默认 en（优先英文）──
  const [lang, setLang] = useState<Lang>(() => {
    const saved = localStorage.getItem('lang');
    return saved === 'zh' ? 'zh' : 'en';
  });
  useEffect(() => {
    localStorage.setItem('lang', lang);
  }, [lang]);
  const t = useCallback((key: string) => TEXTS[lang][key] ?? key, [lang]);

  // 语音输入（STT）：浏览器 Web Speech API，识别结果填入输入框
  const speech = useSpeechRecognition(lang === 'zh' ? 'zh-CN' : 'en-US');
  const micErrorText = useCallback(
    (code: string) => {
      const map: Record<string, string> = {
        'no-speech': t('micErrNoSpeech'),
        'not-allowed': t('micErrNotAllowed'),
        'audio-capture': t('micErrAudioCapture'),
        network: t('micErrNetwork'),
      };
      return map[code] ?? code;
    },
    [t],
  );
  const toggleMic = useCallback(() => {
    if (speech.listening) {
      speech.stop();
      return;
    }
    speech.start({
      onFinal: (text) => {
        setInput((prev) => (prev ? `${prev} ${text}` : text));
        // 内联 autoResize（taRef 始终可用；autoResize 定义在下方，避免 TDZ）
        const el = taRef.current;
        if (el) {
          el.style.height = 'auto';
          el.style.height = `${Math.min(el.scrollHeight, 160)}px`;
        }
      },
    });
  }, [speech]);

  const messagesEndRef = useRef<HTMLDivElement>(null);
  const taRef = useRef<HTMLTextAreaElement>(null);
  const closeRef = useRef<{ close: () => void } | null>(null);
  const [submenuOpen, setSubmenuOpen] = useState(false);
  const submenuRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!submenuOpen) return;
    const onDocClick = (ev: MouseEvent) => {
      if (submenuRef.current && !submenuRef.current.contains(ev.target as Node)) {
        setSubmenuOpen(false);
      }
    };
    document.addEventListener('mousedown', onDocClick);
    return () => document.removeEventListener('mousedown', onDocClick);
  }, [submenuOpen]);
  const streamingRef = useRef(false);
  // HITL 确认框同步 ref：setPendingConfirm 异步生效，双击/连点时闭包仍是旧值，
  // 用 ref 做同步守卫（防重复 resume 导致写操作重复执行）
  const pendingConfirmRef = useRef<PendingConfirm | null>(null);

  // ── 深色模式：class 策略 + localStorage 持久化（仅手动设置时写入，未设置则跟随系统）──
  useEffect(() => {
    document.documentElement.classList.toggle('dark', dark);
    if (manualThemeRef.current) {
      localStorage.setItem('theme', dark ? 'dark' : 'light');
    }
  }, [dark]);

  // ── 自动滚动到底部 ──
  useEffect(() => {
    const element = messagesEndRef.current;
    if (element && typeof element.scrollIntoView === 'function') {
      element.scrollIntoView({ behavior: 'smooth' });
    }
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

  const setMatchResults = useCallback((msgId: string, matches: AgentMatchResult[]) => {
    setMessages((prev) =>
      prev.map((message) => (message.id === msgId ? { ...message, matches } : message)),
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

  const settleRunningStep = useCallback(
    (
      msgId: string,
      selector: { agent?: string; tool?: string },
      settled: AgentStep,
    ) => {
      setMessages((prev) =>
        prev.map((message) => {
          if (message.id !== msgId) return message;
          let matchingIndex = -1;
          message.steps.forEach((step, index) => {
            const agentMatches = selector.agent == null || step.agent === selector.agent;
            const toolMatches = selector.tool == null || step.tool === selector.tool;
            if (step.status === 'running' && agentMatches && toolMatches) {
              matchingIndex = index;
            }
          });
          if (matchingIndex < 0) {
            return { ...message, steps: [...message.steps, settled] };
          }
          return {
            ...message,
            steps: message.steps.map((step, index) =>
              index === matchingIndex ? settled : step,
            ),
          };
        }),
      );
    },
    [],
  );

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
            label: `${String(data.agent ?? 'Agent')} 处理中…`,
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

        case 'agent_done': {
          const agent = String(data.agent ?? 'Agent');
          settleRunningStep(
            msgId,
            { agent },
            { agent, status: 'ok', label: `${agent} ${t('completed')}` },
          );
          break;
        }

        case 'match_results': {
          const items = Array.isArray(data.items) ? data.items as AgentMatchResult[] : [];
          if (items.length > 0) setMatchResults(msgId, items);
          break;
        }

        case 'agent_error': {
          const agent = String(data.agent ?? 'Agent');
          settleRunningStep(
            msgId,
            { agent },
            {
              agent,
              status: 'error',
              label: `${agent} 失败：${String(data.message ?? t('unknownError'))}`,
            },
          );
          finish();
          break;
        }

        case 'utility_start':
          appendStep(msgId, {
            tool: data.tool as string,
            status: 'running',
            label: `${String(data.tool ?? '工具')} 执行中…`,
          });
          break;

        case 'utility_result': {
          const tool = String(data.tool ?? '工具');
          settleRunningStep(
            msgId,
            { tool },
            { tool, status: 'ok', label: `${tool} ${t('completed')}` },
          );
          break;
        }

        case 'confirm_required': {
          const confirmData: PendingConfirm = {
            msgId,
            agent: String(data.agent ?? ''),
            details: (data.details as Record<string, unknown>) ?? {},
            message: typeof data.message === 'string' ? data.message : undefined,
          };
          pendingConfirmRef.current = confirmData;
          setPendingConfirm(confirmData);
          appendStep(msgId, { status: 'running', label: t('confirmStep') });
          break;
        }

        case 'error': {
          const msg = typeof data.message === 'string' ? data.message : t('unknownError');
          appendContent(msgId, `\n${t('errPrefix')} ${msg}`);
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
    [appendContent, appendStep, finish, setMatchResults, settleRunningStep, t],
  );

  // ── 发送 ────────────────────────────────────

  const send = useCallback(
    (text?: string) => {
      const msg = (text ?? input).trim();
      // 有挂起的确认框时禁发新消息（确认框对应旧 thread，新消息会换 thread 并行执行）
      if (!msg || streamingRef.current || pendingConfirmRef.current) return;

      const userMsg: ChatMessage = {
        id: `msg-${++msgCounter}`,
        role: 'user',
        content: msg,
        steps: [],
        matches: [],
        timestamp: Date.now(),
      };
      const assistantMsg: ChatMessage = {
        id: `msg-${++msgCounter}`,
        role: 'assistant',
        content: '',
        steps: [],
        matches: [],
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
          `\n${t('errPrefix')} ${err instanceof Error ? err.message : t('unknownError')}`,
        );
        finish();
      }
    },
    [input, sessionId, handleEvent, appendContent, finish, t],
  );

  // ── 停止生成 ────────────────────────────────

  const stop = useCallback(() => {
    closeRef.current?.close();
    closeRef.current = null;
    finish();
  }, [finish]);

  // ── 确认操作（HITL）────────────────────────────

  const resolveConfirmation = useCallback(
    (approved: boolean) => {
      // 防双击/防过期确认：ref 同步守卫（setPendingConfirm 异步生效）
      if (!pendingConfirmRef.current) return;
      const { msgId } = pendingConfirmRef.current;
      pendingConfirmRef.current = null;
      setPendingConfirm(null);
      // 本地提示 + 标记确认步骤
      appendContent(
        msgId,
        `\n${approved ? t('confirmedNote') : t('cancelledNote')}`,
      );
      markLastStepOk(msgId);

      // 关闭挂起的旧流（interrupt 时编排层 SSE 连接仍保持），再开 resume 流
      closeRef.current?.close();
      closeRef.current = null;

      // 恢复编排层挂起的 LangGraph（确认/取消都触发 resume）：
      // 确认 → 编排层以 confirmed=true 重调子 Agent，写操作执行结果流式追加到本条消息
      // 取消 → 编排层跳过该 Agent（index 前进）
      streamingRef.current = true;
      setStreaming(true);
      setConnected(true);
      try {
        const stream = createChatResumeStream(
          sessionId,
          approved,
          (evt) => handleEvent(evt, msgId),
          () => finish(),
        );
        closeRef.current = stream;
      } catch (err) {
        appendContent(
          msgId,
          `\n${t('errPrefix')} ${err instanceof Error ? err.message : t('unknownError')}`,
        );
        finish();
      }
    },
    [
      sessionId,
      handleEvent,
      appendContent,
      finish,
      markLastStepOk,
      t,
    ],
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

  // 新对话：换新 thread_id 并清空界面（旧会话仍保留在后端 checkpoint，不删除）
  const handleNewChat = useCallback(() => {
    closeRef.current?.close();
    closeRef.current = null;
    const id = randomUUID();
    localStorage.setItem('sessionId', id);
    setSessionId(id);
    setMessages([]);
    setInput('');
    setPendingConfirm(null);
    pendingConfirmRef.current = null;
    setConnected(false);
    setStreaming(false);
    streamingRef.current = false;
  }, []);

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
    <div className={`${compact ? 'h-full min-h-0' : 'h-screen'} flex flex-col bg-slate-100 text-slate-800 transition-colors dark:bg-slate-900 dark:text-slate-100`}>
      {/* ── Header ── */}
      <header className="flex shrink-0 items-center justify-between border-b border-slate-200 bg-white px-4 py-3 dark:border-slate-700 dark:bg-slate-800">
        <div className="flex items-center gap-3">
          <div className="flex h-9 w-9 items-center justify-center rounded-xl bg-gradient-to-br from-blue-600 to-indigo-700 text-lg font-bold text-white shadow-sm">
             <ForumOutlinedIcon sx={{ fontSize: 22 }} />
          </div>
          <div>
            <h1 className="text-base font-bold leading-tight">Campus Link</h1>
            <p className="text-xs text-slate-500 dark:text-slate-400">{t('subtitle')}</p>
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
            {streaming ? t('status_streaming') : connected ? t('status_connected') : t('status_idle')}
          </span>
        </div>

        <div className={`${compact ? 'hidden' : 'flex'} items-center gap-2`}>
          {/* 语言切换（en/zh） */}
          <button
            type="button"
            onClick={() => setLang((l) => (l === 'zh' ? 'en' : 'zh'))}
            title="Switch language / 切换语言"
             className="flex h-9 items-center gap-2 rounded-xl border border-indigo-100 bg-indigo-50/50 px-3 text-sm font-medium text-indigo-700 transition-all hover:-translate-y-0.5 hover:border-indigo-300 hover:bg-indigo-50 hover:shadow-sm dark:border-indigo-500/30 dark:bg-indigo-500/10 dark:text-indigo-300 dark:hover:bg-indigo-500/20 [&>span:nth-child(2)]:hidden [&>span:last-child]:hidden"
          >
            <span className="grid h-5 w-5 place-items-center rounded-md bg-white/80 text-indigo-600 dark:bg-indigo-500/20 dark:text-indigo-300"><LanguageOutlinedIcon sx={{ fontSize: 16 }} /></span>
            <span className="text-base leading-none">🌐</span>
            {t('langToggle')}
          </button>

          {/* 新对话：换新 thread_id 开新会话 */}
          <button
            type="button"
            onClick={handleNewChat}
            title={t('newChatTitle')}
             className="flex h-9 items-center gap-2 rounded-xl border border-indigo-100 bg-indigo-50/50 px-3 text-sm font-medium text-indigo-700 transition-all hover:-translate-y-0.5 hover:border-indigo-300 hover:bg-indigo-50 hover:shadow-sm dark:border-indigo-500/30 dark:bg-indigo-500/10 dark:text-indigo-300 dark:hover:bg-indigo-500/20 [&>span:nth-child(2)]:hidden"
          >
            <span className="grid h-5 w-5 place-items-center rounded-md bg-white/80 text-indigo-600 dark:bg-indigo-500/20 dark:text-indigo-300"><AddCommentOutlinedIcon sx={{ fontSize: 16 }} /></span>
            <span className="text-base leading-none">✨</span>
            {t('newChat')}
          </button>

          {/* 前往各子系统入口（折叠为下拉，避免顶部拥挤） */}
          <div className="relative hidden" ref={submenuRef}>
            <button
              type="button"
              onClick={() => setSubmenuOpen((o) => !o)}
              title={t('servicesTitle')}
             className="flex h-9 items-center gap-2 rounded-xl border border-indigo-100 bg-indigo-50/50 px-3 text-sm font-medium text-indigo-700 transition-all hover:-translate-y-0.5 hover:border-indigo-300 hover:bg-indigo-50 hover:shadow-sm dark:border-indigo-500/30 dark:bg-indigo-500/10 dark:text-indigo-300 dark:hover:bg-indigo-500/20 [&>span:nth-child(2)]:hidden"
            >
              <span className="grid h-5 w-5 place-items-center rounded-md bg-white/80 text-indigo-600 dark:bg-indigo-500/20 dark:text-indigo-300"><AppsOutlinedIcon sx={{ fontSize: 16 }} /></span>
              <span className="text-base leading-none">🧩</span>
              {t('services')}
              <ExpandMoreIcon className={`text-indigo-500 transition-transform ${submenuOpen ? 'rotate-180' : ''}`} sx={{ fontSize: 18 }} />
            </button>
            {submenuOpen && (
              <div className="absolute right-0 top-full z-20 mt-2 w-72 overflow-hidden rounded-2xl border border-slate-200 bg-white p-2 shadow-xl shadow-slate-900/10 dark:border-slate-600 dark:bg-slate-800">
                <Link
                  to="/mail"
                  onClick={() => setSubmenuOpen(false)}
                  className="group flex items-center gap-3 rounded-xl px-3 py-3 text-sm text-slate-700 transition-colors hover:bg-indigo-50 dark:text-slate-200 dark:hover:bg-indigo-500/10 [&>span:nth-child(2)]:hidden"
                >
                  <span className="grid h-9 w-9 shrink-0 place-items-center rounded-xl bg-violet-50 text-violet-600 group-hover:bg-violet-100 dark:bg-violet-500/10 dark:text-violet-300"><MailOutlineIcon sx={{ fontSize: 20 }} /></span>
                  <span className="text-base leading-none">✉️</span>
                  <span className="flex-1 font-medium">{t('mail')}</span>
                  <span className="text-xs text-slate-400 transition-colors group-hover:text-indigo-500">/mail</span>
                </Link>
                <Link
                  to="/lost-found"
                  onClick={() => setSubmenuOpen(false)}
                  className="group flex items-center gap-3 rounded-xl px-3 py-3 text-sm text-slate-700 transition-colors hover:bg-indigo-50 dark:text-slate-200 dark:hover:bg-indigo-500/10 [&>span:nth-child(2)]:hidden"
                >
                  <span className="grid h-9 w-9 shrink-0 place-items-center rounded-xl bg-amber-50 text-amber-600 group-hover:bg-amber-100 dark:bg-amber-500/10 dark:text-amber-300"><SearchOutlinedIcon sx={{ fontSize: 20 }} /></span>
                  <span className="text-base leading-none">🧭</span>
                  <span className="flex-1 font-medium">{t('lostFound')}</span>
                  <span className="text-xs text-slate-400 transition-colors group-hover:text-indigo-500">/lost-found</span>
                </Link>
                <Link
                  to="/facilities"
                  onClick={() => setSubmenuOpen(false)}
                  className="group flex items-center gap-3 rounded-xl px-3 py-3 text-sm text-slate-700 transition-colors hover:bg-indigo-50 dark:text-slate-200 dark:hover:bg-indigo-500/10 [&>span:nth-child(2)]:hidden"
                >
                  <span className="grid h-9 w-9 shrink-0 place-items-center rounded-xl bg-sky-50 text-sky-600 group-hover:bg-sky-100 dark:bg-sky-500/10 dark:text-sky-300"><MeetingRoomOutlinedIcon sx={{ fontSize: 20 }} /></span>
                  <span className="text-base leading-none">🏢</span>
                  <span className="flex-1 font-medium">{t('facilities')}</span>
                  <span className="text-xs text-slate-400 transition-colors group-hover:text-indigo-500">/facilities</span>
                </Link>
                {user?.role && ['ADMIN', 'SUPER_ADMIN'].includes(user.role) && (
                  <Link
                    to="/admin/dashboard"
                    onClick={() => setSubmenuOpen(false)}
                     className="group flex items-center gap-3 rounded-xl px-3 py-3 text-sm text-slate-700 transition-colors hover:bg-indigo-50 dark:text-slate-200 dark:hover:bg-indigo-500/10 [&>span:nth-child(2)]:hidden"
                   >
                     <span className="grid h-9 w-9 shrink-0 place-items-center rounded-xl bg-rose-50 text-rose-600 group-hover:bg-rose-100 dark:bg-rose-500/10 dark:text-rose-300"><AdminPanelSettingsOutlinedIcon sx={{ fontSize: 20 }} /></span>
                    <span className="text-base leading-none">🛠</span>
                   <span className="flex-1 font-medium">{t('admin')}</span>
                   <span className="text-xs text-slate-400 transition-colors group-hover:text-indigo-500">/admin</span>
                  </Link>
                )}
              </div>
            )}
          </div>

          {/* 深色模式切换 */}
          <button
            type="button"
            onClick={() => { manualThemeRef.current = true; setDark((d) => !d); }}
            title={dark ? '切换到浅色模式' : '切换到深色模式'}
             className="flex h-9 w-9 items-center justify-center rounded-xl border border-indigo-100 bg-indigo-50/50 text-indigo-700 transition-all hover:-translate-y-0.5 hover:border-indigo-300 hover:bg-indigo-50 hover:shadow-sm dark:border-indigo-500/30 dark:bg-indigo-500/10 dark:text-indigo-300 dark:hover:bg-indigo-500/20"
          >
            <DarkModeOutlinedIcon sx={{ fontSize: 18 }} />
          </button>
          <button
            type="button"
            onClick={handleLogout}
             className="hidden flex items-center gap-1.5 rounded-lg border border-slate-200 bg-white px-3 py-1.5 text-sm text-slate-600 transition-colors hover:border-indigo-300 hover:bg-indigo-50 hover:text-indigo-700 dark:border-slate-600 dark:bg-slate-700 dark:text-slate-300 dark:hover:bg-indigo-500/10"
          >
            退出
          </button>
        </div>
      </header>

      {/* ── 消息区 ── */}
      <main className="flex-1 overflow-y-auto px-4 py-6">
        <div className="mx-auto flex min-h-full w-full max-w-3xl flex-col gap-5">
          {messages.length === 0 && (
            <div className="flex flex-1 flex-col items-center justify-center gap-3 py-24 text-center">
              <div className="relative flex h-24 w-24 items-center justify-center">
                <span className="absolute inset-2 animate-ping rounded-[1.6rem] bg-blue-400/10 [animation-duration:2.8s]" />
                <span className="absolute inset-3 animate-pulse rounded-[1.4rem] bg-indigo-400/10" />
                <span className="relative flex h-20 w-20 animate-bounce items-center justify-center rounded-3xl bg-gradient-to-br from-blue-600 via-indigo-600 to-indigo-700 text-white shadow-lg shadow-blue-500/25 [animation-duration:3s]">
                  <ForumOutlinedIcon sx={{ fontSize: 42 }} />
                </span>
              </div>
              <p className="text-lg font-medium text-slate-700 dark:text-slate-200">
                {t('welcomeTitle')}
              </p>
              <p className="text-sm text-slate-400 dark:text-slate-500">
                {t('welcomeSub')}
              </p>
              <div className="mt-4 flex flex-wrap justify-center gap-2">
                {SUGGESTIONS[lang].map((hint) => (
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

              {msg.role === 'assistant' && msg.matches.length > 0 && (
                <div className="grid w-full gap-3 sm:grid-cols-2">
                  {msg.matches.map((match) => (
                    <article
                      key={`${msg.id}-match-${match.item_id}`}
                      className="overflow-hidden rounded-xl border border-indigo-100 bg-white shadow-sm dark:border-slate-700 dark:bg-slate-800"
                    >
                      {match.image_urls[0] && (
                        <img
                          src={match.image_urls[0]}
                          alt={match.item_name}
                          className="h-36 w-full object-cover"
                        />
                      )}
                      <div className="space-y-2 p-3">
                        <div className="flex items-start justify-between gap-2">
                          <Link
                            to={`/lost-found/${match.item_id}`}
                            className="font-semibold text-indigo-600 hover:underline dark:text-indigo-300"
                          >
                            #{match.item_id} [{match.report_type}] {match.item_name}
                          </Link>
                          <span className="shrink-0 rounded-full bg-indigo-50 px-2 py-0.5 text-xs font-semibold text-indigo-700 dark:bg-indigo-500/10 dark:text-indigo-300">
                            {Math.round(match.match_score * 100)}%
                          </span>
                        </div>
                        <p className="text-xs text-slate-500 dark:text-slate-400">
                          {match.category}{match.colour ? ` · ${match.colour}` : ''} · {match.location} · {match.event_date}
                        </p>
                        <p className="text-sm text-slate-700 dark:text-slate-200">{match.description}</p>
                        <div className="flex flex-wrap gap-1">
                          {match.match_reason.map((reason) => (
                            <span
                              key={`${match.item_id}-${reason}`}
                              className="rounded-full bg-emerald-50 px-2 py-0.5 text-[11px] text-emerald-700 dark:bg-emerald-500/10 dark:text-emerald-300"
                            >
                              {reason}
                            </span>
                          ))}
                        </div>
                        {match.matching_mode === 'baseline' && (
                          <p className="text-xs text-amber-700 dark:text-amber-300">
                            智能模型暂不可用，当前使用基础匹配。
                          </p>
                        )}
                        {match.score_breakdown && (
                          <div className="flex flex-wrap gap-1">
                            {Object.entries(match.score_breakdown).map(([name, value]) => (
                              <span
                                key={`${match.item_id}-score-${name}`}
                                className="rounded-full bg-slate-100 px-2 py-0.5 text-[11px] text-slate-600 dark:bg-slate-700 dark:text-slate-300"
                              >
                                {name} {Math.round(value * 100)}%
                              </span>
                            ))}
                          </div>
                        )}
                      </div>
                    </article>
                  ))}
                </div>
              )}

              {/* 确认操作栏 */}
              {pendingConfirm?.msgId === msg.id && (
                <div className="flex items-center gap-2">
                  {(() => {
                    const d = pendingConfirm.details as Record<string, unknown> | null;
                    const detailText =
                      (d && typeof d.message === 'string' && d.message) ||
                      (d && typeof d.summary === 'string' && d.summary) ||
                      pendingConfirm.message ||
                      '';
                    return (
                      <span className="text-xs text-slate-500 dark:text-slate-400">
                        {pendingConfirm.agent} {t('needConfirm')} {detailText}
                      </span>
                    );
                  })()}
                  <button
                    type="button"
                    onClick={() => resolveConfirmation(true)}
                    className="rounded-lg bg-indigo-600 px-3 py-1 text-xs font-medium text-white transition-colors hover:bg-indigo-700"
                  >
                    {t('confirm')}
                  </button>
                  <button
                    type="button"
                    onClick={() => resolveConfirmation(false)}
                    className="rounded-lg border border-slate-300 px-3 py-1 text-xs text-slate-600 transition-colors hover:bg-slate-100 dark:border-slate-600 dark:text-slate-300 dark:hover:bg-slate-700"
                  >
                    {t('cancel')}
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
              <span className="text-xs text-slate-400 dark:text-slate-500">{t('thinking')}</span>
            </div>
          )}

          <div ref={messagesEndRef} />
        </div>
      </main>

      {/* ── 输入区 ── */}
      <footer className="shrink-0 border-t border-slate-200 bg-white px-4 py-3 dark:border-slate-700 dark:bg-slate-800">
        {/* 语音输入实时反馈：中间结果 + 错误提示 */}
        {(speech.listening && speech.interim) || speech.error ? (
          <div className="mx-auto mb-1.5 w-full max-w-3xl">
            {speech.listening && speech.interim && (
              <p
                aria-live="polite"
                className="truncate text-xs text-indigo-500 dark:text-indigo-400"
              >
                🎤 {speech.interim}
              </p>
            )}
            {speech.error && (
              <p role="alert" className="text-xs text-red-500">
                {t('micError')}：{micErrorText(speech.error)}
              </p>
            )}
          </div>
        ) : null}
        <div className="mx-auto flex w-full max-w-3xl items-end gap-2">
          {speech.supported && (
            <div className="flex shrink-0 flex-col items-center gap-1">
              <button
                type="button"
                onClick={toggleMic}
                title={speech.listening ? t('micStop') : t('micStart')}
                aria-label={speech.listening ? t('micStop') : t('micStart')}
                className={`flex h-[44px] w-[44px] shrink-0 items-center justify-center rounded-2xl transition-colors ${
                  speech.listening
                    ? 'animate-pulse bg-red-500 text-white'
                    : 'bg-slate-100 text-slate-600 hover:bg-slate-200 dark:bg-slate-700 dark:text-slate-300 dark:hover:bg-slate-600'
                }`}
              >
                <svg
                  className="h-5 w-5"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth="1.8"
                  viewBox="0 0 24 24"
                >
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    d="M12 15a3 3 0 0 0 3-3V7a3 3 0 1 0-6 0v5a3 3 0 0 0 3 3z"
                  />
                  <path
                    strokeLinecap="round"
                    d="M19 11a7 7 0 0 1-14 0M12 18v3"
                  />
                </svg>
              </button>
              {/* 实时输入音量条（getUserMedia 不可用时恒 0，不显示激活格） */}
              {speech.listening && (
                <div className="flex h-4 items-end gap-[3px]" aria-hidden>
                  {[0, 1, 2, 3, 4].map((bar) => (
                    <span
                      key={bar}
                      className={`w-[3px] rounded-full transition-colors ${
                        speech.volume * 5 > bar
                          ? 'bg-red-500'
                          : 'bg-slate-200 dark:bg-slate-600'
                      }`}
                      style={{ height: `${8 + bar * 2}px` }}
                    />
                  ))}
                </div>
              )}
            </div>
          )}
          <textarea
            ref={taRef}
            rows={1}
            value={input}
            onChange={(e) => {
              setInput(e.target.value);
              autoResize();
            }}
            onKeyDown={handleKeyDown}
            placeholder={compact ? 'Type a message' : t('placeholder')}
            className="max-h-40 min-h-[44px] min-w-0 flex-1 resize-none rounded-2xl border border-slate-300 bg-slate-50 px-4 py-2.5 text-sm outline-none transition-all placeholder:text-slate-400 focus:border-indigo-500 focus:bg-white focus:ring-4 focus:ring-indigo-500/10 disabled:opacity-60 dark:border-slate-600 dark:bg-slate-900 dark:text-slate-100 dark:placeholder:text-slate-500 dark:focus:border-indigo-500 dark:focus:bg-slate-900"
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
              {t('stop')}
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
              {t('send')}
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
  const isTableSep = (line: string) => /^[\s|:-]+$/.test(line) && line.includes('-');

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
