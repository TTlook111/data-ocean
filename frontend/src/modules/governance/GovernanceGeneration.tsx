import { Alert, App, Button, Checkbox, Modal, Space, Spin } from 'antd';
import { useMutation, useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { api, ApiError, mutation } from '../../shared/api';
import { useReturnFocus } from '../../shared/useReturnFocus';
import { modelFailureLabels } from '../models/types';
import type { Definition, Generation, Item, Suggestion } from './types';

/**
 * 生成前给出当次可见的实际资料修订。
 */
type Preview = {
  /**
   * 只在有原文查看权时带可识别的资料元数据。
   */
  notes?: { title: string; revision: number; noteId: string }[] | null;
  /**
   * 固定当次实际输入、结构和目标的核对凭据。
   */
  reviewToken: string;
};

/**
 * 提交真实任务、回读实际状态，最后按差异保留人工输入。
 */
export function GovernanceGeneration({
  source,
  path,
  item,
  fields,
  current,
  apply,
  close,
}: {
  source: string;
  path: string;
  item: Item;
  fields: { key: keyof Definition; label: string }[];
  current: Definition;
  apply: (suggestion: Suggestion, paths: (keyof Definition)[]) => void;
  close: () => void;
}) {
  useReturnFocus();
  const { message } = App.useApp();
  const [command] = useState(() => crypto.randomUUID());
  const [request, setRequest] = useState<string>();
  const [selected, setSelected] = useState<(keyof Definition)[]>();
  const base = path.split('/items/')[0];
  const subject = item.target.kind === 'COLUMN' ? '字段解释' : '表说明';
  const scope = {
    targets: [
      {
        kind: item.target.kind,
        targetId: item.target.targetId,
        precondition: {
          expectedVersion: item.version,
          expectedStructureRevisionId:
            item.structureBasis.columnRevisionId ?? item.structureBasis.objectRevisionId,
        },
      },
    ],
  };
  const preview = useQuery({
    queryKey: [
      'governance-ai-preview',
      path,
      item.version,
      item.structureBasis.objectRevisionId,
      item.structureBasis.columnRevisionId,
    ],
    queryFn: () => api<Preview>(base + '/generation-preview', { method: 'POST', body: scope }),
    enabled: request === undefined,
    staleTime: Infinity,
    gcTime: 0,
    retry: false,
  });
  const launch = useMutation({
    mutationFn: () =>
      mutation<Generation & { targetId?: string }>(
        base + '/generations',
        'POST',
        { ...scope, reviewToken: preview.data?.reviewToken },
        'governance.suggest',
        command,
      ),
    onSuccess: (task) => {
      // 回包不明时公共命令读取仍能恢复稳定任务编号。
      setRequest(task.requestId ?? task.targetId);
    },
    onError: async (error) => {
      // 过期的凭据由重新核对刷新，用户仍需明确提交。
      void message.error(error.message);
      if (error instanceof ApiError && error.code === 409002) {
        await preview.refetch();
      }
    },
  });
  const task = useQuery({
    queryKey: ['governance', source, 'generation', request],
    queryFn: () => api<Generation>('/governance/sources/' + source + '/generations/' + request),
    enabled: Boolean(request),
    refetchInterval: ({ state }) =>
      ['SUCCEEDED', 'PARTIAL_FAILED', 'FAILED'].includes(state.data?.state ?? '') ? false : 1000,
  });
  const suggestion = task.data?.targets?.[0]?.suggestion;
  const differences = suggestion
    ? fields.filter(
        ({ key }) =>
          suggestion.content[key] &&
          JSON.stringify(suggestion.content[key]) !== JSON.stringify(current[key]),
      )
    : [];
  const checked = selected ?? differences.filter(({ key }) => !current[key]).map(({ key }) => key);
  const failed = task.data?.state === 'FAILED';
  const withheld = task.data?.state === 'SUCCEEDED' && !suggestion;

  /**
   * 生成和填入是当前弹窗的连续操作，写入仍通过治理草稿。
   */
  function advance() {
    if (!request) {
      // 预览之后才接收任务。
      launch.mutate();
    } else if (suggestion && item.actions.canEdit) {
      // 只应用用户明确选中的内容。
      apply(suggestion, checked);
    } else {
      close();
    }
  }

  return (
    <Modal
      open
      centered
      className="app-dialog"
      width={suggestion ? 900 : 680}
      title={suggestion ? 'AI 建议对比' : '生成 AI 建议'}
      onCancel={close}
      onOk={advance}
      confirmLoading={launch.isPending}
      cancelText="关闭"
      okText={!request ? '生成建议' : suggestion && item.actions.canEdit ? '填入草稿' : '完成'}
      okButtonProps={{
        disabled: !request
          ? !preview.data || preview.isFetching || preview.isError
          : Boolean(suggestion && item.actions.canEdit && !checked.length) ||
            (!suggestion && !failed && !withheld),
      }}
    >
      {/* 预览只展示可见修订和当前范围。 */}
      {!request && (
        <div className="gov-generation">
          <dl className="gov-generation-summary">
            <dt>生成范围</dt>
            <dd>{subject}</dd>
          </dl>
          {preview.isFetching && (
            <Space role="status">
              <Spin size="small" />
              正在核对可用资料
            </Space>
          )}
          {preview.error && (
            <Alert
              type="error"
              title={preview.error.message}
              action={<Button onClick={() => void preview.refetch()}>重新核对</Button>}
            />
          )}
          {preview.data?.notes && (
            <section aria-label="本次取用的业务说明">
              <h3>
                业务说明 <small>{preview.data.notes.length} 份</small>
              </h3>
              {preview.data.notes.length ? (
                <ul className="gov-generation-notes">
                  {preview.data.notes.map((note) => (
                    <li key={note.noteId}>
                      <span>{note.title}</span>
                      <small>第 {note.revision} 版</small>
                    </li>
                  ))}
                </ul>
              ) : (
                <p className="gov-empty">当前没有可取用的业务说明</p>
              )}
            </section>
          )}
        </div>
      )}
      {/* 只展示已持久化的终态建议，模型错误保留当前输入。 */}
      {request && !suggestion && !failed && !withheld && !task.error && (
        <Space>
          <Spin size="small" />
          正在生成{subject}
        </Space>
      )}
      {withheld && <Alert type="warning" title="当前权限无法查看生成结果" />}
      {task.error && <Alert type="error" title={task.error.message} />}
      {failed && (
        <Alert
          type="error"
          title="生成未完成"
          description={
            modelFailureLabels[task.data?.failureCode ?? ''] ?? '模型服务未返回可用建议。'
          }
        />
      )}
      {suggestion && differences.length > 0 && (
        <Checkbox.Group
          className="gov-ai-choices"
          aria-label="选择填入草稿的内容"
          value={checked}
          onChange={(values) => setSelected(values as (keyof Definition)[])}
        >
          <div className="gov-ai-differences" role="table" aria-label="AI 建议对比">
            {/* 列头固定对照方向，长建议由弹窗正文承接滚动。 */}
            <div className="gov-ai-difference gov-ai-difference-head" role="row">
              <span role="columnheader">内容项</span>
              <span role="columnheader">当前内容</span>
              <span role="columnheader">AI 建议</span>
            </div>
            {/* 现有人工文本默认保留，空字段可直接填入候选内容。 */}
            {differences.map(({ key, label }) => (
              <div className="gov-ai-difference" key={key} role="row">
                <div role="rowheader">
                  <Checkbox value={key} disabled={!item.actions.canEdit}>
                    {label}
                  </Checkbox>
                </div>
                <p className="gov-pre-line" role="cell">
                  {typeof current[key] === 'string' ? current[key] || '—' : '—'}
                </p>
                <p className="gov-pre-line gov-ai-proposal" role="cell">
                  {typeof suggestion.content[key] === 'string' ? suggestion.content[key] : null}
                </p>
              </div>
            ))}
          </div>
        </Checkbox.Group>
      )}
      {suggestion && !differences.length && <Alert type="info" title="建议与当前内容相同" />}
    </Modal>
  );
}
