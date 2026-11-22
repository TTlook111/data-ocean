import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { App, ConfigProvider } from 'antd';
import { beforeEach, expect, it, vi } from 'vitest';
import { api, ApiError, mutation } from '../../shared/api';
import { FeedbackActionDialog } from './FeedbackActionDialog';
import type { IssueDetail } from './types';

// 只隔离后端，表单校验、弹窗焦点和按钮状态用真实组件验证。
vi.mock('../../shared/api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../shared/api')>()),
  api: vi.fn(),
  mutation: vi.fn(),
}));

/** 控制候选人和当前审批版本，避免依赖运行数据库。 */
const assignee = { id: '7', displayName: '林处理员', username: 'lin' };
const result = { items: [assignee], pageNum: 1, pageSize: 30, total: 1 };
const issue: IssueDetail = {
  issue: {
    id: '21',
    sourceId: '17',
    sourceName: '库存',
    origin: 'USER_DISLIKE',
    status: 'PENDING_ASSIGNMENT',
    assigneeId: null,
    assigneeName: null,
    updatedAt: '2026-11-22T10:00:00Z',
  },
  version: 2,
  direction: 'UNDETERMINED',
  canAssign: true,
  canReview: true,
  evidenceRestricted: true,
  question: null,
  reports: [],
  latestReview: null,
  activities: [],
};

/** 弹窗作为正式详情的一部分渲染，保留真实关闭、刷新和命令参数。 */
async function openDialog(kind: 'assign' | 'review', detail = issue) {
  const cache = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: Infinity } },
  });
  const close = vi.fn();
  const refresh = vi.fn<() => Promise<IssueDetail | undefined>>().mockResolvedValue(detail);
  const view = render(
    <ConfigProvider theme={{ token: { motion: false } }}>
      <App>
        <QueryClientProvider client={cache}>
          <FeedbackActionDialog kind={kind} detail={detail} close={close} refresh={refresh} />
        </QueryClientProvider>
      </App>
    </ConfigProvider>,
  );
  await waitFor(() => expect(screen.getByRole('dialog')).toBeVisible());
  return { close, refresh, ...view };
}

/** 通过真实下拉搜索和候选选项填写分派人。 */
async function chooseAssignee() {
  const employee = await screen.findByRole('combobox', { name: '负责人' });
  fireEvent.mouseDown(employee);
  fireEvent.change(employee, { target: { value: '林' } });
  fireEvent.click(await screen.findByRole('option', { name: '林处理员（lin）' }));
  return employee;
}

/** 核对前必须填写具体依据。 */
function fillReason(value = '  核对库存展示  ') {
  fireEvent.change(screen.getByLabelText('分派依据'), { target: { value } });
}

beforeEach(() => {
  vi.mocked(api).mockReset().mockResolvedValue(result);
  vi.mocked(mutation).mockReset().mockResolvedValue({ targetId: '21', version: 3 });
});

/** 核对、返回和确认使用同一份提案，提交进行中阻止再发一条命令。 */
it('验证分派字段，保留草稿并确认一次', async () => {
  const { close, refresh } = await openDialog('assign');
  fireEvent.click(await screen.findByRole('button', { name: '核对分派' }));
  expect(await screen.findByText('请选择负责人')).toBeVisible();
  expect(screen.getByText('请填写分派依据')).toBeVisible();
  expect(mutation).not.toHaveBeenCalled();
  await chooseAssignee();
  expect(api).toHaveBeenCalledWith('/feedback/assignees?keyword=%E6%9E%97&pageSize=30');
  fillReason();
  fireEvent.click(screen.getByRole('button', { name: '核对分派' }));
  const confirm = await screen.findByRole('button', { name: '确认分派' });
  await waitFor(() => expect(confirm).toHaveFocus());
  expect(screen.getByText('核对库存展示', { selector: 'span' })).toBeVisible();
  fireEvent.click(screen.getByRole('button', { name: '返回修改' }));
  expect(screen.getByLabelText('分派依据')).toHaveValue('  核对库存展示  ');
  fireEvent.click(screen.getByRole('button', { name: '核对分派' }));
  const button = await screen.findByRole('button', { name: '确认分派' });
  let finish: (value: { targetId: string }) => void = () => undefined;
  const submit = new Promise<{ targetId: string }>((resolve) => {
    // 用待定回执模拟同一时刻用户连续触发确认。
    finish = resolve;
  });
  vi.mocked(mutation).mockReturnValueOnce(submit);
  fireEvent.click(button);
  fireEvent.click(button);
  expect(mutation).toHaveBeenCalledTimes(1);
  expect(mutation).toHaveBeenCalledWith(
    '/feedback/issues/21/assignments',
    'POST',
    {
      assigneeId: '7',
      direction: 'UNDETERMINED',
      reason: '核对库存展示',
      expectedVersion: 2,
    },
    'feedback.assign',
    expect.any(String),
  );
  await act(async () => finish({ targetId: '21' }));
  await waitFor(() => expect(close).toHaveBeenCalledOnce());
  expect(refresh).toHaveBeenCalledOnce();
});

