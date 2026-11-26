import { Alert, App, Button, Form, Input, Modal, Select, Space, Tag } from 'antd';
import { MinusCircleOutlined, PlusOutlined } from '@ant-design/icons';
import { useQueryClient } from '@tanstack/react-query';
import { useEffect, useRef, useState } from 'react';
import { useBeforeUnload, useBlocker } from 'react-router';
import { api, ApiError, mutation } from '../../shared/api';
import { displayTime } from '../../shared/Records';
import { ReviewDialog } from '../../shared/ReviewDialog';
import type { ReviewSubmission } from '../../shared/ReviewDialog';
import { allowed, useSession } from '../../shared/session';
import type { Preview } from '../iam/types';
import { GovernanceGeneration } from './GovernanceGeneration';
import {
  attachmentLocation,
  GovernanceNotePicker,
  GovernanceSourceDrawer,
} from './GovernanceSources';
import type { SourcePeek } from './GovernanceSources';
import type { Definition, Evidence, Impact, Item, NoteReference, Suggestion } from './types';

/**
 * 每个字段的标签用于编辑、引用定位和模型建议差异。
 */
type Field = {
  /**
   * 服务端声明的内容字段。
   */
  key: keyof Definition;
  /**
   * 通俗业务标签。
   */
  label: string;
  /**
   * 适合单行编辑的短内容。
   */
  short?: boolean;
};

/**
 * 同一组内容共用标题及阅读顺序。
 */
interface FieldGroup {
  /** 当前业务分组名称。 */
  title: string;
  /** 按录入顺序排列的内容定义。 */
  fields: Field[];
  /** 当前分组是否承载字段编码表。 */
  withMappings?: boolean;
}

/**
 * 表说明按用途、范围和使用约束组织，保持原字段顺序。
 */
const tableGroups: FieldGroup[] = [
  {
    title: '业务定义',
    fields: [
      { key: 'businessName', label: '业务名称', short: true },
      { key: 'purpose', label: '业务用途' },
      { key: 'rowGrain', label: '一行代表什么' },
      { key: 'rowIdentity', label: '记录识别与去重' },
    ],
  },
  {
    title: '范围与更新',
    fields: [
      { key: 'dataScope', label: '数据范围与排除' },
      { key: 'timeSemantics', label: '业务时间' },
      { key: 'updatePolicy', label: '更新时间与保留' },
    ],
  },
  {
    title: '使用与质量',
    fields: [
      { key: 'usageNotes', label: '使用注意事项' },
      { key: 'qualityNotes', label: '已知质量问题' },
    ],
  },
];

/**
 * 字段定义按业务含义、取值、使用规则组织。
 */
const columnGroups: FieldGroup[] = [
  {
    title: '字段定义',
    fields: [
      { key: 'businessName', label: '业务名称', short: true },
      { key: 'meaning', label: '业务含义' },
    ],
  },
  {
    title: '取值与格式',
    withMappings: true,
    fields: [
      { key: 'valueDescription', label: '取值说明' },
      { key: 'unit', label: '单位', short: true },
      { key: 'format', label: '格式', short: true },
      { key: 'nullMeaning', label: '空值含义' },
      { key: 'emptyMeaning', label: '空字符串含义' },
      { key: 'zeroMeaning', label: '零值含义' },
    ],
  },
  {
    title: '使用规则',
    fields: [
      { key: 'timeSemantics', label: '业务时间' },
      { key: 'businessTimeZone', label: '业务时区', short: true },
      { key: 'usageRule', label: '使用口径' },
      { key: 'usageNotes', label: '使用注意事项' },
      { key: 'sensitivityNotes', label: '敏感性与限制' },
    ],
  },
];

/**
 * 当前界面能展示和选择的业务内容字段。
 */
const statuses: Record<string, string> = {
  UNSTARTED: '待填写',
  DRAFT: '待确认',
  CONFIRMED: '已确认',
  PENDING_SOURCE_REVIEW: '来源事实待核实',
  SCOPE_PENDING: '当前范围待重新采集',
  INACCESSIBLE: '当前对象不可读取',
  READ_ONLY: '当前来源仅供回看',
  REMOVED: '来源已移除',
};

/**
 * 表单空白与服务端省略的字段等价，编码表按完整字段比较。
 */
