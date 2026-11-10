import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { useDemoState, demoStore } from '../api/demoState';
import { authStore, useSession } from '../api/auth';
import { EP } from '../api/endpoints';
import { http } from '../api/client';
import { ErrorBoundary } from './ErrorBoundary';

const NAV_ITEMS = [
  { to: '/monitor', label: '监控大盘', icon: '◫', desc: '聚合大盘 / TPS / 分片分布' },
  { to: '/datasources', label: '数据源', icon: '⛁', desc: '连接与探活' },
  { to: '/tasks', label: '同步任务', icon: '⇄', desc: '任务与向导' },
  { to: '/compare/CMP-20261109-0007', label: '比对', icon: '⊜', desc: '报告与差异' },
  { to: '/cutover/CUT-20261109-01', label: '切流', icon: '⇥', desc: '卡点与灰度' },
  { to: '/system', label: '系统', icon: '⚙', desc: '审计 / 用户 / 告警' },
];

const TITLE_MAP: Array<{ match: RegExp; crumbs: string[] }> = [
  { match: /^\/monitor/, crumbs: ['监控大盘'] },
  { match: /^\/datasources/, crumbs: ['数据源管理'] },
  { match: /^\/tasks\/new/, crumbs: ['同步任务', '新建任务向导'] },
  { match: /^\/tasks\/[^/]+/, crumbs: ['同步任务', '任务详情'] },
  { match: /^\/tasks/, crumbs: ['同步任务'] },
  { match: /^\/compare/, crumbs: ['比对报告'] },
  { match: /^\/cutover/, crumbs: ['切流控制台'] },
  { match: /^\/system/, crumbs: ['系统设置'] },
];

export function Layout() {
  const demo = useDemoState();
  const session = useSession();
  const location = useLocation();
  const navigate = useNavigate();
  const crumbs = TITLE_MAP.find((x) => x.match.test(location.pathname))?.crumbs ?? ['控制台'];

  async function logout(): Promise<void> {
    try {
      await http.post(EP.logout());
    } catch {
      /* 后端不可用时也允许本地登出 */
    }
    authStore.clear();
    navigate('/login', { replace: true });
  }

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="brand">
          <div className="brand-mark">DB</div>
          <div>
            <div className="brand-title">异构库同步平台</div>
            <div className="brand-sub">DB Sync Console</div>
          </div>
        </div>

        <nav className="nav">
          <div className="nav-group">运维</div>
          {NAV_ITEMS.map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              className={({ isActive }) => `nav-item${isActive ? ' active' : ''}`}
              title={item.desc}
            >
              <span className="nav-icon">{item.icon}</span>
              <span>{item.label}</span>
            </NavLink>
          ))}
        </nav>

        <div className="sidebar-foot">
          <span>v0.2.0 · 内网部署</span>
          <span>元数据库 MySQL 8.0 · 主备</span>
          <span className="mono">cluster: dbsync-prod-01</span>
        </div>
      </aside>

      <div className="main-col">
        <header className="topbar">
          <div className="crumb">
            <span>控制台</span>
            {crumbs.map((c) => (
              <span key={c} style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                <span className="faint">/</span>
                <b>{c}</b>
              </span>
            ))}
          </div>
          <div className="topbar-right">
            <span className="row" style={{ gap: 6 }}>
              <i className="dot ok" />
              调度实例 3/3 在线
            </span>
            <span className="row" style={{ gap: 6 }}>
              <i className="dot ok" />
              元数据库正常
            </span>
            <span className="mono" title={session?.expiresAt ? `token 到期 ${session.expiresAt}` : undefined}>
              {session?.displayName ?? session?.username ?? '未登录'} · {session?.role ?? '-'}
            </span>
            <button className="btn-link" onClick={() => void logout()}>
              退出
            </button>
          </div>
        </header>

        {demo.active ? (
          <div className="demo-banner">
            <span className="tag">演示数据</span>
            <span>{demo.reason}</span>
            <button className="btn-link" style={{ marginLeft: 'auto' }} onClick={() => demoStore.reset()}>
              重试连后端
            </button>
          </div>
        ) : null}

        <main className="content">
          <ErrorBoundary>
            <Outlet />
          </ErrorBoundary>
        </main>
      </div>
    </div>
  );
}
