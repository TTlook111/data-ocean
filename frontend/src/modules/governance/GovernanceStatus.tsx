import { Button, Pagination, Space, Tag } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { api, queryString } from '../../shared/api';
import { RecordTable } from '../../shared/RecordTable';
import { ReadState } from '../../shared/Records';
import type { Page } from '../iam/types';
import type { Candidate, DiscoveryKind } from './GovernanceDiscoveryPanel';
import type { ColumnRow, GovernanceObject, Target } from './types';

/**
 * 状态清单中的业务对象、实际进度与现有办理入口。
 */
type StatusRow = {
  /** 区分不同类型的稳定记录。 */
  id: string;
  /** 表说明、字段、关联或指标。 */
  kind: string;
  /** 当前对象显示名称。 */
  name: string;
  /** 统一状态词。 */
  state: string;
  /** 当前状态的颜色信号。 */
  color?: string;
  /** 跳回对应编辑或查看页签。 */
  open?: () => void;
};

/**
 * 使用现有分页接口查看整表待办与已确认条目。
 */
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
  const [pendingPage, setPendingPage] = useState(1);
  const [confirmedPage, setConfirmedPage] = useState(1);
  const [relationPage, setRelationPage] = useState(1);
  const [metricPage, setMetricPage] = useState(1);
  const pending = useQuery({
    queryKey: ['governance', base, 'pending-columns', pendingPage],
    queryFn: () =>
      api<Page<ColumnRow>>(
        base + '/columns?' + queryString({ status: 'PENDING', pageNum: pendingPage, pageSize: 20 }),
      ),
  });
  const confirmed = useQuery({
    queryKey: ['governance', base, 'confirmed-columns', confirmedPage],
    queryFn: () =>
      api<Page<ColumnRow>>(
        base +
          '/columns?' +
          queryString({ status: 'CONFIRMED', pageNum: confirmedPage, pageSize: 20 }),
      ),
  });
  const candidatePath = base + '/candidates';
  const relations = useQuery({
    queryKey: ['governance-candidates', candidatePath, 'RELATION', relationPage],
    queryFn: () =>
      api<Page<Candidate>>(
        candidatePath +
          '?' +
          queryString({ kind: 'RELATION', pageNum: relationPage, pageSize: 12 }),
      ),
  });
  const metrics = useQuery({
    queryKey: ['governance-candidates', candidatePath, 'METRIC', metricPage],
    queryFn: () =>
      api<Page<Candidate>>(
        candidatePath + '?' + queryString({ kind: 'METRIC', pageNum: metricPage, pageSize: 12 }),
      ),
  });
  const reads = [pending, confirmed, relations, metrics];
  const table = object.tableEntry;
  const tablePending = table.pendingReview || table.state !== 'CONFIRMED';

  /** 按原字段身份回到治理编辑器。 */
  const columnRow = ({ target, name, state, pendingReview }: ColumnRow): StatusRow => ({
    id: 'COLUMN:' + target.targetId,
    kind: '字段',
    name,
    state: reviewState(state, pendingReview),
    color: pendingReview ? 'orange' : undefined,
    open: () => choose(target),
  });

  /** 关联和指标保留各自待复核和不可读状态。 */
  const candidateRows = (kind: DiscoveryKind, rows: Candidate[], pageNum: number) => {
    const name = kind === 'RELATION' ? '关联' : '指标';
    return rows.map((row): StatusRow => ({
      id: kind + ':' + row.id,
      kind: name,
      name: row.definition ? row.definition.name || '未命名' + name : '受限候选',
      state: reviewState(row.state, row.state === 'REVIEW'),
      color: row.state === 'REVIEW' ? 'orange' : undefined,
      open: row.definition ? () => openCandidate(kind, row.id, pageNum) : undefined,
    }));
  };
  const candidates = [
    ...candidateRows('RELATION', relations.data?.items ?? [], relationPage),
    ...candidateRows('METRIC', metrics.data?.items ?? [], metricPage),
  ];
  const tableRow: StatusRow = {
    id: 'TABLE:' + table.target.targetId,
    kind: '表说明',
    name: '表说明',
    state: reviewState(table.state, table.pendingReview),
    color: table.pendingReview ? 'orange' : undefined,
    open: () => choose(table.target),
  };
  // 已移除的字段通过影响记录继续查到，避免当前目录缺失后无法接续处理。
  const removed = [
    ...new Map(
      object.pendingImpacts
        .filter(({ kind, target }) => kind === 'REMOVAL' && target.kind === 'COLUMN')
        .map((impact) => [impact.target.targetId, impact]),
    ).values(),
  ];
  const removedIds = new Set(removed.map(({ target }) => target.targetId));
  const pendingRows = [
    ...(tablePending ? [tableRow] : []),
    ...removed.map(({ target, targetName }): StatusRow => ({
      id: 'COLUMN:' + target.targetId,
      kind: '字段',
      name: targetName,
      state: '待确认移除',
      color: 'red',
      open: () => choose(target),
    })),
    ...(pending.data?.items ?? [])
      .filter(({ target }) => !removedIds.has(target.targetId))
      .map(columnRow),
    ...candidates.filter(({ state }) => state !== '已确认' && state !== '已拒绝'),
  ];
  const confirmedRows = [
    ...(!tablePending ? [tableRow] : []),
    ...(confirmed.data?.items ?? []).map(columnRow),
    ...candidates.filter(({ state }) => state === '已确认'),
  ];
  const rejectedRows = candidates.filter(({ state }) => state === '已拒绝');
  const pages = [
    {
      name: '待处理字段',
      page: pendingPage,
      size: 20,
      total: pending.data?.total ?? 0,
      change: setPendingPage,
    },
    {
      name: '已确认字段',
      page: confirmedPage,
      size: 20,
      total: confirmed.data?.total ?? 0,
      change: setConfirmedPage,
    },
    {
      name: '关联',
      page: relationPage,
      size: 12,
      total: relations.data?.total ?? 0,
      change: setRelationPage,
    },
    {
      name: '指标',
      page: metricPage,
      size: 12,
      total: metrics.data?.total ?? 0,
      change: setMetricPage,
    },
  ].filter(({ size, total }) => total > size);

  return (
    <section className="gov-content gov-status" aria-label="整表治理状态">
      <ReadState
        loading={reads.some(({ isPending }) => isPending)}
        error={reads.find(({ error }) => error)?.error ?? null}
        retry={() => {
          reads.forEach((read) => void read.refetch());
        }}
      >
        {/* 按办理顺序组织条目，已确认定义使用同样的内容轴。 */}
        <StatusTable title="待处理" rows={pendingRows} empty="暂无待处理事项" />
        {confirmedRows.length > 0 && <StatusTable title="已确认" rows={confirmedRows} />}
        {rejectedRows.length > 0 && <StatusTable title="已拒绝" rows={rejectedRows} />}
        {pages.length > 0 && (
          <Space className="gov-status-pages" wrap>
            {pages.map(({ name, page, size, total, change }) => (
              <div className="gov-status-page" key={name}>
                <span>{name}</span>
                <Pagination
                  current={page}
                  pageSize={size}
                  total={total}
                  onChange={change}
                  showSizeChanger={false}
                  size="small"
                />
              </div>
            ))}
          </Space>
        )}
      </ReadState>
    </section>
  );
}

/** 呈现已加载的治理条目，文字和入口沿通用列表轴排列。 */
function StatusTable({ title, rows, empty }: { title: string; rows: StatusRow[]; empty?: string }) {
  return (
    <section className="gov-status-section" aria-label={title}>
      <h2>{title}</h2>
      <RecordTable<StatusRow>
        className="gov-status-table"
        rowKey="id"
        dataSource={rows}
        emptyDescription={empty}
        pagination={false}
        flexibleColumns={[0, 1, 2]}
        scroll={{ x: 484 }}
        columns={[
          { title: '条目', width: 212, dataIndex: 'name', ellipsis: true },
          { title: '类型', width: 80, dataIndex: 'kind' },
          {
            title: '状态',
            width: 96,
            render: (_, row) => <Tag color={row.color}>{row.state}</Tag>,
          },
          {
            title: '操作',
            width: 96,
            className: 'gov-status-action',
            render: (_, row) =>
              row.open && (
                <Button type="link" onClick={row.open}>
                  {row.state === '已确认' || row.state === '已拒绝' ? '查看' : '处理'}
                </Button>
              ),
          },
        ]}
      />
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