function fieldValue(content: Definition, key: keyof Definition) {
  return content[key] ?? (key === 'valueMappings' ? [] : '');
}

/**
 * 对比单个业务字段，避免将未填内容识别为人工修改。
 */
function differs(left: Definition, right: Definition, key: keyof Definition) {
  return JSON.stringify(fieldValue(left, key)) !== JSON.stringify(fieldValue(right, key));
}

/**
 * 可编辑的来源引用用于预览和精确移除。
 */
type NoteLink = {
  /**
   * 业务定义的确切原始依据。
   */
  reference: NoteReference;
  /**
   * 选中资料的显示名称。
   */
  title: string;
  /**
   * 前端回看用的资料修订序号。
   */
  revision: number;
};

/**
 * 编辑区锁定打开时的版本，背景读取不会覆盖正在输入的业务内容。
 */
export function GovernanceEditor({
  item,
  source,
  path,
  name,
  sourceComment,
  nativeType,
  next,
  suggestionRequest,
  onSuggestionClose,
}: {
  item: Item;
  source: string;
  path: string;
  name: string;
  sourceComment?: string | null;
  nativeType?: string;
  next?: () => void;
  suggestionRequest?: string;
  onSuggestionClose?: () => void;
}) {
  const cache = useQueryClient();
  const { message, modal } = App.useApp();
  const session = useSession();
  const [form] = Form.useForm<Definition>();
  const [base, setBase] = useState(item);
  const [links, setLinks] = useState(() => noteLinks(item.currentRevision?.evidenceRefs ?? []));
  const [noteChanged, setNoteChanged] = useState(false);
  const [notePicker, setNotePicker] = useState(false);
  const [peek, setPeek] = useState<SourcePeek>();
  const [touched, setTouched] = useState(false);
  const [answers, setAnswers] = useState<Record<string, string>>({});
  const [generation, setGeneration] = useState(false);
  const [selected, setSelected] = useState<{
    suggestionId: string;
    contentPaths: (keyof Definition)[];
  }>();
  const [reasonOpen, setReasonOpen] = useState(false);
  const [reason, setReason] = useState('');
  const [review, setReview] = useState<ReviewSubmission>();
  const [gapOpen, setGapOpen] = useState(false);
  const [gapField, setGapField] = useState<keyof Definition>();
  const [gapQuestion, setGapQuestion] = useState('');
  const [error, setError] = useState<string>();
  const [busy, setBusy] = useState(false);
  const command = useRef<string | undefined>(undefined);
  const dirty = touched || noteChanged || Boolean(selected) || Object.values(answers).some(Boolean);
  const blocker = useBlocker(dirty || busy);
  const groups = base.target.kind === 'COLUMN' ? columnGroups : tableGroups;
  const fields = groups.flatMap((group) => group.fields);
  const reference = useRef<HTMLElement>(null);

  useBeforeUnload((event) => {
    // 原生刷新时继续保护真实的未保存内容。
    if (dirty || busy) {
      event.preventDefault();
      event.returnValue = '';
    }
  });

  useEffect(() => {
    // 切换条目、页签及离开工作台共用未保存核对。
    if (blocker.state !== 'blocked') return;
    if (busy) {
      // 正在等待正式写入确定结果时继续停留。
      blocker.reset();
      void message.info('治理内容正在提交');
      return;
    }
    const dialog = modal.confirm({
      centered: true,
      title: '有未保存的修改',
      content: '离开会丢弃本次修改。',
      okText: '放弃修改并离开',
      cancelText: '继续编辑',
      focusable: { autoFocusButton: 'cancel' },
      onOk: () => blocker.proceed(),
      onCancel: () => blocker.reset(),
    });
    return () => dialog.destroy();
  }, [blocker, busy, message, modal]);

  /**
   * 稳定命令随相同内容保留，成功后重读真实条目和表进度。
   */
  async function submit(action: 'save' | 'confirm' | 'remove' | 'supplement') {
    command.current ??= crypto.randomUUID();
    const precondition = {
      expectedVersion: base.version,
      expectedStructureRevisionId:
        base.structureBasis.columnRevisionId ?? base.structureBasis.objectRevisionId,
    };
    const draft = {
      content: form.getFieldsValue(true),
      manualEvidence: noteChanged ? links.map(({ reference }) => reference) : undefined,
      suggestionSelection: selected,
      gapResolutions: Object.entries(answers)
        .filter(([, resolution]) => resolution.trim())
        .map(([gapId, resolution]) => ({ gapId, resolution })),
    };
    setBusy(true);
    setError(undefined);
    try {
      // 公共写入封装可在回包未知时按原命令回读。
      if (action === 'save') {
        await mutation(
          path + '/draft',
          'PUT',
          { precondition, draft },
          'governance.edit',
          command.current,
        );
      } else if (action === 'supplement') {
        await mutation(
          path + '/gaps',
          'POST',
          { precondition, contentPath: gapField, question: gapQuestion },
          'governance.supplement',
          command.current,
        );
        setGapOpen(false);
        setGapQuestion('');
      } else {
        await mutation(
          path + '/confirmations',
          'POST',
          {
            precondition,
            action: action === 'remove' ? 'ACK_REMOVAL' : 'CONFIRM',
            draft: action === 'confirm' && base.actions.canEdit && dirty ? draft : undefined,
          },
          'governance.confirm',
          command.current,
        );
      }
      command.current = undefined;
      // 请求补充可与未保存正文并存，其余写入均已处理当前表单。
      await reload(action !== 'supplement');
      void message.success(
        {
          save: '草稿已保存',
          confirm: '治理已确认',
          remove: '已确认移除处理',
          supplement: '已记录待补充问题',
        }[action],
      );
    } catch (failure) {
      // 按字段错误聚焦，在并发冲突时保留编辑正文。
      handleError(failure);
    } finally {
      setBusy(false);
    }
  }

  /**
   * 从服务端回读实际版本，保留用户明确需要留存的输入。
   */
  async function reload(afterWrite = false) {
    setError(undefined);
    try {
      const fresh = await api<Item>(path);
      const latest = fresh.currentRevision?.content ?? {};
      const before = base.currentRevision?.content ?? {};
      const local = form.getFieldsValue(true);
      const keys = Object.keys({ ...before, ...latest, ...local }) as (keyof Definition)[];
      // 仅合入本次改动；双方改为同一值时已经收敛。
      const changes = !dirty || afterWrite ? [] : keys.filter((key) => differs(local, before, key));
      const conflicts = changes.filter(
        (key) => differs(latest, before, key) && differs(latest, local, key),
      );
      const evidenceConflict =
        noteChanged &&
        JSON.stringify(base.currentRevision?.evidenceRefs ?? []) !==
          JSON.stringify(fresh.currentRevision?.evidenceRefs ?? []);
      const structureChanged =
        (base.structureBasis.columnRevisionId ?? base.structureBasis.objectRevisionId) !==
        (fresh.structureBasis.columnRevisionId ?? fresh.structureBasis.objectRevisionId);
      if (!afterWrite && dirty && (conflicts.length || evidenceConflict || structureChanged)) {
        // 确认前保留原版本。取消后下一次写入仍会按旧版本检查。
        const keep = await modal.confirm({
          title: '最新版本有冲突',
          content: (
            <div>
              {/* 冲突时展示实际内容的差异，默认返回编辑。 */}
              {conflicts.map((key) => (
                <p key={key}>
                  <strong>
                    {fields.find((field) => field.key === key)?.label ?? '编码与含义'}
                  </strong>
                  <br />
                  当前版本：{JSON.stringify(fieldValue(latest, key))}
                  <br />
                  我的修改：{JSON.stringify(fieldValue(local, key))}
                </p>
              ))}
              {evidenceConflict && <p>业务依据已被修改。继续将保留你选择的依据。</p>}
              {structureChanged && <p>来源结构已变化，请核对后再保存。</p>}
            </div>
          ),
          okText: '保留我的修改',
          cancelText: '继续核对',
          focusable: { autoFocusButton: 'cancel' },
        });
        if (!keep) {
          // 本次合并取消，原输入和原并发版本仍有效。
          return false;
        }
      }
      if (fresh.version !== base.version || structureChanged) {
        // 旧版本写命令已无法提交，合并后的请求使用新的命令编号。
        command.current = undefined;
      }
      setBase(fresh);
      // 完整重置表单值会清除已被他人删除的未修改字段。
      form.setFields(
        keys.map((name) => ({
          name,
          value: fieldValue(changes.includes(name) ? local : latest, name),
          errors: [],
        })),
      );
      setTouched(changes.some((key) => differs(local, latest, key)));
      if (afterWrite || !noteChanged) {
        // 对未编辑的依据同步最新版本。
        setLinks(noteLinks(fresh.currentRevision?.evidenceRefs ?? []));
        setNoteChanged(false);
      }
      if (afterWrite) {
        // 已提交的建议选择和核对答复不再保留。
        setSelected(undefined);
        setAnswers({});
      }
      await cache.invalidateQueries({ queryKey: ['governance', source] });
      return true;
    } catch (failure) {
      // 已知提交的结果仍通过后续回读核实。
      handleError(failure);
      return false;
    }
  }

  /**
   * 将后端字段差错映射到实际控件，普通连接问题仍留在当前页。
   */
  function handleError(failure: unknown) {
    setError((failure as Error).message);
    if (failure instanceof ApiError) {
      // 明确业务拒绝后可以核对新输入，再用新命令提交。
      command.current = undefined;
      const fields = failure.fields.map(({ field, message }) => ({
        name: field
          .replace(/^draft\.content\./, '')
          .split(/[.\[\]]+/)
          .filter(Boolean)
          .map((part) => (/^\d+$/.test(part) ? Number(part) : part)) as Parameters<
          typeof form.setFields
        >[0][number]['name'],
        errors: [message],
      }));
      if (fields.length) {
        // 聚焦第一个真正需要修正的控件。
        form.setFields(fields);
        form.focusField(fields[0].name);
      }
    }
  }

  /**
   * 人工禁止使用之前使用共同的影响核对弹窗。
   */
  async function previewForbid() {
    try {
      // 使用服务端签名预览绑定当时限制影响。
      const body = {
        action: 'FORBID',
        expectedVersion: base.version,
        proposal: { reason: reason.trim() },
      };
      const preview = await api<Preview>(path + '/impact-preview', { method: 'POST', body });
      setReasonOpen(false);
      setReview({
        title: '禁止对象使用',
        path: path + '/usage-control',
        method: 'POST',
        operation: 'governance.forbid',
        body,
        preview,
        confirmText: '确认禁止',
        danger: true,
        done: () => {
          // 现行限制回读后刷新当前工具区。
          setReview(undefined);
          void reload();
        },
      });
    } catch (failure) {
      handleError(failure);
    }
  }

  /**
   * 只将已选定的模型建议放入编辑区，仍需人工保存。
   */
  async function applySuggestion(suggestion: Suggestion, paths: (keyof Definition)[]) {
    // 未选中的人工字段继续使用当前输入和原引用。
    if (base.version === null) {
      // 首次生成会物化稳定条目，读取它的真实并发版本。
      if (!(await reload())) {
        // 合并取消或刷新失败时保留原输入和待选建议。
        return;
      }
    }
    const picked = Object.fromEntries(paths.map((field) => [field, suggestion.content[field]]));
    form.setFieldsValue(picked);
    setSelected({ suggestionId: suggestion.suggestionId, contentPaths: paths });
    command.current = undefined;
    setGeneration(false);
    onSuggestionClose?.();
  }

  /**
   * 业务字段和直接相关的人工待核对项保持在一起。
   */
  function renderField(field: Field) {
    return (
      <div
        key={field.key}
        className={
          ['unit', 'format', 'purpose', 'rowGrain'].includes(field.key)
            ? 'gov-field gov-field-pair'
            : 'gov-field'
        }
      >
        {/* 原型使用就地输入，短字段走相同的标签和字段错误定位。 */}
        <Form.Item name={field.key} label={field.label}>
          {field.short ? (
            <Input maxLength={160} />
          ) : (
            <Input.TextArea autoSize={{ minRows: 2, maxRows: 8 }} />
          )}
        </Form.Item>
        {/* 实际存在的疑点直接展示在对应说明下方。 */}
        {base.gaps
          .filter((gap) => gap.contentPath === field.key && gap.state === 'OPEN')
          .map((gap) => (
            <div className="gov-gap" key={gap.gapId}>
              <strong>{gap.question}</strong>
              {base.actions.canEdit && (
                <Input.TextArea
                  aria-label={'核对结论 ' + field.label}
                  placeholder="核对结论"
                  maxLength={1000}
                  value={answers[gap.gapId] ?? ''}
                  onChange={(event) => {
                    // 结论与更新后的实际业务内容同批提交。
                    setAnswers({ ...answers, [gap.gapId]: event.target.value });
                    command.current = undefined;
                  }}
                />
              )}
            </div>
          ))}
      </div>
    );
  }

  /**
   * 打开获准的结构事实或资料修订，原编辑区仍然保留。
   */
  function openImpact(impact: Impact, after = false) {
    const origin = impact.origin;
    if (origin?.changeId) {
      // 事实已经核实，进入同一页的只读证据。
      setPeek({ kind: 'change', id: origin.changeId });
    } else if (origin?.noteId) {
      const revision = after ? origin.afterRevision : origin.beforeRevision;
      if (revision) {
        // 新旧修订各自保留出处，而不是回读最新资料。
        setPeek({ kind: 'note', id: origin.noteId, revision });
      }
    }
  }

  return (
    <article className="gov-editor" aria-label={name + '治理编辑'}>
      {/* 条目名与状态先交代当前编辑对象。 */}
      <div className="gov-entry-title">
        <h2>{name}</h2>
        <Tag color={base.state === 'CONFIRMED' ? 'green' : undefined}>{statuses[base.state]}</Tag>
        {base.pendingReview && <Tag color="orange">变更待核对</Tag>}
        {base.blocked && <Tag color="red">禁止使用</Tag>}
        {/* 窄窗的依据移至表单后，待复核时可以直接定位。 */}
        {base.pendingReview && (
          <Button
            type="link"
            className="gov-reference-jump"
            onClick={() => reference.current?.focus()}
          >
            查看依据
          </Button>
        )}
      </div>
      {/* 主列处理定义，资料与复核依据在参照区持续可见。 */}
      <div className="gov-editor-layout">
        <div className="gov-editor-main">
          {base.availability !== 'AVAILABLE' && (
            <Alert type="warning" showIcon title={statuses[base.availability]} />
          )}
          {error && (
            <Alert
              type="error"
              showIcon
              title={error}
              action={
                <Button type="link" onClick={() => void reload()}>
                  重新读取版本
                </Button>
              }
            />
          )}
          {/* 按既定结构保存表说明或字段的独立内容。 */}
          <Form
            form={form}
            name={'governance-' + base.target.kind.toLowerCase()}
            layout="vertical"
            initialValues={base.currentRevision?.content ?? {}}
            disabled={!base.actions.canEdit || busy}
            onValuesChange={(_, values) => {
              // 保持确切的内容差异，确定失败后无需重用旧命令。
              const before = base.currentRevision?.content ?? {};
              const keys = Object.keys({ ...before, ...values }) as (keyof Definition)[];
              setTouched(keys.some((key) => differs(values, before, key)));
              command.current = undefined;
            }}
          >
            {/* 表单按业务任务分组，条件核对仍紧跟原输入。 */}
            {groups.map((group) => (
              <section className="gov-form-group" aria-label={group.title} key={group.title}>
                <h3>{group.title}</h3>
                {group.fields.map(renderField)}
                {group.withMappings && (
                  <Form.List name="valueMappings">
                    {(mappings, { add, remove }) => (
                      <section className="gov-code-list" aria-label="编码与含义">
                        {/* 编码按文本输入并在行内展示真实校验错误。 */}
                        <strong>编码与含义</strong>
                        {mappings.map((mapping, index) => (
                          <div className="gov-code-row" key={mapping.key}>
                            <Form.Item name={[mapping.name, 'code']}>
                              <Input aria-label={'编码 ' + (index + 1)} placeholder="编码" />
                            </Form.Item>
                            <Form.Item name={[mapping.name, 'meaning']}>
                              <Input aria-label={'编码含义 ' + (index + 1)} placeholder="含义" />
                            </Form.Item>
                            {base.actions.canEdit && (
                              <Button
                                icon={<MinusCircleOutlined />}
                                aria-label={'移除编码 ' + (index + 1)}
                                onClick={() => remove(mapping.name)}
                              />
                            )}
                          </div>
                        ))}
                        {base.actions.canEdit && (
                          <Button icon={<PlusOutlined />} onClick={() => add()}>
                            添加编码
                          </Button>
                        )}
                      </section>
                    )}
                  </Form.List>
                )}
              </section>
            ))}
          </Form>
        </div>
        <aside ref={reference} tabIndex={-1} className="gov-reference" aria-label="来源与业务依据">
          <section className="gov-source-summary">
            {/* 来源属性和确认时间并排显示，变化证据保留在摘要下方。 */}
            <div className="gov-source-line">
              <h3>当前来源</h3>
              {(nativeType || sourceComment) && (
                <span>{[nativeType, sourceComment].filter(Boolean).join(' · ')}</span>
              )}
              {base.latestConfirmation && (
                <small>最近确认 {displayTime(base.latestConfirmation.confirmedAt)}</small>
              )}
            </div>
            {base.impacts
              .filter(({ state }) => state !== 'REVIEWED')
              .map((impact) => (
                <div key={impact.impactId} className="gov-affected">
                  <span>
                    {impact.kind === 'REMOVAL'
                      ? '来源已移除'
                      : impact.kind === 'NEW_ITEM'
                        ? '新增治理项'
                        : impact.origin?.changeId
                          ? '采集结构变化'
                          : impact.origin?.noteId
                            ? '业务说明变化'
                            : '业务依据变化'}
                  </span>
                  {impact.origin?.changeId && (
                    <Button type="link" onClick={() => openImpact(impact)}>
                      查看变化证据
                    </Button>
                  )}
                  {impact.origin?.noteId && (
                    <Space>
                      <Button type="link" onClick={() => openImpact(impact)}>
                        变化前资料
                      </Button>
                      {impact.origin.afterRevision && (
                        <Button type="link" onClick={() => openImpact(impact, true)}>
                          变化后资料
                        </Button>
                      )}
                    </Space>
                  )}
                </div>
              ))}
          </section>
          {/* 已引用资料与手工编辑独立维护，来源附件仍按权限回看。 */}
          {(links.length > 0 ||
            base.currentRevision?.evidenceRefs.some((ref) => ref.redacted) ||
            (base.actions.canEdit && allowed(session, 'business-notes.view'))) && (
            <section className="gov-evidence gov-reference-evidence" aria-label="业务依据">
              <h3>业务依据</h3>
              {links.map((link, index) => (
                <div key={link.reference.noteId + '-' + index}>
                  <Button
                    type="link"
                    onClick={() => {
                      // 从固定附件依据信息打开同一资料修订。
                      setPeek({
                        kind: 'note',
                        id: link.reference.noteId,
                        revision: link.revision,
                        attachmentId: link.reference.attachmentId,
                        section: link.reference.location.section,
                      });
                    }}
                  >
                    {link.title} · 第 {link.revision} 版
                    {attachmentLocation(link.reference.location.section) &&
                      ' · ' + attachmentLocation(link.reference.location.section)}
                  </Button>
                  {base.actions.canEdit && (
                    <Button
                      type="text"
                      onClick={() => {
                        // 显式移除本条引用，历史修订仍可回看。
                        setLinks(links.filter((_, position) => position !== index));
                        setNoteChanged(true);
                        command.current = undefined;
                      }}
                    >
                      移除引用
                    </Button>
                  )}
                </div>
              ))}
              {base.currentRevision?.evidenceRefs.some((ref) => ref.redacted) && (
                <span>部分来源需相应查看权限</span>
              )}
              {base.actions.canEdit && allowed(session, 'business-notes.view') && (
                <Button onClick={() => setNotePicker(true)}>引用业务说明</Button>
              )}
            </section>
          )}
        </aside>
      </div>
      {/* 操作集中在编辑区底部，审核入口遵循服务端状态。 */}
      <div className="gov-editor-actions">
        {/* 辅助处理和危险操作靠左，常规保存和确认靠右。 */}
        <Space wrap>
          {base.actions.canSuggest && !base.actions.suggestUnavailableReason && (
            <Button disabled={busy} onClick={() => setGeneration(true)}>
              {selected ? '重新生成 AI 建议' : '生成 AI 建议'}
            </Button>
          )}
          {base.actions.canSupplement && (
            <Button disabled={busy} onClick={() => setGapOpen(true)}>
              要求补充
            </Button>
          )}
          {base.actions.canForbid && !base.blocked && (
            <Button danger type="text" disabled={busy} onClick={() => setReasonOpen(true)}>
              禁止使用
            </Button>
          )}
        </Space>
        <Space wrap className="gov-commit-actions">
          {base.actions.canEdit && (
            <Button loading={busy} onClick={() => void submit('save')}>
              保存草稿
            </Button>
          )}
          {base.actions.canConfirm && (
            <Button type="primary" loading={busy} onClick={() => void submit('confirm')}>
              确认治理
            </Button>
          )}
          {base.actions.canAcknowledgeRemoval && (
            <Button type="primary" loading={busy} onClick={() => void submit('remove')}>
              确认移除处理
            </Button>
          )}
          {next && base.state === 'CONFIRMED' && (
            <Button type="link" onClick={next}>
              下一待处理项
            </Button>
          )}
        </Space>
      </div>
      {peek && (
        <GovernanceSourceDrawer
          key={peek.kind + peek.id}
          peek={peek}
          source={source}
          close={() => setPeek(undefined)}
        />
      )}
      {notePicker && (
        <GovernanceNotePicker
          source={source}
          fields={fields}
          close={() => setNotePicker(false)}
          apply={(reference, title, revision) => {
            // 把确切修订作为待保存业务引用，保持原编辑流程。
            setLinks([...links, { reference, title, revision }]);
            setNoteChanged(true);
            command.current = undefined;
            setNotePicker(false);
          }}
        />
      )}
      {(generation || suggestionRequest) && (
        <GovernanceGeneration
          key={suggestionRequest}
          source={source}
          path={path}
          item={base}
          fields={fields}
          current={form.getFieldsValue(true)}
          initialRequest={suggestionRequest}
          close={() => {
            // 已接收批次回看关闭后仍可从结果列表重开。
            setGeneration(false);
            onSuggestionClose?.();
          }}
          apply={(suggestion, paths) => {
            void applySuggestion(suggestion, paths);
          }}
        />
      )}
      {/* 由弹窗处理退场和焦点归还，问题内容留在本条编辑器。 */}
      <Modal
        className="app-dialog"
        open={gapOpen}
        destroyOnHidden
        centered
        title="要求补充"
        okText="记录问题"
        cancelText="取消"
        okButtonProps={{ disabled: !gapField || !gapQuestion.trim() }}
        confirmLoading={busy}
        onCancel={() => setGapOpen(false)}
        onOk={() => void submit('supplement')}
      >
        {/* 问题需要定位到实际有缺口的业务字段。 */}
        <Form layout="vertical">
          <Form.Item label="业务内容">
            <Select
              aria-label="待补充业务内容"
              value={gapField}
              onChange={setGapField}
              options={fields.map(({ key, label }) => ({ value: key, label }))}
            />
          </Form.Item>
          <Form.Item label="待核对问题">
            <Input.TextArea
              aria-label="待核对问题"
              value={gapQuestion}
              maxLength={1000}
              onChange={(event) => setGapQuestion(event.target.value)}
            />
          </Form.Item>
        </Form>
      </Modal>
      {/* 保留操作入口，关闭核对前的原因弹窗后能继续键盘操作。 */}
      <Modal
        className="app-dialog"
        open={reasonOpen}
        destroyOnHidden
        centered
        title="禁止对象使用"
        okText="核对影响"
        cancelText="取消"
        okButtonProps={{ disabled: !reason.trim() }}
        onCancel={() => setReasonOpen(false)}
        onOk={() => void previewForbid()}
      >
        {/* 危险操作记录实际限制原因。 */}
        <Input.TextArea
          aria-label="禁止原因"
          placeholder="禁止原因"
          value={reason}
          maxLength={500}
          onChange={(event) => setReason(event.target.value)}
        />
      </Modal>
      {review && <ReviewDialog submission={review} close={() => setReview(undefined)} />}
    </article>
  );
}

/**
 * 已授权的确切资料引用可再次查看或移除。
 */
function noteLinks(references: Evidence[]): NoteLink[] {
  return references.flatMap((ref) => {
    if (ref.kind !== 'NOTE' || !ref.noteId || !ref.revisionId || !ref.revision || !ref.location) {
      // 隐藏的依据永远不能在浏览器自行恢复标识。
      return [];
    }
    return [
      {
        title: '业务说明',
        revision: ref.revision,
        reference: {
          contentPath: ref.contentPath,
          noteId: ref.noteId,
          revisionId: ref.revisionId,
          attachmentId: ref.attachmentId,
          location: ref.location,
        },
      },
    ];
  });
}
