import { PlusOutlined } from '@ant-design/icons';
import { Alert, App, Button } from 'antd';
import { useQueryClient } from '@tanstack/react-query';
import { useReleaseCommand } from './useReleaseCommand';

/**
 * 保存已核对文档的准备任务并进入详情。
 */
export function ReleasePreparation({
  userId,
  source,
  documentId,
  documentRevisionId,
  checking,
  onConflict,
  onCreated,
}: {
  /** 当前可信用户、来源和文档构成命令恢复范围。 */
  userId: string;
  /** 本次确认所在源。 */
  source: string;
  /** 正在准备的文档。 */
  documentId: string;
  /** 核对通过的文档确认，缺题或缺模型时仍可保存。 */
  documentRevisionId: string | null;
  /** 当前正在刷新预检。 */
  checking: boolean;
  /** 依据冲突时回读。 */
  onConflict: () => void;
  /** 新建成功后进入实际任务详情。 */
  onCreated: (id: string) => void;
}) {
  const { message } = App.useApp();
  const cache = useQueryClient();
  const command = useReleaseCommand({
    key: `dataocean:release-task:${userId}:${source}:${documentId}`,
    path: `/knowledge/sources/${source}/releases`,
    parse: (body) => {
      // 已保存的准备命令沿用原依据，文档来自隔离的存储位置。
      const value = body as Record<string, unknown>;
      return typeof value.documentRevisionId === 'string'
        ? {
            documentId,
            documentRevisionId: value.documentRevisionId,
          }
        : undefined;
    },
    committed: async (id) => {
      // 原命令确认成功后再回读任务列表。
      await cache.invalidateQueries({ queryKey: ['release-tasks', source] });
      void message.success('发布任务已创建');
      onCreated(id);
    },
    conflict: onConflict,
  });

  return (
    <footer className="knowledge-actions knowledge-release-create-actions">
      {/* 失败反馈留在原确认窗口，原命令待核实时优先恢复。 */}
      {command.note && (
        <Alert
          className="knowledge-prepare-feedback"
          type={command.pending ? 'warning' : 'error'}
          showIcon
          title={command.note}
        />
      )}
      {command.pending ? (
        <Button
          type="primary"
          aria-label={command.action}
          aria-busy={command.busy}
          loading={command.busy}
          onClick={command.resolve}
        >
          {command.action}
        </Button>
      ) : (
        <Button
          type="primary"
          icon={<PlusOutlined aria-hidden="true" />}
          loading={command.busy}
          disabled={checking || !documentRevisionId}
          onClick={() => documentRevisionId && command.submit({ documentId, documentRevisionId })}
        >
          创建任务
        </Button>
      )}
    </footer>
  );
}
