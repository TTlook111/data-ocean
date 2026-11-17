import { Alert, Button, Empty, Result, Space, Spin, Tag } from 'antd';
import type { ReactNode } from 'react';
import { ApiError } from './api';

/**
 * 将业务状态编码统一映射为清楚的中文名称。
 */
export const statuses: Record<string, string> = {
  ENABLED: '正常',
  DISABLED: '停用',
  ARCHIVED: '已归档',
  PENDING_FIRST_LOGIN: '待首次登录',
  PASSWORD_CHANGE_REQUIRED: '待改密',
};

/**
 * 状态用文字和克制色彩共同表达，归档保留历史含义。
 */
export function Status({ value, label }: { value: string; label?: string }) {
  return (
    <Tag color={value === 'ENABLED' ? 'success' : value === 'DISABLED' ? 'warning' : 'default'}>
      {label ?? statuses[value] ?? value}
    </Tag>
  );
}

/**
 * 网络和浏览器异常给出可操作的中文反馈，不将技术错误直接放入业务界面。
 */
export function readErrorMessage(error: Error) {
  return /[\u4e00-\u9fff]/u.test(error.message) ? error.message : '暂时无法加载，请检查连接后重试';
}

/**
 * 失败与空结果分开呈现，重试不触发任何写操作。
 */
export function ReadState({
  error,
  retry,
  children,
  empty = false,
  loading = false,
}: {
  error: Error | null;
  retry: () => void;
  children: ReactNode;
  empty?: boolean;
  loading?: boolean;
}) {
  // 详情失权与普通连接失败分别反馈，不渲染旧资料或正常空态。
  if (error instanceof ApiError && Math.floor(error.code / 1000) === 403) {
    return <Result status="403" title="当前无权查看这些内容" />;
  }
  if (error) {
    return (
      <Alert
        type="error"
        title={readErrorMessage(error)}
        action={<Button onClick={retry}>重试</Button>}
        showIcon
      />
    );
  }
  if (empty) {
    return <Empty description="暂无记录" />;
  }
  // 首次读取时不展示尚未到达的人员资料或功能配置空白值。
  if (loading)
    return (
      <div className="read-loading" role="status" aria-label="正在加载">
        <Spin />
      </div>
    );
  return children;
}

/**
 * 底部固定的详情动作区，保持危险操作和关闭入口可达。
 */
export function DetailFooter({ children, close }: { children?: ReactNode; close: () => void }) {
  return (
    <div className="detail-footer">
      <span />
      <Space wrap>
        {children}
        <Button onClick={close}>关闭</Button>
      </Space>
    </div>
  );
}

/**
 * 日期统一按上海业务时区展示，服务端始终传 UTC 时刻。
 */
export function displayTime(value?: string | null) {
  return value
    ? new Intl.DateTimeFormat('sv-SE', {
        timeZone: 'Asia/Shanghai',
        year: 'numeric',
        month: '2-digit',
        day: '2-digit',
        hour: '2-digit',
        minute: '2-digit',
        hour12: false,
      }).format(new Date(value))
    : '—';
}
