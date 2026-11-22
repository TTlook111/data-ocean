import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { api, ApiError } from '../../shared/api';
import { QueryAnswer } from './QueryAnswer';
import type { Snapshot } from './types';

vi.mock('../../shared/api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../shared/api')>()),
  api: vi.fn(),
}));

/** 每个用例从正常结果卡片进入并独立使用查询缓存。 */
function mount(answerId = 'answer-one') {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: Infinity } },
  });
  const view = render(
    <QueryClientProvider client={client}>
      <QueryAnswer row={answer(answerId)} onDownload={vi.fn()} />
    </QueryClientProvider>,
  );
  return { client, ...view };
}

/** 一条已交付的可评价库存结果。 */
function answer(id: string): Snapshot {
  return {
    id,
    question: '可售库存是多少？',
    state: 'RESULT_DELIVERED',
    executionState: 'SUCCEEDED',
    sourceId: '1',
    cancelRequested: false,
    clarification: null,
    supplement: null,
    result: {
      columns: [{ name: 'value', label: '可售库存' }],
      rows: [['85']],
      unit: '箱',
      scope: '可售库存口径',
      dataUpdatedAt: null,
      truncated: false,
    },
    evidence: null,
    sql: null,
    conversationId: 'conversation-' + id,
    runVersion: 1,
    explanation: null,
    answerRef: { answerId: id, answerRevision: 1 },
    createdAt: '2026-11-22T06:00:00Z',
    updatedAt: '2026-11-22T06:00:00Z',
    executedAt: '2026-11-22T06:00:00Z',
  };
}

/** 成功回读的初始评价，不需要任何写操作。 */
const unrated = {
  rating: 'NONE',
  reason: null,
  version: null,
  queueState: 'NONE',
  issueId: null,
};

/** 模拟本人已经有一条待分派事项。 */
const queued = { ...unrated, queueState: 'QUEUED', issueId: '12' };

/** 始终使用同一个回答的评价与回执地址。 */
const ratingPath = '/query/requests/answer-one/answers/1/rating';
const ratingKey = ['answer-rating', 'answer-one', 1];

/** 通过真实表单入口打开并返回原因字段。 */
async function openReason(label = '点踩') {
  fireEvent.click(await screen.findByRole('button', { name: label }));
  return screen.getByRole('textbox', { name: '点踩原因' });
}

afterEach(() => {
  // 清除接口回执，避免用例共享前一轮请求的状态。
  vi.mocked(api).mockReset();
});

/** 评价读失败仍有恢复操作，已经交付的数据仍可读。 */
it('评价读失败时保留原结果并允许单独重试', async () => {
  vi.mocked(api).mockRejectedValueOnce(new Error('连接中断')).mockResolvedValue(unrated);
  mount();
  const retry = await screen.findByRole('button', { name: '重试读取评价' });
  expect(screen.getByRole('heading', { name: /可售库存.*85/ })).toBeVisible();
  fireEvent.click(retry);
  await waitFor(() => expect(screen.getByRole('button', { name: '点赞' })).toBeEnabled());
  expect(vi.mocked(api).mock.calls.map(([path, options]) => [path, options?.method])).toEqual([
    ['/query/requests/answer-one/answers/1/rating', undefined],
    ['/query/requests/answer-one/answers/1/rating', undefined],
  ]);
});

/** 服务拒绝且原命令不存在时给出实际原因，评价操作可重新使用。 */
it('评价返回 503 且无提交回执时显示服务提示并恢复按钮', async () => {
  vi.mocked(api).mockImplementation(async (path, options) => {
    if (options?.method === 'POST') throw new ApiError(503001, '评价暂不可提交');
    if (path.startsWith(ratingPath + '/commands/')) throw new ApiError(404001, '未找到命令');
    return unrated;
  });
  mount();
  const like = await screen.findByRole('button', { name: '点赞' });
  fireEvent.click(like);
  expect(await screen.findByRole('alert')).toHaveTextContent('评价暂不可提交');
  expect(like).toBeEnabled();
  expect(screen.getByRole('button', { name: '点踩' })).toBeEnabled();
});

