import type { ReactNode } from 'react';
import { cls } from '../utils/format';

interface StatCardProps {
  label: string;
  value: ReactNode;
  unit?: string;
  of?: string;
  sub?: ReactNode;
  tone?: 'accent' | 'ok' | 'warn' | 'danger' | 'none';
  right?: ReactNode;
}

/** KPI 卡：大数字用等宽字体，右上角可放标记，底部放采样说明 */
export function StatCard({ label, value, unit, of, sub, tone = 'none', right }: StatCardProps) {
  return (
    <div className={cls('stat', tone !== 'none' && `tone-${tone}`)}>
      <div className="stat-label">
        <span>{label}</span>
        {right}
      </div>
      <div className="stat-value">
        {value}
        {of ? <span className="of">/ {of}</span> : null}
        {unit ? <span className="unit">{unit}</span> : null}
      </div>
      {sub ? <div className="stat-sub">{sub}</div> : null}
    </div>
  );
}
