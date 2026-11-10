import { useSyncExternalStore } from 'react';

/**
 * 全局「演示数据」状态。
 * 真实模式下请求失败（网络错误 / 404 / 5xx）时进入降级，由 Layout 顶部展示标记条。
 */
interface DemoState {
  active: boolean;
  reason: string;
}

let state: DemoState = { active: false, reason: '' };
const listeners = new Set<() => void>();

function emit(): void {
  for (const l of Array.from(listeners)) l();
}

export const demoStore = {
  subscribe(listener: () => void): () => void {
    listeners.add(listener);
    return () => {
      listeners.delete(listener);
    };
  },
  get(): DemoState {
    return state;
  },
  enter(reason: string): void {
    if (state.active) return;
    state = { active: true, reason };
    emit();
  },
  reset(): void {
    if (!state.active) return;
    state = { active: false, reason: '' };
    emit();
  },
};

export function useDemoState(): DemoState {
  return useSyncExternalStore(demoStore.subscribe, demoStore.get, demoStore.get);
}