/** 空白原因就近校验，取消不写入并恢复键盘焦点。 */
it('拒绝空白原因，Escape 返回点踩入口且没有写请求', async () => {
  vi.mocked(api).mockResolvedValue(unrated);
  mount();
  const field = await openReason();
  fireEvent.change(field, { target: { value: '　 ' } });
  fireEvent.click(screen.getByRole('button', { name: '提交反馈' }));
  expect(await screen.findByText('请填写点踩原因')).toBeVisible();
  expect(field).toHaveFocus();
  fireEvent.keyDown(field, { key: 'Escape' });
  expect(screen.queryByRole('textbox', { name: '点踩原因' })).not.toBeInTheDocument();
  await waitFor(() => expect(screen.getByRole('button', { name: '点踩' })).toHaveFocus());
  expect(vi.mocked(api).mock.calls.some(([, options]) => options?.method === 'POST')).toBe(false);
});

/** 受理、入队、改赞和撤销分别按服务端回读呈现。 */
it('点踩后依次显示已收到和已入队，改赞及撤销保留事项编号', async () => {
  const state = { ...unrated };
  const writes: unknown[] = [];
  vi.mocked(api).mockImplementation(async (_path, options) => {
    if (options?.method === 'POST') {
      // 评价变化只更新当前摘要，已有入队进度继续保留。
      const body = options.body as {
        action: string;
        reason?: string;
        expectedVersion: number | null;
      };
      writes.push(body);
      Object.assign(state, {
        rating: body.action === 'REVOKE' ? 'NONE' : body.action,
        reason: body.reason ?? null,
        version: (state.version ?? 0) + 1,
        queueState: body.action === 'DISLIKE' ? 'RECEIVED' : state.queueState,
      });
      return {};
    }
    return { ...state };
  });
  const { client } = mount();
  fireEvent.change(await openReason(), { target: { value: '  核对库存口径  ' } });
  fireEvent.click(screen.getByRole('button', { name: '提交反馈' }));
  expect(await screen.findByText('反馈已收到')).toBeVisible();
  expect(screen.queryByText(/已进入问题队列/)).not.toBeInTheDocument();
  Object.assign(state, { queueState: 'QUEUED', issueId: '12' });
  // 用一次实际查询回读更新状态，无须手工推进组件内部状态。
  await act(() => client.refetchQueries({ queryKey: ratingKey }));
  expect(await screen.findByText('已进入问题队列 · #12')).toBeVisible();
  await waitFor(() => expect(screen.getByRole('button', { name: '点赞' })).toBeEnabled());
  fireEvent.click(screen.getByRole('button', { name: '点赞' }));
  await waitFor(() => expect(screen.getByRole('button', { name: '已点赞' })).toBeEnabled());
  fireEvent.click(screen.getByRole('button', { name: '已点赞' }));
  await waitFor(() => expect(screen.getByRole('button', { name: '点赞' })).toBeEnabled());
  expect(screen.getByText('已进入问题队列 · #12')).toBeVisible();
  expect(writes).toEqual([
    { action: 'DISLIKE', reason: '核对库存口径', expectedVersion: null },
    { action: 'LIKE', expectedVersion: 1 },
    { action: 'REVOKE', expectedVersion: 2 },
  ]);
});

/** 回包未知时先读原命令，确认缺失后才允许重试同一提交。 */
it('回包未知后核对原命令，重试复用相同编号和原因', async () => {
  const writes: unknown[] = [];
  vi.mocked(api).mockImplementation(async (path, options) => {
    if (options?.method === 'POST') {
      // 第一次请求结果不明，第二次接收同一命令。
      writes.push(options);
      if (writes.length === 1) throw new Error('连接中断');
      return {};
    }
    if (path.includes('/commands/')) {
      // 服务端确认原命令尚无记录。
      throw new ApiError(404001, '记录不存在');
    }
    return writes.length > 1
      ? { ...queued, rating: 'DISLIKE', version: 1, reason: '核对' }
      : unrated;
  });
  mount();
  fireEvent.change(await openReason(), { target: { value: '核对' } });
  fireEvent.click(screen.getByRole('button', { name: '提交反馈' }));
  const retry = await screen.findByRole('button', { name: '核对并重试' });
  expect(screen.getByRole('textbox', { name: '点踩原因' })).toBeDisabled();
  fireEvent.click(retry);
  expect(await screen.findByText('已进入问题队列 · #12')).toBeVisible();
  expect(writes).toHaveLength(2);
  expect(writes[0]).toEqual(writes[1]);
  expect(vi.mocked(api).mock.calls.filter(([path]) => path.includes('/commands/'))).toHaveLength(2);
});

