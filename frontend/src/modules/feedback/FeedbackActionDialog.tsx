import { Alert, App, Button, Checkbox, Descriptions, Form, Input, Modal, Select } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { useDeferredValue, useEffect, useRef, useState, type ComponentRef } from 'react';
import { ApiError, api, mutation, queryString } from '../../shared/api';
import { applyFormError } from '../../shared/formErrors';
import { useReturnFocus } from '../../shared/useReturnFocus';
import type { Page } from '../iam/types';
import { directions, type FeedbackTask, type IssueDetail } from './types';

/**
 * 分派、核查和任务协调用同一组版本、回执与焦点处理。
 */
export type FeedbackAction = 'assign' | 'review' | 'add-task' | 'assign-task' | 'task-status';

/** 候选只包含空闲筛选和分派所需的公开身份。 */
type Candidate = { id: string; displayName: string; username: string };

/** 表单草稿的归属由打开时的事项与版本固定。 */
type Fields = {
  assigneeId?: string;
  direction?: keyof typeof directions;
  reason?: string;
  basis?: string;
  rootCause?: string;
  /** 退出类型或恢复后的在办状态。 */
  status?: FeedbackTask['status'];
  /** 退出后剩余影响及跟进安排。 */
  remainingImpact?: string;
  /** 本次退出已确认无未覆盖影响。 */
  remainingImpactCovered?: boolean;
};

