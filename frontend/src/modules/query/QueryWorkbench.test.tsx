import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { afterEach, expect, it, vi } from 'vitest';
import { api, ApiError } from '../../shared/api';
import { QueryWorkbench } from './QueryWorkbench';
import { QueryAnswer } from './QueryAnswer';
import { downloadChart, downloadResult } from './download';
import type { QueryResult, Snapshot } from './types';

vi.mock('../../shared/api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../shared/api')>()),
  api: vi.fn(),
}));

/** 已结束、允许发起追问的测试轮次。 */
function ended(id: string): Snapshot {
  return {
    id,
    question: id + '原问题',
    state: 'WAIT_EXPIRED',
    executionState: 'NOT_SUBMITTED',
    sourceId: '1',
    cancelRequested: false,
    clarification: null,
    supplement: null,
    result: null,
    evidence: null,
    sql: null,
    conversationId: id + '-conversation',
    runVersion: 1,
    explanation: null,
    answerRef: null,
    createdAt: '2026-11-22T01:00:00Z',
    updatedAt: '2026-11-22T01:00:00Z',
    executedAt: null,
  };
}

/** 会话、路由及缓存是页面正常入口，每个测试拥有自己的缓存和身份。 */
function mount(
  url = '/query?source=1&request=answer',
  permissionCodes = ['query.view', 'query.ask'],
) {
  const onBack = vi.fn();
  const onLogout = vi.fn();
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const router = createMemoryRouter(
    [
      {
        path: '/query',
        element: (
          <QueryWorkbench
            session={{
              userId: '7',
              username: 'fixture',
              displayName: '验收人',
              version: 1,
              mustChangePassword: false,
              permissionCodes,
              securityVersion: '1',
            }}
            department="运行组"
            onBack={onBack}
            onLogout={onLogout}
            loggingOut={false}
          />
        ),
      },
    ],
    { initialEntries: [url] },
  );
  const view = render(
    <QueryClientProvider client={client}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
  return { router, client, onBack, onLogout, ...view };
}

/** 使用非库存数据复用与实际接口一致的日期与数值形状。 */
const series: QueryResult = {
  columns: [
    { name: 'date', label: '日期' },
    { name: 'value', label: '净用电量' },
  ],
  rows: [
    ['2026-11-20', '0'],
    ['2026-11-22', '-1.25'],
  ],
  series: { start: '2026-11-20', end: '2026-11-22' },
  scope: '按电表读数核算。',
  unit: 'kWh',
  dataUpdatedAt: null,
  truncated: false,
};

/** 抽掉 JSDOM 缺少的画布和浏览器下载，保留图表、文件内容和页面行为。 */
function downloadBrowser() {
  const context = {
    fillRect: vi.fn(),
    fillText: vi.fn(),
    beginPath: vi.fn(),
    moveTo: vi.fn(),
    lineTo: vi.fn(),
    stroke: vi.fn(),
    arc: vi.fn(),
    fill: vi.fn(),
  };
  vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockImplementation(
    () => context as unknown as CanvasRenderingContext2D,
  );
  const png = new Blob(['image'], { type: 'image/png' });
  const toBlob = vi
    .spyOn(HTMLCanvasElement.prototype, 'toBlob')
    .mockImplementation((callback) => callback(png));
  const objectUrl = vi.fn((_: Blob) => 'blob:fixture');
  const OriginalUrl = URL;
  vi.stubGlobal(
    'URL',
    class extends OriginalUrl {
      static createObjectURL = objectUrl;
      static revokeObjectURL = vi.fn();
    },
  );
  const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});
  return { context, objectUrl, click, toBlob };
}

/** 将浏览器 Blob 按输出编码读取，校验真实下载载荷。 */
function readBlob(blob: Blob) {
  return new Promise<string>((resolve, reject) => {
    const reader = new FileReader();
    reader.addEventListener('load', () => resolve(String(reader.result)));
    reader.addEventListener('error', reject);
    reader.readAsText(blob);
  });
}

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

