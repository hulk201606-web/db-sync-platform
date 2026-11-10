import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { EP } from '../api/endpoints';
import { http } from '../api/client';
import { authStore, useSession } from '../api/auth';
import { errorText } from '../api/errors';

/** 改密页：后端 mustChangePassword=true 时强制进入 */
export function ChangePassword() {
  const navigate = useNavigate();
  const session = useSession();
  const [oldPassword, setOldPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);

  const longEnough = newPassword.length >= 8;
  const same = newPassword === confirm;

  async function submit(e: React.FormEvent): Promise<void> {
    e.preventDefault();
    if (!longEnough) {
      setError('新口令长度需 ≥ 8 位');
      return;
    }
    if (!same) {
      setError('两次输入的新口令不一致');
      return;
    }
    setPending(true);
    setError(null);
    try {
      await http.post(EP.changePassword(), { oldPassword, newPassword });
      authStore.clear();
      navigate('/login', { replace: true });
    } catch (err) {
      setError(errorText(err));
    } finally {
      setPending(false);
    }
  }

  return (
    <div className="login-wrap">
      <form className="login-card" onSubmit={(e) => void submit(e)}>
        <div className="login-brand">
          <div className="brand-mark">DB</div>
          <div>
            <div className="login-title">修改初始口令</div>
            <div className="brand-sub">PASSWORD RESET</div>
          </div>
        </div>
        <div className="login-sub">
          账号 <span className="mono">{session?.username ?? '—'}</span> 使用初始口令，首次登录必须修改后才能进入控制台。
        </div>

        <div className="col" style={{ gap: 12 }}>
          <div className="field">
            <label>原口令</label>
            <input
              className="input"
              type="password"
              value={oldPassword}
              onChange={(e) => setOldPassword(e.target.value)}
              autoComplete="current-password"
            />
          </div>
          <div className="field">
            <label>
              新口令
              <span className="faint mono" style={{ marginLeft: 'auto' }}>
                {newPassword.length}/8
              </span>
            </label>
            <input
              className="input"
              type="password"
              value={newPassword}
              onChange={(e) => setNewPassword(e.target.value)}
              autoComplete="new-password"
            />
            <span className="small faint">≥ 8 位，建议包含大小写、数字与符号</span>
          </div>
          <div className="field">
            <label>确认新口令</label>
            <input
              className="input"
              type="password"
              value={confirm}
              onChange={(e) => setConfirm(e.target.value)}
              autoComplete="new-password"
            />
            {confirm && !same ? <span className="small" style={{ color: 'var(--danger)' }}>两次输入不一致</span> : null}
          </div>

          {error ? (
            <div className="alert danger">
              <span>{error}</span>
            </div>
          ) : null}

          <button className="btn btn-primary btn-lg" type="submit" disabled={pending || !longEnough || !same}>
            {pending ? '提交中…' : '修改并重新登录'}
          </button>
          <button
            className="btn"
            type="button"
            onClick={() => {
              authStore.clear();
              navigate('/login', { replace: true });
            }}
          >
            退出登录
          </button>

          <div className="alert info">改密成功后旧 token 立即失效，需要用新口令重新登录。</div>
        </div>
      </form>
    </div>
  );
}
