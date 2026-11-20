import { Button, Pagination, Tag } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { api, queryString } from '../../shared/api';
import { ReadState } from '../../shared/Records';
import type { Page } from '../iam/types';
import type { Candidate, DiscoveryKind } from './GovernanceDiscoveryPanel';
import type { ColumnRow, GovernanceObject, Target } from './types';

/** 状态列表沿用稳定条目身份，点击可直接办理。 */
export function GovernanceStatus({
  base,
  object,
  choose,
  openCandidate,
}: {
  base: string;
  object: GovernanceObject;
  choose: (target: Target) => void;
  openCandidate: (kind: DiscoveryKind, id: string, page: number) => void;
}) {
  const [page, setPage] = useState(1);
  const columns = useQuery({
    queryKey: ['governance', base, 'pending-columns', page],
    queryFn: () =>
      api<Page<ColumnRow>>(
        base + '/columns?' + queryString({ status: 'PENDING', pageNum: page, pageSize: 20 }),
      ),
  });
  const table = object.tableEntry;
  const tablePending = table.pendingReview || table.state !== 'CONFIRMED';
  // 移除字段已离开当前目录，仍按稳定字段身份显示待办。
  const removed = [
    ...new Map(
      object.pendingImpacts
        .filter(({ kind, target }) => kind === 'REMOVAL' && target.kind === 'COLUMN')
        .map((impact) => [impact.target.targetId, impact]),
    ).values(),
  ];

  return (
    <section className="gov-content gov-status" aria-label="整表治理状态">
      <section aria-label="表说明与字段待处理">
        <h2>表说明与字段待处理</h2>
        {tablePending && (
          <div className="gov-history-row">
            <Button type="link" onClick={() => choose(table.target)}>
              表说明
            </Button>
            <Tag color={table.pendingReview ? 'orange' : undefined}>
              {reviewState(table.state, table.pendingReview)}
            </Tag>
          </div>
        )}
        {removed.map(({ target, targetName, impactId }) => (
          <div className="gov-history-row" key={impactId}>
            <Button type="link" onClick={() => choose(target)}>
              {targetName}
            </Button>
            <Tag color="red">待确认移除</Tag>
          </div>
        ))}
        <ReadState
          loading={columns.isPending}
          error={columns.error}
          retry={() => void columns.refetch()}
        >
          {columns.data?.items.map(({ target, name, state, pendingReview }) => (
            <div className="gov-history-row" key={target.targetId}>
              <Button type="link" onClick={() => choose(target)}>
                {name}
              </Button>
              <Tag color={pendingReview ? 'orange' : undefined}>
                {reviewState(state, pendingReview)}
              </Tag>
            </div>
          ))}
          {columns.data?.total === 0 && !tablePending && removed.length === 0 && (
            <p className="gov-empty">暂无待处理表说明或字段</p>
          )}
          {(columns.data?.total ?? 0) > 20 && (
            <Pagination
              current={page}
              pageSize={20}
              total={columns.data?.total}
              showSizeChanger={false}
              onChange={setPage}
            />
          )}
        </ReadState>
      </section>
      {/* 关联与指标使用各自实际审核状态和分页，避免改写字段治理进度。 */}
      <CandidateStatus base={base} kind="RELATION" open={openCandidate} />
      <CandidateStatus base={base} kind="METRIC" open={openCandidate} />
    </section>
  );
}

/** 关联和指标含有独立的依赖复核状态。 */
function CandidateStatus({
  base,
  kind,
  open,
}: {
  base: string;
  kind: DiscoveryKind;
  open: (kind: DiscoveryKind, id: string, page: number) => void;
}) {
  const [page, setPage] = useState(1);
  const path = base + '/candidates';
  const list = useQuery({
    queryKey: ['governance-candidates', path, kind, page],
    queryFn: () =>
      api<Page<Candidate>>(path + '?' + queryString({ kind, pageNum: page, pageSize: 12 })),
  });
  const label = kind === 'RELATION' ? '关联' : '指标';
  if (list.data?.total === 0 && !list.error) return null;
  return (
    <section aria-label={label + '治理状态'}>
      <h2>{label}</h2>
      <ReadState loading={list.isPending} error={list.error} retry={() => void list.refetch()}>
        {list.data?.items.map((candidate) => (
          <div className="gov-history-row" key={candidate.id}>
            <Button
              type="link"
              disabled={!candidate.definition}
              onClick={() => open(kind, candidate.id, page)}
            >
              {candidate.definition ? candidate.definition.name || '未命名' + label : '受限候选'}
            </Button>
            <Tag color={candidate.state === 'REVIEW' ? 'orange' : undefined}>
              {reviewState(candidate.state, candidate.state === 'REVIEW')}
            </Tag>
          </div>
        ))}
        {(list.data?.total ?? 0) > 12 && (
          <Pagination
            current={page}
            pageSize={12}
            total={list.data?.total}
            showSizeChanger={false}
            onChange={setPage}
          />
        )}
      </ReadState>
    </section>
  );
}

/** 以页面业务术语表达当前待办和已经办结的状态。 */
function reviewState(state: string, pendingReview: boolean) {
  const labels: Record<string, string> = {
    UNSTARTED: '待填写',
    DRAFT: '待确认',
    CANDIDATE: '待确认',
    CONFIRMED: '已确认',
    REJECTED: '已拒绝',
  };
  return pendingReview ? '待复核' : (labels[state] ?? '待核对');
}
