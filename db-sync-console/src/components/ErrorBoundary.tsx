import { Component, type ErrorInfo, type ReactNode } from 'react';

interface Props {
  children: ReactNode;
}

interface State {
  error: Error | null;
  info: string;
}

/** 页面级错误边界：任何渲染异常都在页面内展示，绝不出现白屏 */
export class ErrorBoundary extends Component<Props, State> {
  state: State = { error: null, info: '' };

  static getDerivedStateFromError(error: Error): Partial<State> {
    return { error };
  }

  componentDidCatch(error: Error, info: ErrorInfo): void {
    this.setState({ info: info.componentStack ?? '' });
    // 真实环境这里上报到前端监控；控制台只做静态部署，故仅落 console
    console.error('[dbsync-console] render error', error, info);
  }

  render(): ReactNode {
    const { error } = this.state;
    if (!error) return this.props.children;
    return (
      <div className="card" style={{ borderColor: 'var(--danger)' }}>
        <div className="card-head">
          <div className="card-title" style={{ color: 'var(--danger)' }}>
            ⚠ 页面渲染失败
          </div>
          <button className="btn btn-sm" onClick={() => window.location.reload()}>
            重新加载
          </button>
        </div>
        <div className="card-body">
          <div className="alert danger">
            <div>
              <div style={{ fontWeight: 700 }}>{error.message || '未知渲染异常'}</div>
              <div className="small mt8">该异常已被前端错误边界拦截，其他页面不受影响。</div>
            </div>
          </div>
          <pre className="code-block mt12" style={{ maxHeight: 260, color: 'var(--muted)' }}>
            {error.stack ?? ''}
            {'\n'}
            {this.state.info}
          </pre>
        </div>
      </div>
    );
  }
}
