import { Button, Empty, Space, Tag } from 'antd';
import type { ReactNode } from 'react';
import { DisconnectOutlined, LockOutlined, ReloadOutlined } from '@ant-design/icons';
import { ApiError } from './api';
import { LoadingStatus } from './LoadingIndicator';

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
    <Tag className={`record-status record-status-${value.toLowerCase()}`}>
      {label ?? statuses[value] ?? value}
    </Tag>
  );
}

/**
 * 核对与审计中的状态编码转为业务名称，普通名称即使与编码同字也保留原文。
 */
export function displayChangeValue(value: string | null, field: string, label: string) {
  // 缺少历史值时明确显示空值，不与状态含义混用。
  if (!value) return '—';
  if (['lifecycle', 'displayStatus', 'status'].includes(field)) {
    // 角色启用与人员、部门正常的展示口径分别沿用当前页面规则。
    if (value === 'ENABLED' && label === '角色状态') return '启用';
    return statuses[value] ?? value;
  }
  return value;
}

/**
 * 网络和浏览器异常给出可操作的中文反馈，不将技术错误直接放入业务界面。
 */
export function readErrorMessage(error: Error) {
  // 将平台依赖分类翻成使用者能理解的恢复反馈，不把存储层术语带入业务页面。
  if (error instanceof ApiError && error.code === 503001) return '服务暂时不可用，请稍后重试';
  return /[\u4e00-\u9fff]/u.test(error.message) ? error.message : '暂时无法加载，请检查连接后重试';
}

/**
 * 列表与详情共用的读取失败态，原因和重试保持紧凑，不拉成横跨整页的错误横幅。
 */
export function ReadFailure({
  error,
  retry,
}: {
  /** 当前读取请求的受控失败原因。 */
  error: Error;
  /** 使用同一组条件重新读取，不重放业务写入。 */
  retry?: () => void;
}) {
  return (
    <div className="read-failure" role="alert">
      <span className="read-state-icon" aria-hidden="true">
        <DisconnectOutlined />
      </span>
      <div className="read-failure-copy">
        <h3>数据暂时无法加载</h3>
        <p>{readErrorMessage(error)}</p>
      </div>
      {/* 恢复入口只在存在真实可执行的读取回调时显示。 */}
      {retry && (
        <Button icon={<ReloadOutlined aria-hidden="true" />} onClick={retry}>
          重新加载
        </Button>
      )}
    </div>
  );
}

/**
 * 在当前读取位置说明无权访问，保持上下文与有权执行的返回操作。
 */
export function ReadAccessDenied({
  title,
  children,
}: {
  /** 就地标明被拒的页面或数据范围。 */
  title: string;
  /** 当前位置确实可用的返回入口。 */
  children?: ReactNode;
}) {
  return (
    <div className="read-access-denied" role="status">
      {/* 文字说明访问结果，锁图标提供辅助识别。 */}
      <span className="read-state-icon" aria-hidden="true">
        <LockOutlined />
      </span>
      <h3>{title}</h3>
      {children}
    </div>
  );
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
    return <ReadAccessDenied title="当前无权查看这些内容" />;
  }
  if (error) {
    return <ReadFailure error={error} retry={retry} />;
  }
  if (empty) {
    return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无记录" />;
  }
  // 首次读取时不展示尚未到达的人员资料或功能配置空白值。
  if (loading)
    return (
      <div className="read-loading muted">
        <LoadingStatus>正在加载</LoadingStatus>
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
