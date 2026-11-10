import { useEffect, useState, type ReactNode } from 'react';
import { Modal } from './Modal';

interface ConfirmDialogProps {
  open: boolean;
  title: string;
  /** 影响说明 */
  children: ReactNode;
  /** 必须原样输入的确认文本（对象名 / SKIP-GATE 等） */
  confirmText: string;
  confirmLabel?: string;
  /** 需要填写理由，并校验最小长度 */
  requireReason?: boolean;
  reasonMinLength?: number;
  reasonLabel?: string;
  /** 额外勾选项 */
  extraCheckbox?: string;
  danger?: boolean;
  pending?: boolean;
  onCancel: () => void;
  onConfirm: (payload: { confirmText: string; reason: string; checked: boolean }) => void;
}

/** 危险操作二次确认：必须逐字输入对象名，可选必填理由 */
export function ConfirmDialog({
  open,
  title,
  children,
  confirmText,
  confirmLabel = '确认执行',
  requireReason,
  reasonMinLength = 10,
  reasonLabel = '操作理由（≥ {n} 字，落审计）',
  extraCheckbox,
  danger = true,
  pending,
  onCancel,
  onConfirm,
}: ConfirmDialogProps) {
  const [input, setInput] = useState('');
  const [reason, setReason] = useState('');
  const [checked, setChecked] = useState(false);

  useEffect(() => {
    if (open) {
      setInput('');
      setReason('');
      setChecked(false);
    }
  }, [open, confirmText]);

  const textOk = input.trim() === confirmText;
  const reasonOk = !requireReason || reason.trim().length >= reasonMinLength;
  const checkOk = !extraCheckbox || checked;
  const ok = textOk && reasonOk && checkOk;

  return (
    <Modal
      open={open}
      title={<span style={{ color: danger ? 'var(--danger)' : undefined }}>{title}</span>}
      onClose={onCancel}
      footer={
        <>
          <button className="btn" onClick={onCancel} disabled={pending}>
            取消
          </button>
          <button
            className={danger ? 'btn btn-danger' : 'btn btn-primary'}
            disabled={!ok || pending}
            onClick={() => onConfirm({ confirmText: input.trim(), reason: reason.trim(), checked })}
          >
            {pending ? '执行中…' : confirmLabel}
          </button>
        </>
      }
    >
      <div className="col" style={{ gap: 12 }}>
        {children}

        {requireReason ? (
          <div className="field">
            <label>
              {reasonLabel.replace('{n}', String(reasonMinLength))}
              <span className="req">*</span>
              <span className="faint mono" style={{ marginLeft: 'auto' }}>
                {reason.trim().length}/{reasonMinLength}
              </span>
            </label>
            <textarea
              className="textarea"
              rows={3}
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              placeholder="例如：目标库已完成双写验证，比对通过率 99.993%，经 DBA 与业务方确认后切流"
            />
          </div>
        ) : null}

        {extraCheckbox ? (
          <label className="check">
            <input type="checkbox" checked={checked} onChange={(e) => setChecked(e.target.checked)} />
            {extraCheckbox}
          </label>
        ) : null}

        <div className="field">
          <label>
            请输入 <b className="mono" style={{ color: 'var(--warn)' }}>{confirmText}</b> 以确认
          </label>
          <input
            className="input mono"
            value={input}
            onChange={(e) => setInput(e.target.value)}
            placeholder={confirmText}
            autoFocus
          />
        </div>
      </div>
    </Modal>
  );
}
