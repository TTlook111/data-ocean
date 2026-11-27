import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { App, ConfigProvider } from 'antd';
import { beforeEach, expect, it, vi } from 'vitest';
import { api, ApiError, mutation } from '../../shared/api';
import { QueryFeedbackSupplement } from './QueryFeedbackSupplement';

// 真实表单、弹窗与查询刷新共用，接口由用例提供。
vi.mock('../../shared/api', async (original) => ({
  ...(await original<typeof import('../../shared/api')>()),
  api: vi.fn(),
  mutation: vi.fn(),
}));

/**
 * 主动补充和回复用各自状态模拟当前原问证据。
 */
const path = '/query/requests/answer-8/feedback-supplements';
const pending = {
  id: 'follow-8',
  prompt: '请说明库存业务日',
  answer: null,
  requestedAt: '2026-11-23T08:00:00Z',
  answeredAt: null,
};
const state = { version: 4, status: 'PENDING_SUPPLEMENT', canSubmit: true, entries: [pending] };

beforeEach(() => {
  vi.mocked(api).mockReset();
  vi.mocked(mutation).mockReset();
});

/**
 * 普通问数无补充区域，已有反馈仍在原卡片上独立回看。
 */
function mount(awaitIntake = false) {
  const cache = new QueryClient();
  render(
    <ConfigProvider theme={{ token: { motion: false } }}>
      <App>
        <QueryClientProvider client={cache}>
          <QueryFeedbackSupplement requestId="answer-8" awaitIntake={awaitIntake} />
        </QueryClientProvider>
      </App>
    </ConfigProvider>,
  );
  return cache;
}

/**
 * 只回复当前原问，提交后更新实际状态及已回复正文。
 */
it('原提问人从本轮问数回复必要补充', async () => {
  vi.mocked(api)
    .mockResolvedValueOnce(state)
    .mockResolvedValue({
      ...state,
      version: 5,
      status: 'IN_PROGRESS',
      canSubmit: false,
      entries: [{ ...pending, answer: '以闭店盘点为准', answeredAt: '2026-11-23T09:00:00Z' }],
    });
  vi.mocked(mutation).mockResolvedValue({ version: 5 });
  mount();
  fireEvent.click(await screen.findByRole('button', { name: '回复补充请求' }));
  const dialog = within(await screen.findByRole('dialog', { name: '补充业务说明' }));
  await waitFor(() => expect(dialog.getByText(pending.prompt)).toBeVisible());
  fireEvent.change(dialog.getByLabelText('业务补充说明'), {
    target: { value: ' 以闭店盘点为准 ' },
  });
  fireEvent.click(dialog.getByRole('button', { name: '提交补充' }));
  await waitFor(() =>
    expect(mutation).toHaveBeenCalledWith(
      path,
      'POST',
      { expectedVersion: 4, supplementId: 'follow-8', answer: '以闭店盘点为准' },
      'query.view',
      expect.any(String),
    ),
  );
  expect(await screen.findByText('处理中')).toBeVisible();
  expect(screen.getByText('以闭店盘点为准')).toBeVisible();
});

/**
 * 主动补充不携带其他请求身份，版本冲突重新读取并保留草稿。
 */
it('知识缺口可主动补充并处理旧表单冲突', async () => {
  vi.mocked(api)
    .mockResolvedValueOnce({ ...state, entries: [] })
    .mockResolvedValue({ ...state, version: 5, entries: [] });
  vi.mocked(mutation)
    .mockRejectedValueOnce(new ApiError(409001, '事项已更新'))
    .mockResolvedValue({ version: 6 });
  mount(true);
  fireEvent.click(await screen.findByRole('button', { name: '补充业务说明' }));
  const dialog = within(await screen.findByRole('dialog', { name: '补充业务说明' }));
  fireEvent.change(dialog.getByLabelText('业务补充说明'), { target: { value: '补齐商品规格' } });
  fireEvent.click(dialog.getByRole('button', { name: '提交补充' }));
  fireEvent.click(await dialog.findByRole('button', { name: '读取最新请求' }));
  await waitFor(() => expect(dialog.getByRole('button', { name: '提交补充' })).toBeEnabled());
  expect(dialog.getByLabelText('业务补充说明')).toHaveValue('补齐商品规格');
  fireEvent.click(dialog.getByRole('button', { name: '提交补充' }));
  await waitFor(() =>
    expect(mutation).toHaveBeenLastCalledWith(
      path,
      'POST',
      { expectedVersion: 5, answer: '补齐商品规格' },
      'query.view',
      expect.any(String),
    ),
  );
});

/**
 * 处置会结束已打开的回复表单，即使知识缺口仍可另行主动补充。
 */
it.each([
  ['WITHDRAWN', '补充请求已撤回'],
  ['UNAVAILABLE', '补充请求已结束'],
] as const)('等待结束后旧表单不能提交 %s', async (disposition, label) => {
  vi.mocked(api)
    .mockResolvedValueOnce(state)
    .mockResolvedValue({
      ...state,
      version: 5,
      status: 'IN_PROGRESS',
      entries: [
        {
          ...pending,
          disposition,
          dispositionReason: '内部处置记录',
          disposedAt: '2026-11-23T09:00:00Z',
        },
      ],
    });
  const cache = mount();
  fireEvent.click(await screen.findByRole('button', { name: '回复补充请求' }));
  const dialog = within(await screen.findByRole('dialog', { name: '补充业务说明' }));
  fireEvent.change(dialog.getByLabelText('业务补充说明'), { target: { value: '已填写旧草稿' } });
  await act(() => cache.invalidateQueries({ queryKey: ['feedback-supplement', 'answer-8'] }));
  await waitFor(() => expect(dialog.getByRole('button', { name: '提交补充' })).toBeDisabled());
  expect(dialog.getByLabelText('业务补充说明')).toHaveValue('已填写旧草稿');
  const history = within(screen.getByRole('list', { name: '补充记录' }));
  expect(history.getByText(label)).toBeVisible();
  expect(history.getByText('原补充要求')).toBeVisible();
  expect(history.getByText(pending.prompt)).toBeVisible();
  expect(screen.queryByText('内部处置记录')).not.toBeInTheDocument();
  expect(mutation).not.toHaveBeenCalled();
});

/**
 * 有历史要求时，先呈现当前可回复的内容，再回看已结束请求。
 */
it('当前补充请求优先于已撤回的历史要求', async () => {
  vi.mocked(api).mockResolvedValue({
    ...state,
    entries: [
      {
        ...pending,
        id: 'follow-old',
        prompt: '请核对之前的仓库名称',
        disposition: 'WITHDRAWN',
        disposedAt: '2026-11-23T07:30:00Z',
      },
      pending,
    ],
  });
  mount();

  const history = within(await screen.findByRole('list', { name: '补充记录' }));
  const [current, withdrawn] = history.getAllByRole('listitem');
  expect(within(current).getByText(pending.prompt)).toBeVisible();
  expect(within(current).queryByText('原补充要求')).not.toBeInTheDocument();
  expect(within(withdrawn).getByText('补充请求已撤回')).toBeVisible();
  expect(within(withdrawn).getByText('原补充要求')).toBeVisible();
  expect(screen.getByRole('button', { name: '回复补充请求' })).toBeEnabled();
});
