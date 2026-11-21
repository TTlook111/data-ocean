import { Button, Empty, Pagination, Select, Table } from 'antd';
import type { TableProps } from 'antd';
import type { CSSProperties, ReactNode } from 'react';
import { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { ApiError } from './api';
import { ReadAccessDenied, ReadFailure } from './Records';

/**
 * 共用结果状态在表体内呈现，权限拒绝独立阻断受限结构与数量。
 */
interface RecordTableProps<T extends object> extends TableProps<T> {
  /**
   * 当前查询错误。
   */
  error?: Error | null;
  /**
   * 重试当前已应用条件。
   */
  retry?: () => void;
  /**
   * 是否应用了业务筛选条件。
   */
  filtered?: boolean;
  /**
   * 清空当前筛选并恢复查询入口。
   */
  onReset?: () => void;
  /**
   * 未应用筛选时的业务空态文案。
   */
  emptyDescription?: ReactNode;
  /**
   * 可选指定弹性列；默认所有非固定数据列按字段预算分配余宽，操作列保持固定。
   */
  flexibleColumns?: number[];
}

/**
 * 按原型将表体和固定分页分开，零条结果也保留同一个分页页脚。
 */
export function RecordTable<T extends object>({
  error,
  retry,
  filtered = false,
  onReset,
  emptyDescription = '暂无记录',
  flexibleColumns,
  ...props
}: RecordTableProps<T>) {
  const container = useRef<HTMLDivElement>(null);

  const [availableWidth, setAvailableWidth] = useState(0);

  const config = useMemo(
    () => (props.pagination === false ? null : (props.pagination ?? {})),
    [props.pagination],
  );

  const current = config?.current ?? 1;

  const pageSize = config?.pageSize ?? 10;

  const total = config?.total ?? 0;

  const pages = Math.max(1, Math.ceil(total / pageSize));

  const busy =
    typeof props.loading === 'object' ? Boolean(props.loading.spinning) : Boolean(props.loading);

  const hasError = Boolean(error);

  useLayoutEffect(() => {
    /**
     * 按当前表体实际宽度分配数据列，滚动条占位不参与内容预算。
     */
    function measure() {
      const element = container.current;
      if (!element) {
        // 无权状态不挂载受限表格，也不继续读取其尺寸。
        return;
      }
      const body = element.querySelector<HTMLElement>('.ant-table-content, .ant-table-body');
      setAvailableWidth(body?.clientWidth ?? element.clientWidth);
    }
    measure();
    const observer = new ResizeObserver(measure);
    if (container.current) {
      // 表格随工作区、弹窗和缩放改变，不依赖整页窗口的固定宽度。
      observer.observe(container.current);
      const body = container.current.querySelector('.ant-table-content, .ant-table-body');
      if (body) observer.observe(body);
    }
    return () => observer.disconnect();
  }, [hasError, props.dataSource?.length]);

  // 全部数据列按字段预算分配可用空间，避免只有首列拉长而其余列挤在右侧。
  const stretchColumns =
    flexibleColumns ??
    props.columns?.flatMap((column, index) => (column.fixed ? [] : [index])) ??
    [];
  const budgets =
    props.columns?.map((column) =>
      'width' in column && typeof column.width === 'number' ? column.width : 160,
    ) ?? [];
  const flexibleWidth = budgets.reduce(
    (sum, width, index) => sum + (stretchColumns.includes(index) ? width : 0),
    0,
  );
  const minimumWidth = budgets.reduce((sum, width) => sum + width, 0);
  const tableWidth = Math.max(
    availableWidth,
    minimumWidth,
    typeof props.scroll?.x === 'number' ? props.scroll.x : 0,
  );
  const distributeWidth = Boolean(config || flexibleColumns);
  const columns = distributeWidth
    ? props.columns?.map((column, index) =>
        stretchColumns.includes(index)
          ? {
              ...column,
              width:
                budgets[index] + ((tableWidth - minimumWidth) * budgets[index]) / flexibleWidth,
            }
          : column,
      )
    : props.columns;
  // 常规列表的表头与表体共享同一张表，显式限高的嵌入表沿用组件的高度。
  const scroll = distributeWidth
    ? {
        ...props.scroll,
        x: tableWidth,
      }
    : props.scroll;

  useEffect(() => {
    // 办理后结果减少时回到有效末页，加载中不基于临时空数据校正。
    if (!busy && !error && config?.total !== undefined && current > pages)
      config.onChange?.(pages, pageSize);
  }, [busy, error, current, pages, pageSize, config]);

  // 权限错误不展示受限列和数量，不冒充正常零结果。
  if (error instanceof ApiError && Math.floor(error.code / 1000) === 403)
    return <ReadAccessDenied title="当前无权查看这些记录" />;

  // 空态与失败复用可见表体的占位，横向滚动不带走恢复入口。
  const empty = (
    <div className="record-body-state">
      {error ? (
        <ReadFailure error={error} retry={retry} />
      ) : (
        <Empty
          image={Empty.PRESENTED_IMAGE_SIMPLE}
          description={busy ? '正在加载' : filtered ? '没有匹配的记录' : emptyDescription}
        >
          {!busy && filtered && onReset && <Button onClick={onReset}>清空条件</Button>}
        </Empty>
      )}
    </div>
  );

  // 表头、空态或失败、留白与分页沿用同一个面板，表体承接余高滚动。
  return (
    <div
      className="record-table"
      data-scrollable={scroll?.x && scroll.y === undefined ? 'true' : undefined}
      data-columns-fit={minimumWidth > 0 && tableWidth === availableWidth ? 'true' : undefined}
      ref={container}
      aria-busy={busy}
      style={
        { '--record-state-width': availableWidth ? `${availableWidth}px` : '100%' } as CSSProperties
      }
    >
      <Table<T>
        {...props}
        columns={columns}
        dataSource={error ? [] : props.dataSource}
        tableLayout="fixed"
        locale={{ ...props.locale, emptyText: empty }}
        scroll={scroll}
        pagination={false}
      />
      {config && (
        <RecordPagination
          current={current}
          pageSize={pageSize}
          total={total}
          busy={busy}
          error={Boolean(error)}
          onChange={config.onChange}
        />
      )}
    </div>
  );
}

/**
 * 后台表格与审批队列共用结果数量和分页操作。
 */
export function RecordPagination({
  current,
  pageSize,
  total,
  busy,
  error,
  onChange,
}: {
  current: number;
  pageSize: number;
  total: number;
  busy?: boolean;
  error?: boolean;
  onChange?: (page: number, size: number) => void;
}) {
  const footer = useRef<HTMLElement>(null);
  const [compact, setCompact] = useState(false);

  useLayoutEffect(() => {
    // 分页按自己的容器决定样式，表格和独立审批队列遵循同一阈值。
    const element = footer.current;
    if (!element) return;

    /** 更新窄容器的页码表达，宽容器继续展示常规页码。 */
    const measure = () => {
      const width = element.clientWidth;
      setCompact(width > 0 && width <= 760);
    };

    measure();
    const observer = new ResizeObserver(measure);
    observer.observe(element);
    return () => observer.disconnect();
  }, []);

  return (
    <footer
      ref={footer}
      className={`record-pagination${compact ? ' is-compact' : ''}`}
      aria-label="结果分页"
      aria-busy={busy}
    >
      <span>{error ? '记录暂不可用' : busy ? '正在读取记录' : `共 ${total} 条`}</span>
      <div className="record-pagination-controls">
        <Select
          aria-label="每页条数"
          value={pageSize}
          disabled={error}
          options={[10, 20, 50].map((size) => ({ value: size, label: `${size}条/页` }))}
          onChange={(size) => onChange?.(1, size)}
        />
        {/* 零条记录时保留总数和每页条数，避免紧凑分页显示 1 / 0。 */}
        {total > 0 && (
          <Pagination
            current={current}
            pageSize={pageSize}
            total={total}
            disabled={error}
            showSizeChanger={false}
            simple={compact ? { readOnly: true } : false}
            hideOnSinglePage={false}
            onChange={onChange}
          />
        )}
      </div>
    </footer>
  );
}
