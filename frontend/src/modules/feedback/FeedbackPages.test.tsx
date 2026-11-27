import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { App, ConfigProvider } from 'antd';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { beforeEach, expect, it, vi } from 'vitest';
import { api, ApiError, mutation } from '../../shared/api';
import { FeedbackIssuePage } from './FeedbackIssuePage';
import { FeedbackQueue } from './FeedbackQueue';
import type { FeedbackLifecycle, FeedbackTask, Issue, IssueDetail, Resolution } from './types';

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
      canSubmitResult: true,
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
  expect(await screen.findByRole('menuitem', { name: '修订处理结果' })).toBeInTheDocument();
  fireEvent.click(await screen.findByRole('menuitem', { name: '说明并结案' }));
  expect(await screen.findByRole('dialog', { name: '说明并结案' })).toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: /取\s*消/ }));
  const completed = {
    ...stored,
    issue: { ...stored.issue, status: 'RESOLVED' },
    resolution: {
      ...stored.resolution,
      canSubmitResult: false,
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

/**
 * 同一事项中两位负责人各有任务入口，提交结果后等待需要的复验。
 */
it('区分治理和技术的任务操作及历史归属', async () => {
  const unresolved: Resolution = {
    canResolve: false,
    canCloseOther: false,
    canVerify: false,
    canSubmitResult: false,
    result: null,
    verification: null,
    notice: null,
  };
  const knowledge: FeedbackTask = {
    id: 1,
    status: 'IN_PROGRESS',
    direction: 'KNOWLEDGE',
    scope: '核对可售库存定义',
    assigneeId: '8',
    assigneeName: '治理员',
    canReview: false,
    canAssign: true,
    canChangeStatus: true,
    progress: null,
    resolution: { ...unresolved, canSubmitKnowledge: true },
  };
  const technical: FeedbackTask = {
    ...knowledge,
    id: 6,
    status: 'SUBMITTED',
    direction: 'TECHNICAL',
    scope: null,
    assigneeId: '7',
    assigneeName: '技术员',
    canReview: true,
    resolution: {
      ...unresolved,
      canVerify: true,
      canSubmitResult: true,
      result: {
        version: 5,
        impact: 'DISPLAY',
        summary: '库存单位改为箱',
        checkMethod: '核对结果页',
        reference: null,
        regression: null,
        submittedAt: occurredAt,
      },
    },
  };
  vi.mocked(api).mockResolvedValue({
    ...assigned,
    resolution: unresolved,
    tasks: [knowledge, technical],
    activities: [
      {
        ...assigned.activities[0],
        action: 'TASK_CANCELLED',
        taskId: 3,
        rootCause: '其他任务已覆盖',
      },
    ],
  });
  mount('/feedback/21');

  const governanceTask = within(await screen.findByRole('listitem', { name: '任务 #1 业务知识' }));
  const technicalTask = within(screen.getByRole('listitem', { name: '任务 #6 技术问题' }));
  expect(governanceTask.getByText('治理员')).toBeVisible();
  expect(governanceTask.getByRole('link', { name: '发布与版本' })).toBeVisible();
  expect(governanceTask.getByRole('button', { name: '提交治理结果' })).toBeEnabled();
  expect(technicalTask.getByText('技术员')).toBeVisible();
  expect(technicalTask.getByRole('button', { name: '修订处理结果' })).toBeEnabled();
  fireEvent.click(technicalTask.getByText('查看处理记录'));
  expect(technicalTask.getByText('库存单位改为箱')).toBeVisible();
  expect(screen.queryByRole('button', { name: '验证结案并回告' })).not.toBeInTheDocument();
  fireEvent.click(technicalTask.getByRole('button', { name: '登记人工复验' }));
  await waitFor(() =>
    expect(screen.getByRole('dialog', { name: '登记人工复验 · 任务 #6' })).toBeVisible(),
  );
  fireEvent.click(
    within(screen.getByRole('dialog', { name: '登记人工复验 · 任务 #6' })).getByRole('button', {
      name: /取\s*消/,
    }),
  );

  fireEvent.click(screen.getByRole('tab', { name: '记录与回告' }));
  expect(screen.getByText('取消任务 · 任务 #3')).toBeVisible();
  expect(screen.getByText('其他任务已覆盖')).toBeVisible();
});

/** 等待生效时用任务的最新进展开启办理，切换阶段不会丢失已填写的记录。 */
it('等待生效的任务回填进展并保留阶段切换前的内容', async () => {
  const waiting: IssueDetail = {
    ...assigned,
    tasks: [
      {
        id: 2,
        status: 'IN_PROGRESS',
        direction: 'TECHNICAL',
        assigneeId: '7',
        assigneeName: '技术员',
        scope: null,
        canReview: true,
        canAssign: false,
        canChangeStatus: false,
        waitingExternal: true,
        progress: { workDone: '变更已完成核对', nextStep: '发布后检查原问题' },
        resolution: {
          canSubmitResult: true,
          canResolve: false,
          canCloseOther: false,
          canVerify: false,
          result: null,
          verification: null,
          notice: null,
        },
      } satisfies FeedbackTask,
    ],
    // 其他任务的二十条更新已经把原进展挤出事项历史。
    activities: Array.from({ length: 20 }, (_, index) => {
      // 给其他任务独立的核查版本，当前任务没有留在历史窗口中。
      return { ...assigned.activities[0], version: 23 - index, action: 'TASK_REVIEWED', taskId: 3 };
    }),
  };
  vi.mocked(api).mockResolvedValue(waiting);
  const { cache } = mount('/feedback/21');
  const task = within(await screen.findByRole('listitem', { name: '任务 #2 技术问题' }));
  expect(task.getByText('发布后检查原问题')).toBeVisible();
  fireEvent.click(task.getByRole('button', { name: '更新进展' }));
  const dialog = within(await screen.findByRole('dialog', { name: '技术处理 · 任务 #2' }));
  const stages = within(dialog.getByRole('radiogroup', { name: '目前进展' }));
  expect(stages.getByRole('radio', { name: '等待修复生效' })).toBeChecked();
  expect(await dialog.findByRole('textbox', { name: '已做工作' })).toHaveValue('变更已完成核对');
  expect(await dialog.findByRole('textbox', { name: '下一步安排' })).toHaveValue(
    '发布后检查原问题',
  );
  fireEvent.click(stages.getByRole('radio', { name: '已修复并生效' }));
  await waitFor(() => expect(dialog.getByRole('button', { name: '提交处理结果' })).toBeVisible());
  fireEvent.click(stages.getByRole('radio', { name: '等待修复生效' }));
  expect(await dialog.findByRole('textbox', { name: '下一步安排' })).toHaveValue(
    '发布后检查原问题',
  );
  expect(mutation).not.toHaveBeenCalled();

  // 读取权限变化后，仍可办理但不能继续展示先前预填的正文。
  vi.mocked(api).mockResolvedValue({
    ...waiting,
    evidenceRestricted: true,
    tasks: waiting.tasks?.map((item) => {
      // 仍保留进展存在的事实，只将受限正文置空。
      return { ...item, progress: { workDone: null, nextStep: null } };
    }),
  });
  await act(() => cache.invalidateQueries({ queryKey: ['feedback-issue'] }));
  await waitFor(() => {
    const restricted = within(screen.getByRole('dialog', { name: '技术处理 · 任务 #2' }));
    expect(restricted.getByRole('textbox', { name: '已做工作' })).toHaveValue('');
    expect(restricted.getByRole('textbox', { name: '下一步安排' })).toHaveValue('');
  });
});

/**
 * 系统返回的当前关联和办理权限，单个用例按动作补齐差异。
 */
const followUp: FeedbackLifecycle = {
  mainIssueId: null,
  memberIssueIds: [],
  supplementTargets: [],
  supplements: [],
  canMerge: false,
  canUnmerge: false,
  canReopen: false,
  completionRound: 1,
  noticeRounds: [],
};

/**
 * 两阶段核对会固定主事项当时的版本，回退后可以改依据。
 */
it('核对同源主事项后才能确认合并', async () => {
  const main = {
    ...assigned,
    version: 8,
    issue: { ...assigned.issue, id: '22', summary: '库存主事项' },
  };
  vi.mocked(api).mockImplementation(async (path) => {
    if (path.startsWith('/feedback/issues?')) return page([main.issue, assigned.issue]);
    if (path === '/feedback/issues/22') return main;
    return { ...assigned, lifecycle: { ...followUp, canMerge: true } };
  });
  vi.mocked(mutation).mockResolvedValue({ version: 3 });
  mount('/feedback/21');
  fireEvent.click(await screen.findByRole('button', { name: '合并重复事项' }));
  const dialog = within(await screen.findByRole('dialog', { name: '合并重复事项' }));
  fireEvent.mouseDown(dialog.getByRole('combobox', { name: '同源主事项' }));
  const option = await screen.findByRole('option', { name: '#22 · 库存主事项' });
  await waitFor(() => expect(option).toBeVisible());
  fireEvent.click(option);
  fireEvent.change(dialog.getByLabelText('相同根因和处理范围的依据'), {
    target: { value: '同一展示问题' },
  });
  await waitFor(() => expect(dialog.getByRole('button', { name: '核对合并范围' })).toBeEnabled());
  fireEvent.click(dialog.getByRole('button', { name: '核对合并范围' }));
  await waitFor(() => expect(api).toHaveBeenCalledWith('/feedback/issues/22'));
  await waitFor(() =>
    expect(
      dialog.getByText('#22 · 库存主事项', { selector: '.ant-descriptions-item-content' }),
    ).toBeVisible(),
  );
  expect(mutation).not.toHaveBeenCalled();
  fireEvent.click(dialog.getByRole('button', { name: '返回修改' }));
  expect(dialog.getByLabelText('相同根因和处理范围的依据')).toHaveValue('同一展示问题');
  fireEvent.click(dialog.getByRole('button', { name: '核对合并范围' }));
  fireEvent.click(await dialog.findByRole('button', { name: '确认合并' }));
  await waitFor(() =>
    expect(mutation).toHaveBeenCalledWith(
      '/feedback/issues/21/merges',
      'POST',
      { expectedVersion: 2, targetId: '22', targetVersion: 8, reason: '同一展示问题' },
      'feedback.assign',
      expect.any(String),
    ),
  );
});

/**
 * 相似建议由处理人主动领取，选中候选不提交任何办理动作。
 */
it('在人工合并表单内查找并选择同源相似候选', async () => {
  const path = '/feedback/issues/21/similarity';
  vi.mocked(api).mockImplementation(async (url) => {
    if (url.startsWith('/feedback/issues?')) {
      // 手动下拉与相似候选可能包含同一个事项。
      return page([{ ...assigned.issue, id: '22', summary: '候选事项' }]);
    }
    if (url === path) {
      // 模型结果只提供审查对象。
      return [{ id: '22', question: '上月接诊量是多少？' }];
    }
    return { ...assigned, lifecycle: { ...followUp, canMerge: true } };
  });
  mount('/feedback/21');
  fireEvent.click(await screen.findByRole('button', { name: '合并重复事项' }));
  const dialog = within(await screen.findByRole('dialog', { name: '合并重复事项' }));
  expect(api).not.toHaveBeenCalledWith(path, expect.anything());
  fireEvent.click(dialog.getByRole('button', { name: '查找相似事项' }));
  const status = await dialog.findByText('找到 1 条相似候选');
  await waitFor(() => expect(status).toBeVisible());
  expect(api).toHaveBeenCalledWith(path, { method: 'POST' });
  fireEvent.mouseDown(dialog.getByRole('combobox', { name: '同源主事项' }));
  fireEvent.click(await screen.findByRole('option', { name: '#22 · 上月接诊量是多少？' }));
  expect(dialog.getByLabelText('相同根因和处理范围的依据')).toHaveValue('');
  expect(mutation).not.toHaveBeenCalled();
});

/**
 * 后台面板把补充、纠正和重开交给对应接口，保留确切的事项引用。
 */
it.each([
  ['请求用户补充', '需要补充的业务信息', 'supplement-requests', 'feedback.process'],
  ['纠正合并', '纠正依据', 'merge-corrections', 'feedback.assign'],
  ['凭新证据重开', '新证据或同范围复发依据', 'reopens', 'feedback.process'],
])('提交事项动作 %s', async (label, field, path, permission) => {
  const lifecycle: FeedbackLifecycle = {
    ...followUp,
    mainIssueId: path === 'merge-corrections' ? '22' : null,
    memberIssueIds: ['23'],
    canUnmerge: path === 'merge-corrections',
    canReopen: path === 'reopens',
    supplementTargets:
      path === 'supplement-requests'
        ? [{ issueId: '23', requestId: 'answer-23', name: '原提问人', question: '库存业务日' }]
        : [],
    supplements: [
      {
        id: 'follow-23',
        issueId: '23',
        prompt: '核对批次',
        answer: '已核对',
        requestedAt: occurredAt,
        answeredAt: occurredAt,
      },
      {
        id: 'follow-restricted',
        issueId: '24',
        prompt: null,
        answer: null,
        requestedAt: occurredAt,
        answeredAt: null,
      },
    ],
  };
  vi.mocked(api).mockResolvedValue({ ...assigned, lifecycle });
  vi.mocked(mutation).mockResolvedValue({ version: 3 });
  mount('/feedback/21?scope=OWNED');
  expect(await screen.findByRole('link', { name: '原事项 #23' })).toHaveAttribute(
    'href',
    '/feedback/23?scope=OWNED',
  );
  expect(screen.getByText('已收到补充')).toBeVisible();
  expect(screen.getByText('等待用户补充')).toBeVisible();
  if (lifecycle.mainIssueId)
    expect(screen.getByRole('link', { name: '查看主事项 #22' })).toBeVisible();
  fireEvent.click(await screen.findByRole('button', { name: label }));
  const dialog = within(await screen.findByRole('dialog', { name: label }));
  fireEvent.change(dialog.getByLabelText(field), { target: { value: '已核实事项依据' } });
  fireEvent.click(dialog.getByRole('button', { name: label }));
  await waitFor(() =>
    expect(mutation).toHaveBeenCalledWith(
      '/feedback/issues/21/' + path,
      'POST',
      path === 'supplement-requests'
        ? { expectedVersion: 2, requestId: 'answer-23', prompt: '已核实事项依据' }
        : { expectedVersion: 2, reason: '已核实事项依据' },
      permission,
      expect.any(String),
    ),
  );
});

/**
 * 重开后回看原轮次，两条合并回告的送达及受限情况独立展示。
 */
it('在记录页回看合并依据与以往回告轮次', async () => {
  vi.mocked(api).mockResolvedValue({
    ...assigned,
    lifecycle: {
      ...followUp,
      completionRound: 2,
      noticeRounds: [
        {
          round: 1,
          total: 4,
          pending: 1,
          delivered: 2,
          restricted: 1,
          suppressed: 1,
          readCount: 1,
        },
      ],
    },
    activities: [
      { ...assigned.activities[0], action: 'REOPENED', completionRound: 2, relatedIssueId: '22' },
      {
        ...assigned.activities[0],
        action: 'CLOSED',
        completionRound: 1,
        closureCode: 'DEFINITION_CONFIRMED',
      },
      {
        ...assigned.activities[0],
        action: 'CLOSED',
        completionRound: 2,
        closureCode: 'RESOLVED',
      },
    ],
  });
  mount('/feedback/21');
  fireEvent.click(await screen.findByRole('tab', { name: '记录与回告' }));
  expect(screen.getByText('第 1 轮回告')).toBeVisible();
  expect(screen.getByText('送达 2/4，已读 1')).toBeVisible();
  expect(screen.getByText('待投递 1')).toBeVisible();
  expect(screen.getByText('无法投递 1')).toBeVisible();
  expect(screen.getByText('重开事项 · 第 2 轮')).toBeVisible();
  expect(screen.getByText('按现有口径确认无误')).toBeVisible();
  expect(screen.getByText('已解决')).toBeVisible();
  expect(screen.getByRole('link', { name: '#22' })).toHaveAttribute('href', '/feedback/22');
});

/**
 * 重开依据在版本冲突后保留，重新读取并确认后使用最新版本。
 */
it('重开冲突后重新核对事项版本并保留依据', async () => {
  let version = 2;
  vi.mocked(api).mockImplementation(async () => ({
    ...assigned,
    version,
    issue: { ...assigned.issue, status: 'RESOLVED' },
    lifecycle: { ...followUp, canReopen: true },
  }));
  vi.mocked(mutation)
    .mockRejectedValueOnce(new ApiError(409001, '事项已更新'))
    .mockResolvedValue({ version: 4 });
  mount('/feedback/21');
  fireEvent.click(await screen.findByRole('button', { name: '凭新证据重开' }));
  const dialog = within(await screen.findByRole('dialog', { name: '凭新证据重开' }));
  fireEvent.change(dialog.getByLabelText('新证据或同范围复发依据'), {
    target: { value: '同一库存范围再次复发' },
  });
  fireEvent.click(dialog.getByRole('button', { name: '凭新证据重开' }));
  const latest = await dialog.findByRole('button', { name: '读取最新版本' });
  expect(dialog.getByRole('button', { name: '凭新证据重开' })).toBeDisabled();
  version = 3;
  fireEvent.click(latest);
  await waitFor(() => expect(dialog.getByRole('button', { name: '凭新证据重开' })).toBeEnabled());
  expect(dialog.getByLabelText('新证据或同范围复发依据')).toHaveValue('同一库存范围再次复发');
  fireEvent.click(dialog.getByRole('button', { name: '凭新证据重开' }));
  await waitFor(() =>
    expect(mutation).toHaveBeenLastCalledWith(
      '/feedback/issues/21/reopens',
      'POST',
      { expectedVersion: 3, reason: '同一库存范围再次复发' },
      'feedback.process',
      expect.any(String),
    ),
  );
});

/**
 * 纠正合并的回包未知时，即使当前已显示独立事项，仍可核对同一原命令。
 */
it('纠正合并回包未知时保持原命令重试', async () => {
  let merged = true;
  vi.mocked(api).mockImplementation(async () => ({
    ...assigned,
    lifecycle: { ...followUp, canUnmerge: merged, mainIssueId: merged ? '22' : null },
  }));
  vi.mocked(mutation)
    .mockRejectedValueOnce(new Error('网络中断'))
    .mockResolvedValue({ version: 3 });
  const { cache } = mount('/feedback/21');
  fireEvent.click(await screen.findByRole('button', { name: '纠正合并' }));
  const dialog = within(await screen.findByRole('dialog', { name: '纠正合并' }));
  fireEvent.change(dialog.getByLabelText('纠正依据'), { target: { value: '库存范围不一致' } });
  fireEvent.click(dialog.getByRole('button', { name: '纠正合并' }));
  const retry = await dialog.findByRole('button', { name: '核对并重试' });
  expect(dialog.getByLabelText('纠正依据')).toBeDisabled();
  merged = false;
  await act(() => cache.invalidateQueries({ queryKey: ['feedback-issue', '21'] }));
  expect(retry).toBeEnabled();
  fireEvent.click(retry);
  await waitFor(() => expect(mutation).toHaveBeenCalledTimes(2));
  expect(vi.mocked(mutation).mock.calls[0]).toEqual(vi.mocked(mutation).mock.calls[1]);
});

/**
 * 合并态属于结案回看，切回待我处理时清除原先的合并态筛选。
 */
it('合并态只保留在可用的反馈分组筛选中', async () => {
  vi.mocked(api).mockResolvedValue(page([]));
  const { router } = mount('/feedback?scope=CLOSED&status=MERGED');
  const status = await screen.findByRole('combobox', { name: '事项状态' });
  fireEvent.mouseDown(status);
  const merged = await screen.findByRole('option', { name: '已合并' });
  await waitFor(() => expect(merged).toBeVisible());
  expect(screen.queryByRole('option', { name: '待补充' })).not.toBeInTheDocument();
  fireEvent.keyDown(status, { key: 'Escape' });
  fireEvent.click(screen.getByRole('tab', { name: '待我处理' }));
  expect(router.state.location.search).not.toContain('status=MERGED');
  expect(router.state.location.search).toContain('scope=MINE');
});

/**
 * 负责人处置指定原事项的待补充请求，历史保留结果和实际依据。
 */
it.each([
  ['撤回补充请求', 'WITHDRAWN', '已撤回补充请求'],
  ['无法取得补充', 'UNAVAILABLE', '无法取得补充'],
] as const)('主负责人可%s并继续办理', async (choice, disposition, label) => {
  const request = {
    id: 'follow-23',
    issueId: '23',
    prompt: '核对库存业务日',
    answer: null,
    requestedAt: occurredAt,
    answeredAt: null,
  };
  let submitted = false;
  vi.mocked(api).mockImplementation(async () => ({
    ...assigned,
    issue: { ...assigned.issue, status: submitted ? 'IN_PROGRESS' : 'PENDING_SUPPLEMENT' },
    lifecycle: {
      ...followUp,
      canDisposeSupplement: true,
      memberIssueIds: ['23'],
      supplements: [
        submitted
          ? {
              ...request,
              disposition,
              dispositionReason: '原账号已停用',
              disposedAt: occurredAt,
            }
          : request,
      ],
    },
  }));
  vi.mocked(mutation).mockImplementation(async () => {
    // 接口成功后，详情重读持久化结果。
    submitted = true;
    return { version: 3 };
  });
  mount('/feedback/21');
  fireEvent.click(await screen.findByRole('button', { name: '处置补充请求' }));
  const dialog = within(await screen.findByRole('dialog', { name: '处置补充请求' }));
  await waitFor(() => expect(dialog.getByText('核对库存业务日')).toBeVisible());
  fireEvent.mouseDown(dialog.getByRole('combobox', { name: '处置方式' }));
  const option = await screen.findByRole('option', { name: choice });
  await waitFor(() => expect(option).toBeVisible());
  fireEvent.click(option);
  fireEvent.change(dialog.getByLabelText('处置原因'), { target: { value: '原账号已停用' } });
  fireEvent.click(dialog.getByRole('button', { name: '处置补充请求' }));
  const supplements = within(screen.getByRole('region', { name: '用户补充' }));
  expect(await supplements.findByText(label)).toBeVisible();
  expect(screen.getByText('处置原因：原账号已停用')).toBeVisible();
  await waitFor(() =>
    expect(screen.queryByRole('button', { name: '处置补充请求' })).not.toBeInTheDocument(),
  );
  expect(mutation).toHaveBeenCalledWith(
    '/feedback/issues/21/supplement-requests/follow-23/dispositions',
    'POST',
    { expectedVersion: 2, disposition, reason: '原账号已停用' },
    'feedback.process',
    expect.any(String),
  );
});
