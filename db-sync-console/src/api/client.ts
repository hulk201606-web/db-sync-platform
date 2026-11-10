import { authStore } from './auth';
import { demoStore } from './demoState';
import { MockError } from './mock/error';
import { mockResolve } from './mock';

/**
 * 统一请求层。
 * 1. VITE_USE_MOCK=true → 直接走 src/api/mock 的仿真数据（不发任何网络请求）
 * 2. 真实模式默认一律不降级；仅当 MODE==='demo' 或 VITE_ALLOW_MOCK_FALLBACK==='true' 时，
 *    非写操作遇到 5xx / 网络错误 / 超时 / 非 JSON 才允许降级 mock，并点亮「演示数据」标记条。
 *    404 / 405 一律按路径/方法笔误暴露；写操作（POST/PUT/DELETE/PATCH）任何情况下都不降级。
 *    res.ok 且响应体为空（204 / DELETE 无返回体）视为成功，payload 交给调用方，不降级。
 * 3. 业务错误：
 *    - HTTP 401 或 code=E8004/TOKEN_INVALID → 清 token，广播 unauthorized，跳登录页
 *    - HTTP 403 或 code=E8001 → 抛 ApiError（带 required 权限点），只提示不跳页
 *    - 其它 4xx → 抛中文提示；未映射的错误码回退展示后端返回的 message，不自造文案
 */

const API_BASE: string = (import.meta.env.VITE_API_BASE as string | undefined) ?? '';
export const USE_MOCK: boolean = String(import.meta.env.VITE_USE_MOCK ?? 'false') === 'true';
const DEFAULT_TIMEOUT_MS = 12000;
const TOKEN_HEADER = 'X-DBSync-Token';

export type HttpMethod = 'GET' | 'POST' | 'PUT' | 'DELETE';

export const UNAUTHORIZED_EVENT = 'dbsync:unauthorized';

/**
 * 后端错误码 → 默认中文（唯一真源：db-sync-common ErrorCode.java）。
 * 去掉模板里的「：%s」占位符，仅作为短标签；未映射的错误码由调用方回退展示后端返回的 message。
 */
const ERROR_TEXT: Record<string, string> = {
  // E1xxx 通用 / 参数 / 工具
  E1001: '参数非法',
  E1002: '标识符非法',
  E1003: 'JSON 处理失败',
  E1010: '加解密失败',
  E1020: '重试耗尽',
  E1099: '内部错误',
  // E2xxx 数据源 / 连接 / 池
  E2001: '连接失败（认证）',
  E2002: '连接失败（网络）',
  E2003: '数据源被引用',
  E2004: '数据源不存在',
  E2005: '数据源配置非法',
  E2010: '连接池超限',
  E2020: '探活失败',
  // E3xxx 任务 / 迁移 / 分片
  E3001: '非法任务状态',
  E3002: '预检失败',
  E3003: '分片区间非法',
  E3004: '任务不存在',
  E3005: '乐观锁冲突',
  E3010: '分片重试耗尽',
  // E4xxx 增量 / CDC
  E4001: '分组计算失败',
  E4002: '水位线回退',
  E4003: '触发器部署失败',
  E4010: '变更应用失败',
  // E5xxx 比对 / 修复
  E5001: '比对分块失败',
  E5002: '归一化规则冲突',
  E5003: '修复证据不足',
  E5010: '报告生成失败',
  // E6xxx 切流 / Gate
  E6001: '非法状态跃迁',
  E6002: 'Gate 阻断',
  E6003: '路由下发未确认',
  E6004: '状态不一致需人工介入',
  E6010: '灰度规则非法',
  // E7xxx 驱动 / 方言 / 环境
  E7001: '驱动缺失',
  E7002: '方言不支持该能力',
  E7003: '标识符超长',
  E7010: '版本/协议不支持',
  // E8xxx 权限 / 审计 / 安全
  E8001: '无权限',
  E8002: '需要二次确认',
  E8003: '审计写入失败',
  E8004: '令牌无效或已过期',
  E8005: '告警发送失败',
  // E9xxx 预留（外部集成）
  E9001: '外部集成未实现',
};