/** 问数工作台能浏览相邻轮次，并以不同业务单位切换和下载结果。 */
it('浏览两轮能耗历史并下载核对过权限的 CSV 与图表', async () => {
  const { objectUrl, context } = downloadBrowser();
  const oldRating = '/query/requests/old/answers/1/rating';
  vi.mocked(api).mockImplementation(async (path) => {
    if (path === oldRating) {
      // 只有与该历史回答交互后，页面才读取本轮的评价。
      return { rating: 'NONE', version: null, reason: null, queueState: 'NONE', issueId: null };
    }
    if (path === '/query/sources')
      return [
        { id: '1', name: '能耗' },
        { id: '2', name: '物流' },
      ];
    if (path.startsWith('/query/conversations?')) {
      return [
        {
          id: 'answer-conversation',
          title: '能耗历史',
          latestRequestId: 'answer',
          updatedAt: new Date().toISOString(),
        },
        { id: 'other', title: '其他历史', latestRequestId: 'other', updatedAt: '2026-11-11' },
      ];
    }
    if (path.startsWith('/query/conversations/')) {
      return [
        { id: 'next', question: '下一轮' },
        { id: 'answer', question: '当前轮' },
        { id: 'old', question: '上一轮' },
      ];
    }
    const id = path.split('/').at(-1)!;
    return {
      ...ended(id),
      question: id === 'next' ? '下一轮' : id === 'old' ? '上一轮' : '当前轮',
      conversationId: 'answer-conversation',
      result: series,
      state: 'RESULT_DELIVERED',
      answerRef: id === 'old' ? { answerId: id, answerRevision: 1 } : null,
    };
  });
  const { router } = mount();
  const result = within(await screen.findByRole('region', { name: '当前轮次' })).getByRole(
    'article',
    { name: '查询结果' },
  );
  expect(within(result).queryByAltText('')).not.toBeInTheDocument();
  expect(within(result).getByRole('img', { name: '净用电量趋势图' })).toBeVisible();
  expect(context.fillText).toHaveBeenCalledWith(
    '净用电量（kWh）',
    expect.any(Number),
    expect.any(Number),
    expect.any(Number),
  );
  fireEvent.click(within(result).getByText('表格'));
  expect(within(result).getByRole('cell', { name: '-1.25' })).toBeVisible();
  expect(within(result).getByRole('cell', { name: '—' })).toBeVisible();
  fireEvent.click(within(result).getByRole('button', { name: '导出 CSV' }));
  fireEvent.click(within(result).getByRole('button', { name: '导出图表' }));
  await waitFor(() => expect(objectUrl).toHaveBeenCalledTimes(2));
  expect(await readBlob(objectUrl.mock.calls[0][0])).toContain('"2026-11-21",""');
  expect(objectUrl.mock.calls[1][0].type).toBe('image/png');
  // 历史工具保留键盘入口，首次聚焦后才读取原轮次的评价。
  const older = within((await screen.findByRole('button', { name: '上一轮' })).closest('section')!);
  expect(older.queryByAltText('')).not.toBeInTheDocument();
  expect(older.getByRole('img', { name: '净用电量趋势图' })).toBeVisible();
  expect(older.queryByRole('button', { name: '更多操作' })).not.toBeInTheDocument();
  expect(vi.mocked(api)).not.toHaveBeenCalledWith(oldRating);
  fireEvent.focus(older.getByRole('button', { name: '查询依据' }));
  expect(await older.findByRole('button', { name: '点赞' })).toBeInTheDocument();
  expect(vi.mocked(api)).toHaveBeenCalledWith(oldRating);
  const beforeExport = vi.mocked(api).mock.calls.length;
  fireEvent.click(older.getByRole('button', { name: '导出 CSV' }));
  await waitFor(() => expect(objectUrl).toHaveBeenCalledTimes(3));
  expect(
    vi
      .mocked(api)
      .mock.calls.slice(beforeExport)
      .map(([path]) => path),
  ).toContain('/query/requests/old');
  fireEvent.click(screen.getByRole('button', { name: '搜索对话' }));
  fireEvent.change(screen.getByRole('textbox', { name: '按问题搜索' }), {
    target: { value: '能耗' },
  });
  expect(screen.queryByRole('button', { name: '其他历史' })).not.toBeInTheDocument();
  fireEvent.click(await screen.findByRole('button', { name: '下一轮' }));
  expect(router.state.location.search).toContain('request=next');
  fireEvent.click(screen.getByRole('button', { name: '新建问数' }));
  expect(router.state.location.search).toBe('?source=1');
});

