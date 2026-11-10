/** 数字/容量/时间格式化，统一中文习惯（万 / 亿 / TB） */

export function fmtInt(n: number): string {
  return n.toLocaleString('en-US');
}

/** 8.2亿 / 4.12亿 / 3200万 / 1.2 TB */
export function fmtCount(n: number): string {
  const abs = Math.abs(n);
  if (abs >= 1e8) {
    const v = n / 1e8;
    return `${trim(v)}亿`;
  }
  if (abs >= 1e4) {
    const v = n / 1e4;
    return `${trim(v)}万`;
  }
  return fmtInt(n);
}

function trim(v: number): string {
  if (Math.abs(v) >= 100) return v.toFixed(0);
  if (Math.abs(v) >= 10) return v.toFixed(1);
  return v.toFixed(2).replace(/\.?0+$/, '');
}

export function fmtBytes(bytes: number): string {
  const units = ['B', 'KB', 'MB', 'GB', 'TB', 'PB'];
  let v = bytes;
  let i = 0;
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024;
    i++;
  }
  return `${v.toFixed(v >= 100 ? 0 : 1)} ${units[i]}`;
}

export function fmtPct(v: number, digits = 1): string {
  return `${v.toFixed(digits)}%`;
}

export function fmtNum(v: number): string {
  return v.toLocaleString('en-US');
}

export function cls(...names: Array<string | false | null | undefined>): string {
  return names.filter(Boolean).join(' ');
}

export const TASK_STATUS_TEXT: Record<string, string> = {
  DRAFT: '草稿',
  READY: '待启动',
  RUNNING: '运行中',
  PAUSED: '已暂停',
  FAILED: '失败',
  FINISHED: '已完成',
  CANCELLED: '已取消',
  ARCHIVED: '已归档',
};

export type Tone = 'ok' | 'warn' | 'danger' | 'neutral' | 'accent' | 'info';

export function taskStatusTone(status: string): Tone {
  switch (status) {
    case 'RUNNING':
      return 'ok';
    case 'PAUSED':
      return 'warn';
    case 'FAILED':
      return 'danger';
    case 'FINISHED':
      return 'accent';
    case 'DRAFT':
    case 'READY':
      return 'neutral';
    default:
      return 'neutral';
  }
}

/** 与后端 CutoverState.java 的 9 个状态一一对应，保证任意状态中文不为空 */
export const CUTOVER_STATE_TEXT: Record<string, string> = {
  DRAFT: '草稿',
  PRE_CHECK: '预检',
  PRE_CHECK_FAILED: '预检不通过',
  GRAY_READ: '灰度读',
  GRAY_WRITE: '灰度写',
  FULL_TARGET: '全量目标',
  OBSERVE: '观察期',
  DONE: '已完成',
  ROLLED_BACK: '已回滚',
};

export const CDC_MODE_TEXT: Record<string, string> = {
  SDK_DUAL_WRITE: 'SDK 双写',
  TRIGGER_LOG: '触发器日志表',
  TIMESTAMP: '时间戳 + 版本号',
};

export const DIFF_TYPE_TEXT: Record<string, string> = {
  VALUE_DIFF: '值不同',
  ONLY_SOURCE: '仅源有',
  ONLY_TARGET: '仅目标有',
};

export const OP_TEXT: Record<string, string> = {
  INSERT: '插入',
  UPDATE: '更新',
  DELETE: '删除',
};

export function shortTime(ts: string): string {
  const m = /(\d{2}:\d{2}:\d{2})/.exec(ts);
  return m?.[1] ?? ts;
}

export function nowText(): string {
  const d = new Date();
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
}
