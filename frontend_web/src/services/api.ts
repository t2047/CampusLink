// ──────────────────────────────────────────────
//  API Service Layer
//  Centralises HTTP calls to the Chat Backend.
// ──────────────────────────────────────────────

const API_BASE = import.meta.env.VITE_API_BASE ?? 'http://localhost:8080';

// ── Types ────────────────────────────────────

export interface AuthResponse {
  token: string;
  email: string;
  role: string;
}

export interface LoginRequest {
  email: string;
  password: string;
}

export interface RegisterRequest {
  email: string;
  password: string;
  name?: string;
}

// ── Auth helpers ─────────────────────────────

function getToken(): string | null {
  return localStorage.getItem('jwt');
}

export function setToken(token: string): void {
  localStorage.setItem('jwt', token);
}

export function clearToken(): void {
  localStorage.removeItem('jwt');
}

export function isLoggedIn(): boolean {
  return getToken() !== null;
}

// ── Auth API ─────────────────────────────────

export async function login(req: LoginRequest): Promise<AuthResponse> {
  const res = await fetch(`${API_BASE}/api/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(req),
  });
  if (!res.ok) {
    const text = await res.text().catch(() => 'Login failed');
    throw new Error(text);
  }
  return res.json() as Promise<AuthResponse>;
}

export async function register(req: RegisterRequest): Promise<AuthResponse> {
  const res = await fetch(`${API_BASE}/api/auth/register`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(req),
  });
  if (!res.ok) {
    const text = await res.text().catch(() => 'Registration failed');
    throw new Error(text);
  }
  return res.json() as Promise<AuthResponse>;
}

// ── SSE Chat Stream ──────────────────────────

export type SseEventType =
  | 'intent_detected'
  | 'token'
  | 'agent_start'
  | 'agent_step'
  | 'agent_done'
  | 'agent_error'
  | 'utility_start'
  | 'utility_result'
  | 'utility_done'
  | 'confirm_required'
  | 'error'
  | 'done'
  | 'message';

export interface SseEvent {
  type: SseEventType;
  data: Record<string, unknown>;
}

/**
 * 打开到聊天端点的 SSE 流（fetch + ReadableStream，支持 Authorization 头）。
 *
 * 解析器健壮性：
 * - 统一 CRLF → LF，按空行切分事件块
 * - 支持多行 `data:` 合并（按 SSE 规范以换行连接）
 * - 忽略注释行（以 `:` 开头）
 * - 未知事件名回退为 `message`（前端兜底渲染，不再静默丢弃）
 */
export function createChatStream(
  message: string,
  onEvent: (evt: SseEvent) => void,
  onError: (err: Event) => void,
): { close: () => void } {
  const token = getToken();
  if (!token) throw new Error('Not authenticated');

  const url = `${API_BASE}/api/chat/stream?message=${encodeURIComponent(message)}`;

  const controller = new AbortController();
  let closed = false;

  void (async () => {
    try {
      const res = await fetch(url, {
        headers: {
          Authorization: `Bearer ${token}`,
          Accept: 'text/event-stream',
        },
        signal: controller.signal,
      });

      if (!res.ok) {
        onEvent({ type: 'error', data: { message: `HTTP ${res.status}` } });
        return;
      }

      const reader = res.body?.getReader();
      if (!reader) return;

      const decoder = new TextDecoder();
      let buffer = '';

      while (!closed) {
        const { done, value } = await reader.read();
        if (done) break;

        // 统一 CRLF → LF，简化按空行切分
        buffer += decoder.decode(value, { stream: true }).replace(/\r\n/g, '\n');

        let sep: number;
        while ((sep = buffer.indexOf('\n\n')) !== -1) {
          const block = buffer.slice(0, sep);
          buffer = buffer.slice(sep + 2);
          const event = parseSseBlock(block);
          if (event) onEvent(event);
        }
      }

      // 末尾残留块（流结束未带空行的情况）
      if (buffer.trim()) {
        const event = parseSseBlock(buffer);
        if (event) onEvent(event);
      }
    } catch (err) {
      if (!closed) onError(err as Event);
    }
  })();

  return {
    close: () => {
      closed = true;
      controller.abort();
    },
  };
}

/** 解析单个 SSE 块（可能含多个事件行），返回第一个有效事件。 */
function parseSseBlock(block: string): SseEvent | null {
  const lines = block.split('\n');
  let eventType: SseEventType = 'message';
  const dataLines: string[] = [];

  for (const line of lines) {
    if (!line || line.startsWith(':')) continue; // 注释行
    if (line.startsWith('event:')) {
      const t = line.slice(6).trim();
      if (isValidEventType(t)) eventType = t;
    } else if (line.startsWith('data:')) {
      // 按 SSE 规范，data 值可去掉单个前导空格
      dataLines.push(line.slice(5).replace(/^ /, ''));
    }
  }

  // done 事件允许无 data
  if (dataLines.length === 0 && eventType !== 'done') return null;

  const dataStr = dataLines.join('\n');
  let data: Record<string, unknown> = {};
  try {
    data = JSON.parse(dataStr) as Record<string, unknown>;
  } catch {
    data = { raw: dataStr };
  }

  return { type: eventType, data };
}

function isValidEventType(t: string): t is SseEventType {
  const valid: string[] = [
    'intent_detected',
    'token',
    'agent_start',
    'agent_step',
    'agent_done',
    'agent_error',
    'utility_start',
    'utility_result',
    'utility_done',
    'confirm_required',
    'error',
    'done',
    'message',
  ];
  return valid.includes(t);
}
