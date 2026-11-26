import { useEffect, useRef, useState } from 'react';
import {
  Alert,
  App,
  Button,
  Checkbox,
  DatePicker,
  Descriptions,
  Drawer,
  Empty,
  Form,
  Input,
  Pagination,
  Select,
  Space,
  Tag,
} from 'antd';
import dayjs, { type Dayjs } from 'dayjs';
import { useIsMutating, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useBeforeUnload, useBlocker } from 'react-router';
import { api, ApiError, mutation, queryString } from '../../shared/api';
import { readErrorMessage, ReadState } from '../../shared/Records';
import { allowed, useSession } from '../../shared/session';
import type { Page } from '../iam/types';
import { modelFailureLabels } from '../models/types';
import {
  GovernanceDependencyDialog,
  type Dependencies,
  type DependencyField,
} from './GovernanceDependencyDialog';
import { GovernanceSourceDrawer } from './GovernanceSources';
import type { SourcePeek } from './GovernanceSources';
import { MetricRulesFields, type FixedFilterMode, type MetricRules } from './MetricRulesFields';
import { useGenerationTask } from './useGenerationTask';

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
  /**
   * 当前指标是否指定为适用条件内的默认口径。
   */
  defaultMetric?: boolean;
  /**
   * 当前提取或人工核对的指标执行口径。
   */
  metricRules?: MetricRules | null;
  /**
   * 关系确认的含端点日期窗口。
   */
  relationWindow?: {
    /**
     * 不限日期或按业务日期范围适用。
     */
    mode?: 'NONE' | 'DATE_RANGE' | null;
    /**
     * 开放端点留空。
     */
    validFrom?: string | null;
    validThrough?: string | null;
  } | null;
};

/**
 * 固定筛选选择状态只用于区分未审核和确认无条件，不进入后端定义。
 */
type FormDefinition = Definition & { fixedFilterMode?: FixedFilterMode };

/**
 * 只提交当前选定模式使用的可执行字段，未确认的固定条件保留为空。
 */
function metricRules(values: FormDefinition): MetricRules {
  const rules = values.metricRules;
  return {
    dateMode: rules?.dateMode ?? null,
    dateFieldId: rules?.dateMode === 'BUSINESS_DAY' ? (rules.dateFieldId ?? null) : null,
    fixedFilters:
      values.fixedFilterMode === 'NONE'
        ? []
        : values.fixedFilterMode === 'FIELDS' && rules?.fixedFilters?.length
          ? rules.fixedFilters
          : null,
    unitMode: rules?.unitMode ?? null,
    unit: rules?.unitMode === 'FIXED' ? (rules.unit ?? null) : null,
    unitFieldId: rules?.unitMode === 'COLUMN' ? (rules.unitFieldId ?? null) : null,
  };
}

/**
 * 输入与保存都沿用同一日期类型，未选择的模式保持待确认。
 */
function relationWindow(values: FormDefinition): Definition['relationWindow'] {
  const window = values.relationWindow;
  if (!window) return null;
  return {
    mode: window.mode ?? null,
    validFrom: window.mode === 'DATE_RANGE' ? (window.validFrom ?? null) : null,
    validThrough: window.mode === 'DATE_RANGE' ? (window.validThrough ?? null) : null,
  };
}

/**
 * 使用已有表单状态保存 ISO 日期，关联窗口支持开放起止端点。
 */
function RelationWindowFields() {
  const mode = Form.useWatch(['relationWindow', 'mode']);
  return (
    <>
      {/* 确认人在同一治理表单选择能用于查询的业务日期规则。 */}
      <Form.Item label="日期适用期" name={['relationWindow', 'mode']}>
        <Select
          placeholder="请选择日期适用期"
          options={[
            { value: 'NONE', label: '不限日期' },
            { value: 'DATE_RANGE', label: '指定日期范围' },
          ]}
        />
      </Form.Item>
      {mode === 'DATE_RANGE' && (
        <Space wrap>
          {/* 起止日期可以分别确认，字段标签明确包含边界。 */}
          {(
            [
              ['validFrom', '开始日期（含）'],
              ['validThrough', '结束日期（含）'],
            ] as const
          ).map(([field, label]) => (
            <Form.Item
              key={field}
              label={label}
              name={['relationWindow', field]}
              getValueProps={(value?: string) => ({ value: value ? dayjs(value) : null })}
              normalize={(value: Dayjs | null) => value?.format('YYYY-MM-DD') ?? null}
            >
              <DatePicker format="YYYY-MM-DD" />
            </Form.Item>
          ))}
        </Space>
      )}
    </>
  );
}

/**
 * 当次发现保留的结构位置。
 */
