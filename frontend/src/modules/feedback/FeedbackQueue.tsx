import { useQuery } from '@tanstack/react-query';
import { Button, Form, Input, Space, Tabs } from 'antd';
import { useEffect, useRef } from 'react';
import { useLocation, useNavigate, useSearchParams } from 'react-router';
import { api, queryString } from '../../shared/api';
import { displayTime, Status } from '../../shared/Records';
import { RecordTable } from '../../shared/RecordTable';
import type { Page } from '../iam/types';
import { statusLabels, type Issue } from './types';
import './feedback.css';

/** 搜索与服务端使用十进制正编号，两端都允许清空条件。 */
const issueNumber = /^(?:[1-9][0-9]{0,18})?$/u;

/** 查找已受理并持久入队的待分派事项。 */
export function FeedbackQueue() {
  const [params, setParams] = useSearchParams();
  const navigate = useNavigate();
  const location = useLocation();
  const focus = (location.state as { feedbackFocus?: string } | null)?.feedbackFocus;
  const restored = useRef<string | undefined>(undefined);
  const issueId = params.get('issueId') ?? '';
  const sourceId = params.get('sourceId') ?? '';
  const pageNum = params.get('pageNum');
  const pageSize = params.get('pageSize');
  const scope = params.get('scope') ?? 'ALL';
  const query = queryString({ issueId, sourceId, scope, pageNum, pageSize });
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

  /** 清除两个实际筛选条件，保留列表默认布局和分页规模。 */
  function reset() {
    setParams(queryString({ pageSize, scope }));
  }

  return (
    <>
      {/* 已应用条件留在地址中，列表刷新与返回不会改变查询。 */}
      <section className="panel filter-panel feedback-filter" aria-label="查询问题事项">
        <Form
          key={issueId + ':' + sourceId}
          layout="inline"
          scrollToFirstError={{ focus: true }}
          initialValues={{ issueId, sourceId }}
          onFinish={(values: { issueId?: string; sourceId?: string }) => {
            // 提交新条件时恢复第一页。
            setParams(
              queryString({
                issueId: values.issueId,
                sourceId: values.sourceId,
                scope,
                pageSize,
              }),
            );
          }}
        >
          <Form.Item
            name="issueId"
            label="事项编号"
            htmlFor="feedback-issue-filter"
            rules={[{ pattern: issueNumber, message: '请输入有效的事项编号' }]}
          >
            <Input id="feedback-issue-filter" allowClear inputMode="numeric" />
          </Form.Item>
          <Form.Item
            name="sourceId"
            label="数据源编号"
            rules={[{ pattern: issueNumber, message: '请输入有效的数据源编号' }]}
          >
            <Input allowClear inputMode="numeric" />
          </Form.Item>
          <Space>
            <Button htmlType="submit">查询</Button>
            <Button onClick={reset}>重置</Button>
          </Space>
        </Form>
      </section>
      {/* 台账已关联的事项才在后台出现，查询失败交给共用状态处理。 */}
      <section className="panel records-panel" aria-label="问题队列与处理">
        {/* 分组同时影响实际查询和分页，切换后从第一页读起。 */}
        <Tabs
          className="feedback-queue-tabs"
          activeKey={scope}
          onChange={(value) =>
            setParams(queryString({ issueId, sourceId, pageSize, scope: value }))
          }
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
          filtered={Boolean(issueId || sourceId)}
          onReset={reset}
          emptyDescription={scope === 'MINE' ? '暂无待办事项' : '暂无入队事项'}
          scroll={{ x: 825 }}
          columns={[
            { title: '事项编号', dataIndex: 'id', width: 110 },
            { title: '数据源', dataIndex: 'sourceName', width: 190, ellipsis: true },
            {
              title: '来源',
              dataIndex: 'origin',
              width: 120,
              render: (origin: string) => (origin === 'USER_DISLIKE' ? '问数点踩' : origin),
            },
            {
              title: '状态',
              dataIndex: 'status',
              width: 115,
              render: (state: string) => <Status value={state} label={statusLabels[state]} />,
            },
            {
              title: '负责人',
              dataIndex: 'assigneeName',
              width: 105,
              render: (value: string | null) => value ?? '—',
            },
            { title: '最近更新', dataIndex: 'updatedAt', width: 170, render: displayTime },
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
                  查看事项
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
                queryString({ issueId, sourceId, scope, pageNum: nextPage, pageSize: size }),
              );
            },
          }}
        />
      </section>
    </>
  );
}
