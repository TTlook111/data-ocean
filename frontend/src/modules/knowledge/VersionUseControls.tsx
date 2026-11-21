import { Alert, App, Button, Dropdown, Form, Input, Modal, Tag } from 'antd';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { type ReactNode, useState } from 'react';
import { api } from '../../shared/api';
import { displayTime } from '../../shared/Records';
import { allowed, useSession } from '../../shared/session';
import { KnowledgeWithdrawalDialog } from './KnowledgeWithdrawal';
import { useReleaseCommand } from './useReleaseCommand';

/** 暂停和解除的持久记录。 */
type Pause = {
  /** 暂停编号。 */
  id: string;
  /** 实际暂停原因。 */
  reason: string;
  /** 暂停提交时间。 */
  pausedAt: string;
  /** 解除时间，生效中为空。 */
  restoredAt: string | null;
  /** 风险消除依据。 */
  restoreReason: string | null;
  /** 最近验证的固定运行。 */
  runId: string | null;
  /** 最近验证实际采用的版本，限制回看入口。 */
  runBindingId: string | null;
};

/** 版本当前的使用限制及恢复状态。 */
export type VersionUse = {
  /** 尚有效的暂停。 */
  active: Pause | null;
  /** 当前绑定适用的隔离验证。 */
  recovery: {
    /** 当前真实状态。 */
    state: keyof typeof recoveryStateLabels;
    /** 实际运行的可回看入口。 */
    runId: string | null;
    /** 当前阻断原因。 */
    issue: string | null;
    /** 服务端允许发起新一轮。 */
    canRun: boolean;
  } | null;
  /** 按实际时间回看的限制记录。 */
  history: Pause[];
};

/** 隔离验证状态沿用原型术语。 */
export const recoveryStateLabels = {
  UNVERIFIED: '未验证',
  QUEUED: '待验证',
  RUNNING: '验证中',
  AWAITING_JUDGMENT: '待裁定',
  VALIDATION_FAILED: '验证未通过',
  VALIDATION_ERROR: '执行异常',
  RECOVERY_PASSED: '通过',
  EXPIRED: '已失效',
} as const;

/** 版本详情和验证页共用实际使用限制。 */
export function useVersionUse(path: string, enabled: boolean) {
  return useQuery({
    queryKey: ['release-binding-controls', path],
    enabled,
    queryFn: () => api<VersionUse>(path + '/controls'),
    refetchInterval: (query) =>
      ['QUEUED', 'RUNNING'].includes(query.state.data?.recovery?.state ?? '') ? 1200 : false,
  });
}

/** 版本条目就地呈现暂停范围及原因，正文仍可只读回看。 */
export function VersionPauseSummary({ title, data }: { title: string; data: VersionUse }) {
  return (
    data.active && (
      <section className="knowledge-build-section" aria-label="暂停范围与原因">
        <h2>暂停范围与原因</h2>
        <span>{title}</span>
        <p>{data.active.reason}</p>
      </section>
    )
  );
}

/** 恢复验证和处理记录承接上一版下方的原型区块。 */
export function VersionRecoverySummary({
  data,
  bindingId,
  openRun,
}: {
  data: VersionUse;
  /** 只链接适用于本页绑定的历史验证。 */
  bindingId: string;
  openRun: (id: string) => void;
}) {
  const recovery = data.recovery;
  const run = recovery?.runId;
  return (
    <>
      {/* 首次暂停也明确显示未验证。 */}
      {recovery && (
        <section className="knowledge-build-section" aria-label="恢复验证">
          <h2>恢复验证</h2>
          <div className="knowledge-version-recovery">
            <Tag
              color={
                recovery.state === 'RECOVERY_PASSED'
                  ? 'success'
                  : recovery.state === 'EXPIRED'
                    ? 'error'
                    : undefined
              }
            >
              {recoveryStateLabels[recovery.state]}
            </Tag>
            {run && <Button onClick={() => openRun(run)}>查看验证记录</Button>}
          </div>
          {recovery.issue && <Alert type="warning" showIcon title={recovery.issue} />}
        </section>
      )}
      {/* 历史审计保留原因和时间，并与当前限制分开。 */}
      {data.history.length > 0 && (
        <details className="knowledge-release-disclosure">
          <summary>版本与恢复记录</summary>
          <div className="knowledge-release-process-body">
            {data.history.map((item) => {
              // 恢复后也可回看当时的证据，其他绑定的运行留在各自详情。
              const historyRun = item.restoredAt && item.runBindingId === bindingId && item.runId;
              return (
                <section className="knowledge-build-section" key={item.id}>
                  <p>暂停知识 · {displayTime(item.pausedAt)}</p>
                  <p>{item.reason}</p>
                  {item.restoredAt && (
                    <>
                      <p>显式恢复知识 · {displayTime(item.restoredAt)}</p>
                      <p>{item.restoreReason}</p>
                    </>
                  )}
                  {historyRun && (
                    <div>
                      <Button onClick={() => openRun(historyRun)}>回看恢复验证</Button>
                    </div>
                  )}
                </section>
              );
            })}
          </div>
        </details>
      )}
    </>
  );
}

