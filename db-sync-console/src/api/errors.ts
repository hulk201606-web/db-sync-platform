import { ApiError } from './client';

/**
 * 统一错误文案：message · HTTP 状态 · traceId。
 * traceId 来源优先级 body.data.traceId → 响应头 X-Trace-Id → 本地生成（带「本地」前缀）。
 */
export function errorText(err: unknown): string {
  if (err instanceof ApiError) {
    const parts: string[] = [err.message];
    if (err.status > 0) parts.push(`HTTP ${err.status}`);
    parts.push(`traceId ${err.traceLocal ? '本地 ' : ''}${err.traceId}`);
    return parts.join(' · ');
  }
  return err instanceof Error ? err.message : '操作失败';
}
