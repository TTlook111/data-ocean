import { useQuery } from '@tanstack/react-query';
import { Button, Form, Input, Select, Space, Tabs } from 'antd';
import { useEffect, useRef, useState } from 'react';
import { useLocation, useNavigate, useSearchParams } from 'react-router';
import { api, queryString } from '../../shared/api';
import { MoreFilters } from '../../shared/MoreFilters';
import { displayTime, Status } from '../../shared/Records';
import { RecordTable } from '../../shared/RecordTable';
import type { Page } from '../iam/types';
import { originLabels, statusLabels, type Issue } from './types';
import './feedback.css';

/**
 * 结案回看包含独立结束及合并，但待办不包含等待补充。
 */
const closedStatuses = new Set(['RESOLVED', 'CLOSED_OTHER', 'MERGED']);

/**
 * 各分组只提供实际能出现的筛选状态。
 */
function statusInScope(scope: string, status: string) {
  if (scope === 'CLOSED') return closedStatuses.has(status);
  if (scope === 'MINE') return !closedStatuses.has(status) && status !== 'PENDING_SUPPLEMENT';
  return scope !== 'OWNED' || !closedStatuses.has(status);
}

/** 常用条件与收起的条件统一保存在队列地址中。 */
type Criteria = {
  keyword?: string;
  sourceId?: string;
  status?: string;
  origin?: string;
  assignee?: string;
};

