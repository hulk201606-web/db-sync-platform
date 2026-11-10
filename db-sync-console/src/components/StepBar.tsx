import { cls } from '../utils/format';

export interface StepItem {
  key: string;
  title: string;
  desc?: string;
}

interface StepBarProps {
  steps: StepItem[];
  current: number;
  onJump?: (index: number) => void;
  maxReached?: number;
}

/** 向导步骤条：常显，已完成的步骤打勾 */
export function StepBar({ steps, current, onJump, maxReached = current }: StepBarProps) {
  return (
    <div className="steps">
      {steps.map((step, index) => {
        const done = index < current;
        const active = index === current;
        const reachable = index <= maxReached;
        return (
          <div
            key={step.key}
            className={cls('step', done && 'done', active && 'active')}
            onClick={() => reachable && onJump?.(index)}
            style={{ cursor: reachable && onJump ? 'pointer' : 'default', opacity: reachable ? 1 : 0.55 }}
            title={step.desc}
          >
            <span className="step-idx">{done ? '' : index + 1}</span>
            <span>
              <span className="step-label">{step.title}</span>
            </span>
          </div>
        );
      })}
    </div>
  );
}