/** 发起当前暂停的隔离验证，完成接收再打开真实任务。 */
export function RecoveryStartButton({
  data,
  path,
  openRun,
}: {
  data: VersionUse;
  path: string;
  openRun: (run: string) => void;
}) {
  const session = useSession();
  const cache = useQueryClient();
  const recovery = data.recovery;
  const start = useReleaseCommand<Record<string, never>>({
    key: 'dataocean:recovery:' + session.userId + ':' + path + ':' + data.active?.id,
    path: path + '/pauses/' + data.active?.id + '/runs',
    operationCode: 'releases.test',
    parse: (body) => (Object.keys(body).length === 0 ? {} : undefined),
    committed: async (id) => {
      await cache.invalidateQueries({ queryKey: ['release-binding-controls', path] });
      openRun(id);
    },
    conflict: () => void cache.invalidateQueries({ queryKey: ['release-binding-controls', path] }),
  });
  if (!recovery || !allowed(session, 'releases.test') || !allowed(session, 'test-cases.view')) {
    return null;
  }
  return (
    <>
      {/* 未知提交继续使用同一命令编号核实。 */}
      {start.note && (
        <span role="status" className="muted">
          {start.note}
        </span>
      )}
      <Button
        id="start-version-recovery"
        type="primary"
        disabled={!recovery.canRun && !start.pending}
        loading={start.busy}
        onClick={() => (start.pending ? start.resolve() : start.submit({}))}
      >
        {start.pending
          ? start.action
          : recovery.state === 'UNVERIFIED'
            ? '运行恢复验证'
            : '重新运行恢复验证'}
      </Button>
    </>
  );
}

/** 版本底栏保留更多操作和本阶段的主按钮。 */
export function VersionControlActions({
  title,
  path,
  data,
  available,
  openRun,
  children,
}: {
  title: string;
  path: string;
  data?: VersionUse;
  available: boolean;
  openRun: (id: string) => void;
  /** 查阅文档与任务的原有次级动作。 */
  children: ReactNode;
}) {
  const session = useSession();
  const [dialog, setDialog] = useState<'pause' | 'restore' | 'withdraw'>();
  const canPause = data && available && !data.active && allowed(session, 'versions.pause');
  const canWithdraw = data && available && allowed(session, 'releases.withdraw');
  const actions = [
    ...(canPause ? [{ key: 'pause', label: '暂停知识', danger: true }] : []),
    ...(canWithdraw ? [{ key: 'withdraw', label: '撤下知识', danger: true }] : []),
  ];
  const passed = data?.recovery?.state === 'RECOVERY_PASSED';
  const restoreRun =
    passed && allowed(session, 'test-cases.view') && allowed(session, 'governance.allow')
      ? (data?.recovery?.runId ?? null)
      : null;

  /** 弹窗关完后优先归还原入口，阶段变化后接到新动作或页头。 */
  function closeDialog() {
    const original = dialog === 'restore' ? 'restore-version' : 'version-more';
    setDialog(undefined);
    requestAnimationFrame(() =>
      (
        document.getElementById(original) ??
        document.getElementById('start-version-recovery') ??
        document.getElementById('version-more') ??
        document.getElementById('effective-version-title')
      )?.focus({ preventScroll: true }),
    );
  }

  return (
    <>
      {actions.length > 0 && (
        <Dropdown
          trigger={['click']}
          placement="topRight"
          menu={{
            items: actions,
            onClick: ({ key }) => setDialog(key === 'withdraw' ? 'withdraw' : 'pause'),
          }}
        >
          <Button id="version-more">更多</Button>
        </Dropdown>
      )}
      {children}
      {restoreRun ? (
        <Button id="restore-version" type="primary" onClick={() => setDialog('restore')}>
          确认恢复
        </Button>
      ) : (
        !passed &&
        data?.active && (
          <RecoveryStartButton key={data.active.id} data={data} path={path} openRun={openRun} />
        )
      )}
      {dialog === 'withdraw' ? (
        <KnowledgeWithdrawalDialog path={path} close={closeDialog} />
      ) : (
        dialog && (
          <VersionControlDialog
            title={title}
            path={path}
            pause={data?.active ?? null}
            run={restoreRun}
            mode={dialog}
            close={closeDialog}
          />
        )
      )}
    </>
  );
}