/** 历史详情按原轮次读取，并与当前结果保持同一个顺序。 */
it('同一对话按原顺序显示历史问答，并分别处理已过期和失权的轮次', async () => {
  vi.mocked(api).mockImplementation(async (path) => {
    if (path === '/query/sources') return [{ id: '1', name: '库存' }];
    if (path.startsWith('/query/conversations?')) return [];
    if (path === '/query/conversations/stock/requests') {
      // 接口返回倒序的轮次摘要，页面要还原正序。
      return [
        { id: 'answer', question: '本轮问题' },
        { id: 'restricted', question: '受限旧问题' },
        { id: 'expired', question: '已过期问题' },
        { id: 'old', question: '历史问题' },
      ];
    }
    const id = path.split('/').at(-1)!;
    if (id === 'restricted') throw new ApiError(403003, '已撤权');
    const questions: Record<string, string> = {
      answer: '本轮问题',
      expired: '已过期问题',
      old: '历史问题',
    };
    return {
      ...ended(id),
      conversationId: 'stock',
      question: questions[id],
      clarification: id === 'old' ? '哪个仓库？' : null,
      supplement: id === 'old' ? '华东仓' : null,
      state: 'RESULT_DELIVERED',
      result:
        id === 'expired'
          ? null
          : {
              ...series,
              columns: [{ name: 'value', label: id === 'old' ? '历史可售库存' : '本轮可售库存' }],
              rows: [[id === 'old' ? '155' : '85']],
              unit: '箱',
              series: null,
            },
    };
  });
  mount();
  expect(await screen.findByRole('heading', { name: /历史可售库存.*155/ })).toBeVisible();
  expect(await screen.findByText('哪个仓库？')).toBeVisible();
  expect(screen.getByText('华东仓')).toBeVisible();
  expect(await screen.findByText('本次结果已超过保留期。')).toBeVisible();
  expect(await screen.findByText('当前权限不足，无法读取该轮问数。')).toBeVisible();
  expect(screen.queryByText('受限旧问题')).not.toBeInTheDocument();
  expect(screen.getAllByRole('heading', { level: 2 }).map((node) => node.textContent)).toEqual([
    '历史可售库存155箱',
    '本轮可售库存85箱',
  ]);
  expect(vi.mocked(api).mock.calls.some(([, options]) => options?.method === 'POST')).toBe(false);
});

/** 长会话分批读取，新增轮次放回原时间顺序。 */
it('长会话先读取相邻轮次，再把更早记录补到正文上方', async () => {
  const summaries = ['answer', 'seven', 'six', 'five', 'four', 'three', 'two', 'one'];
  vi.mocked(api).mockImplementation(async (path) => {
    if (path === '/query/sources') return [{ id: '1', name: '库存' }];
    if (path.startsWith('/query/conversations?')) return [];
    if (path === '/query/conversations/stock/requests') {
      // 整个摘要可查，较长的正文在请求下一批时才读取。
      return summaries.map((id) => ({ id, question: id + '原问题' }));
    }
    return { ...ended(path.split('/').at(-1)!), conversationId: 'stock' };
  });
  mount();
  await screen.findByRole('button', { name: 'seven原问题' });
  expect(vi.mocked(api)).not.toHaveBeenCalledWith('/query/requests/one');
  expect(screen.queryByRole('button', { name: 'one原问题' })).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: '查看更早问答' }));
  await screen.findByRole('button', { name: 'one原问题' });
  const history = screen.getAllByRole('region', { name: '历史问答' });
  expect(history.map((turn) => within(turn).getByRole('button').textContent)).toEqual(
    summaries
      .slice(1)
      .reverse()
      .map((id) => id + '原问题'),
  );
});

