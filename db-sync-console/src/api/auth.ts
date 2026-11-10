import { useSyncExternalStore } from 'react';
import type { AuthSession } from '../types';

/**
 * 会话存储。
 * token 是后端内存态（重启失效），故前端只放 sessionStorage，关闭标签即失效。
 * 权限判断一律使用后端返回的 permissions 数组，不按角色自行推导。
 */
const KEY = 'dbsync.session';

const EMPTY_DEMO: AuthSession = {
  token: 'demo-token',
  username: 'admin',
  displayName: 'Administrator',
  role: 'ADMIN',
  permissions: [
    'TASK_VIEW',
    'TASK_EDIT',
    'TASK_EXEC',
    'DS_MANAGE',
    'COMPARE_RUN',
    'COMPARE_FIX_SOURCE',
    'CUTOVER_VIEW',
    'CUTOVER_ADVANCE',
    'CUTOVER_ROLLBACK',
    'SKIP_GATE',
    'DEAD_LETTER_MANAGE',
    'USER_MANAGE',
  ],
  expiresAt: '2026-11-10T01:44:00Z',
  mustChangePassword: false,
};

function readStored(): AuthSession | null {
  try {
    const raw = window.sessionStorage.getItem(KEY);
    if (!raw) return null;
    return JSON.parse(raw) as AuthSession;
  } catch {
    return null;
  }
}

let current: AuthSession | null = readStored();

// 纯前端演示模式（VITE_USE_MOCK=true）自动以演示管理员身份进入，便于本地预览
if (!current && String(import.meta.env.VITE_USE_MOCK ?? 'false') === 'true') {
  current = EMPTY_DEMO;
}

const listeners = new Set<() => void>();

function emit(): void {
  for (const l of Array.from(listeners)) l();
}

export const authStore = {
  subscribe(listener: () => void): () => void {
    listeners.add(listener);
    return () => {
      listeners.delete(listener);
    };
  },
  get(): AuthSession | null {
    return current;
  },
  set(session: AuthSession): void {
    current = session;
    try {
      window.sessionStorage.setItem(KEY, JSON.stringify(session));
    } catch {
      /* 隐私模式下写入失败不影响内存态 */
    }
    emit();
  },
  clear(): void {
    current = null;
    try {
      window.sessionStorage.removeItem(KEY);
    } catch {
      /* ignore */
    }
    emit();
  },
};

export function useSession(): AuthSession | null {
  return useSyncExternalStore(authStore.subscribe, authStore.get, authStore.get);
}

/** 12 个权限点的中文名与风险等级（用于按钮提示与权限矩阵） */
export const PERM_META: Record<string, { label: string; desc: string; danger?: boolean }> = {
  TASK_VIEW: { label: '查看任务', desc: '查看任务列表、详情、迁移进度' },
  TASK_EDIT: { label: '编辑任务', desc: '新建/修改任务与字段映射' },
  TASK_EXEC: { label: '执行任务', desc: '启动、暂停、恢复、取消、预检、核数' },
  DS_MANAGE: { label: '数据源管理', desc: '数据源增删改与连接测试' },
  COMPARE_RUN: { label: '发起比对', desc: '发起比对、重放到目标、取消比对' },
  COMPARE_FIX_SOURCE: { label: '反向修复源端', desc: '把目标端数据写回生产源库', danger: true },
  CUTOVER_VIEW: { label: '查看切流', desc: '查看切流计划、卡点与时间线' },
  CUTOVER_ADVANCE: { label: '推进切流', desc: '推进状态机、灰度放量', danger: true },
  CUTOVER_ROLLBACK: { label: '切流回滚', desc: '一键回滚读流量到源库', danger: true },
  SKIP_GATE: { label: '跳过卡点', desc: '强行绕过 Gate 校验', danger: true },
  DEAD_LETTER_MANAGE: { label: '死信处置', desc: '重放或丢弃死信分片' },
  USER_MANAGE: { label: '用户管理', desc: '用户启停、重置口令' },
};

export function permissionLabel(perm: string): string {
  return PERM_META[perm]?.label ?? perm;
}

/** 按钮无权限时的统一提示文案 */
export function needPermText(perm: string): string {
  return `需要权限：${perm}（${permissionLabel(perm)}）`;
}

/** 权限判断：直接用后端下发的 permissions 数组，不按角色推导 */
export function useCan(): (perm?: string | null) => boolean {
  const session = useSyncExternalStore(authStore.subscribe, authStore.get, authStore.get);
  return (perm?: string | null) => (perm ? Boolean(session?.permissions.includes(perm)) : true);
}