/** 分派先核对所选人员和依据，核查记录可直接保存。 */
export function FeedbackActionDialog({
  kind,
  detail,
  task,
  refresh,
  close,
}: {
  kind: FeedbackAction;
  detail: IssueDetail;
  task?: FeedbackTask;
  refresh: () => Promise<IssueDetail | undefined>;
  close: () => void;
}) {
  useReturnFocus();
  const [form] = Form.useForm<Fields>();
  const { message } = App.useApp();
  const [version, setVersion] = useState(detail.version);
  const [keyword, setKeyword] = useState('');
  const search = useDeferredValue(keyword.trim());
  const [preview, setPreview] = useState<Fields>();
  const [assignee, setAssignee] = useState<{ value: string; label: string }>();
  const [busy, setBusy] = useState(false);
  const [conflict, setConflict] = useState(false);
  const [uncertain, setUncertain] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const inFlight = useRef(false);
  const confirmButton = useRef<ComponentRef<typeof Button>>(null);
  const attempt = useRef<{ key: string; body: Fields & { expectedVersion: number } } | undefined>(
    undefined,
  );
  const adding = kind === 'add-task';
  const assigningTask = kind === 'assign-task';
  const disposition = kind === 'task-status';
  const assigning = kind === 'assign' || adding || assigningTask;
  const reassigning = assigningTask || (kind === 'assign' && Boolean(detail.issue.assigneeId));
  const exited = task?.status === 'NO_CHANGE' || task?.status === 'CANCELLED';
  const [restoring] = useState(exited);
  const dispositionLabel = restoring ? '恢复办理' : '退出任务';
  const reasonLabel = adding ? '任务范围' : disposition ? '办理依据' : '分派依据';
  const directionLabel = adding || assigningTask ? '任务类型' : '初步方向';
  const canSave = disposition
    ? Boolean(task?.canChangeStatus && exited === restoring)
    : assigningTask
      ? Boolean(task?.canAssign)
      : assigning
        ? detail.canAssign
        : (task?.canReview ?? detail.canReview);
  const base = `/feedback/issues/${detail.issue.id}`;
  const taskBase = task ? `${base}/tasks/${task.id}` : base;
  const path = disposition
    ? taskBase + '/dispositions'
    : adding
      ? base + '/tasks'
      : assigning
        ? taskBase + '/assignments'
        : base + '/reviews' + (task ? `?task=${task.id}` : '');
  const authority =
    assigning || (disposition && detail.canAssign) ? 'feedback.assign' : 'feedback.process';
  const title = adding
    ? '新增处理任务'
    : disposition
      ? dispositionLabel
      : reassigning
        ? '重新分派'
        : assigning
          ? '分派事项'
          : '记录核查';
  const candidates = useQuery({
    queryKey: ['feedback-assignees', search],
    enabled: assigning && canSave,
    retry: false,
    queryFn: () =>
      api<Page<Candidate>>('/feedback/assignees?' + queryString({ keyword: search, pageSize: 30 })),
  });
  const options =
    candidates.data?.items
      .map((person) => ({
        value: person.id,
        label: `${person.displayName}（${person.username}）`,
      }))
      .filter((option) => !assigningTask || option.value !== task?.assigneeId) ?? [];
  if (assignee && !options.some((option) => option.value === assignee.value)) {
    // 搜索其他人员时保留当前已选人员的显示名称。
    options.unshift(assignee);
  }

  useEffect(() => {
    // 核对步骤更换正文时，将键盘焦点移到实际的确认入口。
    if (preview) confirmButton.current?.focus({ preventScroll: true });
  }, [preview]);

  /** 当前失败可修正，回包丢失复用原编号和原命令。 */
  async function submit(fields: Fields) {
    if (inFlight.current || conflict || !canSave) return;
    const body = disposition
      ? {
          expectedVersion: version,
          status: restoring ? 'IN_PROGRESS' : fields.status,
          reason: fields.reason?.trim(),
          remainingImpact: restoring ? undefined : fields.remainingImpact?.trim(),
          remainingImpactCovered: !restoring && fields.remainingImpactCovered,
        }
      : assigning
        ? {
            expectedVersion: version,
            assigneeId: fields.assigneeId,
            direction: fields.direction,
            reason: fields.reason?.trim(),
          }
        : {
            expectedVersion: version,
            basis: fields.basis?.trim(),
            rootCause: fields.rootCause?.trim(),
          };
    const request =
      uncertain && attempt.current ? attempt.current : { key: crypto.randomUUID(), body };
    attempt.current = request;
    inFlight.current = true;
    setBusy(true);
    setError(null);
    try {
      await mutation(path, 'POST', request.body, authority, request.key);
      await refresh();
      message.success(
        disposition
          ? restoring
            ? '任务已恢复'
            : '任务已退出'
          : assigning
            ? '已分派'
            : '已保存核查记录',
      );
      close();
    } catch (failure) {
      if (failure instanceof ApiError && failure.code === 409001) {
        // 不用新版本静默覆盖别人的核查或转派。
        setPreview(undefined);
        setConflict(true);
      } else if (failure instanceof ApiError) {
        setPreview(undefined);
        setUncertain(false);
        setError(applyFormError(form, failure));
        if (failure.code === 403003) void refresh();
      } else {
        setUncertain(true);
        setError(failure instanceof Error ? failure.message : '暂时无法核实提交结果');
      }
    } finally {
      inFlight.current = false;
      setBusy(false);
    }
  }

  /** 用户主动核对新版本后才能把原草稿用于新的提交。 */
  async function rebase() {
    setBusy(true);
    setError(null);
    try {
      const current = await refresh();
      if (current) {
        setVersion(current.version);
        setConflict(false);
        setUncertain(false);
      } else {
        setError('暂时无法读取当前事项');
      }
    } finally {
      setBusy(false);
    }
  }

  /** 返回修改保留草稿和字段焦点，普通取消关闭当前操作。 */
  function cancel() {
    if (busy) return;
    if (preview && !uncertain) {
      setPreview(undefined);
      requestAnimationFrame(() => form.scrollToField('assigneeId', { focus: true }));
    } else {
      close();
    }
  }

  /** 已核对的草稿和待确认回执沿用原输入，编辑阶段先通过表单校验。 */
  function confirm() {
    if (uncertain && attempt.current) void submit(attempt.current.body);
    else if (preview) void submit(preview);
    else form.submit();
  }

  return (
    <Modal
      centered
      open
      width={620}
      className="operation-dialog"
      title={preview ? '核对分派' : title + (task ? ` · 任务 #${task.id}` : '')}
      closable={!busy}
      mask={{ closable: false }}
      onCancel={cancel}
      footer={[
        <Button key="cancel" onClick={cancel} disabled={busy}>
          {preview && !uncertain ? '返回修改' : '取消'}
        </Button>,
        <Button
          key="confirm"
          type="primary"
          ref={confirmButton}
          loading={busy}
          disabled={conflict || !canSave}
          onClick={confirm}
        >
          {uncertain
            ? '核对并重试'
            : preview
              ? '确认分派'
              : assigning
                ? '核对分派'
                : disposition
                  ? restoring
                    ? '恢复办理'
                    : '确认退出'
                  : '保存核查'}
        </Button>,
      ]}
    >
      {/* 冲突提示保留输入，直接反映这次未提交的原因。 */}
      {conflict && (
        <Alert
          className="form-alert"
          showIcon
          type="warning"
          title="事项已更新"
          description="读取当前事项后，可以保留草稿继续核对。"
          action={
            <Button size="small" loading={busy} onClick={() => void rebase()}>
              读取最新版本
            </Button>
          }
        />
      )}
      {!canSave && (
        <Alert className="form-alert" showIcon type="warning" title="当前已无权执行此操作" />
      )}
      {error && <Alert className="form-alert" showIcon type="error" title={error} />}
      {/* 核对确认沿用同一组已验证字段。 */}
      {preview && (
        <Descriptions
          column={1}
          size="small"
          items={[
            { key: 'issue', label: '事项编号', children: '#' + detail.issue.id },
            { key: 'owner', label: '负责人', children: assignee?.label ?? preview.assigneeId },
            {
              key: 'direction',
              label: directionLabel,
              children: preview.direction && directions[preview.direction],
            },
            {
              key: 'reason',
              label: reasonLabel,
              children: <span className="feedback-text">{preview.reason}</span>,
            },
          ]}
        />
      )}
      <Form<Fields>
        form={form}
        layout="vertical"
        hidden={Boolean(preview)}
        disabled={busy || uncertain || !canSave}
        scrollToFirstError={{ focus: true }}
        initialValues={{
          direction: task?.direction ?? (adding ? undefined : detail.direction),
          status: 'NO_CHANGE',
        }}
        onFinish={(fields) => {
          // 表单只展示动作需要的字段，分派保留单独确认。
          setError(null);
          if (assigning) setPreview(fields);
          else void submit(fields);
        }}
      >
        {/* 负责人候选受服务端过滤，转派时明确选择本次人员。 */}
        {assigning ? (
          <>
            <div className="feedback-assignment-fields">
              <Form.Item
                label={directionLabel}
                name="direction"
                rules={[{ required: true, message: `请选择${directionLabel}` }]}
              >
                <Select
                  virtual={false}
                  disabled={assigningTask}
                  options={Object.entries(directions)
                    .filter(
                      ([value]) =>
                        !adding || ['KNOWLEDGE', 'TECHNICAL', 'SOURCE_DATA'].includes(value),
                    )
                    .map(([value, label]) => ({ value, label }))}
                />
              </Form.Item>
              <Form.Item
                label="负责人"
                name="assigneeId"
                rules={[{ required: true, message: '请选择负责人' }]}
              >
                <Select
                  virtual={false}
                  showSearch={{ filterOption: false, onSearch: setKeyword }}
                  options={options}
                  placeholder="搜索姓名或登录名"
                  loading={candidates.isFetching}
                  notFoundContent={
                    candidates.isError
                      ? '读取失败'
                      : candidates.isFetching
                        ? '读取中'
                        : '无匹配人员'
                  }
                  onChange={(value: string) =>
                    setAssignee(options.find((option) => option.value === value))
                  }
                />
              </Form.Item>
            </div>
            {candidates.isError && (
              <Button type="link" onClick={() => void candidates.refetch()}>
                重新读取负责人
              </Button>
            )}
            <Form.Item
              label={reasonLabel}
              name="reason"
              rules={[{ required: true, whitespace: true, message: `请填写${reasonLabel}` }]}
            >
              <Input.TextArea rows={3} maxLength={1000} />
            </Form.Item>
          </>
        ) : disposition ? (
          <>
            {/* 任务退出影响结案资格，保留具体影响核对和处置依据。 */}
            <Alert
              className="form-alert"
              type="warning"
              showIcon
              title={restoring ? '恢复后需要重新处理和复验' : '退出会使已有复验失效'}
            />
            {!restoring && (
              <Form.Item label="退出类型" name="status">
                <Select
                  virtual={false}
                  options={[
                    { value: 'NO_CHANGE', label: '无需变更' },
                    { value: 'CANCELLED', label: '取消任务' },
                  ]}
                />
              </Form.Item>
            )}
            <Form.Item
              label={reasonLabel}
              name="reason"
              rules={[{ required: true, whitespace: true, message: `请填写${reasonLabel}` }]}
            >
              <Input.TextArea rows={3} maxLength={1000} />
            </Form.Item>
            {!restoring && (
              <>
                <Form.Item
                  label="剩余影响及安排"
                  name="remainingImpact"
                  rules={[{ required: true, whitespace: true, message: '请说明剩余影响及安排' }]}
                >
                  <Input.TextArea rows={2} maxLength={1000} />
                </Form.Item>
                <Form.Item
                  name="remainingImpactCovered"
                  valuePropName="checked"
                  rules={[
                    {
                      validator: async (_, value) => {
                        // 未覆盖影响需保留在办任务。
                        if (!value) throw new Error('请先确认没有未覆盖的影响');
                      },
                    },
                  ]}
                >
                  <Checkbox>已确认没有未覆盖的影响</Checkbox>
                </Form.Item>
              </>
            )}
          </>
        ) : (
          <>
            <Form.Item label="核实根因" name="rootCause">
              <Input.TextArea rows={2} maxLength={500} placeholder="原因未明时可留空" />
            </Form.Item>
            <Form.Item
              label="核查依据"
              name="basis"
              rules={[{ required: true, whitespace: true, message: '请填写核查依据' }]}
            >
              <Input.TextArea rows={4} maxLength={1000} />
            </Form.Item>
          </>
        )}
      </Form>
    </Modal>
  );
}