/** 原型确认弹窗显示操作范围、后果及必填的人工依据。 */
function VersionControlDialog({
  title,
  path,
  pause,
  run,
  mode,
  close,
}: {
  title: string;
  path: string;
  pause: Pause | null;
  run: string | null;
  mode: 'pause' | 'restore';
  close: () => void;
}) {
  const [form] = Form.useForm<{ reason: string }>();
  const cache = useQueryClient();
  const session = useSession();
  const { modal } = App.useApp();
  const [open, setOpen] = useState(true);
  // 保留用户打开时核对的确切暂停与运行。
  const [review] = useState({ pause, run });
  const restoring = mode === 'restore';
  const command = useReleaseCommand({
    key:
      'dataocean:version-control:' +
      session.userId +
      ':' +
      path +
      ':' +
      mode +
      ':' +
      (review.pause?.id ?? ''),
    path: path + (restoring ? '/pauses/' + review.pause?.id + '/restore' : '/pauses'),
    operationCode: restoring ? 'governance.allow' : 'versions.pause',
    parse: (body) => {
      // 只恢复原命令的原因和确切验证编号。
      const input = body as { reason?: unknown; runId?: unknown };
      return typeof input.reason === 'string' && (!restoring || typeof input.runId === 'string')
        ? { reason: input.reason, runId: typeof input.runId === 'string' ? input.runId : undefined }
        : undefined;
    },
    committed: async () => {
      await refresh();
      setOpen(false);
    },
    conflict: () => void refresh(),
  });

  /** 取消和 Esc 走同一出口，已填写的说明可以继续编辑。 */
  function discard() {
    if (command.busy) return;
    if (form.getFieldValue('reason')?.trim() && !command.pending) {
      modal.confirm({
        title: '放弃已填写的说明？',
        okText: '放弃填写',
        cancelText: '继续填写',
        autoFocusButton: 'cancel',
        onOk: () => setOpen(false),
      });
      return;
    }
    setOpen(false);
  }

  /** 写入后让列表、版本和发布结果同时更新真实状态。 */
  async function refresh() {
    await Promise.all([
      cache.invalidateQueries({ queryKey: ['release-binding-controls', path] }),
      cache.invalidateQueries({ queryKey: ['release-binding-detail', path] }),
      cache.invalidateQueries({ queryKey: ['release-bindings'] }),
      cache.invalidateQueries({ queryKey: ['release-publication'] }),
    ]);
  }

  return (
    <Modal
      open={open}
      centered
      width={680}
      afterClose={close}
      focusable={{ focusTriggerAfterClose: false }}
      title={restoring ? '核对并恢复知识' : '暂停知识'}
      onCancel={discard}
      closable={!command.busy}
      keyboard={!command.busy}
      mask={{ closable: false }}
      footer={[
        <Button key="cancel" disabled={command.busy} onClick={discard}>
          取消
        </Button>,
        <Button
          key="submit"
          danger
          type="primary"
          loading={command.busy}
          onClick={() => (command.pending ? command.resolve() : form.submit())}
        >
          {command.pending ? command.action : restoring ? '确认恢复' : '确认暂停'}
        </Button>,
      ]}
    >
      {/* 危险操作的影响在输入和提交前展示，失败原位反馈。 */}
      <Alert
        type="warning"
        showIcon
        title={
          restoring
            ? '确认后，此范围将重新用于问数。提交时会重新核对版本和验证依据。'
            : '暂停后，受影响的知识将停止用于问数。'
        }
      />
      <Form
        form={form}
        layout="vertical"
        className="knowledge-version-control-form"
        scrollToFirstError={{ focus: true }}
        onFinish={({ reason }) =>
          command.submit({
            reason: reason.trim(),
            runId: restoring ? (review.run ?? undefined) : undefined,
          })
        }
      >
        <Form.Item label={restoring ? '恢复范围' : '暂停范围'}>
          <span>{title}</span>
          {restoring && review.pause && <p>{review.pause.reason}</p>}
        </Form.Item>
        <Form.Item
          name="reason"
          label={restoring ? '风险已消除的依据' : '暂停原因'}
          rules={[
            {
              required: true,
              whitespace: true,
              message: restoring ? '请填写风险已消除的依据' : '请填写暂停原因',
            },
          ]}
        >
          <Input.TextArea rows={3} maxLength={300} showCount disabled={Boolean(command.pending)} />
        </Form.Item>
      </Form>
      {command.note && (
        <Alert type={command.pending ? 'warning' : 'error'} showIcon title={command.note} />
      )}
    </Modal>
  );
}