/** 导出继续使用已核权的结果，导入不再出现在问数页面。 */
it('保留结果导出和输入框，CSV 公式文本安全写入文件', async () => {
  const { objectUrl, toBlob } = downloadBrowser();
  vi.mocked(api).mockImplementation(async (path) => {
    if (path === '/query/sources') return [{ id: '1', name: '物流' }];
    if (path.startsWith('/query/conversations')) return [];
    return {
      ...ended('answer'),
      state: 'RESULT_DELIVERED',
      result: { ...series, columns: [series.columns[0], { name: 'value', label: '=妥投量' }] },
    };
  });
  const { container } = mount();
  const result = await screen.findByRole('article', { name: '查询结果' });
  expect(container.querySelector('input[type=file]')).toBeNull();
  expect(screen.queryByRole('button', { name: '导入 CSV' })).not.toBeInTheDocument();
  expect(screen.getByRole('textbox', { name: '输入问题' })).toBeEnabled();
  fireEvent.click(within(result).getByRole('button', { name: '导出 CSV' }));
  await waitFor(() => expect(objectUrl).toHaveBeenCalledTimes(1));
  expect(await readBlob(objectUrl.mock.calls[0][0])).toContain('"\'=妥投量（kWh）"');
  toBlob.mockImplementationOnce((callback) => callback(null));
  fireEvent.click(within(result).getByRole('button', { name: '导出图表' }));
  await screen.findByText('无法生成图表图片。');
});

/** 澄清和正常重查命令分别绑定当前轮次，不重发已确认的旧执行。 */
it('断线后的澄清重试复用命令，重新查询清除原上下文继承', async () => {
  let waiting = true;
  const submissions: unknown[] = [];
  vi.mocked(api).mockImplementation(async (path, options) => {
    if (path === '/query/sources') return [{ id: '1', name: '能耗' }];
    if (path.startsWith('/query/conversations')) return [];
    if (options?.method === 'POST') {
      submissions.push(options);
      if (submissions.length === 1) throw new Error('网络断开');
      waiting = false;
      return { id: path.endsWith('/clarifications') ? 'answer' : 'fresh' };
    }
    return waiting
      ? { ...ended('answer'), state: 'WAITING', clarification: '哪一个车间？' }
      : {
          ...ended('answer'),
          state: 'RESULT_DELIVERED',
          result: {
            ...series,
            series: null,
            columns: [{ name: 'value', label: '用电量' }],
            rows: [['12']],
          },
        };
  });
  const { router } = mount();
  const currentTurn = within(await screen.findByRole('region', { name: '当前轮次' }));
  expect(currentTurn.getByRole('status')).toHaveTextContent('哪一个车间？');
  expect(currentTurn.queryByAltText('')).not.toBeInTheDocument();
  const input = screen.getByRole('textbox', { name: '补充条件' });
  fireEvent.change(input, { target: { value: '第二车间' } });
  fireEvent.keyDown(input, { key: 'Enter', shiftKey: true });
  expect(submissions).toHaveLength(0);
  fireEvent.keyDown(input, { key: 'Enter' });
  await screen.findByText('网络断开');
  const resend = screen.getByRole('button', { name: '发送问题' });
  await waitFor(() => expect(resend).toBeEnabled());
  await act(async () => fireEvent.submit(resend.closest('form')!));
  await waitFor(() => expect(submissions).toHaveLength(2));
  expect(await screen.findByRole('article', { name: '查询结果' }, { timeout: 5000 })).toBeVisible();
  expect(submissions[0]).toEqual(submissions[1]);
  expect(submissions[1]).toMatchObject({ body: { answer: '第二车间' } });
  fireEvent.click(screen.getByRole('button', { name: '重新查询' }));
  await waitFor(() => expect(router.state.location.search).toContain('request=fresh'));
  expect(submissions[2]).toMatchObject({ body: { inheritContext: false, previousId: 'answer' } });
});

