import { Alert, App, Button, Modal, Table } from 'antd';
import { useState } from 'react';
import type { ReactNode } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import type { Preview } from '../modules/iam/types';
import { mutation } from './api';
import { useReturnFocus } from './useReturnFocus';
import { displayChangeValue } from './Records';

/**
 * 服务端稳定对象类型转为业务名称，不在核对面板直接展示协议代码。
 */
const impactLabels: Record<string, string> = {
  USER: '影响人员',
  DEPARTMENT: '影响部门',
  ROLE: '影响角色',
  MEMBERSHIP: '成员关系',
};

/**
 * 核对结果与完整正式请求绑定，密码只在本次内存中提交。
 */
export interface ReviewSubmission {
  /**
   * 当前对象的明确操作名称。
   */
  title: string;
  /**
   * 真实服务计算的差异及签名凭据。
   */
  preview: Preview;
  /**
   * 正式写入接口路径。
   */
  path: string;
  /**
   * 与契约一致的 HTTP 方法。
   */
  method: string;
  /**
   * 用于原命令核实的动作代码。
   */
  operation: string;
  /**
   * 由具体业务表单构造的完整类型化请求。
   */
  body: object;
  /**
   * 提交确认后携带真实目标标识，回看该记录或恢复原查询。
   */
  done: (targetId: string, commandId?: string) => void;
  /**
   * 危险或生命周期动作使用准确的确认名称，普通编辑默认确认保存。
   */
  confirmText?: string;
  /**
   * 异步任务已接收时说明实际结果。
   */
  successText?: string;
  /**
   * 停用、归档及重置等后果明确的动作使用危险按钮样式。
   */
  danger?: boolean;
  /**
   * 与输入阶段一致的对象摘要，避免把长对象名称挤入确认标题。
   */
  context?: ReactNode;
}

/**
 * 危险或共享配置操作使用服务端核对，不自行估算影响人数。
 */
export function ReviewDialog({
  submission,
  close,
}: {
  submission: ReviewSubmission;
  close: () => void;
}) {
  useReturnFocus();

  const [command] = useState(() => crypto.randomUUID());

  const [busy, setBusy] = useState(false);

  const [error, setError] = useState<string | null>(null);

  const cache = useQueryClient();

  const { message } = App.useApp();

  /**
   * 使用固定命令键提交本次核对，失败保留提案以便返回修改。
   */
  async function submit() {
    setBusy(true);
    setError(null);
    try {
      const result = await mutation(
        submission.path,
        submission.method,
        { ...submission.body, reviewToken: submission.preview.reviewToken },
        submission.operation,
        command,
      );
      await cache.invalidateQueries();
      // 采集接口返回任务编号。原命令核实时仍沿用通用目标编号。
      const targetId = result.targetId ?? result.taskId;
      if (!targetId) {
        // 避免把空编号用于下一步定位。
        throw new Error('操作已提交，暂时无法定位记录');
      }
      message.success(submission.successText ?? '已保存');
      submission.done(targetId, command);
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : '操作未完成');
    } finally {
      setBusy(false);
    }
  }

  // 核对表、实际影响与危险后果共同组成提交前的可读确认。
  return (
    <Modal
      centered
      open
      title={submission.title}
      className={submission.context ? 'operation-dialog operation-review-dialog' : undefined}
      onCancel={() => {
        if (!busy) close();
      }}
      closable={!busy}
      width={submission.context ? 640 : 760}
      footer={[
        <Button key="back" onClick={close} disabled={busy}>
          返回修改
        </Button>,
        <Button
          key="save"
          type="primary"
          danger={submission.danger}
          loading={busy}
          onClick={submit}
        >
          {submission.confirmText ?? '确认保存'}
        </Button>,
      ]}
      mask={{ closable: false }}
    >
      {error && <Alert type="error" title={error} showIcon className="form-alert" />}
      {submission.context}
      <Table
        className="full-text-table"
        rowKey="field"
        pagination={false}
        size="small"
        dataSource={submission.preview.changes}
        columns={[
          { title: '核对项', dataIndex: 'label', width: 140 },
          {
            title: '当前',
            dataIndex: 'before',
            render: (value, change) => displayChangeValue(value, change.field, change.label),
          },
          {
            title: '保存后',
            dataIndex: 'after',
            className: 'review-change-after',
            render: (value, change) => displayChangeValue(value, change.field, change.label),
          },
        ]}
      />
      {/* 影响范围以紧凑事实展示，使用业务单位，不再塞进一张只含零值的描述表。 */}
      {submission.preview.affected.length > 0 && (
        <div className="review-impact" aria-label="影响范围">
          {submission.preview.affected.map((item) => (
            <span key={item.kind}>
              {impactLabels[item.kind] ?? '影响对象'}
              <strong>{item.count}</strong>
              {item.kind === 'USER' ? '人' : item.kind === 'MEMBERSHIP' ? '条' : '个'}
            </span>
          ))}
        </div>
      )}
      {submission.preview.warnings.map((warning) => (
        <Alert key={warning} type="warning" title={warning} showIcon className="form-alert" />
      ))}
    </Modal>
  );
}
