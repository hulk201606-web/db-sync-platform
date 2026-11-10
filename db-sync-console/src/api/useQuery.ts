import { useCallback, useEffect, useRef, useState } from 'react';
import { errorText } from './errors';

export interface QueryState<T> {
  data: T | null;
  loading: boolean;
  error: string | null;
}

/**
 * 极简数据请求 hook。
 * 不使用 deps 数组的隐式展开，改为显式 key，避免 hook 依赖长度不一致的问题。
 */
export function useQuery<T>(key: string, factory: () => Promise<T>): QueryState<T> & { reload: () => void } {
  const [state, setState] = useState<QueryState<T>>({ data: null, loading: true, error: null });
  const [tick, setTick] = useState(0);
  const factoryRef = useRef(factory);
  factoryRef.current = factory;

  useEffect(() => {
    let alive = true;
    setState((prev) => ({ data: prev.data, loading: true, error: null }));
    factoryRef
      .current()
      .then((data) => {
        if (alive) setState({ data, loading: false, error: null });
      })
      .catch((err: unknown) => {
        if (!alive) return;
        setState({
          data: null,
          loading: false,
          error: errorText(err),
        });
      });
    return () => {
      alive = false;
    };
  }, [key, tick]);

  const reload = useCallback(() => setTick((t) => t + 1), []);
  return { ...state, reload };
}

/** 本地异步动作（提交类），带 pending 与错误 */
export function useAction(): {
  run: (fn: () => Promise<void>) => Promise<void>;
  pending: boolean;
  error: string | null;
} {
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const run = useCallback(async (fn: () => Promise<void>) => {
    setPending(true);
    setError(null);
    try {
      await fn();
    } catch (err: unknown) {
      setError(errorText(err));
    } finally {
      setPending(false);
    }
  }, []);

  return { run, pending, error };
}