export class ApiError extends Error {
  readonly code: string;
  readonly status: number;
  readonly traceId: string;
  /** 后端附带的上下文，例如切流非法跃迁的 allowed 列表 */
  readonly data: unknown;
  /** 403 时后端给出的必需权限点 */
  readonly required: string | null;
  /** true 表示 traceId 是前端本地生成的（后端未回传），展示时需加「本地」前缀避免误导 */
  readonly traceLocal: boolean;

  constructor(
    message: string,
    code: string,
    status: number,
    traceId: string,
    data?: unknown,
    required?: string | null,
    traceLocal = false,
  ) {
    super(message);
    this.name = 'ApiError';
    this.code = code;
    this.status = status;
    this.traceId = traceId;
    this.data = data;
    this.required = required ?? null;
    this.traceLocal = traceLocal;
  }
}

function newTraceId(): string {
  const c = window.crypto;
  if (c && typeof c.randomUUID === 'function') return c.randomUUID();
  return `TR-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
}

function readTrace(data: unknown): string | undefined {
  if (data && typeof data === 'object' && 'traceId' in data) {
    const v = (data as { traceId?: unknown }).traceId;
    if (typeof v === 'string' && v.length > 0) return v;
  }
  return undefined;
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

interface ApiEnvelope<T> {
  code?: string | number;
  message?: string;
  data?: T;
  traceId?: string;
}

export interface RequestOptions {
  method?: HttpMethod;
  body?: unknown;
  timeoutMs?: number;
  /** 登录接口等无需 token 的请求 */
  anonymous?: boolean;
}

function isSuccessCode(code: string | number | undefined): boolean {
  if (code === undefined) return true;
  return code === 'OK' || code === 0 || code === 200 || code === '0' || code === '200';
}

function isUnauthorized(status: number, code: string): boolean {
  // E8003 = 审计写入失败，不是登录过期，必须排除
  return status === 401 || code === 'E8004' || code === 'TOKEN_INVALID';
}

function isForbidden(status: number, code: string): boolean {
  return status === 403 || code === 'E8001';
}

/**
 * 是否允许把一次真实请求降级到 mock（集中判定）。
 * 生产/真实模式默认一律不允许；仅 demo 模式或显式开关放行，保留演示能力。
 */
function canFallback(): boolean {
  const env = import.meta.env as unknown as Record<string, unknown>;
  const isDemo = String(env.MODE ?? '') === 'demo';
  const explicitlyAllowed = String(env.VITE_ALLOW_MOCK_FALLBACK ?? 'false') === 'true';
  return isDemo || explicitlyAllowed;
}

function isWriteMethod(method: string): boolean {
  return method !== 'GET' && method !== 'HEAD';
}

/** 仅 demo/显式开关 + 非写操作，才允许降级 */
function canDegrade(method: string): boolean {
  return canFallback() && !isWriteMethod(method);
}

/**
 * 可降级的服务端状态：仅 5xx / 0（网络层不可达）。
 * 404 与 405 必须排除：它们表示「路径/方法写错了」，静默降级成演示数据会把笔误掩盖掉。
 */
function shouldDegrade(status: number): boolean {
  return status >= 500 || status === 0;
}

function handleUnauthorized(): void {
  authStore.clear();
  window.dispatchEvent(new CustomEvent(UNAUTHORIZED_EVENT));
}

async function mockCall<T>(path: string, method: HttpMethod | string, body?: unknown): Promise<T> {
  try {
    return await mockResolve<T>(path, method, body);
  } catch (err) {
    if (err instanceof MockError) {
      if (isUnauthorized(err.status, err.code)) handleUnauthorized();
      throw new ApiError(err.message, err.code, err.status, 'MOCK-TRACE', err.data, null);
    }
    throw err;
  }
}

async function fallback<T>(path: string, opts: RequestOptions, reason: string): Promise<T> {
  if (!USE_MOCK) demoStore.enter(reason);
  await sleep(120);
  return mockCall<T>(path, opts.method ?? 'GET', opts.body);
}

export async function request<T>(path: string, opts: RequestOptions = {}): Promise<T> {
  const method = opts.method ?? 'GET';

  if (USE_MOCK) {
    await sleep(140 + Math.random() * 200);
    return mockCall<T>(path, method, opts.body);
  }

  const traceId = newTraceId();
  const controller = new AbortController();
  const timer = window.setTimeout(() => controller.abort(), opts.timeoutMs ?? DEFAULT_TIMEOUT_MS);
  const session = authStore.get();

  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    Accept: 'application/json',
    'X-Trace-Id': traceId,
  };
  if (!opts.anonymous && session?.token) headers[TOKEN_HEADER] = session.token;

  try {
    const res = await fetch(`${API_BASE}${path}`, {
      method,
      headers,
      body: opts.body === undefined ? undefined : JSON.stringify(opts.body),
      signal: controller.signal,
      credentials: 'same-origin',
    });

    if (shouldDegrade(res.status) && canDegrade(method)) {
      return await fallback<T>(path, opts, `接口 ${path} 不可用（HTTP ${res.status}），已切换演示数据`);
    }

    const text = await res.text();
    const emptyBody = text.trim().length === 0;
    let payload: ApiEnvelope<T> | null = null;
    if (!emptyBody) {
      try {
        payload = JSON.parse(text) as ApiEnvelope<T>;
      } catch {
        payload = null;
      }
    }

    const code = payload?.code !== undefined ? String(payload.code) : `HTTP_${res.status}`;
    const contextData = payload?.data as unknown;

    // traceId 优先级：body.data.traceId → 响应头 X-Trace-Id → 前端本地生成（标「本地」）
    const bodyTrace = readTrace(contextData) ?? payload?.traceId ?? undefined;
    const headerTrace = res.headers.get('X-Trace-Id') ?? undefined;
    const resolvedTrace = bodyTrace ?? headerTrace ?? traceId;
    const traceLocal = bodyTrace === undefined && headerTrace === undefined;

    // 404 / 405 绝不降级：路径或方法笔误必须让运维看见，而不是静默展示演示数据
    if (res.status === 404 || res.status === 405) {
      const is405 = res.status === 405;
      throw new ApiError(
        is405
          ? `接口方法或路径不匹配：${method} ${path}${payload?.message ? `（${payload.message}）` : ''}`
          : `接口路径不存在：${path}${payload?.message ? `（${payload.message}）` : ''}`,
        code === `HTTP_${res.status}` ? (is405 ? 'E405' : 'E404') : code,
        res.status,
        resolvedTrace,
        contextData,
        null,
        traceLocal,
      );
    }

    if (isUnauthorized(res.status, code)) {
      handleUnauthorized();
      throw new ApiError(
        ERROR_TEXT[code] ?? payload?.message ?? '登录已过期，请重新登录',
        code,
        res.status,
        resolvedTrace,
        contextData,
        null,
        traceLocal,
      );
    }

    if (isForbidden(res.status, code)) {
      const required =
        contextData && typeof contextData === 'object' && 'required' in contextData
          ? String((contextData as { required?: unknown }).required ?? '')
          : null;
      throw new ApiError(
        required ? `权限不足：需要 ${required}` : '权限不足：该操作需要更高权限',
        code === `HTTP_${res.status}` ? 'E8001' : code,
        res.status,
        resolvedTrace,
        contextData,
        required,
        traceLocal,
      );
    }

    if (!res.ok) {
      throw new ApiError(
        ERROR_TEXT[code] ?? payload?.message ?? `请求失败（HTTP ${res.status}）`,
        code,
        res.status,
        resolvedTrace,
        contextData,
        null,
        traceLocal,
      );
    }

    // res.ok 且响应体为空（204 / DELETE 无返回体）：视为成功，payload 用 undefined 交给调用方
    if (emptyBody) {
      return payload?.data as T;
    }

    if (payload === null) {
      // res.ok 但 body 非空且非 JSON：演示模式可降级，生产模式直接抛错，绝不生成假数据
      if (canDegrade(method)) {
        return await fallback<T>(path, opts, `接口 ${path} 返回非 JSON 响应，已切换演示数据`);
      }
      throw new ApiError(
        `接口 ${path} 返回非 JSON 响应（HTTP ${res.status}）`,
        `HTTP_${res.status}`,
        res.status,
        resolvedTrace,
        null,
        null,
        traceLocal,
      );
    }

    if (!isSuccessCode(payload.code)) {
      throw new ApiError(
        ERROR_TEXT[code] ?? payload.message ?? `业务处理失败（${code}）`,
        code,
        res.status,
        resolvedTrace,
        contextData,
        null,
        traceLocal,
      );
    }

    return payload.data as T;
  } catch (err) {
    if (err instanceof ApiError) throw err;
    const reason =
      err instanceof DOMException && err.name === 'AbortError'
        ? `请求 ${path} 超时`
        : `后端连接失败（${path}）`;
    // 写操作永不降级；生产模式网络/超时错误直接抛 ApiError，不生成演示数据
    if (canDegrade(method)) {
      return await fallback<T>(path, opts, `${reason}，已切换演示数据`);
    }
    throw new ApiError(reason, 'NETWORK_ERROR', 0, traceId, null, null, true);
  } finally {
    window.clearTimeout(timer);
  }
}

export const http = {
  get: <T>(path: string, timeoutMs?: number) => request<T>(path, { method: 'GET', timeoutMs }),
  post: <T>(path: string, body?: unknown) => request<T>(path, { method: 'POST', body }),
  put: <T>(path: string, body?: unknown) => request<T>(path, { method: 'PUT', body }),
  del: <T>(path: string, body?: unknown) => request<T>(path, { method: 'DELETE', body }),
  anonymous: <T>(path: string, body?: unknown) =>
    request<T>(path, { method: 'POST', body, anonymous: true }),
};

/** 导出类接口（text/csv）：真实模式直接落盘，任何失败都直接报错，绝不生成假 CSV */
export async function downloadFile(path: string, filename: string): Promise<void> {
  let text: string;
  if (USE_MOCK) {
    text = await mockCall<string>(path, 'GET');
  } else {
    const traceId = newTraceId();
    const controller = new AbortController();
    const timer = window.setTimeout(() => controller.abort(), DEFAULT_TIMEOUT_MS);
    const session = authStore.get();
    try {
      const res = await fetch(`${API_BASE}${path}`, {
        headers: {
          Accept: 'text/csv',
          'X-Trace-Id': traceId,
          ...(session?.token ? { [TOKEN_HEADER]: session.token } : {}),
        },
        signal: controller.signal,
        credentials: 'same-origin',
      });
      const body = await res.text();

      if (isUnauthorized(res.status, '')) {
        handleUnauthorized();
        throw new ApiError('登录已过期，请重新登录', 'E8004', res.status, traceId, null, null, true);
      }
      if (isForbidden(res.status, '')) {
        let required: string | null = null;
        try {
          const parsed = JSON.parse(body) as { data?: { required?: unknown } };
          if (parsed.data && typeof parsed.data === 'object' && 'required' in parsed.data) {
            required = String((parsed.data as { required?: unknown }).required ?? '') || null;
          }
        } catch {
          // 响应体非 JSON，忽略权限点解析
        }
        throw new ApiError(
          required ? `权限不足：导出需要 ${required}` : '权限不足：导出需要更高权限',
          'E8001',
          res.status,
          traceId,
          null,
          required,
          true,
        );
      }
      if (!res.ok) {
        throw new ApiError(`导出失败（HTTP ${res.status}）：${path}`, `HTTP_${res.status}`, res.status, traceId, null, null, true);
      }
      text = body;
    } catch (err) {
      if (err instanceof ApiError) throw err;
      const reason =
        err instanceof DOMException && err.name === 'AbortError'
          ? `导出请求超时（${path}）`
          : `导出连接失败（${path}）`;
      throw new ApiError(reason, 'NETWORK_ERROR', 0, traceId, null, null, true);
    } finally {
      window.clearTimeout(timer);
    }
  }
  const blob = new Blob([`\uFEFF${text}`], { type: 'text/csv;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  window.setTimeout(() => URL.revokeObjectURL(url), 2000);
}

/** 导出纯文本文件（SQL 等） */
export function downloadText(content: string, filename: string, mime = 'text/plain;charset=utf-8'): void {
  const blob = new Blob([`\uFEFF${content}`], { type: mime });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  window.setTimeout(() => URL.revokeObjectURL(url), 2000);
}