type Structure = {
  /**
   * 字段所属的稳定表编号。
   */
  objectId: string;
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
export type Candidate = {
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
  canCreate,
  suggestReason,
  initialCandidate,
  initialPage = 1,
}: {
  kind: DiscoveryKind;
  path: string;
  source: string;
  canCreate?: boolean;
  suggestReason?: string | null;
  initialCandidate?: string;
  initialPage?: number;
}) {
  const session = useSession();
  const client = useQueryClient();
  const { message, modal } = App.useApp();
  const label = kind === 'RELATION' ? '关联' : '指标';
  const [request, setRequest] = useState<string>();
  const [selected, setSelected] = useState(initialCandidate);
  const [draft, setDraft] = useState<Candidate>();
  const [newCandidate, setNewCandidate] = useState<Candidate>();
  const [page, setPage] = useState(Math.max(1, Math.trunc(initialPage)));
  const key = ['governance-candidates', path, kind];
  const saveKey = ['governance-candidate-save', path];
  const saving = useIsMutating({ mutationKey: saveKey }) > 0;
  const list = useQuery({
    queryKey: [...key, page],
    queryFn: () =>
      api<Page<Candidate>>(path + '?' + queryString({ kind, pageNum: page, pageSize: 12 })),
  });
  const task = useGenerationTask(source, request);
  const terminal = task.data?.state;
  useEffect(() => {
    // 以持久任务结果触发列表回读，避免用模型响应直接覆盖编辑区。
    if (terminal === 'SUCCEEDED') {
      void client.invalidateQueries({ queryKey: ['governance-candidates', path, kind] });
    }
  }, [terminal, client, path, kind]);
  const launch = useMutation({
    mutationFn: (command: string) =>
      mutation(`${path}/discovery/${kind}`, 'POST', undefined, 'governance.suggest', command),
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
  const candidate =
    newCandidate ?? (draft && latest?.id === draft.id && latest.definition ? draft : latest);
  const changed = Boolean(
    !newCandidate && candidate === draft && latest?.version !== draft?.version,
  );
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
    setNewCandidate(undefined);
    next();
  }

  /**
   * 打开空白编辑区，第一次保存前只保留本地草稿。
   */
  function begin() {
    void leave(() =>
      setNewCandidate({
        id: '',
        version: 0,
        state: 'DRAFT',
        snapshotId: '',
        definition: { name: '', meaning: '', rule: '', usage: '', defaultMetric: false, pairs: [] },
        basis: { structures: [], notes: [], foreignKey: false },
      }),
    );
  }

  /**
   * 创建后回读候选列表，最新候选在首页。
   */
  async function created(id: string) {
    setPage(1);
    setSelected(id);
    await client.invalidateQueries({ queryKey: key });
    setNewCandidate(undefined);
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
      {/* 手工填写和 AI 发现都从当前页签进入。 */}
      {(canCreate || allowed(session, 'governance.suggest')) && (
        <Space className="gov-discovery-toolbar" wrap>
          {canCreate && (
            <Button type="primary" disabled={saving || Boolean(newCandidate)} onClick={begin}>
              新增{label}
            </Button>
          )}
          {allowed(session, 'governance.suggest') && (
            <Button
              disabled={Boolean(suggestReason)}
              loading={running}
              onClick={() => launch.mutate(crypto.randomUUID())}
            >
              发现 AI {label}
            </Button>
          )}
        </Space>
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
            kind={kind}
            path={path}
            source={source}
            label={label}
            dirty={candidate === draft}
            changed={changed}
            onDraftChange={setDraft}
            saveKey={saveKey}
            choose={(id) => void leave(() => setSelected(id))}
            reload={() => void leave(() => undefined)}
            created={created}
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
  kind,
  path,
  source,
  label,
  dirty,
  changed,
  onDraftChange,
  saveKey,
  choose,
  reload,
  created,
  update,
}: {
  candidate: Candidate;
  items: Candidate[];
  kind: DiscoveryKind;
  path: string;
  source: string;
  label: string;
  dirty: boolean;
  changed: boolean;
  onDraftChange: (draft?: Candidate) => void;
  saveKey: string[];
  choose: (id: string) => void;
  reload: () => void;
  created: (id: string) => Promise<void>;
  update: (saved: Candidate) => Promise<void>;
}) {
  const session = useSession();
  const { modal, message } = App.useApp();
  const [form] = Form.useForm<FormDefinition>();
  const [peek, setPeek] = useState<SourcePeek>();
  const [structure, setStructure] = useState(false);
  const [dependenciesOpen, setDependenciesOpen] = useState(false);
  const [dependencyEdit, setDependencyEdit] = useState<Dependencies>();
  const [error, setError] = useState('');
  const createCommand = useRef<{ payload: string; id: string }>(undefined);
  const creating = !candidate.id;
  const edit = allowed(session, 'governance.edit');
  const confirm = allowed(session, 'governance.confirm');
  const save = useMutation({
    mutationKey: saveKey,
    mutationFn: async ({
      action,
      values,
    }: {
      action: 'SAVE' | 'CONFIRM' | 'REJECT';
      values?: FormDefinition;
    }) => {
      // 只有明确修改才提交新的技术依赖，拒绝只保存审核结论。
      const dependencies =
        dependencyEdit && action !== 'REJECT'
          ? {
              pairs:
                label === '关联'
                  ? dependencyEdit.pairs.map(({ left, right }) => ({
                      left: left!.target,
                      right: right!.target,
                    }))
                  : undefined,
              fields: dependencyEdit.fields.map(({ target }) => target),
            }
          : undefined;
      const body = {
        name: values?.name,
        meaning: values?.meaning,
        rule: values?.rule,
        usage: values?.usage,
        defaultMetric: values?.defaultMetric,
        ...dependencies,
        metricRules: kind === 'METRIC' && values ? metricRules(values) : undefined,
        relationWindow: kind === 'RELATION' && values ? relationWindow(values) : undefined,
        action,
      };
      if (creating) {
        // 同样的人工内容重试时沿用原命令，避免重复建立候选。
        const payload = JSON.stringify(body);
        if (createCommand.current?.payload !== payload) {
          createCommand.current = { payload, id: crypto.randomUUID() };
        }
        const result = await mutation(
          path + '?' + queryString({ kind }),
          'POST',
          body,
          'governance.edit',
          createCommand.current.id,
        );
        if (!result.targetId) {
          // 创建完成后的回读需要确切编号。
          throw new Error('草稿已提交，暂时无法定位记录');
        }
        return { createdId: result.targetId };
      }
      const saved = await api<Candidate>(path + '/' + candidate.id, {
        method: 'PUT',
        body: { ...body, version: candidate.version },
      });
      return { saved };
    },
    onSuccess: async (result, { action }) => {
      // 再次读取平台确认版本，保留当前候选。
      setError('');
      if (result.saved) await update(result.saved);
      else await created(result.createdId);
      setDependencyEdit(undefined);
      onDraftChange();
      void message.success(
        action === 'CONFIRM' ? '治理已确认' : action === 'REJECT' ? '已拒绝关联' : '草稿已保存',
      );
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
    REJECTED: '已拒绝',
  };
  if (!definition) return <Alert type="warning" title="当前权限无法查看该候选依据" />;
  const initial: FormDefinition = {
    ...definition,
    fixedFilterMode:
      definition.metricRules?.fixedFilters == null
        ? undefined
        : definition.metricRules.fixedFilters.length
          ? 'FIELDS'
          : 'NONE',
  };
  const fields = new Map(
    candidate.basis.structures.map((ref) => [
      ref.fieldId,
      {
        target: { kind: 'COLUMN', objectId: ref.objectId, targetId: ref.fieldId },
        label: ref.label,
      } satisfies DependencyField,
    ]),
  );
  const pairs = definition.pairs ?? [];
  const pairIds = new Set(pairs.flatMap(({ left, right }) => [left, right]));
  const dependencies = dependencyEdit ?? {
    pairs: pairs.map(({ left, right }) => ({ left: fields.get(left), right: fields.get(right) })),
    fields: [...fields.entries()].filter(([id]) => !pairIds.has(id)).map(([, field]) => field),
  };
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
      <div className="gov-content gov-edit-content gov-discovery-editor">
        {/* 候选名称和当前状态定位在用来编辑的条目前。 */}
        <div className="gov-entry-title">
          <h2>{creating ? '新增' + label : definition.name || label + '候选'}</h2>
          <Tag>{status[candidate.state] ?? candidate.state}</Tag>
          {definition.defaultMetric && candidate.state === 'CONFIRMED' && (
            <Tag color="blue">默认口径</Tag>
          )}
        </div>
        <div className="gov-editor-layout">
          <div className="gov-editor-main">
            {/* 编辑沿用原型明示的关联或指标字段。 */}
            <Form
              form={form}
              layout="vertical"
              initialValues={initial}
              disabled={!edit || save.isPending}
              onValuesChange={() => {
                // 与当前修订比较，恢复原值不触发离开保护。
                const values = form.getFieldsValue();
                const modified =
                  Boolean(dependencyEdit) ||
                  (kind === 'METRIC' &&
                    JSON.stringify(metricRules(values)) !== JSON.stringify(metricRules(initial))) ||
                  (kind === 'RELATION' &&
                    JSON.stringify(relationWindow(values)) !==
                      JSON.stringify(relationWindow(initial))) ||
                  (['name', 'meaning', 'rule', 'usage', 'defaultMetric'] as const).some(
                    (key) => values[key] !== definition[key],
                  );
                onDraftChange(modified ? candidate : undefined);
              }}
            >
              <Form.Item label={label + '名称'} name="name">
                <Input autoFocus={creating} maxLength={80} />
              </Form.Item>
              <Form.Item label={label === '关联' ? '关联条件' : '指标含义'} name="meaning">
                <Input.TextArea rows={2} maxLength={4000} />
              </Form.Item>
              <Form.Item label={label === '关联' ? '数量关系与完整键' : '计算公式'} name="rule">
                <Input.TextArea rows={2} maxLength={4000} />
              </Form.Item>
              <Form.Item
                label={label === '关联' ? '适用条件与限制' : '单位与统计边界'}
                name="usage"
              >
                <Input.TextArea rows={2} maxLength={4000} />
              </Form.Item>
              {/* 关联说明之外，独立确认进入查询编译器的日期规则。 */}
              {kind === 'RELATION' && <RelationWindowFields />}
              {label === '指标' && (
                <>
                  {/* 治理人员在原表单确认 AI 提取的口径，可从已选依赖重新定字段和值。 */}
                  <MetricRulesFields
                    dependencies={dependencies.fields}
                    disabled={!edit || save.isPending}
                  />
                  <Form.Item name="defaultMetric" valuePropName="checked">
                    <Checkbox>设为适用条件下的默认口径</Checkbox>
                  </Form.Item>
                </>
              )}
            </Form>
            {/* 技术依赖与业务文字分别核对，确认前可明确调整。 */}
            <section className="gov-dependency-summary" aria-label="技术依赖">
              <div>
                <strong>技术依赖</strong>
                {edit && (
                  <Button
                    type="link"
                    disabled={save.isPending}
                    onClick={() => setDependenciesOpen(true)}
                  >
                    调整依赖
                  </Button>
                )}
              </div>
              {dependencies.pairs.map(({ left, right }, index) => (
                <p key={index}>
                  {left?.label} → {right?.label}
                </p>
              ))}
              {dependencies.fields.length > 0 && (
                <p>{dependencies.fields.map(({ label: name }) => name).join('、')}</p>
              )}
            </section>
            {changed && (
              <Alert
                type="warning"
                title="候选已有新版本"
                action={<Button onClick={reload}>载入最新版本</Button>}
                showIcon
              />
            )}
            {error && <Alert type="error" title={error} showIcon />}
          </div>
          {/* 候选仍复用原证据与固定资料回看入口。 */}
          {(candidate.basis.structures.length > 0 || candidate.basis.notes.length > 0) && (
            <aside className="gov-reference" aria-label="候选来源">
              <section className="gov-evidence gov-reference-evidence" aria-label="候选依据">
                <h3>候选依据</h3>
                <Space wrap>
                  {candidate.basis.structures.length > 0 && (
                    <Button type="link" onClick={() => setStructure(true)}>
                      {candidate.basis.foreignKey ? '查看采集外键' : '查看结构依据'}
                    </Button>
                  )}
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
          )}
        </div>
        <div className="gov-editor-actions">
          {creating && (
            <Button disabled={save.isPending} onClick={reload}>
              取消
            </Button>
          )}
          {!creating && confirm && label === '关联' && candidate.state !== 'REJECTED' && (
            <Button
              disabled={save.isPending || changed}
              onClick={() => {
                // 拒绝固定候选版本，保留原定义和依据。
                void modal.confirm({
                  title: '拒绝该关联候选？',
                  okText: '拒绝关联',
                  onOk: () => save.mutateAsync({ action: 'REJECT' }),
                });
              }}
            >
              拒绝关联
            </Button>
          )}
          {edit && (
            <Button
              disabled={save.isPending || changed || (creating && !dirty)}
              onClick={() => save.mutate({ action: 'SAVE', values: form.getFieldsValue() })}
            >
              保存草稿
            </Button>
          )}
          {confirm && (
            <Button
              type="primary"
              disabled={changed || (creating && !dirty)}
              loading={save.isPending}
              onClick={() => save.mutate({ action: 'CONFIRM', values: form.getFieldsValue() })}
            >
              确认治理
            </Button>
          )}
        </div>
      </div>
      {/* 抽屉关闭动画结束后由组件恢复原结构依据入口。 */}
      <Drawer
        open={structure}
        destroyOnHidden
        size={640}
        title="结构依据"
        onClose={() => setStructure(false)}
      >
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
      {peek && (
        <GovernanceSourceDrawer peek={peek} source={source} close={() => setPeek(undefined)} />
      )}
      {dependenciesOpen && (
        <GovernanceDependencyDialog
          source={source}
          relation={label === '关联'}
          initial={dependencies}
          close={() => setDependenciesOpen(false)}
          apply={(selection) => {
            // 应用只更新当前编辑副本，正式保存仍核对候选版本。
            setDependencyEdit(selection);
            onDraftChange(candidate);
            setDependenciesOpen(false);
          }}
        />
      )}
    </div>
  );
}
