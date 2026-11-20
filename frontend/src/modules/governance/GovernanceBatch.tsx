import { Alert, Button, Checkbox, Modal, Select, Space, Spin, Tag } from 'antd';
import { useMutation, useQuery } from '@tanstack/react-query';
import { useEffect, useRef, useState } from 'react';
import { api, mutation, queryString } from '../../shared/api';
import { useReturnFocus } from '../../shared/useReturnFocus';
import type { Page } from '../iam/types';
import { modelFailureLabels } from '../models/types';
import { GenerationNotes, generationTarget, type GenerationPreview } from './GovernanceGeneration';
import type { ColumnRow, Generation, GenerationTarget, Item } from './types';
import { useGenerationTask } from './useGenerationTask';

/**
 * 批量生成保留业务条目名称及任务编号，关闭后仍可回看。
 */
export type BatchRun = {
  /**
   * 实际接收的批次编号。
   */
  request: string;
  /**
   * 生成范围内选定字段的技术名称。
   */
  fields: Record<string, string>;
};

/**
 * 批量发现与单独页签使用相同的候选类型。
 */
const discoveryNames = { RELATION: '发现关联候选', METRIC: '发现指标候选' };

/**
 * 每个批内目标只显示实际执行状态。
 */
const states: Record<string, { label: string; color?: string }> = {
  PENDING_DISPATCH: { label: '等待派发' },
  QUEUED: { label: '已排队' },
  RUNNING: { label: '生成中', color: 'processing' },
  SUCCEEDED: { label: '已完成', color: 'success' },
  FAILED: { label: '未完成', color: 'error' },
};

/**
 * 在同一弹窗选择真实范围、核对资料并回看每项独立结果。
 */
