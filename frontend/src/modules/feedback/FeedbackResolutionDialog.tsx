import {
  Alert,
  App,
  Button,
  Checkbox,
  Descriptions,
  Form,
  Input,
  Modal,
  Segmented,
  Select,
} from 'antd';
import { useQuery } from '@tanstack/react-query';
import { useEffect, useRef, useState, type ComponentRef } from 'react';
import { Link } from 'react-router';
import { api, ApiError, queryString } from '../../shared/api';
import { applyFormError } from '../../shared/formErrors';
import { LoadingStatus } from '../../shared/LoadingIndicator';
import { useReturnFocus } from '../../shared/useReturnFocus';
import type { Page } from '../iam/types';
import { closeReasons, statusLabels, type FeedbackTask, type IssueDetail } from './types';
import { useFeedbackCommand } from './useFeedbackCommand';

/** 后台只开放当前服务端确认的工作动作。 */
export type ResolutionAction = 'result' | 'knowledge' | 'source' | 'verify' | 'resolve' | 'explain';

/** 配置明确共用动作权限、行文和接口地址。 */
const actions = {
  result: {
    title: '技术处理',
    path: 'results',
    permission: 'feedback.process',
    flag: 'canSubmitResult',
  },
  knowledge: {
    title: '提交治理结果',
    path: 'knowledge-results',
    permission: 'feedback.process',
    flag: 'canSubmitKnowledge',
  },
  source: {
    title: '源数据处理',
    path: 'source-results',
    permission: 'feedback.process',
    flag: 'canSaveSourceData',
  },
  verify: {
    title: '登记人工复验',
    path: 'verifications',
    permission: 'feedback.verify',
    flag: 'canVerify',
  },
  resolve: {
    title: '验证结案并回告',
    path: 'closures',
    permission: 'feedback.close',
    flag: 'canResolve',
  },
  explain: {
    title: '说明并结案',
    path: 'closures',
    permission: 'feedback.close',
    flag: 'canCloseOther',
  },
} as const;

/** 技术影响范围和回告原因使用与服务端相同的明确编码。 */
export const impactLabels: Record<string, string> = {
  DISPLAY: '展示与表述',
  CONNECTION: '连接与配置',
  QUERY_SAFETY: '查询与安全',
  KNOWLEDGE: '业务知识',
  SOURCE_DATA: '源数据修正',
};

/** 表单仅发送选中动作所需字段，隐藏的回归内容不参与写入。 */
type Values = {
  bindingId?: string;
  impact?: string;
  summary?: string;
  checkMethod?: string;
  reference?: string;
  effectiveConfirmed?: boolean;
  regression?: string;
  regressionPassed?: boolean;
  decision?: string;
  testScope?: string;
  expected?: string;
  actual?: string;
  dataBasis?: string;
  reason?: string;
  conclusion?: string;
  basis?: string;
  /** 源系统目前所处的跟进、等待或已生效阶段。 */
  progressStage?: 'WORKING' | 'WAITING' | 'EFFECTIVE';
  /** 本次排查、沟通和核对所完成的工作。 */
  workDone?: string;
  /** 待源系统或记录人继续办理的动作。 */
  nextStep?: string;
  /** 实际修正源数据的人员或团队。 */
  repairer?: string;
  /** 区分人工核对和实际运行的问数复验。 */
  sourceRecheckMode?: 'MANUAL' | 'QUESTION';
};

/** 结案预览只含受控模板和确切接收人。 */
type Preview = {
  version: number;
  recipientId: string;
  recipientName: string;
  template: string;
  message: string;
  /** 合并事项按原提问人分别确认回告。 */
  recipients?: { recipientId: string; recipientName: string; template: string; message: string }[];
  /** 核对全组接收人及模板的确认值。 */
  confirmation?: string | null;
};

/** 澄清、取消和失败都停止等待，可以重新发起问数。 */
function isRunning(state: string | undefined) {
  return (
    !state ||
    ['QUEUED', 'RETRIEVING', 'GENERATING', 'CHECKING', 'EXECUTING', 'FINALIZING'].includes(state)
  );
}

/** 通用多行文本保持字段校验、焦点和最大长度一致。 */
function TextField({
  name,
  label,
  max = 1000,
  optional = false,
}: {
  name: keyof Values;
  label: string;
  max?: number;
  optional?: boolean;
}) {
  return (
    <Form.Item
      name={name}
      label={label}
      rules={optional ? [] : [{ required: true, whitespace: true, message: `请填写${label}` }]}
    >
      <Input.TextArea rows={2} maxLength={max} />
    </Form.Item>
  );
}

