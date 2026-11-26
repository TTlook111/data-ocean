import { CalendarOutlined } from '@ant-design/icons';
import { DatePicker } from 'antd';
import { useId } from 'react';
import type { ComponentProps } from 'react';

/**
 * 后台查询统一使用可清除的中文双月日期范围，并标注起止两个输入。
 */
export function QueryDateRange({
  label,
  ...props
}: ComponentProps<typeof DatePicker.RangePicker> & {
  /** 当前业务日期范围的名称。 */
  label: string;
}) {
  const start = useId();
  const end = useId();
  return (
    <>
      {/* 占位说明当前端点，读屏同时获得业务日期名称。 */}
      <label className="sr-only" htmlFor={start}>
        {label}（开始）
      </label>
      <label className="sr-only" htmlFor={end}>
        {label}（结束）
      </label>
      <DatePicker.RangePicker
        {...props}
        id={{ start, end }}
        className="query-range-picker"
        prefix={<CalendarOutlined />}
        suffixIcon={null}
        separator="至"
        placement="bottomRight"
        placeholder={['开始日期', '结束日期']}
        format="YYYY-MM-DD"
      />
    </>
  );
}