/** 查找已受理并持久入队的待分派事项。 */
export function FeedbackQueue() {
  const [params, setParams] = useSearchParams();
  const navigate = useNavigate();
  const location = useLocation();
  const focus = (location.state as { feedbackFocus?: string } | null)?.feedbackFocus;
  const restored = useRef<string | undefined>(undefined);
  const [moreOpen, setMoreOpen] = useState(false);
  const issueId = params.get('issueId') ?? '';
  const filters = {
    keyword: params.get('keyword') || undefined,
    sourceId: params.get('sourceId') || undefined,
    status: params.get('status') || undefined,
    origin: params.get('origin') || undefined,
    assignee: params.get('assignee') || undefined,
  };
  const pageNum = params.get('pageNum');
  const pageSize = params.get('pageSize');
  const scope = params.get('scope') ?? 'ALL';
  const query = queryString({ issueId, ...filters, scope, pageNum, pageSize });
  const sources = useQuery({
    queryKey: ['feedback-sources'],
    queryFn: () => api<{ id: string; name: string }[]>('/feedback/sources'),
    retry: false,
  });
  const listing = useQuery({
    queryKey: ['feedback-issues', query],
    queryFn: () => api<Page<Issue>>('/feedback/issues?' + query),
    retry: false,
  });

  useEffect(() => {
    if (!focus || !listing.data || restored.current === location.key) return;
    // 返回同一列表时先定位已办理事项，筛选排除时保留查询入口。
    restored.current = location.key;
    const trigger = document.getElementById('feedback-issue-' + focus);
    (trigger ?? document.getElementById('feedback-issue-filter'))?.focus({ preventScroll: true });
    trigger?.scrollIntoView?.({ block: 'nearest' });
  }, [focus, listing.data, location.key]);

  /** 详情 URL 保留当前列表条件，面包屑和返回按钮使用相同回程。 */
  function openIssue(row: Issue) {
    navigate('/feedback/' + row.id + location.search);
  }

  /** 重置实际筛选条件，保留当前分组和分页大小。 */
  function reset() {
    setMoreOpen(false);
    setParams(queryString({ pageSize, scope }));
  }

  return (
    <>
      {/* 已应用条件留在地址中，列表刷新与返回不会改变查询。 */}
      <section
        className="panel query-panel compact-query registry-query-panel"
        aria-label="查询问题事项"
      >
        <Form<Criteria>
          key={queryString({ issueId, ...filters })}
          className="registry-query registry-query-three-fields"
          layout="inline"
          scrollToFirstError={{ focus: true }}
          initialValues={{ ...filters, keyword: filters.keyword || issueId || undefined }}
          onFinish={(values) => {
            // 应用查询后保留已选条件并回到第一页。
            setMoreOpen(false);
            setParams(
              queryString({
                ...values,
                keyword: values.keyword?.trim(),
                assignee: values.assignee?.trim(),
                scope,
                pageSize,
              }),
            );
          }}
        >
          <Form.Item name="keyword">
            <Input
              id="feedback-issue-filter"
              aria-label="问题摘要或编号"
              placeholder="问题摘要或编号"
              allowClear
              maxLength={80}
            />
          </Form.Item>
          <Form.Item name="sourceId">
            <Select
              aria-label="数据源"
              placeholder="数据源"
              allowClear
              showSearch={{ optionFilterProp: 'label' }}
              options={sources.data?.map(({ id, name }) => ({ value: id, label: name }))}
              status={sources.isError ? 'error' : undefined}
              loading={sources.isPending}
              notFoundContent={sources.isError ? '数据源加载失败' : undefined}
            />
          </Form.Item>
          <Form.Item name="status">
            <Select
              allowClear
              virtual={false}
              aria-label="事项状态"
              placeholder="全部状态"
              options={Object.entries(statusLabels)
                .filter(([value]) => statusInScope(scope, value))
                .map(([value, label]) => ({ value, label }))}
            />
          </Form.Item>
          {/* 低频条件按用户应用的组数标记，收起后仍与队列查询一致。 */}
          <div className="registry-query-actions">
            <Space>
              <MoreFilters
                open={moreOpen}
                setOpen={setMoreOpen}
                count={Number(Boolean(filters.origin)) + Number(Boolean(filters.assignee))}
              >
                <Form.Item name="origin">
                  <Select
                    allowClear
                    virtual={false}
                    aria-label="事项来源"
                    placeholder="全部来源"
                    options={Object.entries(originLabels).map(([value, label]) => ({
                      value,
                      label,
                    }))}
                  />
                </Form.Item>
                <Form.Item name="assignee">
                  <Input
                    allowClear
                    maxLength={80}
                    aria-label="负责人姓名"
                    placeholder="负责人姓名"
                  />
                </Form.Item>
              </MoreFilters>
              <Button htmlType="submit">查询</Button>
              <Button onClick={reset}>重置</Button>
            </Space>
          </div>
        </Form>
      </section>
      {/* 台账已关联的事项才在后台出现，查询失败交给共用状态处理。 */}
      <section className="panel records-panel feedback-records" aria-label="问题队列与处理">
        {/* 分组同时影响实际查询和分页，切换后从第一页读起。 */}
        <Tabs
          className="feedback-queue-tabs"
          size="small"
          tabBarGutter={40}
          activeKey={scope}
          onChange={(value) => {
            // 切换分组时清除在目标分组不可出现的已应用状态。
            const status =
              filters.status && statusInScope(value, filters.status) ? filters.status : undefined;
            setParams(queryString({ issueId, ...filters, status, pageSize, scope: value }));
          }}
          items={[
            { key: 'MINE', label: '待我处理' },
            { key: 'OWNED', label: '我负责的' },
            { key: 'ALL', label: '全部事项' },
            { key: 'CLOSED', label: '结案回看' },
          ]}
        />
        <RecordTable<Issue>
          rowKey="id"
          dataSource={listing.data?.items}
          loading={listing.isPending}
          error={listing.error}
          retry={listing.refetch}
          filtered={Boolean(issueId || Object.values(filters).some(Boolean))}
          onReset={reset}
          emptyDescription={scope === 'MINE' ? '暂无待办事项' : '暂无入队事项'}
          scroll={{ x: 1104 }}
          columns={[
            {
              title: '问题 / 编号',
              key: 'issue',
              width: 204,
              render: (_, row) => (
                <div className="feedback-table-value">
                  <span className="feedback-table-primary" title={row.summary}>
                    {row.summary ?? '事项 #' + row.id}
                  </span>
                  <small>
                    #{row.id}
                    {row.referenceCount ? ` · ${row.referenceCount} 个关联问数` : ''}
                  </small>
                </div>
              ),
            },
            {
              title: '来源',
              dataIndex: 'origin',
              width: 120,
              render: (value: string) => originLabels[value] ?? value,
            },
            { title: '数据源', dataIndex: 'sourceName', width: 176, ellipsis: true },
            {
              title: '状态',
              dataIndex: 'status',
              width: 88,
              render: (state: string) => <Status value={state} label={statusLabels[state]} />,
            },
            {
              title: '负责人',
              dataIndex: 'assigneeName',
              width: 116,
              ellipsis: true,
              render: (value: string | null) => value ?? '待分派',
            },
            {
              title: '下一步 / 最近更新',
              key: 'next',
              width: 160,
              render: (_, row) => (
                <div className="feedback-table-value">
                  <span className="feedback-table-primary">{row.nextAction ?? '查看进度'}</span>
                  <small>{displayTime(row.updatedAt)}</small>
                </div>
              ),
            },
            {
              title: '提出时间',
              dataIndex: 'createdAt',
              width: 136,
              className: 'feedback-date-cell',
              render: displayTime,
            },
            {
              title: '操作',
              key: 'action',
              width: 104,
              fixed: 'right',
              className: 'record-actions-cell',
              render: (_, row) => (
                <Button
                  id={'feedback-issue-' + row.id}
                  type="link"
                  aria-label={'查看事项 #' + row.id}
                  onClick={() => openIssue(row)}
                >
                  {row.status === 'MERGED'
                    ? '查看合并'
                    : closedStatuses.has(row.status)
                      ? '查看结果'
                      : '进入办理'}
                </Button>
              ),
            },
          ]}
          pagination={{
            current: listing.data?.pageNum ?? 1,
            pageSize: listing.data?.pageSize ?? 10,
            total: listing.data?.total,
            onChange: (nextPage, size) => {
              // 切页只改变分页，不覆盖已应用的过滤条件。
              setParams(
                queryString({
                  issueId,
                  ...filters,
                  scope,
                  pageNum: nextPage,
                  pageSize: size,
                }),
              );
            },
          }}
        />
      </section>
    </>
  );
}
