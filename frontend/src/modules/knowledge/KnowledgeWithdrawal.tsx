import { Alert, App, Button, Form, Input, Modal, Table, Tag } from 'antd';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useRef, useState } from 'react';
import { api } from '../../shared/api';
import { displayTime, ReadState } from '../../shared/Records';
import { useSession } from '../../shared/session';
import { useReleaseCommand } from './useReleaseCommand';

/** 同一批次中确切退出的正式修订。 */
type Member = {
  /** 核对采用的正式绑定。 */
  bindingId: string;
  /** 冻结的文档名称。 */
  title: string;
  /** 退出的文档修订。 */
  revision: number;
  /** 离目标的最短显式引用距离。 */
  depth: number;
};

/** 与本次展示范围绑定的核对回执。 */
type Preview = {
  /** 操作者需一起核对的版本。 */
  members: Member[];
  /** 本次完整范围的短时核对凭据。 */
  reviewToken: string;
};

/** 已生效的撤下批次，可回看原依据和成员。 */
type Withdrawal = {
  /** 实际提交时间。 */
  at: string;
  /** 本次办理退出填写的原因。 */
  reason: string;
  /** 同一笔原批次范围。 */
  members: Member[];
};

/** 同一张范围表可用于预检及已办理记录。 */
function WithdrawalRange({ members }: { members: Member[] }) {
  return (
    <Table<Member>
      aria-label="完整撤下范围"
      size="small"
      rowKey="bindingId"
      dataSource={members}
      pagination={false}
      scroll={members.length > 4 ? { y: 208 } : undefined}
      tableLayout="fixed"
      columns={[
        { title: '文档', dataIndex: 'title' },
        { title: '修订', width: 68, render: (_, item) => 'r' + item.revision },
        {
          title: '纳入原因',
          width: 116,
          render: (_, item) => (
            <Tag color={item.depth === 0 ? 'processing' : undefined}>
              {item.depth === 0 ? '撤下目标' : item.depth === 1 ? '直接引用方' : '间接引用方'}
            </Tag>
          ),
        },
      ]}
    />
  );
}