/** 活动轮次订阅状态并允许停止，过期结果与网络错误都有可恢复的提示。 */
it('从恢复中停止问数并关闭事件订阅', async () => {
  let state: Snapshot['state'] = 'RETRIEVING';
  let failure = true;
  const event = { addEventListener: vi.fn(), close: vi.fn() };
  vi.stubGlobal(
    'EventSource',
    vi.fn(
      class {
        constructor() {
          return event;
        }
      },
    ),
  );
  vi.mocked(api).mockImplementation(async (path) => {
    if (path === '/query/sources') return [{ id: '1', name: '物流' }];
    if (path.startsWith('/query/conversations')) return [];
    if (path.endsWith('/cancel')) {
      if (failure) {
        failure = false;
        throw new Error('稍后停止');
      }
      state = 'USER_CANCELLED';
      return {};
    }
    return { ...ended('answer'), state, runVersion: 2 };
  });
  mount();
  await screen.findByText(/恢复中/);
  expect(event.addEventListener).toHaveBeenCalledWith('query.updated', expect.any(Function));
  act(() => event.addEventListener.mock.calls[0][1]());
  fireEvent.click(screen.getByRole('button', { name: '停止问数' }));
  await screen.findByText('稍后停止');
  fireEvent.click(screen.getByRole('button', { name: '停止问数' }));
  await screen.findByText('问数已取消。');
  act(() => {
    event.addEventListener.mock.calls[0][1](
      new MessageEvent('query.updated', {
        data: JSON.stringify({ id: 'answer', state: 'USER_CANCELLED', runVersion: 2 }),
      }),
    );
  });
  expect(event.close).toHaveBeenCalled();
});

/** 同轮次完成事件直接交付正文，错误修订和等候中的旧结果保持不可见。 */
it('显示思考中并直接采用当前修订的 SSE 答复', async () => {
  const connections: EventTarget[] = [];
  vi.stubGlobal(
    'EventSource',
    vi.fn(
      class extends EventTarget {
        constructor() {
          super();
          connections.push(this);
        }

        /** 模拟关闭当前连接。 */
        close = vi.fn();
      },
    ),
  );
  vi.mocked(api).mockImplementation(async (path) => {
    if (path === '/query/sources') return [{ id: '1', name: '库存' }];
    if (path.startsWith('/query/conversations')) return [];
    return {
      ...ended('answer'),
      state: 'RESULT_DELIVERED',
      result: { ...series, columns: [{ name: 'value', label: '商品种数' }], rows: [['4']] },
      explanation: { revision: 2, status: 'GENERATING', text: null },
    };
  });
  mount();
  expect(await screen.findByText('思考中')).toBeVisible();
  expect(screen.queryByRole('heading', { name: /商品种数/ })).not.toBeInTheDocument();
  const events = connections.at(-1)!;
  const message = {
    id: 'answer',
    state: 'RESULT_DELIVERED',
    runVersion: 1,
    explanationStatus: 'COMPLETED',
    explanationText: '共有 4 种。',
  };
  // 过期修订仅触发回读，当前修订才直接出现在页面上。
  await act(async () => {
    events.dispatchEvent(
      new MessageEvent('query.updated', {
        data: JSON.stringify({ ...message, explanationRevision: 1 }),
      }),
    );
  });
  expect(screen.queryByText(message.explanationText)).not.toBeInTheDocument();
  act(() => {
    events.dispatchEvent(
      new MessageEvent('query.updated', {
        data: JSON.stringify({ ...message, explanationRevision: 2 }),
      }),
    );
  });
  expect(await screen.findByText(message.explanationText)).toBeVisible();
  expect(screen.queryByText('思考中')).not.toBeInTheDocument();
});

