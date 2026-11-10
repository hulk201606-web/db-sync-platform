import { useState } from 'react';
import type { TpsSeries } from '../types';
import { fmtNum } from '../utils/format';

interface StackedAreaChartProps {
  series: TpsSeries[];
  height?: number;
  yLabel?: string;
}

const PAD = { left: 48, right: 12, top: 12, bottom: 22 };
const VIEW_W = 1000;

/**
 * 手写 SVG 堆叠面积图（近 1h TPS，按实例堆叠）。
 * 不引入 echarts/recharts：只有一种图表形态，自绘可控且零体积。
 */
export function StackedAreaChart({ series, height = 190, yLabel = '行/秒' }: StackedAreaChartProps) {
  const [hover, setHover] = useState<number | null>(null);

  const n = series[0]?.values.length ?? 0;
  const plotW = VIEW_W - PAD.left - PAD.right;
  const plotH = height - PAD.top - PAD.bottom;

  const cum: number[][] = [];
  for (let s = 0; s < series.length; s++) {
    const row: number[] = [];
    for (let i = 0; i < n; i++) {
      const prev = s > 0 ? (cum[s - 1]?.[i] ?? 0) : 0;
      row.push(prev + (series[s]?.values[i] ?? 0));
    }
    cum.push(row);
  }

  const maxTotal = Math.max(1, ...(cum[cum.length - 1] ?? [0]));
  const yMax = Math.ceil(maxTotal / 10000) * 10000;

  const x = (i: number): number => PAD.left + (n <= 1 ? 0 : (i / (n - 1)) * plotW);
  const y = (v: number): number => PAD.top + (1 - v / yMax) * plotH;

  function areaPath(s: number): string {
    const upper = cum[s] ?? [];
    const lower = s > 0 ? (cum[s - 1] ?? []) : new Array<number>(n).fill(0);
    if (n === 0) return '';
    const top = upper.map((v, i) => `${i === 0 ? 'M' : 'L'}${x(i).toFixed(1)},${y(v).toFixed(1)}`).join(' ');
    const bottom = lower
      .slice()
      .reverse()
      .map((v, idx) => `L${x(n - 1 - idx).toFixed(1)},${y(v).toFixed(1)}`)
      .join(' ');
    return `${top} ${bottom} Z`;
  }

  function linePath(s: number): string {
    const upper = cum[s] ?? [];
    return upper.map((v, i) => `${i === 0 ? 'M' : 'L'}${x(i).toFixed(1)},${y(v).toFixed(1)}`).join(' ');
  }

  const totalAt = (i: number): number => cum[cum.length - 1]?.[i] ?? 0;
  const hoverX = hover === null ? 0 : x(hover);

  function onMove(e: React.MouseEvent<SVGSVGElement>): void {
    const rect = e.currentTarget.getBoundingClientRect();
    const mx = ((e.clientX - rect.left) / rect.width) * VIEW_W;
    if (mx < PAD.left || mx > VIEW_W - PAD.right) {
      setHover(null);
      return;
    }
    const idx = Math.round(((mx - PAD.left) / plotW) * (n - 1));
    setHover(Math.max(0, Math.min(n - 1, idx)));
  }

  const gridVals = [0, 0.25, 0.5, 0.75, 1].map((r) => r * yMax);

  return (
    <div>
      <svg
        className="chart-svg"
        viewBox={`0 0 ${VIEW_W} ${height}`}
        height={height}
        onMouseMove={onMove}
        onMouseLeave={() => setHover(null)}
        role="img"
        aria-label="近一小时 TPS 曲线"
      >
        <defs>
          {series.map((s, idx) => (
            <linearGradient key={s.name} id={`tps-grad-${idx}`} x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" stopColor={s.color} stopOpacity="0.45" />
              <stop offset="100%" stopColor={s.color} stopOpacity="0.08" />
            </linearGradient>
          ))}
        </defs>

        {gridVals.map((v) => (
          <g key={v}>
            <line
              x1={PAD.left}
              x2={VIEW_W - PAD.right}
              y1={y(v)}
              y2={y(v)}
              stroke="#1f2937"
              strokeWidth="1"
              strokeDasharray={v === 0 ? undefined : '3 4'}
            />
            <text x={PAD.left - 8} y={y(v) + 3.5} textAnchor="end" fontSize="10" fill="#64748b" fontFamily="ui-monospace, monospace">
              {v >= 10000 ? `${Math.round(v / 1000)}k` : v}
            </text>
          </g>
        ))}

        {series.map((s, idx) => (
          <g key={s.name}>
            <path d={areaPath(idx)} fill={`url(#tps-grad-${idx})`} />
            <path d={linePath(idx)} fill="none" stroke={s.color} strokeWidth="1.4" strokeOpacity="0.9" />
          </g>
        ))}

        {/* x 轴刻度：60 分钟窗口 */}
        {[0, 15, 30, 45, 59].map((i) => (
          <text
            key={i}
            x={x(i)}
            y={height - 6}
            textAnchor={i === 0 ? 'start' : i === 59 ? 'end' : 'middle'}
            fontSize="10"
            fill="#64748b"
            fontFamily="ui-monospace, monospace"
          >
            {i === 59 ? '现在' : `-${59 - i}m`}
          </text>
        ))}

        {hover !== null ? (
          <g>
            <line x1={hoverX} x2={hoverX} y1={PAD.top} y2={PAD.top + plotH} stroke="#94a3b8" strokeWidth="1" strokeDasharray="3 3" />
            <circle cx={hoverX} cy={y(totalAt(hover))} r="3" fill="#22D3EE" stroke="#0b0f17" strokeWidth="1.5" />
            <g transform={`translate(${Math.min(hoverX + 8, VIEW_W - 190)}, ${PAD.top + 6})`}>
              <rect width="178" height={20 + series.length * 15} rx="4" fill="#0a0f18" stroke="#2a3748" />
              <text x="9" y="14" fontSize="10.5" fill="#94a3b8" fontFamily="ui-monospace, monospace">
                {hover === 59 ? '现在' : `-${59 - hover} 分钟`} · 合计 {fmtNum(totalAt(hover))}
              </text>
              {series.map((s, idx) => (
                <g key={s.name} transform={`translate(9, ${26 + idx * 15})`}>
                  <rect width="7" height="7" rx="1.5" y="-6" fill={s.color} />
                  <text x="13" y="0" fontSize="10.5" fill="#cbd5e1" fontFamily="ui-monospace, monospace">
                    {s.name} {fmtNum(s.values[hover] ?? 0)}
                  </text>
                </g>
              ))}
            </g>
          </g>
        ) : null}

        <text x={PAD.left} y={PAD.top - 2} fontSize="9.5" fill="#475569">
          {yLabel}
        </text>
      </svg>
      <div className="chart-legend" style={{ marginTop: 4 }}>
        {series.map((s) => (
          <span className="item" key={s.name}>
            <span className="swatch" style={{ background: s.color }} />
            {s.name}
          </span>
        ))}
      </div>
    </div>
  );
}
