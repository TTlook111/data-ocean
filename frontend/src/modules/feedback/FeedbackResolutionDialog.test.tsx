import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { App, ConfigProvider } from 'antd';
import { MemoryRouter } from 'react-router';
import { beforeEach, expect, it, vi } from 'vitest';
import { api, ApiError, mutation } from '../../shared/api';
import { FeedbackResolutionDialog, type ResolutionAction } from './FeedbackResolutionDialog';
import type { IssueDetail } from './types';

// 只替换传输，与正式弹窗共用实际校验、预览及焦点行为。
vi.mock('../../shared/api', async (original) => ({
  ...(await original<typeof import('../../shared/api')>()),
  api: vi.fn(),
  mutation: vi.fn(),
}));

/** 固定当前版本，使重试和并发校验可精确断言。 */
const detail: IssueDetail = {
  version: 4,
  direction: 'TECHNICAL',
  canAssign: true,
  canReview: false,
  reports: [],
  evidenceRestricted: false,
  question: null,
  latestReview: null,
  activities: [],
  issue: {
    id: '8',
    sourceId: '1',
    sourceName: '库存',
    origin: 'USER_DISLIKE',
    status: 'PENDING_VERIFICATION',
    assigneeId: '7',
    assigneeName: '处理员',
    updatedAt: '2026-11-22T10:00:00Z',
  },
  resolution: {
    canSubmitResult: true,
    canVerify: true,
    canResolve: true,
    canCloseOther: true,
    result: null,
    verification: null,
    notice: null,
  },
};
const preview = {
  version: 4,
  recipientId: '9',
  recipientName: '原提问用户',
  template: 'RESOLVED_ASK',
  message: '你提交的反馈已完成处理并通过复验。',
};

beforeEach(() => {
  vi.mocked(api).mockReset().mockResolvedValue(preview);
  vi.mocked(mutation).mockReset().mockResolvedValue({ targetId: '8', version: 5 });
});

/** 为每次实际表单打开独立缓存和可观察父级回调。 */
async function mount(kind: ResolutionAction, issue = detail) {
  const close = vi.fn();
  const refresh = vi.fn<() => Promise<IssueDetail | undefined>>().mockResolvedValue(issue);
  const cache = new QueryClient();
  render(
    <ConfigProvider theme={{ token: { motion: false } }}>
      <App>
        <QueryClientProvider client={cache}>
          <MemoryRouter>
            <FeedbackResolutionDialog detail={issue} kind={kind} close={close} refresh={refresh} />
          </MemoryRouter>
        </QueryClientProvider>
      </App>
    </ConfigProvider>,
  );
  await waitFor(() => expect(screen.getByRole('dialog')).toBeVisible());
  return { close, refresh, dialog: within(screen.getByRole('dialog')) };
}

/** 通过实际下拉操作选择当前字段的选项。 */
async function select(label: string, value: string) {
  fireEvent.mouseDown(screen.getByRole('combobox', { name: label }));
  const dropdown = await screen.findByText(value, { selector: '.ant-select-item-option-content' });
  fireEvent.click(dropdown);
}

/** 使用标签填写实名表单，避免依赖私有 DOM 结构。 */
function fill(label: string, value: string) {
  fireEvent.change(screen.getByLabelText(label), { target: { value } });
}

/** 治理结果必须选择可用的正式版本，提交后保留确切绑定。 */
it('提交知识治理结果时仅可选择可用正式版本', async () => {
  vi.mocked(api).mockResolvedValue({
    items: [
      { id: '61', title: '日末库存口径', revision: 2, availability: 'AVAILABLE' },
      { id: '59', title: '旧日末库存口径', revision: 1, availability: 'PAUSED' },
    ],
  });
  const governance = { ...detail, resolution: { ...detail.resolution!, canSubmitKnowledge: true } };
  const { dialog, close } = await mount('knowledge', governance);
  await select('已发布版本', '日末库存口径 · r2');
  expect(screen.queryByText('旧日末库存口径 · r1')).not.toBeInTheDocument();
  fill('补齐的知识', '补齐商品规格');
  fill('原问题的覆盖依据', '独立对账');
  fireEvent.click(dialog.getByRole('button', { name: '提交治理结果' }));
  await waitFor(() => expect(close).toHaveBeenCalledOnce());
  expect(mutation).toHaveBeenCalledWith(
    '/feedback/issues/8/knowledge-results',
    'POST',
    { bindingId: '61', summary: '补齐商品规格', checkMethod: '独立对账', expectedVersion: 4 },
    'feedback.process',
    expect.any(String),
  );
});