/** 核对接收人和模板后才写结案，回包不明继续使用原命令。 */
export function FeedbackResolutionDialog({
  kind,
  detail,
  task,
  refresh,
  close,
}: {
  kind: ResolutionAction;
  detail: IssueDetail;
  task?: FeedbackTask;
  refresh: () => Promise<IssueDetail | undefined>;
  close: () => void;
}) {
  useReturnFocus();
  const [form] = Form.useForm<Values>();
  const { message } = App.useApp();
  const [version, setVersion] = useState(detail.version);
  const [preview, setPreview] = useState<{ notice: Preview; values: Values }>();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [stale, setStale] = useState(false);
  const { uncertain, send } = useFeedbackCommand(refresh);
  const [launched, setLaunched] = useState<string>();
  const replayCommand = useRef<string | undefined>(undefined);
  const inFlight = useRef(false);
  const confirm = useRef<ComponentRef<typeof Button>>(null);
  const action = actions[kind];
  const resolution = task?.resolution ?? detail.resolution;
  const enabled = Boolean(resolution?.[action.flag]);
  const source = detail.issue.sourceId;
  const knowledgeReplay = kind === 'verify' && resolution?.result?.impact === 'KNOWLEDGE';
  const sourceVerification = kind === 'verify' && resolution?.result?.impact === 'SOURCE_DATA';
  const tracksProgress = kind === 'result' || kind === 'source';
  const lastResult = resolution?.result;
  const progress = task?.progress;
  const initialStage = task?.waitingExternal ? 'WAITING' : lastResult ? 'EFFECTIVE' : 'WORKING';
  const progressStage = Form.useWatch('progressStage', form) ?? initialStage;
  const isFollowingUp = progressStage !== 'EFFECTIVE';
  const sourceRecheckMode = Form.useWatch('sourceRecheckMode', form);
  const needsReplay = knowledgeReplay || (sourceVerification && sourceRecheckMode === 'QUESTION');
  const recheck = needsReplay ? (launched ?? resolution?.recheckRequestId ?? undefined) : undefined;
  const closing = kind === 'resolve' || kind === 'explain';
  const outcome = kind === 'resolve' ? 'RESOLVED' : 'CLOSED_OTHER';
  const path = `/feedback/issues/${detail.issue.id}`;
  const taskQuery = task ? `?task=${task.id}` : '';
  const impact = Form.useWatch('impact', form);
  const bindings = useQuery({
    queryKey: ['feedback-published-bindings', source],
    queryFn: () =>
      api<Page<{ id: string; title: string; revision: number; availability: string }>>(
        `/knowledge/sources/${source}/bindings?pageSize=100`,
      ),
    enabled: kind === 'knowledge',
    retry: false,
  });
  const completed = useQuery({
    queryKey: ['feedback-recheck', recheck],
    queryFn: () => api<{ state: string }>('/query/requests/' + recheck),
    enabled: Boolean(recheck),
    retry: false,
    refetchInterval: (query) =>
      isRunning(query.state.data?.state) && !query.state.error ? 1500 : false,
  });
  const replayState = completed.data?.state;
  const replayFinished =
    replayState === 'RESULT_DELIVERED' ||
    replayState === 'KNOWLEDGE_INSUFFICIENT' ||
    replayState === 'TECHNICAL_FAILURE';

  useEffect(() => {
    // 进入预览时，把键盘流程移到本次确认。
    if (preview) confirm.current?.focus({ preventScroll: true });
  }, [preview]);

  /** 验证输入和状态，必要时先获取服务端纯预览。 */
  async function submit(values: Values) {
    if (inFlight.current || stale || !enabled) return;
    inFlight.current = true;
    setBusy(true);
    setError(null);
    try {
      if (closing && !preview && !uncertain) {
        const notice = await api<Preview>(
          path + '/closure-preview?' + queryString({ outcome, reason: values.reason }),
        );
        if (notice.version !== version) {
          setStale(true);
          return;
        }
        setPreview({ notice, values });
        return;
      }
      let body: object = preview
        ? {
            ...preview.values,
            outcome,
            expectedVersion: version,
            recipientId: preview.notice.recipientId,
            confirmedTemplate: preview.notice.template,
            confirmedRecipients: preview.notice.confirmation ?? undefined,
          }
        : {
            ...values,
            progressStage: undefined,
            sourceRecheckMode: undefined,
            expectedVersion: version,
            ...(needsReplay ? { recheckRequestId: recheck } : {}),
            ...(kind === 'result' && impact !== 'QUERY_SAFETY'
              ? { regression: undefined, regressionPassed: undefined }
              : {}),
          };
      let target = path + '/' + action.path + taskQuery;
      // 按当前阶段构造独立入参，隐藏字段既不参加校验也不写入。
      if (tracksProgress && values.progressStage !== 'EFFECTIVE') {
        // 跟进只写实际工作、等待状态和下一步。
        target =
          kind === 'source'
            ? `${path}/tasks/${task?.id}/source-progress`
            : `${path}/technical-progress${taskQuery}`;
        body = {
          expectedVersion: version,
          waitingExternal: values.progressStage === 'WAITING',
          workDone: values.workDone,
          nextStep: values.nextStep,
        };
      } else if (kind === 'source') {
        // 源系统修正结果额外保存实际修正人。
        target = `${path}/tasks/${task?.id}/${action.path}`;
        body = {
          expectedVersion: version,
          repairer: values.repairer,
          summary: values.summary,
          checkMethod: values.checkMethod,
          reference: values.reference,
          effectiveConfirmed: values.effectiveConfirmed,
        };
      }
      const submittedPath = await send(target, body, action.permission);
      await refresh();
      message.success(
        closing
          ? '已结案，回告已接收'
          : kind === 'verify'
            ? '已登记复验'
            : submittedPath.includes('/technical-progress') ||
                submittedPath.endsWith('/source-progress')
              ? '已保存进展'
              : '已提交处理结果',
      );
      close();
    } catch (failure) {
      if (failure instanceof ApiError) {
        setPreview(undefined);
        if (failure.code === 409001) setStale(true);
        else setError(applyFormError(form, failure));
      } else {
        setError(failure instanceof Error ? failure.message : '操作暂时无法完成');
      }
    } finally {
      inFlight.current = false;
      setBusy(false);
    }
  }

  /** 请求无回包时沿用原命令，避免同一个按钮发起两轮真查询。 */
  async function runRecheck() {
    if (inFlight.current) return;
    inFlight.current = true;
    setBusy(true);
    setError(null);
    replayCommand.current ??= crypto.randomUUID();
    try {
      const accepted = await api<{ id: string }>(path + '/rechecks' + taskQuery, {
        method: 'POST',
        command: replayCommand.current,
      });
      setLaunched(accepted.id);
      replayCommand.current = undefined;
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : '暂时无法发起问数复验');
    } finally {
      inFlight.current = false;
      setBusy(false);
    }
  }

  /** 另一个操作者已推进版本时，重读后由用户保留草稿继续办理。 */
  async function reload() {
    setBusy(true);
    setError(null);
    try {
      const current = await refresh();
      if (current) {
        setVersion(current.version);
        setStale(false);
      } else setError('暂时无法读取当前事项');
    } finally {
      setBusy(false);
    }
  }

  /** 退回确认前的输入或退出当前表单。 */
  function cancel() {
    if (busy) return;
    if (preview && !uncertain) {
      setPreview(undefined);
      requestAnimationFrame(() => form.scrollToField('conclusion', { focus: true }));
    } else close();
  }

  return (
    <Modal
      centered
      open
      width={640}
      className="operation-dialog"
      mask={{ closable: false }}
      closable={!busy}
      title={
        preview
          ? '核对结案与回告'
          : (knowledgeReplay
              ? '用原问题复验'
              : sourceVerification
                ? '登记只读复验'
                : action.title) + (task ? ` · 任务 #${task.id}` : '')
      }
      onCancel={cancel}
      footer={[
        <Button key="cancel" disabled={busy} onClick={cancel}>
          {preview && !uncertain ? '返回修改' : '取消'}
        </Button>,
        <Button
          key="confirm"
          type="primary"
          ref={confirm}
          loading={busy}
          disabled={stale || !enabled || (needsReplay && !replayFinished && !uncertain)}
          onClick={() => {
            if (uncertain || preview) void submit(preview?.values ?? {});
            else form.submit();
          }}
        >
          {uncertain
            ? '核对并重试'
            : preview
              ? '结案并确认回告'
              : closing
                ? '核对结案与回告'
                : knowledgeReplay || sourceVerification
                  ? '登记复验结论'
                  : tracksProgress
                    ? isFollowingUp
                      ? '保存进展'
                      : '提交处理结果'
                    : action.title}
        </Button>,
      ]}
    >
      {/* 提交冲突与一般失败各自给可执行的处理入口。 */}
      {stale && (
        <Alert
          className="form-alert"
          type="warning"
          showIcon
          title="事项已更新"
          action={
            <Button size="small" loading={busy} onClick={() => void reload()}>
              读取最新版本
            </Button>
          }
        />
      )}
      {!enabled && (
        <Alert className="form-alert" type="warning" showIcon title="当前事项已无法执行此操作" />
      )}
      {error && <Alert className="form-alert" type="error" showIcon title={error} />}
      {needsReplay && (
        <div className="feedback-recheck-controls">
          <Button
            loading={busy}
            disabled={
              !resolution?.canReplayQuestion ||
              Boolean(recheck && !completed.isError && isRunning(replayState))
            }
            onClick={() => void runRecheck()}
          >
            {recheck ? '重新问数' : sourceVerification ? '发起只读问数' : '发起问数'}
          </Button>
          {recheck && (
            <Link
              target="_blank"
              rel="noreferrer"
              to={`/query?source=${source}&request=${recheck}`}
            >
              查看本次回答
            </Link>
          )}
          {/* 回到复验界面可直接看到当前问数是否还在运行。 */}
          {recheck && (
            <span className="feedback-meta">
              {!completed.isError && isRunning(replayState) ? (
                <LoadingStatus>问数处理中</LoadingStatus>
              ) : (
                <span role="status">
                  {replayState === 'RESULT_DELIVERED' ? '已返回结果' : '问数未完成'}
                </span>
              )}
            </span>
          )}
          {!resolution?.canReplayQuestion && (
            <Alert type="warning" showIcon title="原问题条件或复验权限不可用" />
          )}
        </div>
      )}
      {preview && (
        <Descriptions
          size="small"
          column={1}
          items={[
            { key: 'state', label: '结案状态', children: statusLabels[outcome] },
            ...(preview.notice.recipients?.length
              ? preview.notice.recipients.map((recipient) => ({
                  key: recipient.recipientId,
                  label: recipient.recipientName,
                  children: recipient.message,
                }))
              : [
                  { key: 'recipient', label: '接收人', children: preview.notice.recipientName },
                  { key: 'notice', label: '产品内回告', children: preview.notice.message },
                ]),
            {
              key: 'basis',
              label: '结案依据',
              children: <span className="feedback-text">{preview.values.basis}</span>,
            },
          ]}
        />
      )}
      <Form<Values>
        form={form}
        layout="vertical"
        scrollToFirstError={{ focus: true }}
        hidden={Boolean(preview) || Boolean(knowledgeReplay && !replayFinished)}
        disabled={busy || uncertain || !enabled}
        initialValues={
          tracksProgress
            ? {
                progressStage: initialStage,
                workDone: progress?.workDone ?? undefined,
                nextStep: progress?.nextStep ?? undefined,
                impact: kind === 'result' ? lastResult?.impact : undefined,
                repairer: lastResult?.sourceRepairer ?? undefined,
                summary: lastResult?.summary ?? undefined,
                checkMethod: lastResult?.checkMethod ?? undefined,
                reference: lastResult?.reference ?? undefined,
                regression: lastResult?.regression ?? undefined,
              }
            : sourceVerification
              ? { sourceRecheckMode: 'MANUAL' }
              : undefined
        }
        onFinish={(values) => void submit(values)}
      >
        {/* 进展决定当前只能保存跟进，还是已具备提交修复结果的条件。 */}
        {tracksProgress && (
          <Form.Item name="progressStage" label="目前进展" rules={[{ required: true }]}>
            <Segmented
              className="feedback-progress-stages"
              name="feedback-progress-stage"
              aria-label="目前进展"
              tabIndex={-1}
              classNames={{ item: 'feedback-progress-stage' }}
              options={[
                { value: 'WORKING', label: '排查跟进' },
                {
                  value: 'WAITING',
                  label: kind === 'source' ? '等待修正或同步' : '等待修复生效',
                },
                { value: 'EFFECTIVE', label: kind === 'source' ? '已修正并生效' : '已修复并生效' },
              ]}
            />
          </Form.Item>
        )}
        {/* 改回跟进时提醒当前待验证结果将被撤回。 */}
        {tracksProgress && isFollowingUp && (
          <>
            {resolution?.result && (
              <Alert type="warning" showIcon title="改为跟进会撤回当前待验证结果" />
            )}
            <TextField name="workDone" label="已做工作" />
            <TextField name="nextStep" label="下一步安排" />
          </>
        )}
        {/* 提交修复确认生效，查询安全影响需要对应真实回归。 */}
        {kind === 'result' && !isFollowingUp && (
          <>
            <Form.Item
              name="impact"
              label="修复影响范围"
              rules={[{ required: true, message: '请选择影响范围' }]}
            >
              <Select
                virtual={false}
                options={Object.entries(impactLabels)
                  .filter(([value]) => value !== 'KNOWLEDGE' && value !== 'SOURCE_DATA')
                  .map(([value, label]) => ({ value, label }))}
              />
            </Form.Item>
            <TextField name="summary" label="修正说明" />
            <TextField name="checkMethod" label="复查方式与结果" />
            <TextField name="reference" label="相关处理记录" optional max={500} />
            {impact === 'QUERY_SAFETY' && (
              <>
                <TextField name="regression" label="相关场景测试结果" />
                <Form.Item
                  name="regressionPassed"
                  valuePropName="checked"
                  rules={[
                    {
                      validator: async (_, value) => {
                        if (!value) throw new Error('请确认相关场景测试已通过');
                      },
                    },
                  ]}
                >
                  <Checkbox>已核对原问题和受限场景，测试均通过</Checkbox>
                </Form.Item>
              </>
            )}
            <Form.Item
              name="effectiveConfirmed"
              valuePropName="checked"
              rules={[
                {
                  validator: async (_, value) => {
                    if (!value) throw new Error('请确认修复已生效');
                  },
                },
              ]}
            >
              <Checkbox>已确认本次修复生效</Checkbox>
            </Form.Item>
          </>
        )}
        {/* 源系统实际修正信息只用于生效后的结果记录。 */}
        {kind === 'source' && progressStage === 'EFFECTIVE' && (
          <>
            <TextField name="repairer" label="实际修正人或团队" max={120} />
            <TextField name="summary" label="修正的数据范围与内容" />
            <TextField name="checkMethod" label="只读复查方式与实际结果" />
            <TextField name="reference" label="相关处理记录" max={500} optional />
            <Form.Item
              name="effectiveConfirmed"
              valuePropName="checked"
              rules={[
                {
                  validator: async (_, value) => {
                    // 未生效的修正应继续保存为跟进。
                    if (!value) throw new Error('请确认修正及必要同步已经生效');
                  },
                },
              ]}
            >
              <Checkbox>已确认源系统修正及必要同步已生效</Checkbox>
            </Form.Item>
          </>
        )}
        {kind === 'knowledge' && (
          <>
            <Form.Item
              name="bindingId"
              label="已发布版本"
              rules={[{ required: true, message: '请选择已发布版本' }]}
            >
              <Select
                showSearch={{ optionFilterProp: 'label' }}
                loading={bindings.isPending}
                options={bindings.data?.items
                  .filter((binding) => binding.availability === 'AVAILABLE')
                  .map(({ id, title, revision }) => ({
                    value: id,
                    label: `${title} · r${revision}`,
                  }))}
                notFoundContent={bindings.isError ? '版本读取失败' : '暂无可用发布版本'}
              />
            </Form.Item>
            <TextField name="summary" label="补齐的知识" />
            <TextField name="checkMethod" label="原问题的覆盖依据" />
          </>
        )}
        {/* 复验是本人实际登记，使用该登录身份的真实来源权限。 */}
        {kind === 'verify' && (
          <>
            {/* 源数据复验可登记代表性案例，也可关联本人实际执行的问数。 */}
            {sourceVerification && (
              <Form.Item name="sourceRecheckMode" label="复验方式">
                <Select
                  virtual={false}
                  options={[
                    { value: 'MANUAL', label: '人工只读核对' },
                    {
                      value: 'QUESTION',
                      label: '用原问题只读复验',
                      disabled: !resolution?.canReplayQuestion,
                    },
                  ]}
                />
              </Form.Item>
            )}
            <Form.Item
              name="decision"
              label="复验结论"
              rules={[{ required: true, message: '请选择复验结论' }]}
            >
              <Select
                virtual={false}
                options={[
                  { value: 'PASSED', label: '通过' },
                  { value: 'FAILED', label: '未通过' },
                ]}
              />
            </Form.Item>
            <TextField name="testScope" label="复验场景" />
            <TextField name="expected" label="期望结果" max={500} />
            <TextField name="actual" label="实际结果" />
            <TextField name="dataBasis" label="数据与复验依据" />
          </>
        )}
        {/* 说明结案需要明确原因，所有自由文本只在后台保存。 */}
        {closing && (
          <>
            {kind === 'explain' && (
              <Form.Item
                name="reason"
                label="结案原因"
                rules={[{ required: true, message: '请选择结案原因' }]}
              >
                <Select
                  virtual={false}
                  options={Object.entries(closeReasons).map(([value, label]) => ({ value, label }))}
                />
              </Form.Item>
            )}
            <TextField name="conclusion" label="结案结论" />
            <TextField name="basis" label="结案依据" />
          </>
        )}
      </Form>
    </Modal>
  );
}