/** 原命令已经提交时，成功回读后不产生额外写入。 */
it('原命令已接收时只查回执和评价', async () => {
  let submitted = false;
  vi.mocked(api).mockImplementation(async (path, options) => {
    if (options?.method === 'POST') {
      // 服务器已提交，只有 HTTP 回包丢失。
      submitted = true;
      throw new Error('响应丢失');
    }
    if (path.includes('/commands/')) return {};
    return submitted ? { ...unrated, rating: 'LIKE', version: 1 } : unrated;
  });
  mount();
  fireEvent.click(await screen.findByRole('button', { name: '点赞' }));
  expect(await screen.findByRole('button', { name: '已点赞' })).toBeVisible();
  expect(
    vi.mocked(api).mock.calls.filter(([, options]) => options?.method === 'POST'),
  ).toHaveLength(1);
  expect(screen.queryByRole('button', { name: '核对并重试' })).not.toBeInTheDocument();
});

/** 同一回答的其他标签页更新时，保留草稿并要求用户显式确认。 */
it('版本冲突保留草稿，确认最新版本后才保存修改', async () => {
  let state = { ...queued, rating: 'DISLIKE', version: 1, reason: '旧原因' };
  const versions: (number | null)[] = [];
  vi.mocked(api).mockImplementation(async (_path, options) => {
    if (options?.method === 'POST') {
      // 第一次提交遇到已经推进的评价版本。
      const body = options.body as { expectedVersion: number; reason: string };
      versions.push(body.expectedVersion);
      if (versions.length === 1) {
        state = { ...state, version: 2, reason: '其他页面的原因' };
        throw new ApiError(409001, '版本已变化');
      }
      state = { ...state, version: 3, reason: body.reason };
      return {};
    }
    return { ...state };
  });
  mount();
  const field = await openReason('已点踩');
  fireEvent.change(field, { target: { value: '保留我的草稿' } });
  fireEvent.click(screen.getByRole('button', { name: '保存修改' }));
  const continueButton = await screen.findByRole('button', { name: '按最新版本继续' });
  expect(field).toHaveValue('保留我的草稿');
  expect(screen.getByRole('button', { name: '保存修改' })).toBeDisabled();
  fireEvent.click(continueButton);
  expect(field).toHaveValue('保留我的草稿');
  fireEvent.click(screen.getByRole('button', { name: '保存修改' }));
  await waitFor(() => expect(field).not.toBeInTheDocument());
  expect(versions).toEqual([1, 2]);
});

/** 已知失权不显示缓存中的编辑区或事项，父级重核同一原回答。 */
it('评价读取失权后撤下旧原因，并触发原回答复核', async () => {
  vi.mocked(api).mockResolvedValueOnce({
    ...queued,
    rating: 'DISLIKE',
    version: 1,
    reason: '受限旧原因',
  });
  const { client } = mount();
  await openReason('已点踩');
  const resultKey = ['workbench-request', '1', 'answer-one'];
  client.setQueryData(resultKey, answer('answer-one'));
  vi.mocked(api).mockRejectedValue(new ApiError(403003, '权限已撤销'));
  await act(() => client.refetchQueries({ queryKey: ratingKey }));
  await waitFor(() =>
    expect(screen.queryByRole('textbox', { name: '点踩原因' })).not.toBeInTheDocument(),
  );
  expect(screen.queryByText('已进入问题队列 · #12')).not.toBeInTheDocument();
  expect(client.getQueryState(resultKey)?.isInvalidated).toBe(true);
  expect(vi.mocked(api)).toHaveBeenCalledWith(ratingPath);
});
