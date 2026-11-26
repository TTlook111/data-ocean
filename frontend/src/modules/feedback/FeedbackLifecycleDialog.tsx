import { Alert, App, Button, Descriptions, Form, Input, Modal, Select } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { useDeferredValue, useEffect, useRef, useState, type ComponentRef } from 'react';
import { api, ApiError, queryString } from '../../shared/api';
import { applyFormError } from '../../shared/formErrors';
import { useReturnFocus } from '../../shared/useReturnFocus';
import type { Page } from '../iam/types';
import {
  isSupplementPending,
  type FeedbackSupplementDisposition,
  type Issue,
  type IssueDetail,
} from './types';
import { useFeedbackCommand } from './useFeedbackCommand';

/**
 * 原事项及主事项的人工办理动作。
 */
export type LifecycleAction = 'supplement' | 'dispose-supplement' | 'merge' | 'unmerge' | 'reopen';

/**
 * 表单通过正式接口复用当前权限及错误回查。
 */
const actions = {
  supplement: {
    label: '请求用户补充',
    path: 'supplement-requests',
    operation: 'feedback.process',
    reason: '需要补充的业务信息',
  },
  'dispose-supplement': {
    label: '处置补充请求',
    path: 'supplement-requests',
    operation: 'feedback.process',
    reason: '处置原因',
  },
  merge: {
    label: '合并重复事项',
    path: 'merges',
    operation: 'feedback.assign',
    reason: '相同根因和处理范围的依据',
  },
  unmerge: {
    label: '纠正合并',
    path: 'merge-corrections',
    operation: 'feedback.assign',
    reason: '纠正依据',
  },
  reopen: {
    label: '凭新证据重开',
    path: 'reopens',
    operation: 'feedback.process',
    reason: '新证据或同范围复发依据',
  },
} as const;

/**
 * 同一表单用于选择原问或主事项，草稿始终绑定打开时的事项版本。
 */
type Fields = {
  /** 补充所属原问题。 */
  requestId?: string;
  /** 撤回请求或记录无法获得补充的选择。 */
  disposition?: FeedbackSupplementDisposition;
  /** 合并目标主事项编号。 */
  targetId?: string;
  /** 操作所需的说明。 */
  reason: string;
};

/**
 * 补充、合并、纠正与重开都在原事项详情中办理。
 */
