import { cls } from '../utils/format';

interface ProgressBarProps {
  value: number;
  tone?: 'accent' | 'ok' | 'warn' | 'danger';
  showPct?: boolean;
  thin?: boolean;
  width?: number;
}

export function ProgressBar({ value, tone = 'accent', showPct, thin, width }: ProgressBarProps) {
  const v = Math.max(0, Math.min(100, value));
  return (
    <div className="bar-row" style={width ? { minWidth: width } : undefined}>
      <div className={cls('bar', thin && 'thin', tone !== 'accent' && tone)}>
        <i style={{ width: `${v}%` }} />
      </div>
      {showPct ? <span className="pct">{v.toFixed(1)}%</span> : null}
    </div>
  );
}
