import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { App, ConfigProvider } from 'antd';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { beforeEach, expect, it, vi } from 'vitest';
import { api, ApiError, mutation } from '../../shared/api';
import { FeedbackIssuePage } from './FeedbackIssuePage';
import { FeedbackQueue } from './FeedbackQueue';
import type { Issue, IssueDetail } from './types';

// 只替换网络，页面继续使用真实路由、表格、页签和表单。
vi.mock('../../shared/api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../shared/api')>()),
  api: vi.fn(),
  mutation: vi.fn(),
}));

/** 列表和详情使用同一个合成事项，保留真实日期、状态和可见性。 */
const occurredAt = '2026-11-22T10:00:00Z';
const issue: Issue = {
  id: '21',
  sourceId: '17',
  sourceName: '库存验收库',
  origin: 'USER_DISLIKE',
  status: 'PENDING_ASSIGNMENT',
  assigneeId: null,
  assigneeName: null,
  updatedAt: occurredAt,
};
const pending: IssueDetail = {
  issue,
  version: 1,
  direction: 'UNDETERMINED',
  canAssign: true,
  canReview: false,
  evidenceRestricted: false,
  question: {
    answerId: 'answer-21',
    question: '常温饮料还有多少库存？',
    supplement: '按上海仓核对',
    executedAt: occurredAt,
    evidence: { title: '可售库存口径', revision: 2, fragment: 3 },
    scope: '按商品型号汇总',
    sql: 'SELECT quantity FROM inventory',
    incomplete: false,
  },
  reports: [{ id: 'report-1', reason: '数量与台账不一致', occurredAt }],
  latestReview: null,
  activities: [],
};

/** 已分派事项保留确认前的原负责人及一次可信操作。 */
const assigned: IssueDetail = {
  ...pending,
  version: 2,
  direction: 'TECHNICAL',
  issue: { ...issue, status: 'IN_PROGRESS', assigneeId: '7', assigneeName: '陈处理员' },
  canReview: true,
  activities: [
    {
      version: 2,
      action: 'ASSIGNED',
      actor: '刘协调人',
      assignee: '陈处理员',
      direction: 'TECHNICAL',
      note: '请复核库存展示',
      rootCause: null,
      occurredAt,
    },
  ],
};

/** 每次页面用例拥有独立缓存和真实历史状态。 */
function mount(path = '/feedback', state?: { feedbackFocus?: string }) {
  const cache = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: Infinity } },
  });
  const url = new URL(path, 'http://feedback.test');
  // 按接口独立回退来源列表，事项列表与详情响应继续由各用例控制。
  if (url.pathname === '/feedback') {
    const response = vi.mocked(api).getMockImplementation();
    vi.mocked(api).mockImplementation(async (path, options) =>
      path === '/feedback/sources' ? [{ id: '17', name: '库存验收库' }] : response?.(path, options),
    );
  }
  const router = createMemoryRouter(
    [
      { path: '/feedback', element: <FeedbackQueue /> },
      { path: '/feedback/:id', element: <FeedbackIssuePage /> },
    ],
    { initialEntries: [{ pathname: url.pathname, search: url.search, state }] },
  );
  render(
    <ConfigProvider theme={{ token: { motion: false } }}>
      <App>
        <QueryClientProvider client={cache}>
          <RouterProvider router={router} />
        </QueryClientProvider>
      </App>
    </ConfigProvider>,
  );
  return { router, cache };
}

/** 测试列表响应沿用真实分页协议。 */
function page(items: Issue[], pageNum = 1, total = items.length) {
  return { items, total, pageNum, pageSize: 10 };
}

beforeEach(() => {
  vi.mocked(api).mockReset();
  vi.mocked(mutation).mockReset();
});

