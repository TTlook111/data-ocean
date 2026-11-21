import { PlusOutlined } from '@ant-design/icons';
import { Alert, App, Button, Tag } from 'antd';
import { useQueryClient } from '@tanstack/react-query';
import { useReleaseCommand } from './useReleaseCommand';

/** 发布准备与候选范围的创建入口。 */
export function ReleasePreparation({
  userId,
  source,
  documentId,
  documentRevisionId,
  inputFingerprint,
  showStatus,
  canPrepare,
  checking,
  beforeSubmit,
  onConflict,
  onCreated,
}: {
  /** 当前可信用户、来源和文档构成命令恢复范围。 */
  userId: string;
  /** 本次确认所在源。 */
  source: string;
  /** 正在准备的文档。 */
  documentId: string;
  /** 预检可用于固定的原确认。 */
  documentRevisionId: string | null;
  /** 预检依赖齐全时的摘要。 */
  inputFingerprint: string | null;
  /** 是否已经取得实际预检结果。 */
  showStatus: boolean;
  /** 读取资格独立于创建资格。 */
  canPrepare: boolean;
  /** 当前正在刷新预检。 */
  checking: boolean;
  /** 新记录回读到第一页。 */
  beforeSubmit?: () => void;
  /** 依据冲突时回读。 */
  onConflict: () => void;
  /** 新建成功后进入实际任务详情。 */
  onCreated?: (id: string) => void;
}) {
  const { message } = App.useApp();
  const cache = useQueryClient();
  const command = useReleaseCommand({
    key: `dataocean:prepare-release:${userId}:${source}:${documentId}`,
    path: `/knowledge/sources/${source}/releases`,
    parse: (body) => {
      // 已保存的准备命令沿用原依据，文档来自隔离的存储位置。
      const value = body as Record<string, unknown>;
      return typeof value.documentRevisionId === 'string' &&
        typeof value.inputFingerprint === 'string'
        ? {
            documentId,
            documentRevisionId: value.documentRevisionId,
            inputFingerprint: value.inputFingerprint,
          }
        : undefined;
    },
    committed: async (id) => {
      // 原命令确认成功后再回读候选列表。
      await cache.invalidateQueries({ queryKey: ['release-candidates', source] });
      void message.success('发布候选已创建');
      onCreated?.(id);
    },
    conflict: onConflict,
    beforeWrite: beforeSubmit,
  });
  const pending = canPrepare && command.pending;

  return (
    <header className="list-toolbar knowledge-preflight-title">
      {/* 待核实状态覆盖预检就绪提示，避免诱发重复提交。 */}
      <div className="knowledge-heading-main">
        <h2>发布准备</h2>
        {(pending || showStatus) && (
          <Tag color={pending || !inputFingerprint ? 'orange' : 'green'}>
            {pending
              ? command.sending
                ? '提交中'
                : '提交待核实'
              : inputFingerprint
                ? '可创建候选'
                : '待处理'}
          </Tag>
        )}
      </div>
      {pending ? (
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
        canPrepare &&
        inputFingerprint &&
        documentRevisionId && (
          <Button
            type="primary"
            aria-label="创建发布候选"
            icon={<PlusOutlined aria-hidden="true" />}
            loading={command.busy}
            disabled={checking}
            onClick={() => command.submit({ documentId, documentRevisionId, inputFingerprint })}
          >
            创建发布候选
          </Button>
        )
      )}
      {/* 失败反馈紧邻原命令，保留重试和核实的区分。 */}
      {canPrepare && command.note && (
        <Alert
          className="knowledge-prepare-feedback"
          type={pending ? 'warning' : 'error'}
          showIcon
          title={command.note}
        />
      )}
    </header>
  );
}
