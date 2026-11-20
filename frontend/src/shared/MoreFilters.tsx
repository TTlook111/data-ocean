import { FilterOutlined } from '@ant-design/icons';
import { Button, Popover } from 'antd';
import { useId, useRef } from 'react';
import type { ReactNode } from 'react';

/**
 * 将低频查询字段放在表单内的浮层，保留原查询和键盘顺序。
 */
export function MoreFilters({
  open,
  setOpen,
  count,
  children,
}: {
  /** 当前是否展开更多条件。 */
  open: boolean;
  /** 只改变浮层展示，不自动提交。 */
  setOpen: (open: boolean) => void;
  /** 已应用且收起的条件组数。 */
  count: number;
  /** 当前查询表单的低频字段。 */
  children: ReactNode;
}) {
  const trigger = useRef<HTMLButtonElement>(null);
  const panelId = useId();
  return (
    <div className="query-more-anchor">
      <Popover
        trigger="click"
        placement="bottomRight"
        arrow={false}
        open={open}
        onOpenChange={setOpen}
        getPopupContainer={(button) => button.parentElement ?? document.body}
        content={
          <div
            className="query-more-fields"
            id={panelId}
            role="group"
            aria-label="更多筛选"
            onKeyDown={(event) => {
              // 日期或选项浮层先处理 Escape，关闭本层时还原焦点。
              if (event.key === 'Escape' && !event.defaultPrevented) {
                setOpen(false);
                trigger.current?.focus();
              }
            }}
          >
            {children}
          </div>
        }
      >
        <Button
          ref={trigger}
          icon={<FilterOutlined />}
          aria-expanded={open}
          aria-controls={open ? panelId : undefined}
          className={count ? 'query-more-active' : undefined}
        >
          {count ? `更多筛选（${count}）` : '更多筛选'}
        </Button>
      </Popover>
    </div>
  );
}