/** 分组、查询和换页均交给服务端，查看后返回原筛选、页码和焦点。 */
it('在队列筛选、分页并通过同一编号返回', async () => {
  vi.mocked(api).mockImplementation(async (path) => {
    if (path === '/feedback/issues/21') return pending;
    const params = new URLSearchParams(path.split('?')[1]);
    return page([issue], Number(params.get('pageNum') ?? 1), 22);
  });
  const { router } = mount();
  await screen.findByRole('button', { name: '查看事项 #21' });
  expect(screen.getByText('问数点踩')).toBeVisible();
  expect(screen.getAllByText('待分派')).toHaveLength(2);
  fireEvent.click(screen.getByRole('tab', { name: '待我处理' }));
  await waitFor(() => expect(router.state.location.search).toContain('scope=MINE'));
  fireEvent.mouseDown(screen.getByRole('combobox', { name: '数据源' }));
  fireEvent.click(
    await screen.findByText('库存验收库', { selector: '.ant-select-item-option-content' }),
  );
  fireEvent.click(screen.getByRole('button', { name: /查\s*询/ }));
  await waitFor(() =>
    expect(vi.mocked(api)).toHaveBeenCalledWith('/feedback/issues?sourceId=17&scope=MINE'),
  );
  fireEvent.click(await screen.findByTitle('2'));
  await waitFor(() =>
    expect(vi.mocked(api)).toHaveBeenCalledWith(
      '/feedback/issues?sourceId=17&scope=MINE&pageNum=2&pageSize=10',
    ),
  );
  fireEvent.click(await screen.findByRole('button', { name: '查看事项 #21' }));
  expect(await screen.findByRole('heading', { name: '事项 #21' })).toHaveFocus();
  fireEvent.click(screen.getByRole('button', { name: '返回队列' }));
  expect(router.state.location.search).toContain('pageNum=2');
  await waitFor(() => expect(screen.getByRole('button', { name: '查看事项 #21' })).toHaveFocus());
});

/** 返回时事项已移出筛选范围则回查询输入，已用条件可以整体清空。 */
it('在无匹配或无权限时提供不同的反馈', async () => {
  vi.mocked(api).mockResolvedValue(page([]));
  const { router, cache } = mount('/feedback?issueId=21&scope=MINE', { feedbackFocus: '21' });
  expect(await screen.findByText('没有匹配的记录')).toBeVisible();
  const number = screen.getByLabelText('问题摘要或编号');
  expect(number).toHaveFocus();
  fireEvent.click(screen.getByRole('button', { name: '清空条件' }));
  expect(await screen.findByText('暂无待办事项')).toBeVisible();
  expect(router.state.location.search).toBe('?scope=MINE');
  vi.mocked(api).mockRejectedValue(new ApiError(403003, '禁止读取'));
  await act(() => cache.invalidateQueries({ queryKey: ['feedback-issues'] }));
  expect(await screen.findByText('当前无权查看这些记录')).toBeVisible();
  expect(screen.queryByRole('table')).not.toBeInTheDocument();
});

/** 读失败的重试只重读同一过滤条件，负责人和新状态均可辨认。 */
it('重读失败列表并保留我负责的分组', async () => {
  let fail = true;
  vi.mocked(api).mockImplementation(async () => {
    if (fail) {
      fail = false;
      throw new Error('连接中断');
    }
    return page([
      {
        ...issue,
        origin: 'TECHNICAL_FAILURE',
        status: 'IN_PROGRESS',
        assigneeId: '7',
        assigneeName: '陈处理员',
      },
    ]);
  });
  mount('/feedback?scope=OWNED');
  fireEvent.click(await screen.findByRole('button', { name: '重新加载' }));
  expect(await screen.findByText('陈处理员')).toBeVisible();
  expect(screen.getByText('处理中')).toBeVisible();
  expect(
    vi
      .mocked(api)
      .mock.calls.map(([path]) => path)
      .filter((path) => path.startsWith('/feedback/issues')),
  ).toEqual(['/feedback/issues?scope=OWNED', '/feedback/issues?scope=OWNED']);
});

/** 原问、用户补充、反馈和 SQL 在相应区域独立呈现。 */
it('核对完整证据与尚无处理记录', async () => {
  vi.mocked(api).mockResolvedValue(pending);
  mount('/feedback/21');
  await screen.findByRole('heading', { name: '事项 #21' });
  fireEvent.click(screen.getByRole('tab', { name: '原问题与证据' }));
  expect(
    within(screen.getByRole('region', { name: '原问题' })).getByText('按上海仓核对'),
  ).toBeVisible();
  expect(
    within(screen.getByRole('region', { name: '点踩原因' })).getByText('数量与台账不一致'),
  ).toBeVisible();
  const basis = within(screen.getByRole('region', { name: '当次查询依据' }));
  expect(basis.getByText('可售库存口径 · r2 · 片段 4')).toBeVisible();
  expect(basis.getByText('answer-21')).toBeVisible();
  expect(basis.getByText('查看 SQL').closest('details')).not.toHaveAttribute('open');
  fireEvent.click(screen.getByRole('tab', { name: '记录与回告' }));
  expect(screen.getByText('暂无处理记录')).toBeVisible();
});