/** 失效的结果和无图表的响应不能从下载入口复用旧结果。 */
it('下载时重新检查结果，拒绝过期快照和非时序图表', async () => {
  vi.mocked(api).mockResolvedValueOnce({ result: null });
  await expect(downloadResult('expired')).rejects.toThrow('当前结果已不可下载。');
  vi.mocked(api).mockResolvedValueOnce({ result: { ...series, series: null } });
  await expect(downloadChart('ordinary')).rejects.toThrow('当前图表已不可下载。');
});

/** 用户切换数据源后，不展示旧来源的历史或权限数据。 */
it('失去数据源资格后保留切换入口并拒绝原会话', async () => {
  vi.mocked(api).mockImplementation(async (path) => {
    if (path === '/query/sources') return [{ id: '2', name: '工业能耗' }];
    if (path.startsWith('/query/conversations')) return [];
    throw new ApiError(403001, '权限不足');
  });
  const { router } = mount('/query?source=1&request=answer');
  await screen.findByText('当前数据源已不可访问，请选择其他数据源。');
  fireEvent.mouseDown(screen.getByRole('combobox', { name: '数据源' }));
  const options = await screen.findAllByText('工业能耗');
  const option = options.find((item) => item.closest('.ant-select-item-option'));
  expect(option).toBeTruthy();
  fireEvent.click(option!);
  expect(router.state.location.search).toBe('?source=2');
  expect(vi.mocked(api)).not.toHaveBeenCalledWith('/query/requests/answer');
});

/** 请求已接受但回包晚到时，用户当前正在查看的另一段对话应继续显示。 */
it('旧会话的迟到提交回执不会切走当前对话', async () => {
  let accept!: (value: { id: string }) => void;
  const pending = new Promise<{ id: string }>((resolve) => {
    accept = resolve;
  });
  vi.mocked(api).mockImplementation(async (path, options) => {
    if (path === '/query/sources') return [{ id: '1', name: '库存' }];
    if (path.startsWith('/query/conversations?')) {
      return [
        { id: 'first-conversation', title: '第一段', latestRequestId: 'first' },
        { id: 'second-conversation', title: '第二段', latestRequestId: 'second' },
      ];
    }
    if (path.startsWith('/query/conversations/')) return [];
    if (path === '/query/requests' && options?.method === 'POST') return pending;
    return ended(path.split('/').at(-1)!);
  });
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const router = createMemoryRouter(
    [
      {
        path: '/query',
        element: (
          <QueryWorkbench
            session={{
              userId: '7',
              username: 'fixture',
              displayName: '验收人',
              version: 1,
              mustChangePassword: false,
              permissionCodes: ['query.view', 'query.ask'],
              securityVersion: '1',
            }}
            onBack={vi.fn()}
            onLogout={vi.fn()}
            loggingOut={false}
          />
        ),
      },
    ],
    { initialEntries: ['/query?source=1&request=first'] },
  );
  render(
    <QueryClientProvider client={client}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );

  await screen.findByText('first原问题');
  const composer = screen.getByRole('textbox', { name: '输入问题' });
  fireEvent.change(composer, { target: { value: '请继续查询' } });
  fireEvent.click(screen.getByRole('button', { name: '发送问题' }));
  fireEvent.click(await screen.findByRole('button', { name: '第二段' }));
  await screen.findByText('second原问题');
  await act(async () => accept({ id: 'late' }));

  expect(router.state.location.search).toContain('request=second');
  expect(screen.getByText('second原问题')).toBeVisible();
});

