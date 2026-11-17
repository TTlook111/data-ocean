import { Alert, App, Button, Descriptions, Modal, Table } from 'antd';
import { useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import type { Preview } from '../modules/iam/types';
import { mutation } from './api';
import { useReturnFocus } from './useReturnFocus';

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
   * 提交确认后回到原记录并重新读取的回调。
   */
  done: () => void;
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
      await mutation(
        submission.path,
        submission.method,
        { ...submission.body, reviewToken: submission.preview.reviewToken },
        submission.operation,
        command,
      );
      await cache.invalidateQueries();
      message.success('已保存');
      submission.done();
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
      onCancel={() => {
        if (!busy) close();
      }}
      closable={!busy}
      width={760}
      footer={[
        <Button key="back" onClick={close} disabled={busy}>
          返回修改
        </Button>,
        <Button key="save" type="primary" loading={busy} onClick={submit}>
          确认保存
        </Button>,
      ]}
      mask={{ closable: false }}
    >
      {error && <Alert type="error" title={error} showIcon className="form-alert" />}
      <Table
        className="full-text-table"
        rowKey="field"
        pagination={false}
        size="small"
        dataSource={submission.preview.changes}
        columns={[
          { title: '核对项', dataIndex: 'label', width: 140 },
          { title: '当前', dataIndex: 'before', render: (value) => value || '—' },
          { title: '保存后', dataIndex: 'after', render: (value) => value || '—' },
        ]}
      />
      <Descriptions
        className="review-counts"
        size="small"
        items={submission.preview.affected.map((item) => ({
          key: item.kind,
          label: impactLabels[item.kind] ?? '影响对象',
          children: `${item.count} 个`,
        }))}
      />
      {submission.preview.warnings.map((warning) => (
        <Alert key={warning} type="warning" title={warning} showIcon className="form-alert" />
      ))}
    </Modal>
  );
}