export function GovernanceBatch({
  source,
  sourceName,
  base,
  table,
  run,
  accepted,
  onView,
  close,
}: {
  source: string;
  sourceName: string;
  base: string;
  table: Item;
  run?: BatchRun;
  accepted: (run?: BatchRun) => void;
  onView: (result: GenerationTarget) => void;
  close: () => void;
}) {
  useReturnFocus();
  const [keyword, setKeyword] = useState('');
  const [fields, setFields] = useState<{ value: string; label: string }[]>([]);
  const [selected, setSelected] = useState<string[]>([]);
  const [reviewing, setReviewing] = useState(false);
  const selectionSummary = useRef<HTMLParagraphElement>(null);
  const [command, setCommand] = useState(() => crypto.randomUUID());
  const tableSelected = selected.includes('TABLE');
  const discoveryKinds = selected.filter((value) => value !== 'TABLE');
  const count = Number(tableSelected) + fields.length + discoveryKinds.length;
  const columns = useQuery({
    queryKey: ['governance', base, 'batch-fields', keyword],
    enabled: !reviewing && !run,
    queryFn: () =>
      api<Page<ColumnRow>>(base + '/columns?' + queryString({ keyword, pageSize: 30 })),
  });
  const preview = useQuery({
    queryKey: ['governance', base, 'batch-preview', command],
    enabled: reviewing && !run,
    staleTime: Infinity,
    gcTime: 0,
    retry: false,
    queryFn: async () => {
      // 刚核对的条目版本与最终预览凭据作为一个请求范围使用。
      const items = await Promise.all([
        ...(tableSelected ? [api<Item>(base + '/items/TABLE/' + table.target.objectId)] : []),
        ...fields.map((field) => api<Item>(base + '/items/COLUMN/' + field.value)),
      ]);
      const scope = { targets: items.map(generationTarget), discoveryKinds };
      const result = await api<GenerationPreview>(base + '/generation-preview', {
        method: 'POST',
        body: scope,
      });
      return { scope, result };
    },
  });
  const launch = useMutation({
    mutationFn: () =>
      mutation<Generation & { targetId?: string }>(
        base + '/generations',
        'POST',
        { ...preview.data?.scope, reviewToken: preview.data?.result.reviewToken },
        'governance.suggest',
        command,
      ),
    onSuccess: (result) => {
      // 请求答复丢失时依旧使用公共命令查询恢复同一批次。
      const request = result.requestId ?? result.targetId;
      if (request) {
        accepted({
          request,
          fields: Object.fromEntries(fields.map(({ value, label }) => [value, label])),
        });
      }
    },
  });
  const task = useGenerationTask(source, run?.request);
  const targets = task.data?.targets ?? [];
  const done = targets.filter(({ state }) => state === 'SUCCEEDED' || state === 'FAILED').length;
  const failed = targets.filter(({ state }) => state === 'FAILED').length;

  useEffect(() => {
    // 切换到核对阶段时，把键盘阅读位置放在实际选中条目上。
    if (reviewing && !run) selectionSummary.current?.focus({ preventScroll: true });
  }, [reviewing, run]);

  /**
   * 修改范围会生成新的幂等命令，重复提交相同范围仍使用原编号。
   */
  function edit() {
    setReviewing(false);
    setCommand(crypto.randomUUID());
    launch.reset();
  }

  return (
    <Modal
      open
      centered
      className="app-dialog"
      title={run ? '批量生成结果' : '批量生成 AI 建议'}
      width={680}
      onCancel={close}
      footer={
        /* 页脚操作与当前阶段一致，关闭任务不会改写现有业务定义。 */
        run ? (
          <Space>
            {done > 0 && done === targets.length && (
              <Button
                onClick={() => {
                  accepted(undefined);
                  edit();
                }}
              >
                新建批次
              </Button>
            )}
            <Button type="primary" onClick={close}>
              完成
            </Button>
          </Space>
        ) : (
          <Space>
            {reviewing && (
              <Button disabled={launch.isPending} onClick={edit}>
                返回选择
              </Button>
            )}
            <Button
              type="primary"
              loading={preview.isFetching || launch.isPending}
              disabled={
                reviewing
                  ? !preview.data
                  : count === 0 || fields.length + Number(tableSelected) > 10
              }
              onClick={() => {
                // 先核对范围，实际提交仍由用户在下一步触发。
                if (reviewing) launch.mutate();
                else setReviewing(true);
              }}
            >
              {reviewing ? '生成建议' : '核对范围'}
            </Button>
          </Space>
        )
      }
    >
      {/* 固定数据源和范围并列，字号、颜色及间距沿用治理页面。 */}
      <div className="gov-generation">
        <dl className="gov-generation-summary gov-batch-summary">
          <dt>数据源</dt>
          <dd>{sourceName}</dd>
          <dt>生成范围</dt>
          <dd aria-live={run ? 'polite' : undefined}>
            {run
              ? targets.length
                ? done + ' / ' + targets.length + ' 项完成'
                : '正在读取'
              : count + ' 项'}
          </dd>
        </dl>
        {!reviewing && !run && (
          <div className="gov-batch-selection">
            {/* 表说明和两个发现类型可组合，字段从当前表的成功目录选择。 */}
            <Checkbox.Group aria-label="生成内容" value={selected} onChange={setSelected}>
              <Checkbox value="TABLE" aria-label="表说明" disabled={fields.length === 10}>
                表说明
              </Checkbox>
              {Object.entries(discoveryNames).map(([value, label]) => (
                <Checkbox key={value} value={value} aria-label={label}>
                  {label}
                </Checkbox>
              ))}
            </Checkbox.Group>
            <label htmlFor="gov-batch-fields">字段</label>
            <Select
              id="gov-batch-fields"
              aria-label="搜索并选择字段"
              mode="multiple"
              labelInValue
              allowClear
              maxCount={10 - Number(tableSelected)}
              maxTagCount="responsive"
              placeholder="搜索并选择字段"
              showSearch={{ filterOption: false, onSearch: setKeyword }}
              value={fields}
              onChange={setFields}
              loading={columns.isFetching}
              options={columns.data?.items.map((field) => ({
                value: field.target.targetId,
                label: field.name,
              }))}
            />
            {columns.error && (
              <Alert
                type="error"
                title={columns.error.message}
                action={
                  <Button size="small" onClick={() => void columns.refetch()}>
                    重新读取
                  </Button>
                }
              />
            )}
          </div>
        )}
        {reviewing && !run && (
          <>
            {/* 比对固定的真实资料修订，预览错误保留已选范围。 */}
            <p
              ref={selectionSummary}
              tabIndex={-1}
              className="gov-batch-targets"
              aria-label="选定条目"
            >
              {[
                ...(tableSelected ? ['表说明'] : []),
                ...fields.map(({ label }) => label),
                ...Object.entries(discoveryNames).flatMap(([value, label]) =>
                  discoveryKinds.includes(value) ? [label] : [],
                ),
              ].join('、')}
            </p>
            <GenerationNotes notes={preview.data?.result.notes} />
            {(preview.error || launch.error) && (
              <Alert type="error" title={(preview.error ?? launch.error)?.message} />
            )}
          </>
        )}
        {run && (
          <>
            {/* 每项独立回看，成功建议仍需进入原编辑器选择差异。 */}
            {task.isPending && <Spin size="small" />}
            {task.error && (
              <Alert
                type="error"
                title={task.error.message}
                action={
                  <Button size="small" onClick={() => void task.refetch()}>
                    重新读取
                  </Button>
                }
              />
            )}
            {failed > 0 && <Alert type="warning" title={failed + ' 项生成未完成'} />}
            <ul className="gov-batch-results" aria-label="批量生成条目">
              {targets.map((result) => {
                // 名称固定来自选择范围，发现结果用所属候选类型识别。
                const status = states[result.state];
                const name = result.discoveryKind
                  ? discoveryNames[result.discoveryKind]
                  : result.target.kind === 'TABLE'
                    ? '表说明'
                    : (run.fields[result.target.targetId] ?? result.target.targetId);
                return (
                  <li key={result.requestId}>
                    <div>
                      <strong>{name}</strong>
                      {result.failureCode && (
                        <small>
                          {modelFailureLabels[result.failureCode] ?? '模型未返回可用结果'}
                        </small>
                      )}
                    </div>
                    <Tag color={status?.color}>{status?.label ?? result.state}</Tag>
                    {result.state === 'SUCCEEDED' &&
                      (result.suggestion || result.discoveryKind) && (
                        <Button type="link" onClick={() => onView(result)}>
                          {result.discoveryKind ? '查看候选' : '查看建议'}
                        </Button>
                      )}
                  </li>
                );
              })}
            </ul>
          </>
        )}
      </div>
    </Modal>
  );
}