export function FeedbackLifecycleDialog({
  detail,
  kind,
  supplementId,
  close,
  refresh,
}: {
  detail: IssueDetail;
  kind: LifecycleAction;
  /** 待处置的确切补充请求。 */
  supplementId?: string;
  close: () => void;
  refresh: () => Promise<IssueDetail | undefined>;
}) {
  useReturnFocus();
  const [form] = Form.useForm<Fields>();
  const { message } = App.useApp();
  const [version, setVersion] = useState(detail.version);
  const [busy, setBusy] = useState(false);
  const [stale, setStale] = useState(false);
  const { uncertain, send } = useFeedbackCommand(refresh);
  const [error, setError] = useState<string | null>(null);
  const [keyword, setKeyword] = useState('');
  const search = useDeferredValue(keyword.trim());
  const [proposal, setProposal] = useState<{
    fields: Fields;
    targetVersion: number;
    targetTitle: string;
  }>();
  const inFlight = useRef(false);
  const confirmButton = useRef<ComponentRef<typeof Button>>(null);
  const lifecycle = detail.lifecycle;
  const targets = lifecycle?.supplementTargets ?? [];
  const action = actions[kind];
  const merge = kind === 'merge';
  const disposing = kind === 'dispose-supplement';
  const requestToDispose = lifecycle?.supplements.find((item) => item.id === supplementId);
  const allows =
    kind === 'supplement'
      ? targets.length > 0
      : Boolean(
          disposing
            ? lifecycle?.canDisposeSupplement &&
                requestToDispose &&
                isSupplementPending(requestToDispose)
            : kind === 'merge'
              ? lifecycle?.canMerge
              : kind === 'unmerge'
                ? lifecycle?.canUnmerge
                : lifecycle?.canReopen,
        );
  const candidates = useQuery({
    queryKey: ['feedback-merge-candidates', detail.issue.sourceId, search],
    enabled: merge && allows,
    retry: false,
    queryFn: () =>
      api<Page<Issue>>(
        '/feedback/issues?' +
          queryString({
            sourceId: detail.issue.sourceId,
            scope: 'MERGE',
            keyword: search,
            pageSize: 30,
          }),
      ),
  });
  useEffect(() => {
    // 核对页替换了表单正文，将键盘焦点移至确认按钮。
    if (proposal) confirmButton.current?.focus({ preventScroll: true });
  }, [proposal]);

  /**
   * 确认后的动作沿用固定的两端版本，回包不明时使用原编号和原提案。
   */
  async function submit(fields: Fields) {
    if (inFlight.current || stale || (!allows && !uncertain)) return;
    inFlight.current = true;
    if (merge && !proposal && !uncertain) {
      setError(null);
      setBusy(true);
      try {
        // 用本次提交字段读取主事项，避免快速选中后跟随表单监听的旧版本。
        const main = await api<IssueDetail>('/feedback/issues/' + fields.targetId);
        setProposal({
          fields,
          targetVersion: main.version,
          targetTitle: main.issue.summary ?? `事项 #${main.issue.id}`,
        });
      } catch {
        setError('暂时无法核对主事项');
      } finally {
        inFlight.current = false;
        setBusy(false);
      }
      return;
    }
    const reviewed = proposal?.fields ?? fields;
    const body =
      kind === 'supplement'
        ? {
            expectedVersion: version,
            requestId: reviewed.requestId,
            prompt: reviewed.reason.trim(),
          }
        : {
            expectedVersion: version,
            reason: reviewed.reason.trim(),
            ...(disposing ? { disposition: reviewed.disposition } : {}),
            ...(proposal
              ? { targetId: reviewed.targetId, targetVersion: proposal.targetVersion }
              : {}),
          };
    setBusy(true);
    setError(null);
    try {
      await send(
        `/feedback/issues/${detail.issue.id}/${action.path}${disposing ? `/${supplementId}/dispositions` : ''}`,
        body,
        action.operation,
      );
      await refresh();
      message.success(
        kind === 'supplement'
          ? '已请求补充'
          : disposing
            ? '已处置补充请求'
            : kind === 'merge'
              ? '已合并到主事项'
              : kind === 'unmerge'
                ? '已解除合并'
                : '已重开事项',
      );
      close();
    } catch (failure) {
      if (failure instanceof ApiError) {
        // 业务冲突需要用户重新读取所见版本，再决定本次提案。
        setProposal(undefined);
        if (failure.code === 409001) setStale(true);
        else setError(applyFormError(form, failure));
      } else {
        setError('提交结果暂未确认，可使用原编号核对并重试');
      }
    } finally {
      inFlight.current = false;
      setBusy(false);
    }
  }

  /**
   * 读取原事项最新版本，下一次核对重新读取主事项版本。
   */
  async function reload() {
    const current = await refresh();
    if (current) {
      setVersion(current.version);
      setStale(false);
    }
  }

  return (
    <Modal
      centered
      open
      width={640}
      title={proposal ? '核对合并范围' : action.label}
      onCancel={busy ? undefined : close}
      mask={{ closable: false }}
      footer={[
        <Button
          key="cancel"
          disabled={busy}
          onClick={() => {
            if (proposal && !uncertain) setProposal(undefined);
            else close();
          }}
        >
          {proposal && !uncertain ? '返回修改' : '取消'}
        </Button>,
        <Button
          key="submit"
          type="primary"
          ref={confirmButton}
          loading={busy}
          disabled={stale || (!allows && !uncertain)}
          onClick={() => {
            if (proposal || uncertain) void submit(proposal?.fields ?? { reason: '' });
            else form.submit();
          }}
        >
          {uncertain ? '核对并重试' : proposal ? '确认合并' : merge ? '核对合并范围' : action.label}
        </Button>,
      ]}
    >
      {/* 合并核对明确两端、证据及原始记录的归属。 */}
      {proposal && (
        <Descriptions
          column={1}
          items={[
            { key: 'member', label: '原事项', children: '#' + detail.issue.id },
            {
              key: 'main',
              label: '主事项',
              children: `#${proposal.fields.targetId} · ${proposal.targetTitle}`,
            },
            { key: 'reason', label: '合并依据', children: proposal.fields.reason },
          ]}
        />
      )}
      {stale && (
        <Alert
          type="warning"
          showIcon
          title="事项已更新"
          action={
            <Button size="small" onClick={() => void reload()}>
              读取最新版本
            </Button>
          }
        />
      )}
      {!allows && <Alert type="warning" showIcon title="当前事项已无法执行此操作" />}
      {error && <Alert type="error" showIcon title={error} />}
      <Form<Fields>
        form={form}
        layout="vertical"
        hidden={Boolean(proposal)}
        disabled={busy || uncertain || !allows}
        initialValues={{ requestId: targets.length === 1 ? targets[0].requestId : undefined }}
        scrollToFirstError={{ focus: true }}
        onFinish={(fields) => void submit(fields)}
      >
        {/* 原问按原提问人、事项和获准问题文本区分。 */}
        {kind === 'supplement' && (
          <Form.Item name="requestId" label="原提问人与问题" rules={[{ required: true }]}>
            <Select
              virtual={false}
              options={targets.map((entry) => ({
                value: entry.requestId,
                label: `${entry.name} · #${entry.issueId}${entry.question ? ' · ' + entry.question : ''}`,
              }))}
            />
          </Form.Item>
        )}
        {/* 标明被处置的原事项，确保结束的是这条请求的等待。 */}
        {disposing && (
          <>
            <Descriptions
              column={1}
              items={[
                {
                  key: 'issue',
                  label: '原事项',
                  children: `#${requestToDispose?.issueId ?? detail.issue.id}`,
                },
                {
                  key: 'prompt',
                  label: '补充请求',
                  children: requestToDispose?.prompt ?? '内容受限',
                },
              ]}
            />
            <Alert type="warning" showIcon title="处置后原提问人将无法回复这条请求" />
            <Form.Item
              name="disposition"
              label="处置方式"
              rules={[{ required: true, message: '请选择处置方式' }]}
            >
              <Select
                virtual={false}
                options={[
                  { value: 'WITHDRAWN', label: '撤回补充请求' },
                  { value: 'UNAVAILABLE', label: '无法取得补充' },
                ]}
              />
            </Form.Item>
          </>
        )}
        {/* 只从同源未结事项选择主事项，按编号或摘要搜索。 */}
        {merge && (
          <Form.Item name="targetId" label="同源主事项" rules={[{ required: true }]}>
            <Select
              virtual={false}
              showSearch={{ filterOption: false, onSearch: setKeyword }}
              placeholder="搜索事项编号或摘要"
              loading={candidates.isFetching}
              notFoundContent={candidates.isError ? '主事项读取失败' : '无匹配事项'}
              options={candidates.data?.items
                .filter((entry) => entry.id !== detail.issue.id)
                .map((entry) => ({
                  value: entry.id,
                  label: `#${entry.id} · ${entry.summary ?? entry.sourceName}`,
                }))}
            />
          </Form.Item>
        )}
        <Form.Item
          name="reason"
          label={action.reason}
          rules={[{ required: true, whitespace: true }]}
        >
          <Input.TextArea rows={4} maxLength={1000} />
        </Form.Item>
      </Form>
    </Modal>
  );
}