/** 实际问数返回之前不能登记结论，网络重试请求保留同一个命令。 */
it('治理复验关联原问题的实际查询并核对结果', async () => {
  vi.mocked(api)
    .mockRejectedValueOnce(new Error('暂时无法连接'))
    .mockResolvedValueOnce({ id: 'recheck-1' })
    .mockResolvedValue({ state: 'RESULT_DELIVERED' });
  const governance = {
    ...detail,
    resolution: {
      ...detail.resolution!,
      canReplayQuestion: true,
      result: {
        version: 3,
        impact: 'KNOWLEDGE',
        knowledgeBindingId: '61',
        summary: null,
        checkMethod: null,
        reference: null,
        regression: null,
        submittedAt: '2026-11-22T10:00:00Z',
      },
    },
  };
  const { dialog, close } = await mount('verify', governance);
  const start = dialog.getByRole('button', { name: '发起问数' });
  const submit = dialog.getByRole('button', { name: '登记复验结论' });
  expect(submit).toBeDisabled();
  fireEvent.click(start);
  expect(await dialog.findByText('暂时无法连接')).toBeVisible();
  fireEvent.click(start);
  expect(await dialog.findByRole('link', { name: '查看本次回答' })).toHaveAttribute(
    'href',
    '/query?source=1&request=recheck-1',
  );
  await dialog.findByText('已返回结果');
  expect(vi.mocked(api).mock.calls[0][1]).toEqual(vi.mocked(api).mock.calls[1][1]);
  await select('复验结论', '通过');
  fill('复验场景', '按原问题复验');
  fill('期望结果', '260 箱');
  fill('实际结果', '260 箱');
  fill('数据与复验依据', '只读库对账');
  fireEvent.click(submit);
  await waitFor(() => expect(close).toHaveBeenCalledOnce());
  expect(mutation).toHaveBeenCalledWith(
    '/feedback/issues/8/verifications',
    'POST',
    expect.objectContaining({ decision: 'PASSED', recheckRequestId: 'recheck-1' }),
    'feedback.verify',
    expect.any(String),
  );
});

/** 结果只有确认生效后才会开始待验证流程。 */
it('提交展示修复前要求生效确认', async () => {
  const { dialog, close } = await mount('result');
  await select('修复影响范围', '展示与表述');
  fill('修正说明', '合并重复的单值结果');
  fill('复查方式与结果', '回看同一答案只显示一句');
  fireEvent.click(dialog.getByRole('button', { name: '提交处理结果' }));
  expect(await dialog.findByText('请确认修复已生效')).toBeVisible();
  expect(mutation).not.toHaveBeenCalled();
  fireEvent.click(dialog.getByRole('checkbox', { name: '已确认本次修复生效' }));
  fireEvent.click(dialog.getByRole('button', { name: '提交处理结果' }));
  await waitFor(() => expect(close).toHaveBeenCalledOnce());
  expect(mutation).toHaveBeenCalledWith(
    '/feedback/issues/8/results',
    'POST',
    expect.objectContaining({
      impact: 'DISPLAY',
      effectiveConfirmed: true,
      expectedVersion: 4,
    }),
    'feedback.process',
    expect.any(String),
  );
});

