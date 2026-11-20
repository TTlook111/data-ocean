import { useEffect, useState } from 'react';
import {
  Alert,
  App,
  Button,
  Descriptions,
  Drawer,
  Empty,
  Form,
  Input,
  Pagination,
  Space,
  Tag,
} from 'antd';
import { useIsMutating, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useBeforeUnload, useBlocker } from 'react-router';
import { api, ApiError, mutation, queryString } from '../../shared/api';
import { readErrorMessage, ReadState } from '../../shared/Records';
import { allowed, useSession } from '../../shared/session';
import type { Page } from '../iam/types';
import { modelFailureLabels } from '../models/types';
import { GovernanceSourceDrawer } from './GovernanceSources';
import type { SourcePeek } from './GovernanceSources';
import type { Generation } from './types';

/**
 * 当前页签对应的实际发现节点。
 */
export type DiscoveryKind = 'RELATION' | 'METRIC';

/**
 * 当前候选的可编辑业务定义。
 */
type Definition = {
  /** 当前名称。 */
  name: string;
  /** 关联条件或含义。 */
  meaning: string;
  /** 完整规则。 */
  rule: string;
  /** 适用边界。 */
  usage: string;
};

/**
 * 当次发现保留的结构位置。
 */
type Structure = {
  /** 字段稳定标识。 */
  fieldId: string;
  /** 对象的固定修订。 */
  objectRevision: string;
  /** 字段固定修订。 */
  fieldRevision: string;
  /** 实际限定名。 */
  label: string;
  /** 采集类型。 */
  type: string;
};

/**
 * 候选与其可披露的实际证据。
 */
type Candidate = {
  /** 候选身份。 */
  id: string;
  /** 编辑并发版本。 */
  version: number;
  /** 最新编辑或审核状态。 */
  state: string;
  /** 固定结构快照。 */
  snapshotId: string;
  /** 可见范围内的当前业务内容和关联配对。 */
  definition: (Definition & { pairs: { left: string; right: string }[] }) | null;
  /** 原候选采用的确切结构和资料。 */
  basis: {
    /** 用到的同源字段。 */
    structures: Structure[];
    /** 原文仍从资料查看入口取用。 */
    notes: { noteId: string; revisionId: string; revision: number; title: string }[];
    /** 是否来自采集外键。 */
    foreignKey: boolean;
  };
};

/**
 * 页签、分页和候选切换沿用相同的离开确认。
 */
const discardDraft = {
  title: '有未保存的修改',
  content: '放弃本次修改？',
  okText: '放弃修改',
  cancelText: '继续编辑',
  focusable: { autoFocusButton: 'cancel' as const },
};

/**
 * 直接在关联、指标页签发现和办理实际候选。
 */
