import { useMemo, useState, type ReactNode } from 'react';
import { cls } from '../utils/format';

export interface Column<T> {
  key: string;
  title: ReactNode;
  align?: 'left' | 'right' | 'center';
  width?: number | string;
  sortable?: boolean;
  sortValue?: (row: T) => number | string;
  render?: (row: T, index: number) => ReactNode;
  titleAttr?: string;
}

interface DataTableProps<T> {
  columns: Column<T>[];
  rows: T[];
  rowKey: (row: T, index: number) => string;
  loading?: boolean;
  emptyText?: string;
  emptyHint?: string;
  onRowClick?: (row: T) => void;
  rowClassName?: (row: T) => string | undefined;
  compact?: boolean;
  minWidth?: number;
  footer?: ReactNode;
  skeletonRows?: number;
}

type SortState = { key: string; dir: 'asc' | 'desc' } | null;

function compare(a: number | string, b: number | string): number {
  if (typeof a === 'number' && typeof b === 'number') return a - b;
  return String(a).localeCompare(String(b), 'zh-Hans-CN');
}

/** 通用表格：粘性表头、斑马纹、hover 高亮、列多横向滚动、点击表头排序 */
export function DataTable<T>({
  columns,
  rows,
  rowKey,
  loading,
  emptyText = '暂无数据',
  emptyHint,
  onRowClick,
  rowClassName,
  compact,
  minWidth = 720,
  footer,
  skeletonRows = 6,
}: DataTableProps<T>) {
  const [sort, setSort] = useState<SortState>(null);

  const sorted = useMemo(() => {
    if (!sort) return rows;
    const col = columns.find((c) => c.key === sort.key);
    if (!col) return rows;
    const get = col.sortValue ?? ((row: T) => String((row as Record<string, unknown>)[col.key] ?? ''));
    const list = [...rows];
    list.sort((a, b) => (sort.dir === 'asc' ? compare(get(a), get(b)) : compare(get(b), get(a))));
    return list;
  }, [rows, sort, columns]);

  function toggleSort(col: Column<T>): void {
    if (!col.sortable) return;
    setSort((prev) => {
      if (prev?.key !== col.key) return { key: col.key, dir: 'desc' };
      return { key: col.key, dir: prev.dir === 'desc' ? 'asc' : 'desc' };
    });
  }

  return (
    <div className="table-wrap">
      <table className={cls('tbl', compact && 'compact')} style={{ minWidth }}>
        <thead>
          <tr>
            {columns.map((col) => (
              <th
                key={col.key}
                className={cls(col.sortable && 'sortable', col.align === 'right' && 'num', col.align === 'center' && 'center')}
                style={col.width ? { width: col.width } : undefined}
                onClick={() => toggleSort(col)}
                title={col.titleAttr ?? (col.sortable ? '点击排序' : undefined)}
              >
                {col.title}
                {sort?.key === col.key ? <span className="sort-mark">{sort.dir === 'desc' ? '▼' : '▲'}</span> : null}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {loading
            ? Array.from({ length: skeletonRows }, (_, i) => (
                <tr key={`sk-${i}`}>
                  {columns.map((col) => (
                    <td key={col.key}>
                      <div className="skeleton" style={{ width: `${45 + ((i * 13 + col.key.length * 7) % 45)}%` }} />
                    </td>
                  ))}
                </tr>
              ))
            : sorted.map((row, index) => (
                <tr
                  key={rowKey(row, index)}
                  className={cls(onRowClick && 'clickable', rowClassName?.(row))}
                  onClick={onRowClick ? () => onRowClick(row) : undefined}
                >
                  {columns.map((col) => (
                    <td
                      key={col.key}
                      className={cls(col.align === 'right' && 'num', col.align === 'center' && 'center')}
                    >
                      {col.render ? col.render(row, index) : String((row as Record<string, unknown>)[col.key] ?? '')}
                    </td>
                  ))}
                </tr>
              ))}
        </tbody>
        {footer ? <tfoot>{footer}</tfoot> : null}
      </table>
      {!loading && sorted.length === 0 ? (
        <div className="state">
          <div className="icon">∅</div>
          <div className="title">{emptyText}</div>
          {emptyHint ? <div>{emptyHint}</div> : null}
        </div>
      ) : null}
    </div>
  );
}