/** 涉及查询安全必须记录复测，回包不明时只重用本次提交。 */
it('安全修复要求回归记录，重试复用同一命令', async () => {
  vi.mocked(mutation).mockRejectedValueOnce(new Error('暂时无法核实提交结果'));
  const { dialog, close } = await mount('result');
  await select('修复影响范围', '查询与安全');
  fill('修正说明', '核查当前筛选');
  fill('复查方式与结果', '原问题从受控页面复查');
  await screen.findByLabelText('相关场景测试结果');
  fill('相关场景测试结果', '撤权回放已阻断');
  fireEvent.click(dialog.getByRole('checkbox', { name: '已确认本次修复生效' }));
  fireEvent.click(dialog.getByRole('button', { name: '提交处理结果' }));
  expect(await dialog.findByText('请确认相关场景测试已通过')).toBeVisible();
  fireEvent.click(dialog.getByRole('checkbox', { name: '已核对原问题和受限场景，测试均通过' }));
  fireEvent.click(dialog.getByRole('button', { name: '提交处理结果' }));
  expect(await dialog.findByText('暂时无法核实提交结果')).toBeVisible();
  fireEvent.click(dialog.getByRole('button', { name: '核对并重试' }));
  await waitFor(() => expect(close).toHaveBeenCalledOnce());
  expect(vi.mocked(mutation).mock.calls[0]).toEqual(vi.mocked(mutation).mock.calls[1]);
  expect(vi.mocked(mutation).mock.calls[0][2]).toMatchObject({
    regressionPassed: true,
    regression: '撤权回放已阻断',
  });
});

/** 人工复验字段和实际失败结论会被真实提交，旧版本保留草稿。 */
it('复验冲突后保留失败结论并重新核对版本', async () => {
  vi.mocked(mutation).mockRejectedValueOnce(new ApiError(409001, '已变化'));
  const { dialog, refresh, close } = await mount('verify');
  refresh.mockResolvedValueOnce(undefined).mockResolvedValueOnce({ ...detail, version: 6 });
  await select('复验结论', '未通过');
  fill('复验场景', '回看原问题');
  fill('期望结果', '只出现一次');
  fill('实际结果', '出现两次');
  fill('数据与复验依据', '当次展示记录');
  fireEvent.click(dialog.getByRole('button', { name: '登记人工复验' }));
  expect(await dialog.findByText('事项已更新')).toBeVisible();
  fireEvent.click(dialog.getByRole('button', { name: '读取最新版本' }));
  expect(await dialog.findByText('暂时无法读取当前事项')).toBeVisible();
  fireEvent.click(dialog.getByRole('button', { name: '读取最新版本' }));
  await waitFor(() => expect(dialog.getByRole('button', { name: '登记人工复验' })).toBeEnabled());
  expect(dialog.getByLabelText('实际结果')).toHaveValue('出现两次');
  fireEvent.click(dialog.getByRole('button', { name: '登记人工复验' }));
  await waitFor(() => expect(close).toHaveBeenCalledOnce());
  expect(mutation).toHaveBeenLastCalledWith(
    '/feedback/issues/8/verifications',
    'POST',
    expect.objectContaining({ decision: 'FAILED', expectedVersion: 6 }),
    'feedback.verify',
    expect.any(String),
  );
});

/** 结案前只读预览，返回修改保留草稿，只有确认后才提交原提问人。 */
it('核对回告模板与原提问人后提交结案', async () => {
  const { dialog, close } = await mount('resolve');
  fill('结案结论', '内部核查说明');
  fill('结案依据', '现场复验记录');
  fireEvent.click(dialog.getByRole('button', { name: '核对结案与回告' }));
  const confirmation = await dialog.findByRole('button', { name: '结案并确认回告' });
  await waitFor(() => expect(confirmation).toHaveFocus());
  expect(dialog.getByText('原提问用户')).toBeVisible();
  expect(dialog.getByText(preview.message)).toBeVisible();
  expect(api).toHaveBeenCalledWith('/feedback/issues/8/closure-preview?outcome=RESOLVED');
  expect(mutation).not.toHaveBeenCalled();
  fireEvent.click(dialog.getByRole('button', { name: '返回修改' }));
  expect(dialog.getByLabelText('结案依据')).toHaveValue('现场复验记录');
  fireEvent.click(dialog.getByRole('button', { name: '核对结案与回告' }));
  fireEvent.click(await dialog.findByRole('button', { name: '结案并确认回告' }));
  await waitFor(() => expect(close).toHaveBeenCalledOnce());
  expect(mutation).toHaveBeenCalledWith(
    '/feedback/issues/8/closures',
    'POST',
    expect.objectContaining({
      recipientId: '9',
      confirmedTemplate: 'RESOLVED_ASK',
      outcome: 'RESOLVED',
      expectedVersion: 4,
    }),
    'feedback.close',
    expect.any(String),
  );
});