export function GovernanceDiscoveryPanel({
  kind,
  path,
  source,
}: {
  kind: DiscoveryKind;
  path: string;
  source: string;
}) {
  const session = useSession();
  const client = useQueryClient();
  const { message, modal } = App.useApp();
  const label = kind === 'RELATION' ? '关联' : '指标';
  const [request, setRequest] = useState<string>();
  const [selected, setSelected] = useState<string>();
  const [draft, setDraft] = useState<Candidate>();
  const [page, setPage] = useState(1);
  const key = ['governance-candidates', path, kind];
  const saveKey = ['governance-candidate-save', path];
  const saving = useIsMutating({ mutationKey: saveKey }) > 0;
  const list = useQuery({
    queryKey: [...key, page],
    queryFn: () =>
      api<Page<Candidate>>(path + '?' + queryString({ kind, pageNum: page, pageSize: 12 })),
  });
  const task = useQuery({
    queryKey: ['candidate-discovery', source, request],
    enabled: Boolean(request),
    queryFn: () => api<Generation>(`/governance/sources/${source}/generations/${request}`),
    refetchInterval: ({ state }) =>
      ['SUCCEEDED', 'FAILED'].includes(state.data?.state ?? '') ? false : 1000,
  });
  const terminal = task.data?.state;
  useEffect(() => {
    // 以持久任务结果触发列表回读，避免用模型响应直接覆盖编辑区。
    if (terminal === 'SUCCEEDED') {
      void client.invalidateQueries({ queryKey: ['governance-candidates', path, kind] });
    }
  }, [terminal, client, path, kind]);
  const launch = useMutation({
    mutationFn: (command: string) =>
      mutation<Generation & { targetId?: string }>(
        `${path}/discovery/${kind}`,
        'POST',
        undefined,
        'governance.suggest',
        command,
      ),
    onSuccess: (result) => {
      // 已接收和成功产出分别由实际请求与回读表达。
      setRequest(result.requestId ?? result.targetId);
    },
    onError: (error) => void message.error(readErrorMessage(error)),
  });
  const running =
    launch.isPending ||
    Boolean(request && !task.isError && !['FAILED', 'SUCCEEDED'].includes(terminal ?? ''));
  const items = list.data?.items ?? [];
  const latest = items.find((row) => row.id === (draft?.id ?? selected)) ?? items[0];
  const candidate = draft && latest?.id === draft.id && latest.definition ? draft : latest;
  const changed = candidate === draft && latest?.version !== draft?.version;
  const readError = list.error;
  const temporarilyUnavailable =
    list.data && readError && (!(readError instanceof ApiError) || readError.code >= 500000);

  /**
   * 列表内切换等待用户决定，正在提交时保留当前编辑位置。
   */
  async function leave(next: () => void) {
    if (saving || (draft && !(await modal.confirm(discardDraft)))) {
      // 继续编辑保留草稿和当前页。
      return;
    }
    setDraft(undefined);
    next();
  }

  /**
   * 先接收本次保存回执的实际版本，再刷新涉及该候选的列表。
   */
  async function update(saved: Candidate) {
    client.setQueriesData<Page<Candidate>>({ queryKey: key }, (current) => {
      // 回包迟于新版本时保留已读到的新记录。
      return (
        current && {
          ...current,
          items: current.items.map((row) =>
            row.id === saved.id && row.version <= saved.version ? saved : row,
          ),
        }
      );
    });
    await client.invalidateQueries({ queryKey: key });
  }

  return (
    <section className="gov-discovery">
      {/* 沿用原型页签内的发现入口，空状态也允许执行。 */}
      {allowed(session, 'governance.suggest') && (
        <div className="gov-discovery-toolbar">
          <Button
            type={items.length ? 'default' : 'primary'}
            loading={running}
            onClick={() => launch.mutate(crypto.randomUUID())}
          >
            发现 AI {label}
          </Button>
        </div>
      )}
      {task.error && <Alert type="error" title={readErrorMessage(task.error)} />}
      {terminal === 'FAILED' && (
        <Alert
          type="error"
          title={modelFailureLabels[task.data?.failureCode ?? ''] ?? '发现候选未完成'}
        />
      )}
      {/* 后台读取异常时保留可继续编辑的内容，明确的权限拒绝仍由读取状态处理。 */}
      {temporarilyUnavailable && <Alert type="warning" title={readErrorMessage(readError)} />}
      <ReadState
        error={temporarilyUnavailable ? null : readError}
        loading={list.isPending}
        retry={() => void list.refetch()}
      >
        {candidate ? (
          <CandidateEditor
            key={candidate.id + ':' + candidate.version}
            candidate={candidate}
            items={items}
            path={path}
            source={source}
            label={label}
            dirty={candidate === draft}
            changed={changed}
            onDraftChange={setDraft}
            saveKey={saveKey}
            choose={(id) => void leave(() => setSelected(id))}
            reload={() => void leave(() => undefined)}
            update={update}
          />
        ) : (
          <Empty
            image={Empty.PRESENTED_IMAGE_SIMPLE}
            description={
              terminal === 'SUCCEEDED' ? `当前依据未发现${label}候选` : `暂无${label}候选`
            }
          />
        )}
        {(list.data?.total ?? 0) > 12 && (
          <Pagination
            current={list.data?.pageNum ?? page}
            pageSize={12}
            total={list.data?.total}
            disabled={saving}
            onChange={(next) => void leave(() => setPage(next))}
            showSizeChanger={false}
          />
        )}
      </ReadState>
    </section>
  );
}