/** 候选读取失败时提供重试，跨搜索仍保留已选负责人。 */
it('重试候选读取并保留跨搜索选中的人员', async () => {
  vi.mocked(api).mockRejectedValueOnce(new Error('读取失败'));
  const { close } = await openDialog('assign', {
    ...issue,
    issue: { ...issue.issue, assigneeId: '3', assigneeName: '原负责人' },
  });
  expect(await screen.findByRole('dialog', { name: '重新分派' })).toBeVisible();
  fireEvent.click(await screen.findByRole('button', { name: '重新读取负责人' }));
  const employee = await chooseAssignee();
  vi.mocked(api).mockResolvedValue({ ...result, items: [], total: 0 });
  fireEvent.mouseDown(employee);
  fireEvent.change(employee, { target: { value: '未匹配' } });
  await waitFor(() =>
    expect(api).toHaveBeenCalledWith(
      '/feedback/assignees?keyword=%E6%9C%AA%E5%8C%B9%E9%85%8D&pageSize=30',
    ),
  );
  fillReason('保留跨搜索选中的负责人');
  fireEvent.click(screen.getByRole('button', { name: '核对分派' }));
  expect(
    await screen.findByText('林处理员（lin）', { selector: '.ant-descriptions-item-content' }),
  ).toBeVisible();
  fireEvent.click(screen.getByRole('button', { name: '返回修改' }));
  fireEvent.click(screen.getByRole('button', { name: /取\s*消/ }));
  expect(close).toHaveBeenCalledOnce();
  expect(mutation).not.toHaveBeenCalled();
});

/** 核查冲突不会丢草稿，只有使用重新读到的版本才允许提交。 */
it('保留冲突草稿并明确重读后再保存', async () => {
  vi.mocked(mutation).mockRejectedValueOnce(new ApiError(409001, '内容已变化'));
  const { close, refresh } = await openDialog('review');
  refresh.mockResolvedValueOnce(undefined).mockResolvedValueOnce({ ...issue, version: 5 });
  fireEvent.change(screen.getByLabelText('核查依据'), { target: { value: '  已核对历史查询  ' } });
  fireEvent.change(screen.getByLabelText('核实根因'), { target: { value: ' 图表显示 ' } });
  fireEvent.click(screen.getByRole('button', { name: '保存核查' }));
  expect(await screen.findByText('事项已更新')).toBeVisible();
  expect(screen.getByRole('button', { name: '保存核查' })).toBeDisabled();
  fireEvent.click(screen.getByRole('button', { name: '读取最新版本' }));
  expect(await screen.findByText('暂时无法读取当前事项')).toBeVisible();
  fireEvent.click(screen.getByRole('button', { name: '读取最新版本' }));
  await waitFor(() => expect(screen.getByRole('button', { name: '保存核查' })).toBeEnabled());
  expect(screen.getByLabelText('核查依据')).toHaveValue('  已核对历史查询  ');
  fireEvent.click(screen.getByRole('button', { name: '保存核查' }));
  await waitFor(() => expect(close).toHaveBeenCalledOnce());
  expect(mutation).toHaveBeenLastCalledWith(
    '/feedback/issues/21/reviews',
    'POST',
    {
      expectedVersion: 5,
      basis: '已核对历史查询',
      rootCause: '图表显示',
    },
    'feedback.process',
    expect.any(String),
  );
});

/** 网络回包不明时冻结原提案并复用同一个提交键。 */
it('核查回包不明时保持原编号和输入', async () => {
  vi.mocked(mutation).mockRejectedValueOnce(new Error('暂时无法核实提交结果'));
  const { close } = await openDialog('review');
  fireEvent.change(screen.getByLabelText('核查依据'), { target: { value: '已发送核查' } });
  fireEvent.click(screen.getByRole('button', { name: '保存核查' }));
  expect(await screen.findByText('暂时无法核实提交结果')).toBeVisible();
  expect(screen.getByLabelText('核查依据')).toBeDisabled();
  fireEvent.click(screen.getByRole('button', { name: '核对并重试' }));
  await waitFor(() => expect(close).toHaveBeenCalledOnce());
  expect(vi.mocked(mutation).mock.calls[0]).toEqual(vi.mocked(mutation).mock.calls[1]);
});

/** 领域校验落到相应字段，失权需重读事项。 */
it('显示服务端字段错误并在权限拒绝时触发复核', async () => {
  vi.mocked(mutation)
    .mockRejectedValueOnce(
      new ApiError(400001, '输入不正确', [{ field: 'basis', message: '请补充核查范围' }]),
    )
    .mockRejectedValueOnce(new ApiError(403003, '责任已变更'));
  const { refresh, close } = await openDialog('review');
  const dialog = within(screen.getByRole('dialog'));
  fireEvent.change(dialog.getByLabelText('核查依据'), { target: { value: '核查记录' } });
  fireEvent.click(dialog.getByRole('button', { name: '保存核查' }));
  expect(await dialog.findByText('请补充核查范围')).toBeVisible();
  fireEvent.change(dialog.getByLabelText('核查依据'), { target: { value: '具体核查记录' } });
  fireEvent.click(dialog.getByRole('button', { name: '保存核查' }));
  expect(await dialog.findByText('责任已变更')).toBeVisible();
  expect(refresh).toHaveBeenCalledOnce();
  expect(close).not.toHaveBeenCalled();
});

/** 已失去分派功能时立即禁用确认，并且不读取候选人。 */
it('禁用无权限的分派弹窗', async () => {
  await openDialog('assign', { ...issue, canAssign: false });
  const dialog = await screen.findByRole('dialog');
  expect(within(dialog).getByText('当前已无权执行此操作')).toBeVisible();
  expect(within(dialog).getByRole('button', { name: '核对分派' })).toBeDisabled();
  expect(api).not.toHaveBeenCalled();
  expect(mutation).not.toHaveBeenCalled();
});