/** 旧历史只有能从当次事实核对的字段，缺失不会虚构为正常证据。 */
it('显示不完整证据、到期原因及受限的原问题', async () => {
  vi.mocked(api).mockResolvedValue({
    ...pending,
    question: {
      ...pending.question,
      question: null,
      supplement: null,
      evidence: null,
      executedAt: null,
      scope: null,
      sql: null,
      incomplete: true,
    },
    reports: [{ id: 'old', reason: null, occurredAt }],
  });
  mount('/feedback/21');
  fireEvent.click(await screen.findByRole('tab', { name: '原问题与证据' }));
  expect(screen.getByText('证据不完整')).toBeVisible();
  expect(screen.getByText('原问题受限')).toBeVisible();
  expect(screen.getByText('原文受限或已到期')).toBeVisible();
  expect(screen.queryByText('查看 SQL')).not.toBeInTheDocument();
});

/** 重新核权后撤下正在阅读的原文，保留负责人可执行的核查。 */
it('证据撤权后清理旧正文并保留处理入口', async () => {
  vi.mocked(api).mockResolvedValue(pending);
  const { cache } = mount('/feedback/21');
  fireEvent.click(await screen.findByRole('tab', { name: '原问题与证据' }));
  expect(screen.getByText('常温饮料还有多少库存？')).toBeVisible();
  const review = {
    version: 2,
    action: 'REVIEWED',
    actor: '陈处理员',
    assignee: null,
    note: null,
    rootCause: null,
    direction: null,
    occurredAt,
  };
  vi.mocked(api).mockResolvedValue({
    ...pending,
    version: 2,
    canAssign: false,
    canReview: true,
    issue: { ...issue, status: 'IN_PROGRESS', assigneeId: '7', assigneeName: '陈处理员' },
    latestReview: review,
    activities: [review],
    evidenceRestricted: true,
    question: null,
    reports: [],
  });
  await act(() => cache.invalidateQueries({ queryKey: ['feedback-issue'] }));
  expect(await screen.findByText('当前无权查看原问题与业务证据')).toBeVisible();
  expect(screen.queryByText('常温饮料还有多少库存？')).not.toBeInTheDocument();
  expect(screen.getByRole('button', { name: '记录核查' })).toBeEnabled();
  expect(screen.queryByRole('button', { name: '重新分派' })).not.toBeInTheDocument();
});

/** 可恢复错误不把旧详情伪装成正常结果。 */
it('详情加载失败后重试同一事项', async () => {
  vi.mocked(api).mockRejectedValueOnce(new Error('连接中断')).mockResolvedValue(pending);
  mount('/feedback/21');
  fireEvent.click(await screen.findByRole('button', { name: '重新加载' }));
  expect(await screen.findByRole('heading', { name: '事项 #21' })).toBeVisible();
  expect(vi.mocked(api).mock.calls.map(([path]) => path)).toEqual([
    '/feedback/issues/21',
    '/feedback/issues/21',
  ]);
});

/** 详情内的回读更新核查、处理历史和当前列表，取消转派不触发写入。 */
it('完成详情中的核查并反映新记录', async () => {
  let stored = assigned;
  vi.mocked(api).mockImplementation(async (path) =>
    path === '/feedback/issues/21'
      ? stored
      : path.startsWith('/feedback/assignees')
        ? page([])
        : page([stored.issue]),
  );
  vi.mocked(mutation).mockImplementation(async <T,>() => {
    const review = {
      version: 3,
      action: 'REVIEWED' as const,
      actor: '陈处理员',
      assignee: null,
      direction: null,
      note: '定位到图表单位设置',
      rootCause: '图表显示单位',
      occurredAt,
    };
    stored = {
      ...stored,
      version: 3,
      latestReview: review,
      activities: [review, ...stored.activities],
    };
    return { targetId: '21', version: 3 } as T;
  });
  mount('/feedback');
  fireEvent.click(await screen.findByRole('button', { name: '查看事项 #21' }));
  expect(await screen.findByText('技术问题')).toBeVisible();
  fireEvent.click(screen.getByRole('button', { name: '重新分派' }));
  expect(await screen.findByRole('dialog', { name: '重新分派' })).toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: /取\s*消/ }));
  expect(mutation).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole('button', { name: '记录核查' }));
  expect(await screen.findByLabelText('核查依据')).toHaveValue('');
  fireEvent.change(screen.getByLabelText('核查依据'), { target: { value: '定位到图表单位设置' } });
  fireEvent.change(screen.getByLabelText('核实根因'), { target: { value: '图表显示单位' } });
  fireEvent.click(screen.getByRole('button', { name: '保存核查' }));
  await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
  expect(screen.getByText('图表显示单位')).toBeVisible();
  fireEvent.click(screen.getByRole('tab', { name: '记录与回告' }));
  const history = within(screen.getByRole('tabpanel', { name: '记录与回告' }));
  expect(history.getByText('分派事项')).toBeVisible();
  expect(history.getByText('负责人').nextElementSibling).toHaveTextContent('陈处理员');
  expect(history.getByText('分派依据').nextElementSibling).toHaveTextContent('请复核库存展示');
  expect(history.getByText('定位到图表单位设置')).toBeVisible();
});

