import type { ReactNode } from 'react';
import { cls, type Tone } from '../utils/format';

interface StatusDotProps {
  tone: Tone;
  children: ReactNode;
  pulse?: boolean;
  className?: string;
}

/** 状态：色点 + 文案（不单纯依赖颜色，满足无障碍要求） */
export function StatusDot({ tone, children, pulse, className }: StatusDotProps) {
  return (
    <span className={cls('status', className)}>
      <i className={cls('dot', tone, pulse && 'pulse')} />
      {children}
    </span>
  );
}

export function Badge({
  tone = 'neutral',
  children,
  title,
}: {
  tone?: Tone;
  children: ReactNode;
  title?: string;
}) {
  return (
    <span className={cls('badge', tone)} title={title}>
      {children}
    </span>
  );
}
