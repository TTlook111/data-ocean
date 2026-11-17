import { App, ConfigProvider } from 'antd';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { describe, expect, it, vi } from 'vitest';
import { SessionContext } from '../../shared/session';
import { RolesPage } from './RolesPage';

// 查询响应只控制列表结果，筛选、路由与表单继续使用正式组件。
vi.mock('../../shared/api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../shared/api')>()),
  /**
   * 空列表不涉及外部写入，测试仅检查导航与可见查询条件的一致性。
   */
  api: vi.fn(async () => ({
    // 当前结果不含角色记录。
    items: [],
    // 当前有效页码。
    pageNum: 1,
    // 当前每页条数。
    pageSize: 10,
    // 当前条件下的总数。
    total: 0,
  })),
}));

/**
 * 浏览器历史恢复已应用条件，翻页时保留用户尚未提交的查询输入。
 */
describe('角色列表查询上下文', () => {
  /**
   * 回退不能只恢复结果而留下空条件，页码变化也不能提交或抹掉新输入。
   */
  it('恢复历史筛选并保留翻页前未提交的输入', async () => {
    const cache = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const router = createMemoryRouter([{ path: '*', element: <RolesPage /> }], {
      // 模拟先查询再重置后的两个浏览器历史位置。
      initialEntries: ['/iam/roles?name=界面', '/iam/roles'],
    });
    render(
      <ConfigProvider theme={{ token: { motion: false } }}>
        <App>
          <QueryClientProvider client={cache}>
            <SessionContext.Provider
              value={{
                // 只读验收身份不开放角色写入。
                userId: 'test-user',
                // 当前稳定登录名。
                username: 'test',
                // 当前展示姓名。
                displayName: '验收人员',
                // 当前人员修订。
                version: 1,
                // 已完成必要改密。
                mustChangePassword: false,
                // 仅授予角色列表查看能力。
                permissionCodes: ['roles.view'],
                // 当前显示安全修订。
                securityVersion: '1',
              }}
            >
              <RouterProvider router={router} />
            </SessionContext.Provider>
          </QueryClientProvider>
        </App>
      </ConfigProvider>,
    );
    const input = screen.getByRole('textbox', { name: '角色名称' });
    expect(input).toHaveValue('');
    await act(async () => {
      // 使用真实路由回退恢复上一轮筛选。
      await router.navigate(-1);
    });
    await waitFor(() => expect(input).toHaveValue('界面'));
    fireEvent.change(input, { target: { value: '尚未提交' } });
    await act(async () => {
      // 页码变化沿用原筛选，不提交当前草稿输入。
      await router.navigate('/iam/roles?name=界面&pageNum=2');
    });
    expect(input).toHaveValue('尚未提交');
  });
});
