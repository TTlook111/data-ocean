import { useQuery } from '@tanstack/react-query';
import { Button, Form, Input, Space, Tag } from 'antd';
import { useSearchParams } from 'react-router';
import { api, queryString } from '../../shared/api';
import { displayTime } from '../../shared/Records';
import { RecordTable } from '../../shared/RecordTable';
import type { Page } from '../iam/types';

/** 队列只使用可查看的事项基础列。 */
interface Issue {
  id: string;
  sourceId: string;
  origin: string;
  status: string;
  assigneeId: string | null;
  updatedAt: string;
}

/** 查找已受理并持久入队的待分派事项。 */
export function FeedbackQueue() {
  const [params, setParams] = useSearchParams();
  const issueId = params.get('issueId') ?? '';
  const sourceId = params.get('sourceId') ?? '';
  const pageNum = params.get('pageNum');
  const pageSize = params.get('pageSize');
  const query = queryString({ issueId, sourceId, pageNum, pageSize });
  const listing = useQuery({
    queryKey: ['feedback-issues', query],
    queryFn: () => api<Page<Issue>>('/feedback/issues?' + query),
    retry: false,
  });

  /** 清除两个实际筛选条件，保留列表默认布局和分页规模。 */
  function reset() {
    setParams(queryString({ pageSize }));
  }

  return (
    <>
      {/* 已应用条件留在地址中，列表刷新与返回不会改变查询。 */}
      <section className="panel filter-panel" aria-label="查询问题事项">
        <Form
          key={issueId + ':' + sourceId}
          layout="inline"
          initialValues={{ issueId, sourceId }}
          onFinish={(values: { issueId?: string; sourceId?: string }) => {
            // 提交新条件时恢复第一页。
            setParams(
              queryString({
                issueId: values.issueId?.trim(),
                sourceId: values.sourceId?.trim(),
                pageSize,
              }),
            );
          }}
        >
          <Form.Item
            name="issueId"
            label="事项编号"
            rules={[{ pattern: /^[0-9]*$/, message: '请输入事项编号' }]}
          >
            <Input allowClear inputMode="numeric" />
          </Form.Item>
          <Form.Item
            name="sourceId"
            label="数据源编号"
            rules={[{ pattern: /^[0-9]*$/, message: '请输入数据源编号' }]}
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
        <RecordTable<Issue>
          rowKey="id"
          dataSource={listing.data?.items}
          loading={listing.isPending}
          error={listing.error}
          retry={listing.refetch}
          filtered={Boolean(issueId || sourceId)}
          onReset={reset}
          emptyDescription="暂无入队事项"
          scroll={{ x: 660 }}
          columns={[
            { title: '事项编号', dataIndex: 'id', width: 110 },
            { title: '数据源编号', dataIndex: 'sourceId', width: 120 },
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
              render: (state: string) => (
                <Tag>{state === 'PENDING_ASSIGNMENT' ? '待分派' : state}</Tag>
              ),
            },
            {
              title: '负责人',
              dataIndex: 'assigneeId',
              width: 105,
              render: (value: string | null) => value ?? '—',
            },
            { title: '最近更新', dataIndex: 'updatedAt', width: 170, render: displayTime },
          ]}
          pagination={{
            current: listing.data?.pageNum ?? 1,
            pageSize: listing.data?.pageSize ?? 10,
            total: listing.data?.total,
            onChange: (nextPage, size) => {
              // 切页只改变分页，不覆盖已应用的过滤条件。
              setParams(queryString({ issueId, sourceId, pageNum: nextPage, pageSize: size }));
            },
          }}
        />
      </section>
    </>
  );
}
