import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { App } from 'antd';
import { beforeEach, expect, it, vi } from 'vitest';
import { api } from '../../shared/api';
import type { FeedbackNotice } from '../feedback/types';
import { FeedbackInbox, FeedbackNoticeLine } from './FeedbackNotices';

// 受控通知只通过实际读取和标记已读接口给出，测试不自行生成结案通知。
vi.mock('../../shared/api', async (original) => ({
  ...(await original<typeof import('../../shared/api')>()),
  api: vi.fn(),
}));

/** 原答复链接只在当前本人有权访问时由服务端返回。 */
const notice: FeedbackNotice = {
  id: 'notice-1',
  message: '你提交的反馈已完成处理并通过复验。',
  sourceId: '1',
  requestId: 'answer-1',
  sentAt: '2026-11-22T10:00:00Z',
  readAt: null,
};

beforeEach(() => {
  vi.mocked(api).mockReset();
});

/** 将收件箱和原回答共用的通知行挂进真实查询缓存。 */
function show(open = vi.fn(), inline = false) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: Infinity } },
  });
  return render(
    <App>
      <QueryClientProvider client={client}>
        {inline ? (
          <FeedbackNoticeLine notice={notice} />
        ) : (
          <FeedbackInbox close={vi.fn()} open={open} />
        )}
      </QueryClientProvider>
    </App>,
  );
}

/** 写入已读后使用服务端重新核对的原问定位。 */
it('回告打开并定位原回答', async () => {
  const open = vi.fn();
  vi.mocked(api).mockImplementation(async (_, options) =>
    options?.method === 'PUT'
      ? { ...notice, readAt: notice.sentAt }
      : { items: [notice], total: 1, pageNum: 1, pageSize: 10 },
  );
  show(open);
  expect(await screen.findByText(notice.message)).toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: '查看原回答' }));
  await waitFor(() => expect(open).toHaveBeenCalledWith('1', 'answer-1'));
  expect(api).toHaveBeenCalledWith('/feedback/notices/notice-1/read', { method: 'PUT' });
});

/** 打开之前失去授权时保持通用提示，旧链接不能导航。 */
it('回告读到撤权后停止打开原回答', async () => {
  const open = vi.fn();
  let current = notice;
  vi.mocked(api).mockImplementation(async (_, options) => {
    if (options?.method === 'PUT') {
      current = {
        ...notice,
        sourceId: null,
        requestId: null,
        message: '详细结果受当前权限限制。',
        readAt: notice.sentAt,
      };
      return current;
    }
    return { items: [current], total: 1, pageNum: 1, pageSize: 10 };
  });
  show(open);
  fireEvent.click(await screen.findByRole('button', { name: '查看原回答' }));
  expect(await screen.findByText('详细结果受当前权限限制。')).toBeInTheDocument();
  expect(open).not.toHaveBeenCalled();
  expect(screen.queryByRole('button', { name: '查看原回答' })).not.toBeInTheDocument();
});

/** 原回答行不重复显示链接，已读失败时仍可再次确认。 */
it('原回答标记已读失败时保留回告', async () => {
  vi.mocked(api).mockRejectedValue(new Error('断线'));
  show(undefined, true);
  fireEvent.click(screen.getByRole('button', { name: '标为已读' }));
  expect(await screen.findByText('暂时无法记录已读')).toBeVisible();
  expect(screen.getByText(notice.message)).toBeVisible();
});
