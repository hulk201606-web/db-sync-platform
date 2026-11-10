import { useEffect, type ReactNode } from 'react';
import { cls } from '../utils/format';

interface DrawerProps {
  open: boolean;
  title: ReactNode;
  subtitle?: ReactNode;
  onClose: () => void;
  children: ReactNode;
  footer?: ReactNode;
  wide?: boolean;
}

/** 右侧抽屉：用于新建/编辑数据源、死信列表等“不跳页”的场景 */
export function Drawer({ open, title, subtitle, onClose, children, footer, wide }: DrawerProps) {
  useEffect(() => {
    if (!open) return;
    function onKey(e: KeyboardEvent): void {
      if (e.key === 'Escape') onClose();
    }
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open, onClose]);

  if (!open) return null;

  return (
    <div className="overlay" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <aside className={cls('drawer', wide && 'wide')} role="dialog" aria-modal="true">
        <div className="drawer-head">
          <div>
            <div className="drawer-title">{title}</div>
            {subtitle ? <div className="small muted" style={{ marginTop: 2 }}>{subtitle}</div> : null}
          </div>
          <button className="close-x" onClick={onClose} aria-label="关闭">
            ×
          </button>
        </div>
        <div className="drawer-body">{children}</div>
        {footer ? <div className="drawer-foot">{footer}</div> : null}
      </aside>
    </div>
  );
}
