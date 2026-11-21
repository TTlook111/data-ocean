import { Alert, App, Button, Form, Input, Modal, Select, Space, Table, Tag } from 'antd';
import { useMutation, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query';
import { useRef, useState } from 'react';
import { Link } from 'react-router';
import { api } from '../../shared/api';
import { displayTime, ReadState } from '../../shared/Records';
import { allowed, useSession } from '../../shared/session';
import type { Page } from '../iam/types';
import type { ReleaseTask } from './types';
import { useReleaseCommand } from './useReleaseCommand';

/** 一份实际参与本次整批办理的正式文档。 */
type BatchRow = {
  /** 原任务编号，表内行稳定。 */
  taskId: string;
  /** 发布时间冻结的文档名称。 */
  title: string;
  /** 当前取到的权威版本。 */
  currentBindingId: string | null;
  /** 真实生效修订号。 */
  currentRevision: number | null;
  /** 目标确切绑定，待发布时尚未分配。 */
  targetBindingId: string | null;
  /** 待发布或直接上一版修订号。 */
  targetRevision: number | null;
  /** 实际阻断原因。 */
  issue: string | null;
  /** 范围仍处于暂停。 */
  paused: boolean;
  /** 回看时保留问题版本的原绑定和修订。 */
  publishedBindingId?: string | null;
  publishedRevision?: number | null;
};

/** 只有完整检查通过才会签发批次核对号。 */
type BatchPreview = {
  /** 已实际核对的范围。 */
  rows: BatchRow[];
  /** 与当前范围、基础及充分权限一致。 */
  reviewToken: string | null;
};

/** 同一真实批次的发布、回退或待核实事实。 */
export type PublicationBatch = {
  /** 权威批次标识。 */
  id: string;
  /** 实际持久阶段。 */
  status: 'PUBLISH_RECONCILE' | 'PUBLISHED' | 'ROLLBACK_RECONCILE' | 'ROLLED_BACK';
  /** 完整目标及当前实际指针。 */
  rows: BatchRow[];
  /** 原回退审计与核实。 */
  rollback: {
    /** 实际办理者。 */
    actor: string;
    /** 确认时的风险核对依据。 */
    reason: string;
    /** 指针切换提交时间。 */
    at: string;
    /** 组合实际读回通过的时间。 */
    verifiedAt: string | null;
  } | null;
};

/** 由已审核的分份准备任务组成完整发布。 */
type Selection = { taskId: string; expectedVersion: number };

/** 同一治理来源的批次路径。 */
const batches = (source: string) => '/knowledge/sources/' + source + '/publication-batches';

/** 批次状态和各入口从权威接口同步刷新。 */
async function refreshBatch(cache: QueryClient) {
  await Promise.all(
    [
      'knowledge-batch',
      'knowledge-rollback-history',
      'release-publication',
      'release-build',
      'release-tasks',
      'release-binding-detail',
      'release-bindings',
      'release-binding-controls',
    ].map((key) => cache.invalidateQueries({ queryKey: [key] })),
  );
}

/** 同一布局让预检、确认和回看逐条比较修订。 */
function BatchRange({
  rows,
  previous,
  source,
}: {
  rows: BatchRow[];
  previous?: boolean;
  source?: string;
}) {
  return (
    <Table<BatchRow>
      rowKey="taskId"
      size="small"
      tableLayout="fixed"
      pagination={false}
      scroll={rows.length > 5 ? { y: 240 } : undefined}
      dataSource={rows}
      aria-label={previous ? '回退范围' : '发布范围'}
      columns={[
        { title: '文档', dataIndex: 'title' },
        ...(source && previous
          ? [
              {
                title: '原发布',
                width: 90,
                render: (_value: unknown, row: BatchRow) =>
                  row.publishedBindingId ? (
                    <Link
                      to={
                        '/releases/versions/' +
                        row.publishedBindingId +
                        '?source=' +
                        source +
                        '&tab=active'
                      }
                    >
                      r{row.publishedRevision}
                    </Link>
                  ) : (
                    '—'
                  ),
              },
            ]
          : []),
        {
          title: '当前生效',
          width: 112,
          render: (_, row) => (
            <Space size={4} wrap>
              {source && row.currentBindingId ? (
                <Link
                  to={
                    '/releases/versions/' +
                    row.currentBindingId +
                    '?source=' +
                    source +
                    '&tab=active'
                  }
                >
                  r{row.currentRevision}
                </Link>
              ) : row.currentRevision == null ? (
                '未发布'
              ) : (
                'r' + row.currentRevision
              )}
              {row.paused && <Tag color="warning">暂停</Tag>}
            </Space>
          ),
        },
        {
          title: previous ? '直接上一版' : '目标修订',
          width: 104,
          render: (_, row) => (row.targetRevision == null ? '—' : 'r' + row.targetRevision),
        },
        ...(!source
          ? [
              {
                title: '核对结果',
                width: 220,
                render: (_value: unknown, row: BatchRow) =>
                  row.issue ?? <Tag color="success">通过</Tag>,
              },
            ]
          : []),
      ]}
    />
  );
}

/** 在发布任务和版本详情回看本批实际状态及回退审计。 */
function BatchRecord({ source, data }: { source: string; data: PublicationBatch }) {
  const labels = {
    PUBLISHED: '已发布',
    PUBLISH_RECONCILE: '发布结果待核实',
    ROLLED_BACK: '已回退',
    ROLLBACK_RECONCILE: '回退结果待核实',
  };
  const pending = data.status.endsWith('RECONCILE');
  return (
    <div className="knowledge-build-section">
      {/* 阶段以服务端核实结果为准。 */}
      <div className="knowledge-version-recovery">
        <Tag color={pending ? 'warning' : 'success'}>{labels[data.status]}</Tag>
        <span className="muted">发布批次 {data.id}</span>
      </div>
      <BatchRange rows={data.rows} previous={Boolean(data.rollback)} source={source} />
      {data.rollback && (
        <div className="knowledge-build-section" aria-label="实际回退记录">
          <p>{data.rollback.reason}</p>
          <span className="muted">
            操作人 {data.rollback.actor} · {displayTime(data.rollback.at)}
            {data.rollback.verifiedAt && ' · 核实于 ' + displayTime(data.rollback.verifiedAt)}
          </span>
        </div>
      )}
      {pending && <ReconcileBatch key={data.status} source={source} data={data} />}
    </div>
  );
}

/** 按原批次核实，不重新请求切换。 */
function ReconcileBatch({ source, data }: { source: string; data: PublicationBatch }) {
  const session = useSession();
  const cache = useQueryClient();
  const rollback = data.status === 'ROLLBACK_RECONCILE';
  const operation = rollback ? 'versions.rollback' : 'releases.publish';
  const command = useReleaseCommand<Record<string, never>>({
    key: 'dataocean:batch-reconcile:' + session.userId + ':' + data.id + ':' + operation,
    path:
      batches(source) +
      '/' +
      data.id +
      (rollback ? '/rollback-reconciliations' : '/reconciliations'),
    operationCode: operation,
    parse: (body) => (Object.keys(body).length === 0 ? {} : undefined),
    committed: () => refreshBatch(cache),
    conflict: () => void refreshBatch(cache),
  });
  return (
    allowed(session, operation) && (
      <Space wrap>
        {command.note && <span role="status">{command.note}</span>}
        <Button
          type="primary"
          loading={command.busy}
          onClick={() => (command.pending ? command.resolve() : command.submit({}))}
        >
          {command.pending ? command.action : '核实实际结果'}
        </Button>
      </Space>
    )
  );
}

/** 正式发布后显示其原批次的当前实际状态。 */
export function PublicationBatchResult({ source, id }: { source: string; id: string }) {
  const result = useQuery({
    queryKey: ['knowledge-batch', source, id],
    queryFn: () => api<PublicationBatch>(batches(source) + '/' + id),
  });
  return (
    <ReadState loading={result.isPending} error={result.error} retry={() => void result.refetch()}>
      {result.data && <BatchRecord source={source} data={result.data} />}
    </ReadState>
  );
}

/** 回退历史在问题版本和恢复版本都可回看。 */
export function RollbackHistory({ path, source }: { path: string; source: string }) {
  const result = useQuery({
    queryKey: ['knowledge-rollback-history', path],
    queryFn: () => api<PublicationBatch | null>(path + '/rollback-history'),
  });
  return result.data ? (
    <section className="knowledge-build-section" aria-label="整批回退记录">
      <h2>整批回退记录</h2>
      <BatchRecord source={source} data={result.data} />
    </section>
  ) : result.error ? (
    <ReadState loading={false} error={result.error} retry={() => void result.refetch()}>
      {null}
    </ReadState>
  ) : null;
}

/** 拥有现行权限的操作者确认回退已核对的完整上一版组合。 */
export function BatchRollbackDialog({
  source,
  id,
  close,
}: {
  source: string;
  id: string;
  close: () => void;
}) {
  const session = useSession();
  const cache = useQueryClient();
  const { modal } = App.useApp();
  const [form] = Form.useForm<{ reason: string }>();
  const [open, setOpen] = useState(true);
  const [submitted, setSubmitted] = useState(false);
  const [changed, setChanged] = useState(false);
  const [acknowledged, setAcknowledged] = useState<string>();
  const heading = useRef<HTMLHeadingElement>(null);
  const path = batches(source) + '/' + id;
  const preview = useQuery({
    queryKey: ['knowledge-rollback-preview', source, id],
    queryFn: () => api<BatchPreview>(path + '/rollback-preview'),
    refetchOnMount: 'always',
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
    enabled: !submitted,
  });
  const command = useReleaseCommand({
    key: 'dataocean:rollback:' + session.userId + ':' + path,
    path: path + '/rollback',
    operationCode: 'versions.rollback',
    parse: (body) => readConfirmation(body),
    committed: async () => {
      setSubmitted(true);
      await refreshBatch(cache);
    },
    conflict: () => setChanged(true),
  });
  const token =
    !changed && !preview.isError && !preview.isFetching ? preview.data?.reviewToken : null;

  /** 重新核对全部范围保留当前填好的原因。 */
  async function checkAgain() {
    const result = await preview.refetch();
    if (result.isSuccess) {
      setAcknowledged(command.note);
      setChanged(false);
      requestAnimationFrame(() => heading.current?.focus());
    }
  }

  /** 只保护用户实际填写过的依据。 */
  function cancel() {
    if (command.busy) return;
    if (!submitted && !command.pending && form.getFieldValue('reason')?.trim()) {
      modal.confirm({
        centered: true,
        title: '放弃已填写的回退原因？',
        okText: '放弃填写',
        cancelText: '继续核对',
        focusable: { autoFocusButton: 'cancel' },
        onOk: () => setOpen(false),
      });
      return;
    }
    setOpen(false);
  }

  return (
    <Modal
      open={open}
      centered
      width={800}
      title={submitted ? '整批回退结果' : '整批回退预检'}
      onCancel={cancel}
      afterClose={close}
      focusable={{ focusTriggerAfterClose: false }}
      mask={{ closable: false }}
      closable={!command.busy}
      keyboard={!command.busy}
      footer={[
        <Button key="cancel" disabled={command.busy} onClick={cancel}>
          {submitted ? '关闭' : '取消'}
        </Button>,
        !submitted && (
          <Button
            key="submit"
            danger
            type="primary"
            loading={command.busy}
            disabled={!token && !command.pending}
            onClick={() => (command.pending ? command.resolve() : form.submit())}
          >
            {command.pending ? command.action : '确认整批回退'}
          </Button>
        ),
      ]}
    >
      {submitted ? (
        <PublicationBatchResult source={source} id={id} />
      ) : (
        <>
          {/* 原型的居中确认区保留业务影响与整批范围。 */}
          <Alert
            showIcon
            type="warning"
            title="整批切换到直接上一版，现有权限、暂停和禁止状态保留。"
          />
          <section className="knowledge-withdrawal-scope" aria-label="完整回退范围">
            <h3 ref={heading} tabIndex={-1}>
              完整回退范围
            </h3>
            <ReadState
              loading={preview.isPending}
              error={preview.error}
              retry={() => void checkAgain()}
            >
              {preview.data && <BatchRange rows={preview.data.rows} previous />}
            </ReadState>
          </section>
          {command.note && (changed || command.note !== acknowledged) && (
            <Alert
              type="warning"
              showIcon
              title={command.note}
              action={
                changed && (
                  <Button loading={preview.isFetching} onClick={() => void checkAgain()}>
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
              if (token) command.submit({ reason: reason.trim(), reviewToken: token });
            }}
          >
            <Form.Item
              name="reason"
              label="回退原因"
              rules={[{ required: true, whitespace: true, message: '请填写回退原因' }]}
            >
              <Input.TextArea
                rows={2}
                maxLength={300}
                showCount
                disabled={Boolean(command.pending)}
              />
            </Form.Item>
          </Form>
        </>
      )}
    </Modal>
  );
}

/** 原命令恢复时保留确切核对凭据与已确认回退原因。 */
function readConfirmation(body: object) {
  const value = body as { reviewToken?: unknown; reason?: unknown };
  return typeof value.reviewToken === 'string' && typeof value.reason === 'string'
    ? { reviewToken: value.reviewToken, reason: value.reason }
    : undefined;
}

/** 把各自验证完成的准备任务核对为一次多文档正式发布。 */
export function BatchPublishDialog({
  source,
  task,
  close,
}: {
  source: string;
  task: ReleaseTask;
  close: () => void;
}) {
  const session = useSession();
  const cache = useQueryClient();
  const [open, setOpen] = useState(true);
  const [selected, setSelected] = useState<string[]>([]);
  const [pickerOpen, setPickerOpen] = useState(false);
  const [reviewed, setReviewed] = useState<{ members: Selection[]; preview: BatchPreview }>();
  const [result, setResult] = useState<string>();
  const path = batches(source);
  const eligible = useQuery({
    queryKey: ['release-tasks', source, 'ready-for-batch'],
    queryFn: () =>
      api<Page<ReleaseTask>>(
        '/knowledge/sources/' + source + '/releases?status=READY_TO_PUBLISH&pageSize=100',
      ),
    enabled: !result,
  });
  const others = eligible.data?.items.filter((row) => row.documentId !== task.documentId) ?? [];
  const preview = useMutation({
    mutationFn: (members: Selection[]) =>
      api<BatchPreview>(path + '/impact-preview', { method: 'POST', body: { members } }),
    onSuccess: (checked, members) => setReviewed({ members, preview: checked }),
  });
  const command = useReleaseCommand({
    key: 'dataocean:publish-batch:' + session.userId + ':' + source + ':' + task.id,
    path,
    operationCode: 'releases.publish',
    parse: (body) => readBatchConfirmation(body),
    committed: async (id) => {
      setResult(id);
      await refreshBatch(cache);
    },
    conflict: () => setReviewed(undefined),
  });

  /** 明确所读到的任务版本，预检和提交共用本次确切选择。 */
  function check() {
    const members = [task, ...others.filter((row) => selected.includes(row.id))].map((row) => ({
      taskId: row.id,
      expectedVersion: row.version,
    }));
    if (members.length > 1) preview.mutate(members);
  }

  /** 未明确提交结果时仅允许核实原操作。 */
  function submit() {
    if (command.pending) command.resolve();
    else if (reviewed?.preview.reviewToken) {
      command.submit({ members: reviewed.members, reviewToken: reviewed.preview.reviewToken });
    }
  }

  return (
    <Modal
      open={open}
      centered
      width={800}
      title={result ? '整批发布结果' : '确认多文档发布'}
      mask={{ closable: false }}
      closable={!command.busy}
      keyboard={!command.busy}
      onCancel={() => setOpen(false)}
      afterClose={close}
      footer={[
        <Button key="cancel" disabled={command.busy} onClick={() => setOpen(false)}>
          {result ? '关闭' : '取消'}
        </Button>,
        !result && (
          <Button
            key="confirm"
            type="primary"
            loading={command.busy || preview.isPending}
            disabled={
              !command.pending &&
              (selected.length === 0 || Boolean(reviewed && !reviewed.preview.reviewToken))
            }
            onClick={() => (command.pending || reviewed ? submit() : check())}
          >
            {command.pending ? command.action : reviewed ? '确认整批发布' : '核对发布范围'}
          </Button>
        ),
      ]}
    >
      {result ? (
        <PublicationBatchResult source={source} id={result} />
      ) : (
        <div className="knowledge-build-section">
          {/* 保留任务定位和完整范围确认，不修改既有候选资料。 */}
          <div>
            <strong>本任务</strong>
            <p>{task.title}</p>
          </div>
          <label htmlFor="additional-publications">同批发布文档</label>
          <Select
            id="additional-publications"
            mode="multiple"
            showSearch
            optionFilterProp="label"
            open={pickerOpen}
            onOpenChange={setPickerOpen}
            maxCount={19}
            placeholder="选择其他已通过验证的任务"
            loading={eligible.isPending}
            disabled={Boolean(command.pending) || preview.isPending}
            value={selected}
            options={others.map((row) => ({ value: row.id, label: row.title + ' · ' + row.id }))}
            onChange={(ids) => {
              // 选中后收起浮层，确认区始终可以继续键盘或鼠标操作。
              if (ids.length > selected.length) setPickerOpen(false);
              setSelected(ids);
              setReviewed(undefined);
            }}
          />
          {eligible.error && <Alert type="error" showIcon title={eligible.error.message} />}
          {preview.error && <Alert type="error" showIcon title={preview.error.message} />}
          {reviewed && <BatchRange rows={reviewed.preview.rows} />}
          {reviewed && !reviewed.preview.reviewToken && (
            <Button onClick={check} loading={preview.isPending}>
              重新核对范围
            </Button>
          )}
          {command.note && <Alert type="warning" showIcon title={command.note} />}
        </div>
      )}
    </Modal>
  );
}

/** 只用原批次任务版本和凭据重放未知提交。 */
function readBatchConfirmation(body: object) {
  const value = body as { members?: Selection[]; reviewToken?: unknown };
  if (
    typeof value.reviewToken !== 'string' ||
    !Array.isArray(value.members) ||
    value.members.length < 2 ||
    value.members.some(
      (row) => typeof row?.taskId !== 'string' || typeof row.expectedVersion !== 'number',
    )
  )
    return undefined;
  return { members: value.members, reviewToken: value.reviewToken };
}