/** 其他结案明确原因，取消或预览失败均不写入。 */
it('说明结案保留真实原因并允许预览重试', async () => {
  vi.mocked(api)
    .mockRejectedValueOnce(new Error('预览暂不可用'))
    .mockResolvedValue({
      ...preview,
      template: 'NOT_REPRODUCED',
      message: '本次核查未能复现问题。',
    });
  const { dialog, close } = await mount('explain');
  await select('结案原因', '未复现');
  fill('结案结论', '当前条件未复现');
  fill('结案依据', '核对实际页面');
  fireEvent.click(dialog.getByRole('button', { name: '核对结案与回告' }));
  expect(await dialog.findByText('预览暂不可用')).toBeVisible();
  fireEvent.click(dialog.getByRole('button', { name: '核对结案与回告' }));
  expect(await dialog.findByText('本次核查未能复现问题。')).toBeVisible();
  expect(api).toHaveBeenLastCalledWith(
    '/feedback/issues/8/closure-preview?outcome=CLOSED_OTHER&reason=NOT_REPRODUCED',
  );
  fireEvent.click(dialog.getByRole('button', { name: '返回修改' }));
  fireEvent.click(dialog.getByRole('button', { name: /取\s*消/ }));
  expect(close).toHaveBeenCalledOnce();
  expect(mutation).not.toHaveBeenCalled();
});

/** 权限撤回与 403 都需要一次事实重读。 */
it('复验被拒绝时重读资格', async () => {
  vi.mocked(mutation).mockRejectedValueOnce(new ApiError(403003, '当前无复验资格'));
  const { dialog, refresh, close } = await mount('verify');
  await select('复验结论', '通过');
  fill('复验场景', '原问');
  fill('期望结果', '一句');
  fill('实际结果', '一句');
  fill('数据与复验依据', '现行界面');
  fireEvent.click(dialog.getByRole('button', { name: '登记人工复验' }));
  expect(await dialog.findByText('当前无复验资格')).toBeVisible();
  expect(refresh).toHaveBeenCalledOnce();
  expect(close).not.toHaveBeenCalled();
});

/** 预览返回的新版本不能直接确认，清除旧资格时按钮立即关闭。 */
it('预览版本变化要求重新读取后才能确认', async () => {
  vi.mocked(api).mockResolvedValueOnce({ ...preview, version: 5 });
  const { dialog, refresh } = await mount('resolve');
  fill('结案结论', '已验证');
  fill('结案依据', '复验记录');
  fireEvent.click(dialog.getByRole('button', { name: '核对结案与回告' }));
  expect(await dialog.findByText('事项已更新')).toBeVisible();
  expect(dialog.getByRole('button', { name: '核对结案与回告' })).toBeDisabled();
  expect(mutation).not.toHaveBeenCalled();
  fireEvent.click(dialog.getByRole('button', { name: /取\s*消/ }));
  expect(refresh).not.toHaveBeenCalled();
});

/** 撤权发生在弹窗已打开时也不提供继续提交。 */
it('当前失去结案资格时暂停提交', async () => {
  const { dialog } = await mount('resolve', {
    ...detail,
    resolution: { ...detail.resolution!, canResolve: false },
  });
  expect(dialog.getByText('当前事项已无法执行此操作')).toBeVisible();
  expect(dialog.getByRole('button', { name: '核对结案与回告' })).toBeDisabled();
  expect(api).not.toHaveBeenCalled();
});