/** 提交成功后读取异常时撤下旧表单，重试仅回读当前详情。 */
it('核查后临时读失败时可重试，避免再次写入', async () => {
  vi.mocked(api)
    .mockResolvedValueOnce(assigned)
    .mockRejectedValueOnce(new Error('读取中断'))
    .mockResolvedValue(assigned);
  vi.mocked(mutation).mockResolvedValue({ targetId: '21', version: 3 });
  mount('/feedback/21');
  fireEvent.click(await screen.findByRole('button', { name: '记录核查' }));
  fireEvent.change(screen.getByLabelText('核查依据'), { target: { value: '记录实际结果' } });
  fireEvent.click(screen.getByRole('button', { name: '保存核查' }));
  fireEvent.click(await screen.findByRole('button', { name: '重新加载' }));
  expect(await screen.findByRole('heading', { name: '事项 #21' })).toBeVisible();
  expect(mutation).toHaveBeenCalledOnce();
});

/** 独立证据标签对空的反馈记录给出明确空态。 */
it('查询依据可读取时明确展示空的反馈记录', async () => {
  vi.mocked(api).mockResolvedValue({
    ...pending,
    reports: [],
    direction: 'RETIRED_DIRECTION',
    issue: { ...issue, origin: 'OTHER_ORIGIN', status: 'UNKNOWN' },
  });
  mount('/feedback/21');
  expect(await screen.findByText('UNKNOWN')).toBeVisible();
  expect(screen.getByText('OTHER_ORIGIN')).toBeVisible();
  fireEvent.click(screen.getByRole('tab', { name: '原问题与证据' }));
  expect(screen.getByText('暂无点踩记录')).toBeVisible();
});

/** 结果、复验与回告在详情中分别保留实际记录和当前权限。 */
it('显示当前复验条件与处理后的回告', async () => {
  const stored: IssueDetail = {
    ...assigned,
    issue: { ...assigned.issue, status: 'PENDING_VERIFICATION' },
    resolution: {
      canSubmitResult: false,
      canVerify: true,
      canResolve: false,
      canCloseOther: true,
      result: {
        version: 3,
        impact: 'QUERY_SAFETY',
        summary: '去掉重复答复',
        checkMethod: '复查原问题',
        reference: '修复提交',
        regression: '核查撤权场景',
        submittedAt: occurredAt,
      },
      verification: {
        version: 4,
        decision: 'PASSED',
        valid: false,
        testScope: '桌面浏览器',
        expected: '只显示一句',
        actual: '实际只显示一句',
        dataBasis: '库存验收记录',
        verifiedAt: occurredAt,
      },
      notice: null,
    },
  };
  vi.mocked(api).mockResolvedValue(stored);
  const { cache } = mount('/feedback/21');
  expect(await screen.findByText('去掉重复答复')).toBeVisible();
  expect(screen.getByText('复验条件已变化，请重新登记')).toBeVisible();
  expect(screen.getByText('核查撤权场景')).toBeVisible();
  expect(screen.queryByRole('button', { name: '验证结案并回告' })).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: /更\s*多/ }));
  fireEvent.click(await screen.findByRole('menuitem', { name: '说明并结案' }));
  expect(await screen.findByRole('dialog', { name: '说明并结案' })).toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: /取\s*消/ }));
  const completed = {
    ...stored,
    issue: { ...stored.issue, status: 'RESOLVED' },
    resolution: {
      ...stored.resolution,
      canVerify: false,
      canCloseOther: false,
      notice: { state: 'DELIVERED', sentAt: occurredAt, readAt: occurredAt },
    },
    activities: [{ ...stored.activities[0], action: 'CLOSED', conclusion: '已核查并结案' }],
  };
  vi.mocked(api).mockResolvedValue(completed);
  await act(() => cache.invalidateQueries({ queryKey: ['feedback-issue'] }));
  await screen.findByText('已解决');
  fireEvent.click(screen.getByRole('tab', { name: '记录与回告' }));
  expect(await screen.findByText('已核查并结案')).toBeVisible();
  expect(screen.getByText(/已投递/)).toBeVisible();
  expect(screen.getByText(/已读/)).toBeVisible();
});

