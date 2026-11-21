import { Alert, Modal, Space, Table, Tag } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { api, ApiError } from '../../shared/api';
import { displayTime, ReadState } from '../../shared/Records';
import { useSession } from '../../shared/session';
import { versionAvailabilityLabels } from './types';
import { useReleaseCommand } from './useReleaseCommand';

/**
 * 正式绑定中的确切发布、文档、索引和当前可用性。
 */
export type PublishedVersion = {
  id: string;
  scopeId: string;
  /** 本次实际发布的原批次。 */
  batchId: string;
  taskId: string;
  documentId: string;
  documentRevisionId: string;
  revision: number;
  title: string;
  candidateId: string;
  buildId: string;
  validationId: string;
  embeddingRevisionId: string;
  collection: string;
  publishedBy: string;
  switchedAt: string;
  verifiedAt: string | null;
  objects: string[];
  availability: keyof typeof versionAvailabilityLabels;
};

/**
 * 历史发布版本与实际当前版本分别回读。
 */
export type PublicationOutcome = {
  state: string;
  published: PublishedVersion | null;
  active: PublishedVersion | null;
  previous: PublishedVersion | null;
  issue: string | null;
  /** 本人仍可核实该来源的完整索引和正文。 */
  canReconcile: boolean;
};

/**
 * 提交确认绑定的预检事实，不从页面状态推测已经生效。
 */
type PublicationPreview = {
  title: string;
  beforeRevision: number | null;
  targetRevision: number;
  candidateId: string;
  buildId: string;
  runId: string;
  verifiedChunks: number;
  reviewToken: string;
};

/**
 * 列表、结果与版本详情沿用同一实际状态。
 */
export function VersionAvailability({ version }: { version: PublishedVersion }) {
  return (
    <Tag
      color={
        version.availability === 'AVAILABLE'
          ? 'success'
          : ['PAUSED', 'WITHDRAWN'].includes(version.availability)
            ? 'error'
            : version.availability === 'SUPERSEDED'
              ? undefined
              : 'warning'
      }
    >
      {versionAvailabilityLabels[version.availability]}
    </Tag>
  );
}

/**
 * 从任务首屏核对本次发布和当前入口。
 */
export function PublicationResult({ outcome }: { outcome: PublicationOutcome }) {
  const { published, active, issue } = outcome;
  if (!published) {
    return issue ? <Alert showIcon type="warning" title={issue} /> : null;
  }
  return (
    <section className="knowledge-build-section" aria-label="本次发布结果">
      {/* 本次冻结版本与之后的实际生效状态独立显示。 */}
      <h2 id="publication-result-title" tabIndex={-1}>
        本次发布结果
      </h2>
      <span className="muted">
        切换提交 {displayTime(published.switchedAt)}
        {published.verifiedAt ? ' · 实际入口核实 ' + displayTime(published.verifiedAt) : ''}
      </span>
      <Table<PublishedVersion>
        size="middle"
        rowKey="id"
        pagination={false}
        scroll={{ x: 540 }}
        dataSource={[published]}
        columns={[
          { title: '文档', dataIndex: 'title', ellipsis: true },
          {
            title: '本次发布',
            width: 154,
            render: (_, row) => (
              <Space size={6} wrap>
                <span>r{row.revision}</span>
                {['SUPERSEDED', 'WITHDRAWN'].includes(row.availability) && (
                  <VersionAvailability version={row} />
                )}
              </Space>
            ),
          },
          { title: '当前生效', width: 108, render: () => (active ? 'r' + active.revision : '—') },
          {
            title: '当前可用性',
            width: 146,
            render: () => (active ? <VersionAvailability version={active} /> : '—'),
          },
        ]}
      />
      {issue && <Alert showIcon type="warning" title={issue} />}
    </section>
  );
}

/**
 * 确认弹窗沿用任务位置及原命令核实机制。
 */
export function PublishReleaseDialog({
  source,
  id,
  path,
  version,
  ready,
  close,
  refresh,
}: {
  source: string;
  id: string;
  path: string;
  version: number;
  ready: boolean;
  close: () => void;
  refresh: () => Promise<unknown>;
}) {
  const session = useSession();
  const [baseline] = useState(version);
  const preview = useQuery({
    queryKey: ['release-publish-preview', source, id, baseline],
    queryFn: () =>
      api<PublicationPreview>(path + '/impact-preview', {
        method: 'POST',
        body: { action: 'PUBLISH', expectedVersion: baseline },
      }),
    refetchOnMount: 'always',
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
  });
  const command = useReleaseCommand({
    key: 'dataocean:publish:' + session.userId + ':' + source + ':' + id,
    path: path + '/publish',
    operationCode: 'releases.publish',
    parse: (payload) => {
      // 核实和重试只能使用原编号及原核对凭据。
      const value = payload as Record<string, unknown>;
      return typeof value.expectedVersion === 'number' && typeof value.reviewToken === 'string'
        ? { expectedVersion: value.expectedVersion, reviewToken: value.reviewToken }
        : undefined;
    },
    committed: async () => {
      await refresh();
      close();
    },
    conflict: () => {
      // 只读最新事实，旧确认不会自动切换到新版本。
      void preview.refetch();
      void refresh();
    },
  });
  const result = preview.data;
  const rejected =
    preview.error instanceof ApiError &&
    [403003, 409001, 409002, 409005, 409301, 409302].includes(preview.error.code)
      ? preview.error.message
      : null;
  const blocked =
    !ready || version !== baseline || preview.isFetching || !!preview.error || !result;

  return (
    <Modal
      open
      centered
      width={700}
      title="确认整批发布"
      okText={command.pending ? command.action : '确认发布'}
      confirmLoading={command.busy}
      okButtonProps={{ disabled: !command.pending && blocked }}
      onCancel={() => {
        // 在途结果仍可核实，提交中不关闭原操作。
        if (!command.busy) close();
      }}
      onOk={() => {
        if (command.pending) command.resolve();
        else if (result && !blocked)
          command.submit({ expectedVersion: baseline, reviewToken: result.reviewToken });
      }}
    >
      {/* 与原型一致，在同一确认窗口给出范围、候选与验证结果。 */}
      {rejected && <Alert type="warning" showIcon title="发布检查未通过" description={rejected} />}
      {!rejected && (
        <ReadState
          loading={preview.isPending}
          error={preview.error}
          retry={() => void preview.refetch()}
        >
          {result && (
            <section className="knowledge-build-section" aria-label="发布检查结果">
              <dl className="knowledge-publication-preview">
                <div>
                  <dt>本批范围</dt>
                  <dd>
                    {result.title} · r{result.targetRevision}
                  </dd>
                </div>
                <div>
                  <dt>生效版本</dt>
                  <dd>
                    {result.beforeRevision ? 'r' + result.beforeRevision : '首次发布'} → r
                    {result.targetRevision}
                  </dd>
                </div>
                <div>
                  <dt>固定候选</dt>
                  <dd>{result.candidateId}</dd>
                </div>
                <div>
                  <dt>验证结果</dt>
                  <dd>全部通过 · {result.verifiedChunks} 个片段已核实</dd>
                </div>
              </dl>
              <Alert type="info" showIcon title="本批范围整体切换，现有使用限制继续生效。" />
            </section>
          )}
        </ReadState>
      )}
      {version !== baseline && !command.pending && (
        <Alert type="warning" showIcon title="任务已变化，请关闭窗口后重新核对。" />
      )}
      {command.note && (
        <Alert type={command.pending ? 'warning' : 'error'} showIcon title={command.note} />
      )}
    </Modal>
  );
}
