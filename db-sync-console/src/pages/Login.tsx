import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { EP } from '../api/endpoints';
import { http } from '../api/client';
import { authStore } from '../api/auth';
import { errorText } from '../api/errors';
import type { AuthSession } from '../types';

const DEMO = { username: 'admin', password: 'Admin@12345' };

export function Login() {
  const navigate = useNavigate();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);

  async function submit(e: React.FormEvent): Promise<void> {
    e.preventDefault();
    if (!username.trim() || !password) {
      setError('请输入账号和口令');
      return;
    }
    setPending(true);
    setError(null);
    try {
      const session = await http.anonymous<AuthSession>(EP.login(), {
        username: username.trim(),
        password,
      });
      authStore.set(session);
      if (session.mustChangePassword) {
        navigate('/change-password', { replace: true });
      } else {
        navigate('/monitor', { replace: true });
      }
    } catch (err) {
      // 401/E8001 都会到这里：不白屏，只提示
      setError(errorText(err));
    } finally {
      setPending(false);
    }
  }

  function fillDemo(): void {
    setUsername(DEMO.username);
    setPassword(DEMO.password);
    setError(null);
  }

  return (
    <div className="login-wrap">
      <form className="login-card" onSubmit={(e) => void submit(e)}>
        <div className="login-brand">
          <div className="brand-mark">DB</div>
          <div>
            <div className="login-title">异构数据库同步与迁移平台</div>
            <div className="brand-sub">DB Sync Console · 运维控制台</div>
          </div>
        </div>
        <div className="login-sub">
          5 种异构库（MySQL / Oracle / PostgreSQL / DM8 / DB2）存量迁移、增量双写、全量比对与灰度切流。
        </div>

        <div className="col" style={{ gap: 12 }}>
          <div className="field">
            <label>账号</label>
            <input
              className="input mono"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              placeholder="admin"
              autoComplete="username"
            />
          </div>
          <div className="field">
            <label>口令</label>
            <input
              className="input"
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              placeholder="Admin@12345"
              autoComplete="current-password"
            />
          </div>

          {error ? (
            <div className="alert danger">
              <span>{error}</span>
            </div>
          ) : null}

          <button className="btn btn-primary btn-lg" type="submit" style={{ width: '100%', marginTop: 2 }} disabled={pending}>
            {pending ? '登录中…' : '登录控制台'}
          </button>
          <button className="btn" type="button" onClick={fillDemo} style={{ width: '100%' }}>
            填充演示账号（admin / Admin@12345）
          </button>

          <div className="alert info">
            <div>
              首次登录且口令为初始值时，后端返回 <span className="mono">mustChangePassword</span>，会强制进入改密页；
              生产环境请立即修改演示账号口令。
            </div>
          </div>

          <div className="small faint" style={{ textAlign: 'center' }}>
            登录后所有请求携带 <span className="mono">X-DBSync-Token</span>，操作全量落审计（操作人 / 对象 / IP / TraceId）
          </div>
        </div>
      </form>
    </div>
  );
}