/** 自动来源以独立条件查询，分组和回退沿用同一查询入口。 */
it('按知识缺口筛选，进入真实事项的默认分派方向', async () => {
  const gap = { ...issue, origin: 'QUERY_KNOWLEDGE_GAP' };
  vi.mocked(api).mockImplementation(async (path) =>
    path === '/feedback/issues/21'
      ? {
          ...pending,
          issue: gap,
          direction: 'KNOWLEDGE',
          reports: [],
          question: { ...pending.question, evidence: null, sql: null },
          incidents: [
            {
              requestId: 'query-gap-1',
              state: 'KNOWLEDGE_INSUFFICIENT',
              phase: 'RETRIEVING',
              diagnostic: 'NO_PUBLISHED_KNOWLEDGE',
              executionState: 'NOT_SUBMITTED',
              failedAt: occurredAt,
            },
          ],
        }
      : path.startsWith('/feedback/assignees')
        ? page([])
        : page([gap]),
  );
  const { router } = mount();
  await screen.findByRole('button', { name: '查看事项 #21' });
  fireEvent.click(screen.getByRole('button', { name: '更多筛选' }));
  fireEvent.mouseDown(await screen.findByRole('combobox', { name: '事项来源' }));
  fireEvent.click(await screen.findByRole('option', { name: '问数知识缺口' }));
  fireEvent.click(screen.getByRole('button', { name: /查\s*询/ }));
  await waitFor(() =>
    expect(api).toHaveBeenCalledWith('/feedback/issues?origin=QUERY_KNOWLEDGE_GAP&scope=ALL'),
  );
  fireEvent.click(screen.getByRole('tab', { name: '待我处理' }));
  await waitFor(() => expect(router.state.location.search).toContain('origin=QUERY_KNOWLEDGE_GAP'));
  fireEvent.click(await screen.findByRole('button', { name: '查看事项 #21' }));
  expect(await screen.findByText('业务知识')).toBeVisible();
  fireEvent.click(screen.getByRole('tab', { name: '原问题与证据' }));
  expect(screen.getByText('数据源暂无生效知识')).toBeVisible();
  expect(screen.getByText('query-gap-1')).toBeVisible();
  expect(screen.getByText('检索知识')).toBeVisible();
  expect(screen.getByText('未执行')).toBeVisible();
  expect(screen.queryByText('点踩原因')).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: '分派负责人' }));
  expect(await screen.findByRole('dialog', { name: '分派事项' })).toHaveTextContent('业务知识');
});

/** 技术协调者可看执行未知等有限诊断，原问、参数和连接错误保持受限。 */
it('技术失败的受限详情保留可分派的状态证据', async () => {
  vi.mocked(api).mockResolvedValue({
    ...pending,
    issue: { ...issue, origin: 'QUERY_TECHNICAL_FAILURE' },
    direction: 'TECHNICAL',
    evidenceRestricted: true,
    question: null,
    reports: [],
    incidents: [
      {
        requestId: 'query-error-1',
        state: 'TECHNICAL_FAILURE',
        phase: 'EXECUTING',
        diagnostic: 'SOURCE_EXECUTION_UNCERTAIN',
        executionState: 'UNKNOWN',
        failedAt: occurredAt,
      },
    ],
  });
  mount('/feedback/21');
  expect(await screen.findByText('技术失败')).toBeVisible();
  fireEvent.click(screen.getByRole('tab', { name: '原问题与证据' }));
  expect(screen.getByText('当前无权查看原问题与业务证据')).toBeVisible();
  expect(screen.getByText('源端执行状态待核实')).toBeVisible();
  expect(screen.getByText('执行查询')).toBeVisible();
  expect(screen.getByRole('button', { name: '分派负责人' })).toBeEnabled();
});
