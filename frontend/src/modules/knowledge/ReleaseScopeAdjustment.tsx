import { Alert, App, Button, Modal } from 'antd';
import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { ReadState } from '../../shared/Records';
import { useSession } from '../../shared/session';
import { ReleaseDocumentPicker, ReleaseScopeSummary } from './ReleaseScopeFields';
import type { ReleaseTask } from './types';
import { useReleaseCommand } from './useReleaseCommand';
import { useReleaseScopeCheck } from './useReleaseScopeCheck';

/**
 * 在原任务预览并保存新的同源文档或确认修订。
 */
export function ReleaseScopeAdjustment({
  source,
  path,
  task,
  revision,
  checking,
  onClose,
  refresh,
}: {
  /** 任务原始来源。 */
  source: string;
  /** 当前任务的 API 路径。 */
  path: string;
  /** 打开的任务以及当前已保存范围。 */
  task: ReleaseTask;
  /** 原任务显示的目标修订序号。 */
  revision: number;
  /** 冲突或后台阶段变化后正在读取任务。 */
  checking: boolean;
  /** 保存或取消后返回原任务详情。 */
  onClose: () => void;
  /** 重新读取任务阶段，成功时返回最新并发版本。 */
  refresh: () => Promise<number | undefined>;
}) {
  const { message } = App.useApp();
  const session = useSession();
  const cache = useQueryClient();
  const [documentId, setDocumentId] = useState<string | null>(task.documentId);
  const [reviewedVersion, setReviewedVersion] = useState(task.version);
  const inspection = useReleaseScopeCheck(source, documentId);
  const result = inspection.data;
  const nextRevision = result?.documentReady ? result.documentRevisionId : null;
  const changed = documentId !== task.documentId || nextRevision !== task.documentRevisionId;
  const editable = ![
    'BUILDING',
    'VALIDATING',
    'CANCELED',
    'PUBLISH_RECONCILE',
    'PUBLISHED',
  ].includes(task.status);
  const taskChanged = task.version !== reviewedVersion;
  const blocked =
    !editable ||
    taskChanged ||
    !nextRevision ||
    !changed ||
    checking ||
    inspection.isFetching ||
    !!inspection.error;
  const command = useReleaseCommand({
    key: `dataocean:adjust-release:${session.userId}:${source}:${task.id}`,
    path: path + '/scope-adjustments',
    parse: (body) => {
      // 保留原提交范围和版本，核实成功后才清除。
      const value = body as Record<string, unknown>;
      return typeof value.documentId === 'string' &&
        typeof value.documentRevisionId === 'string' &&
        typeof value.expectedVersion === 'number'
        ? {
            documentId: value.documentId,
            documentRevisionId: value.documentRevisionId,
            expectedVersion: value.expectedVersion,
          }
        : undefined;
    },
    committed: async () => {
      // 刷新原任务与列表，覆盖按新旧文档筛选的结果。
      await Promise.all([
        cache.invalidateQueries({ queryKey: ['release-build', source, task.id] }),
        cache.invalidateQueries({ queryKey: ['release-tasks', source] }),
      ]);
      void message.success('发布范围已调整');
      onClose();
    },
    conflict: () => {
      // 自动回读只显示变化，由操作者重新核对后才采纳任务版本。
      void refresh();
      void inspection.refetch();
    },
  });

  /**
   * 主动核对成功后采纳新任务版本，保留用户选中的目标。
   */
  async function recheck() {
    const [version] = await Promise.all([refresh(), inspection.refetch()]);
    if (version !== undefined) {
      // 失败或再次变化时仍会阻断确认。
      setReviewedVersion(version);
    }
  }

  return (
    <Modal
      open
      centered
      width={720}
      title="调整发布范围"
      okText={command.pending ? command.action : '确认调整'}
      cancelText="取消"
      confirmLoading={command.busy}
      okButtonProps={{ disabled: !command.pending && blocked }}
      onCancel={() => {
        // 在途命令可核实，提交期间保持当前弹窗。
        if (!command.busy) onClose();
      }}
      onOk={() => {
        if (command.pending) {
          // 未明确收到结果时核实原提交。
          command.resolve();
        } else if (!blocked && documentId && nextRevision) {
          // 已核对范围连同页面看到的原任务版本提交。
          command.submit({
            documentId,
            documentRevisionId: nextRevision,
            expectedVersion: reviewedVersion,
          });
        }
      }}
    >
      {/* 先看原任务目标，再选择新目标并核对前置。 */}
      <p className="muted">
        当前目标　{task.title} · r{revision}
      </p>
      {taskChanged && !command.pending && (
        <Alert type="warning" showIcon title="任务已被更新，请重新核对后再确认。" />
      )}
      <div className="release-modal-source">
        <ReleaseDocumentPicker
          source={source}
          documentId={documentId}
          title={result?.documentTitle ?? (documentId === task.documentId ? task.title : undefined)}
          label="选择已确认文档"
          disabled={!!command.pending}
          choose={setDocumentId}
        />
        <Button
          loading={checking || inspection.isFetching}
          disabled={!documentId || !!command.pending}
          onClick={() => void recheck()}
        >
          重新核对
        </Button>
      </div>
      <ReadState
        loading={Boolean(documentId && inspection.isPending)}
        error={inspection.error}
        retry={() => void inspection.refetch()}
      >
        {result && (
          <section className="release-modal-preflight" aria-label="核对本批完整范围">
            <ReleaseScopeSummary result={result} />
            {result.issues.length > 0 && (
              <Alert type="warning" showIcon title={result.issues.join('；')} />
            )}
          </section>
        )}
      </ReadState>
      {/* 当前候选仍可作为历史回看，新范围回到准备阶段。 */}
      {task.candidateId && changed && nextRevision && (
        <Alert type="info" showIcon title="原候选将保留在历史中，新范围需重新构建。" />
      )}
      {command.note && (
        <Alert type={command.pending ? 'warning' : 'error'} showIcon title={command.note} />
      )}
    </Modal>
  );
}