/**
 * 依据与可编辑定义同页呈现，保存不替代人工确认。
 */
function CandidateEditor({
  candidate,
  items,
  path,
  source,
  label,
  dirty,
  changed,
  onDraftChange,
  saveKey,
  choose,
  reload,
  update,
}: {
  candidate: Candidate;
  items: Candidate[];
  path: string;
  source: string;
  label: string;
  dirty: boolean;
  changed: boolean;
  onDraftChange: (draft?: Candidate) => void;
  saveKey: string[];
  choose: (id: string) => void;
  reload: () => void;
  update: (saved: Candidate) => Promise<void>;
}) {
  const session = useSession();
  const { modal, message } = App.useApp();
  const [form] = Form.useForm<Definition>();
  const [peek, setPeek] = useState<SourcePeek>();
  const [structure, setStructure] = useState(false);
  const [error, setError] = useState('');
  const edit = allowed(session, 'governance.edit');
  const confirm = allowed(session, 'governance.confirm');
  const save = useMutation({
    mutationKey: saveKey,
    mutationFn: ({ action, values }: { action: 'SAVE' | 'CONFIRM'; values: Definition }) =>
      api<Candidate>(`${path}/${candidate.id}`, {
        method: 'PUT',
        body: { ...values, action, version: candidate.version },
      }),
    onSuccess: async (saved, { action }) => {
      // 再次读取平台确认版本，保留当前候选。
      setError('');
      await update(saved);
      onDraftChange();
      void message.success(action === 'CONFIRM' ? '治理已确认' : '草稿已保存');
    },
    onError: (failure) => setError(readErrorMessage(failure)),
  });
  const blocker = useBlocker(dirty || save.isPending);
  useBeforeUnload((event) => {
    // 浏览器级离开也保护实际修改。
    if (dirty || save.isPending) event.preventDefault();
  });
  useEffect(() => {
    // 页签和返回操作都等候人工决定是否丢弃。
    if (blocker.state !== 'blocked') return;
    if (save.isPending) {
      blocker.reset();
      return;
    }
    const dialog = modal.confirm({
      ...discardDraft,
      onOk: () => blocker.proceed(),
      onCancel: () => blocker.reset(),
    });
    return () => dialog.destroy();
  }, [blocker, modal, save.isPending]);
  const definition = candidate.definition;
  const status: Record<string, string> = {
    CANDIDATE: '待确认',
    DRAFT: '草稿',
    CONFIRMED: '已确认',
    REVIEW: '待复核',
  };
  if (!definition) return <Alert type="warning" title="当前权限无法查看该候选依据" />;
  return (
    <div className={items.length > 1 ? 'gov-split' : 'gov-single'}>
      {/* 多候选显示同页导航，单条直接进入行内办理。 */}
      {items.length > 1 && (
        <nav className="gov-column-nav" aria-label={label + '候选'}>
          <div className="gov-column-list">
            {items.map((row) => (
              <button
                key={row.id}
                type="button"
                className="gov-column-button"
                aria-current={row.id === candidate.id}
                disabled={save.isPending}
                onClick={() => {
                  // 已选候选自身无需触发离开确认。
                  if (row.id !== candidate.id) choose(row.id);
                }}
              >
                {row.definition?.name ?? '受限候选'}
              </button>
            ))}
          </div>
        </nav>
      )}
      <div className="gov-content gov-discovery-editor">
        {/* 候选名称和当前状态定位在用来编辑的条目前。 */}
        <div className="gov-entry-title">
          <h2>{definition.name || label + '候选'}</h2>
          <Tag>{status[candidate.state] ?? candidate.state}</Tag>
        </div>
        <div className="gov-editor-layout">
          <div className="gov-editor-main">
            {/* 编辑沿用原型明示的关联或指标字段。 */}
            <Form
              form={form}
              layout="vertical"
              initialValues={definition}
              disabled={!edit || save.isPending}
              onValuesChange={() => {
                // 与当前修订比较，恢复原值不触发离开保护。
                const values = form.getFieldsValue();
                const modified = (['name', 'meaning', 'rule', 'usage'] as const).some(
                  (key) => values[key] !== definition[key],
                );
                onDraftChange(modified ? candidate : undefined);
              }}
            >
              <Form.Item label={label + '名称'} name="name">
                <Input maxLength={80} />
              </Form.Item>
              <Form.Item label={label === '关联' ? '关联条件' : '指标含义'} name="meaning">
                <Input.TextArea rows={2} maxLength={4000} />
              </Form.Item>
              <Form.Item
                label={label === '关联' ? '数量关系与完整键' : '公式与筛选条件'}
                name="rule"
              >
                <Input.TextArea rows={2} maxLength={4000} />
              </Form.Item>
              <Form.Item
                label={label === '关联' ? '适用条件与限制' : '单位与统计边界'}
                name="usage"
              >
                <Input.TextArea rows={2} maxLength={4000} />
              </Form.Item>
            </Form>
            {changed && (
              <Alert
                type="warning"
                title="候选已有新版本"
                action={<Button onClick={reload}>载入最新版本</Button>}
                showIcon
              />
            )}
            {error && <Alert type="error" title={error} showIcon />}
            <div className="gov-editor-actions">
              {edit && (
                <Button
                  disabled={save.isPending || changed}
                  onClick={() => save.mutate({ action: 'SAVE', values: form.getFieldsValue() })}
                >
                  保存草稿
                </Button>
              )}
              {confirm && (
                <Button
                  type="primary"
                  disabled={changed}
                  loading={save.isPending}
                  onClick={() => save.mutate({ action: 'CONFIRM', values: form.getFieldsValue() })}
                >
                  确认治理
                </Button>
              )}
            </div>
          </div>
          {/* 候选仍复用原证据与固定资料回看入口。 */}
          <aside className="gov-reference" aria-label="候选来源">
            <section className="gov-evidence gov-reference-evidence" aria-label="候选依据">
              <h3>候选依据</h3>
              <Space wrap>
                <Button type="link" onClick={() => setStructure(true)}>
                  {candidate.basis.foreignKey ? '查看采集外键' : '查看结构依据'}
                </Button>
                {candidate.basis.notes.map((note) => (
                  <Button
                    key={note.revisionId}
                    type="link"
                    onClick={() =>
                      setPeek({ kind: 'note', id: note.noteId, revision: note.revision })
                    }
                  >
                    {note.title} · 第 {note.revision} 版
                  </Button>
                ))}
              </Space>
            </section>
          </aside>
        </div>
      </div>
      {structure && (
        <Drawer open width={640} title="结构依据" onClose={() => setStructure(false)}>
          {/* 固定字段与采集时修订只读回看。 */}
          <Descriptions
            column={1}
            items={[
              { key: 'snapshot', label: '采集快照', children: candidate.snapshotId },
              ...candidate.basis.structures.map((ref) => ({
                key: ref.fieldId,
                label: ref.label,
                children: `${ref.type} · 对象修订 ${ref.objectRevision} · 字段修订 ${ref.fieldRevision}`,
              })),
            ]}
          />
        </Drawer>
      )}
      {peek && (
        <GovernanceSourceDrawer peek={peek} source={source} close={() => setPeek(undefined)} />
      )}
    </div>
  );
}