/** 源端状态未知时不能通过结果操作再派发。 */
it('未知执行保留待核实反馈并隐藏重新查询入口', () => {
  render(
    <QueryAnswer
      row={{ ...ended('unknown'), state: 'TECHNICAL_FAILURE', executionState: 'UNKNOWN' }}
      onDownload={vi.fn()}
      onRequery={vi.fn()}
    />,
  );
  expect(screen.getByText('源端执行状态待核实。')).toBeVisible();
  expect(screen.queryByRole('button', { name: /重新查询/ })).not.toBeInTheDocument();
});

/** 解释失败保留原值，原始口径仍可从依据查看。 */
it('解释失败仍显示原结果和查询依据，重试只触发解释动作', () => {
  const explain = vi.fn();
  const requery = vi.fn();
  render(
    <QueryAnswer
      row={{
        ...ended('delivered'),
        state: 'RESULT_DELIVERED',
        executionState: 'SUCCEEDED',
        result: {
          columns: [{ name: 'value', label: '可售库存' }],
          rows: [['85']],
          unit: '箱',
          scope: '按仓库当前可售数量统计。',
          truncated: false,
          dataUpdatedAt: null,
        },
        explanation: { revision: 1, status: 'FAILED', text: null },
      }}
      onDownload={vi.fn()}
      onRequery={requery}
      onExplainAgain={explain}
    />,
  );
  fireEvent.click(screen.getByRole('button', { name: '重试说明' }));
  expect(screen.getByText('85', { selector: 'strong' })).toBeVisible();
  expect(screen.getByText('箱')).toBeVisible();
  const scope = screen.getByText('按仓库当前可售数量统计。');
  expect(scope).not.toBeVisible();
  fireEvent.click(screen.getByRole('button', { name: '查询依据' }));
  expect(scope).toBeVisible();
  expect(explain).toHaveBeenCalledOnce();
  expect(requery).not.toHaveBeenCalled();
});

/** 单值结果生成完整答复后只显示一句，原值和口径可按需查看。 */
it('单值结果只显示完整答复，同时保留明细和出处', () => {
  render(
    <QueryAnswer
      row={{
        ...ended('stock'),
        state: 'RESULT_DELIVERED',
        executionState: 'SUCCEEDED',
        executedAt: '2026-11-22T11:04:58Z',
        result: {
          columns: [{ name: 'value', label: '可售库存' }],
          rows: [['85']],
          unit: '箱',
          scope: '源库核对口径。',
          truncated: false,
          dataUpdatedAt: '2026-11-22T10:00:00Z',
        },
        evidence: { title: '库存口径', revision: 1, revisionId: 'r1', fragment: 0 },
        explanation: { revision: 1, status: 'COMPLETED', text: '实际可售库存 85 箱。' },
      }}
      onDownload={vi.fn()}
    />,
  );
  expect(screen.getByText('实际可售库存 85 箱。')).toBeVisible();
  expect(screen.queryByRole('heading', { name: /可售库存.*85/ })).not.toBeInTheDocument();
  const scope = screen.getByText('源库核对口径。');
  expect(scope).not.toBeVisible();
  const basis = screen.getByRole('button', { name: '查询依据' });
  // 辅助时间与工具行同级，图标仍有可访问名称。
  expect(basis).toHaveTextContent(/^$/);
  const executedAt = basis.closest('article')?.querySelector('time');
  expect(executedAt).toHaveAttribute('datetime', '2026-11-22T11:04:58Z');
  expect(executedAt?.parentElement).toContainElement(basis);
  expect(screen.queryByText(/查询于|数据更新/)).not.toBeInTheDocument();
  fireEvent.click(basis);
  expect(basis).toHaveAttribute('aria-expanded', 'true');
  expect(scope).toBeVisible();
  expect(screen.getByText('r1 · 片段 1')).toBeVisible();
  fireEvent.click(screen.getByRole('button', { name: '数据明细' }));
  expect(basis).toHaveAttribute('aria-expanded', 'false');
  expect(scope).not.toBeVisible();
  expect(screen.getByRole('table')).toBeVisible();
  expect(screen.getByRole('cell', { name: '85' })).toBeVisible();
});
