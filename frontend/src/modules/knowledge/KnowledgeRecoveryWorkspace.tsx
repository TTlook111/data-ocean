import { Alert, Button, Steps, Tag } from 'antd';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useRef, useState } from 'react';
import { api } from '../../shared/api';
import { ReadState } from '../../shared/Records';
import { allowed, useSession } from '../../shared/session';
import {
  ValidationQuestionDialog,
  ValidationReport,
  ValidationSummary,
  type ValidationDetail,
  type ValidationQuestion,
} from './ReleaseValidationPanel';
import { RecoveryStartButton, recoveryStateLabels, type VersionUse } from './VersionUseControls';

/** 当前恢复任务和共用逐题报告。 */
type RunDetail = {
  /** 原正式版本的发布任务。 */
  taskId: string;
  /** 审核时仍要检查发布记录的确切版本。 */
  taskVersion: number;
  /** 该运行仍可用于当前暂停。 */
  current: boolean;
  /** 实际逐题证据，不进入普通发布记录。 */
  validation: ValidationDetail;
};

/** 使用原型的三阶段独立任务区回看和办理隔离恢复。 */
export function KnowledgeRecoveryWorkspace({
  source,
  sourceName,
  path,
  runId,
  title,
  revision,
  data,
  back,
  openRun,
}: {
  /** 工作区绑定的实际源。 */
  source: string;
  /** 原型页头的来源标签。 */
  sourceName?: string;
  /** 当前版本的后端地址。 */
  path: string;
  /** 正在查看的运行。 */
  runId: string;
  /** 本次正式文档名称。 */
  title: string;
  /** 固定的正式修订号。 */
  revision: number;
  /** 版本页共用的恢复状态。 */
  data?: VersionUse;
  /** 返回原生效版本。 */
  back: () => void;
  /** 切换本次或历史运行。 */
  openRun: (id: string) => void;
}) {
  const session = useSession();
  const cache = useQueryClient();
  const heading = useRef<HTMLHeadingElement>(null);
  const [taskQuestion, setTaskQuestion] = useState<{
    row: ValidationQuestion;
    judge: boolean;
    trigger: string;
  }>();
  const report = useQuery({
    queryKey: ['version-recovery-run', path, runId],
    queryFn: () => api<RunDetail>(path + '/recovery/' + encodeURIComponent(runId)),
    refetchInterval: (query) =>
      ['QUEUED', 'RUNNING'].includes(query.state.data?.validation.run?.state ?? '') ? 1200 : false,
  });
  const detail = report.data;
  const validation = detail?.validation;
  const run = validation?.run;
  const running = run && ['QUEUED', 'RUNNING'].includes(run.state);
  const passed = run?.state === 'RECOVERY_PASSED';
  const active = data?.recovery?.runId ?? runId;
  const stateLabel =
    run?.state && recoveryStateLabels[run.state as keyof typeof recoveryStateLabels];
  const expired = data?.recovery?.state === 'EXPIRED' && data.recovery.runId === runId;

  useEffect(() => {
    // 切换运行后将键盘焦点落在任务标题。
    heading.current?.focus({ preventScroll: true });
  }, [runId]);

  /** 回读同一运行和版本可办理状态。 */
  async function refresh() {
    await Promise.all([
      report.refetch(),
      cache.invalidateQueries({ queryKey: ['release-binding-controls', path] }),
    ]);
  }

  /** 关闭证据弹窗时把焦点交回原题。 */
  function closeQuestion() {
    const trigger = taskQuestion?.trigger;
    setTaskQuestion(undefined);
    requestAnimationFrame(() =>
      (document.getElementById(trigger ?? '') ?? heading.current)?.focus(),
    );
  }

  return (
    <article className="panel knowledge-build-workspace" aria-label="隔离恢复验证">
      {/* 任务作用于固定正式修订，办理路径始终回到 version 详情。 */}
      <header className="knowledge-build-heading">
        <div className="knowledge-build-identity">
          <h1 tabIndex={-1} ref={heading}>
            恢复验证 · r{revision}
          </h1>
          <small>{sourceName}</small>
        </div>
        <div className="knowledge-build-header-actions">
          <Button onClick={back}>返回版本详情</Button>
          {stateLabel && (
            <Tag
              color={expired ? 'error' : passed ? 'success' : running ? 'processing' : undefined}
            >
              {expired ? '验证依据已失效' : passed ? '验证已完成' : stateLabel}
            </Tag>
          )}
        </div>
      </header>
      <div className="knowledge-build-content">
        <ReadState loading={report.isPending} error={report.error} retry={() => void refresh()}>
          {detail && validation && run && (
            <>
              {/* 恢复验证沿用条件、隔离执行和结果三阶段。 */}
              <Steps
                className="knowledge-build-stages"
                size="small"
                current={passed && !expired ? 3 : running && run.state === 'QUEUED' ? 0 : 1}
                items={['条件核对', '隔离验证', '验证完成'].map((title) => ({ title }))}
              />
              <ValidationSummary data={validation} />
              <section className="knowledge-build-section" aria-label="验证范围">
                <h2>验证范围</h2>
                <span>
                  {title} · r{revision}
                </span>
              </section>
              <ValidationReport
                data={validation}
                title="逐题验证"
                activeRun={active}
                selected={runId === active ? undefined : runId}
                select={(id) => openRun(id ?? active)}
                canJudge={
                  detail.current &&
                  validation.canReadEvidence &&
                  allowed(session, 'test-cases.judge')
                }
                open={(row, judge) =>
                  setTaskQuestion({ row, judge, trigger: document.activeElement?.id ?? '' })
                }
              />
              {expired && (
                <Alert
                  showIcon
                  type="warning"
                  title={data?.recovery?.issue ?? '当前验证依据已变化，请重新验证'}
                />
              )}
              {passed && detail.current && (
                <Alert showIcon type="success" title="隔离验证已通过，生产限制仍保持" />
              )}
              {taskQuestion && (
                <ValidationQuestionDialog
                  recovery
                  row={taskQuestion.row}
                  run={run}
                  version={detail.taskVersion}
                  path={
                    '/knowledge/sources/' + source + '/releases/' + detail.taskId + '/validations'
                  }
                  judge={taskQuestion.judge && detail.current}
                  canReadEvidence={validation.canReadEvidence}
                  close={closeQuestion}
                  refresh={refresh}
                />
              )}
            </>
          )}
        </ReadState>
      </div>
      {/* 需要重跑时给出原位置操作，否则直接返回版本核对。 */}
      <footer className="knowledge-actions knowledge-build-actions">
        {expired && data?.active ? (
          <RecoveryStartButton key={data.active.id} data={data} path={path} openRun={openRun} />
        ) : (
          <Button type="primary" onClick={back}>
            返回恢复核对
          </Button>
        )}
      </footer>
    </article>
  );
}