/** 居中确认保留原型操作，提交只采用操作者确认过的完整清单。 */
export function KnowledgeWithdrawalDialog({ path, close }: { path: string; close: () => void }) {
  const session = useSession();
  const cache = useQueryClient();
  const { modal } = App.useApp();
  const [form] = Form.useForm<{ reason: string }>();
  const [open, setOpen] = useState(true);
  const [needsReview, setNeedsReview] = useState(false);
  const [acknowledged, setAcknowledged] = useState<string>();
  const scopeHeading = useRef<HTMLHeadingElement>(null);
  const preview = useQuery({
    queryKey: ['withdrawal-preview', path],
    queryFn: () => api<Preview>(path + '/withdrawal-preview'),
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
  });
  const command = useReleaseCommand({
    key: 'dataocean:withdraw:' + session.userId + ':' + path,
    path: path + '/withdrawals',
    operationCode: 'releases.withdraw',
    parse: (body) => {
      // 重试采用原核对凭据与原因，不能静默套用新范围。
      const value = body as { reviewToken?: unknown; reason?: unknown };
      return typeof value.reviewToken === 'string' && typeof value.reason === 'string'
        ? { reviewToken: value.reviewToken, reason: value.reason }
        : undefined;
    },
    committed: async () => {
      // 整批可能跨多个绑定，所有实际列表和详情都重新读取。
      await Promise.all([
        cache.invalidateQueries({ queryKey: ['release-binding-detail'] }),
        cache.invalidateQueries({ queryKey: ['release-binding-controls'] }),
        cache.invalidateQueries({ queryKey: ['release-bindings'] }),
        cache.invalidateQueries({ queryKey: ['release-publication'] }),
        cache.invalidateQueries({ queryKey: ['knowledge-withdrawal'] }),
      ]);
      setOpen(false);
    },
    conflict: () => setNeedsReview(true),
  });

  /** 阅读实际新范围后才重新开放提交，原原因仍在。 */
  async function reviewAgain() {
    const result = await preview.refetch();
    if (result.isSuccess) {
      setAcknowledged(command.note);
      setNeedsReview(false);
      // 重新核对按钮随后消失，将键盘焦点交给更新后的清单。
      requestAnimationFrame(() => scopeHeading.current?.focus());
    }
  }

  /** 保留填好的原因和未知提交的原编号。 */
  function discard() {
    if (command.busy) return;
    if (!command.pending && form.getFieldValue('reason')?.trim()) {
      // 安全的默认操作是继续核对。
      modal.confirm({
        centered: true,
        title: '放弃已填写的撤下原因？',
        okText: '放弃填写',
        cancelText: '继续填写',
        focusable: { autoFocusButton: 'cancel' },
        onOk: () => setOpen(false),
      });
      return;
    }
    setOpen(false);
  }

  const canSubmit = Boolean(
    preview.data && !preview.isError && !preview.isFetching && !needsReview,
  );
  return (
    <Modal
      open={open}
      centered
      width={720}
      title="撤下知识及必要依赖"
      afterClose={close}
      focusable={{ focusTriggerAfterClose: false }}
      mask={{ closable: false }}
      onCancel={discard}
      closable={!command.busy}
      keyboard={!command.busy}
      footer={[
        <Button key="cancel" disabled={command.busy} onClick={discard}>
          取消
        </Button>,
        <Button
          key="submit"
          type="primary"
          danger
          loading={command.busy}
          disabled={!canSubmit && !command.pending}
          onClick={() => (command.pending ? command.resolve() : form.submit())}
        >
          {command.pending ? command.action : '确认撤下'}
        </Button>,
      ]}
    >
      {/* 明确整批后果，目标和引用方用每行独立展示。 */}
      <Alert showIcon type="warning" title="清单内知识将停止用于问数，正式版本和历史记录保留。" />
      <section className="knowledge-withdrawal-scope" aria-label="撤下范围核对">
        <h3 ref={scopeHeading} tabIndex={-1}>
          完整撤下范围
        </h3>
        <ReadState
          loading={preview.isPending}
          error={preview.error}
          retry={() => void reviewAgain()}
        >
          {preview.data && <WithdrawalRange members={preview.data.members} />}
        </ReadState>
      </section>
      {command.note && (needsReview || command.note !== acknowledged) && (
        <Alert
          showIcon
          type="warning"
          title={command.note}
          action={
            needsReview && (
              <Button onClick={() => void reviewAgain()} loading={preview.isFetching}>
                重新核对范围
              </Button>
            )
          }
        />
      )}
      <Form
        form={form}
        layout="vertical"
        className="knowledge-version-control-form"
        scrollToFirstError={{ focus: true }}
        onFinish={({ reason }) => {
          // 回调也使用用户看到的同一凭据和实际可提交状态。
          if (canSubmit && preview.data)
            command.submit({ reviewToken: preview.data.reviewToken, reason: reason.trim() });
        }}
      >
        <Form.Item
          label="撤下原因"
          name="reason"
          rules={[{ required: true, whitespace: true, message: '请填写撤下原因' }]}
        >
          <Input.TextArea rows={2} maxLength={300} showCount disabled={Boolean(command.pending)} />
        </Form.Item>
      </Form>
    </Modal>
  );
}

/** 已退出的版本仍可在详情查看实际批次、原因和原范围。 */
export function KnowledgeWithdrawalHistory({ path }: { path: string }) {
  const history = useQuery({
    queryKey: ['knowledge-withdrawal', path],
    queryFn: () => api<Withdrawal>(path + '/withdrawals'),
  });
  return (
    <section className="knowledge-build-section" aria-label="撤下记录">
      <h2>撤下记录</h2>
      <ReadState
        loading={history.isPending}
        error={history.error}
        retry={() => void history.refetch()}
      >
        {history.data && (
          <>
            {/* 当前停止使用的原因及当时确切退出的文档仍可核对。 */}
            <p>{history.data.reason}</p>
            <span className="muted">{displayTime(history.data.at)}</span>
            <WithdrawalRange members={history.data.members} />
          </>
        )}
      </ReadState>
    </section>
  );
}
