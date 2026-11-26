import { App, ConfigProvider } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { SessionContext } from '../../shared/session';
import { UsersPage } from './UsersPage';

// 模拟服务端分配后的读取结果，页面及两个浮层使用正式组件。
const service = vi.hoisted(() => ({ assigned: false }));
vi.mock('../../shared/api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../shared/api')>()),
  /**
   * 列表和详情来自同一份人员事实，保存后再次读取时返回新角色。
   */
  api: vi.fn(async (path: string) => {
    const role = {
      // 用于真实勾选和分配回读的稳定角色标识。
      id: '10',
      // 角色并发版本。
      version: 1,
      // 清单中的可读角色名称。
      name: '核对角色',
      // 允许新分配的启用状态。
      lifecycle: 'ENABLED',
      // 当前主体有权选择此角色。
      selectable: true,
      // 此场景只覆盖普通角色。
      containsProtected: false,
    };
    const person = {
      // 当前回看人员标识。
      id: '1',
      // 测试资料版本。
      version: 1,
      // 人员查看入口名称。
      displayName: '核对人员',
      // 稳定登录名。
      username: 'ux-user',
      // 当前账号正常可用。
      displayStatus: 'ENABLED',
      // 无主部门的独立组件夹具。
      primaryDepartment: null,
      // 此场景不含兼任关系。
      secondaryDepartments: [],
      // 写入后重新读取的角色集合。
      roles: service.assigned ? [role] : [],
    };
    if (path.startsWith('/iam/users?'))
      return { items: [person], total: 1, pageNum: 1, pageSize: 10 };
    if (path === '/iam/users/1') return person;
    if (path.startsWith('/iam/role-options')) return [role];
    return [];
  }),
  /**
   * 测试写入只改变内存，后续由真实查询刷新当前详情。
   */
  mutation: vi.fn(async () => {
    service.assigned = true;
    return { targetId: '1' };
  }),
}));

/**
 * 独立缓存避免场景间复用旧人员角色。
 */
function renderUsers() {
  const cache = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const router = createMemoryRouter([{ path: '*', element: <UsersPage /> }]);
  render(
    <ConfigProvider locale={zhCN} theme={{ token: { motion: false } }}>
      <App>
        <QueryClientProvider client={cache}>
          <SessionContext.Provider
            value={{
              // 当前管理身份标识。
              userId: 'admin',
              // 当前登录名。
              username: 'admin',
              // 当前展示姓名。
              displayName: '验收管理员',
              // 当前人员修订。
              version: 1,
              // 已完成必要改密。
              mustChangePassword: false,
              // 只开放回看与普通分配。
              permissionCodes: ['users.view', 'users.assign'],
              // 当前安全修订。
              securityVersion: '1',
            }}
          >
            <RouterProvider router={router} />
          </SessionContext.Provider>
        </QueryClientProvider>
      </App>
    </ConfigProvider>,
  );
}

/**
 * 每个用例从未分配状态开始，不对外部人员或角色执行写入。
 */
beforeEach(() => {
  service.assigned = false;
});

/**
 * 从详情发起分配，保存和取消均需返回原详情，不退到列表丢失阅读上下文。
 */
describe('人员详情的角色分配往返', () => {
  /**
   * 取消只退出分配，不清除已经打开的人员详情。
   */
  it('取消分配后恢复原详情和未提交查询输入', async () => {
    renderUsers();
    fireEvent.change(screen.getByRole('textbox', { name: '姓名' }), {
      target: { value: '查询草稿' },
    });
    fireEvent.click(await screen.findByRole('button', { name: '核对人员' }));
    // 组件库在 test 环境统一使用 test-id，按真实标题定位对应浮层以避开替身 ID 冲突。
    const detail = (await screen.findByText('人员详情')).closest<HTMLElement>('[role="dialog"]')!;
    fireEvent.click(await within(detail).findByRole('button', { name: '分配角色' }));
    const assignment = (
      await screen.findByText('分配角色', { selector: '.ant-modal-title' })
    ).closest<HTMLElement>('[role="dialog"]')!;
    fireEvent.click(within(assignment).getByRole('button', { name: /取\s*消/ }));
    await waitFor(() => expect(detail).toBeVisible());
    await waitFor(() =>
      expect(within(detail).getByRole('button', { name: '分配角色' })).toHaveFocus(),
    );
    expect(screen.getByRole('textbox', { name: '姓名' })).toHaveValue('查询草稿');
  });

  /**
   * 保存完成后详情应回读新的角色事实，不能仅更新列表或关闭全部浮层。
   */
  it('保存分配后在原详情回看最新角色', async () => {
    renderUsers();
    fireEvent.click(await screen.findByRole('button', { name: '核对人员' }));
    // 浏览器实际名称由正常唯一 ID 关联，此处只核对保存后的上下文与事实刷新。
    const detail = (await screen.findByText('人员详情')).closest<HTMLElement>('[role="dialog"]')!;
    fireEvent.click(await within(detail).findByRole('button', { name: '分配角色' }));
    const assignment = (
      await screen.findByText('分配角色', { selector: '.ant-modal-title' })
    ).closest<HTMLElement>('[role="dialog"]')!;
    fireEvent.click(await within(assignment).findByRole('checkbox', { name: '核对角色' }));
    fireEvent.click(within(assignment).getByRole('button', { name: /保\s*存/ }));
    await waitFor(() => expect(detail).toBeVisible());
    await waitFor(() =>
      expect(within(detail).getByRole('button', { name: '分配角色' })).toHaveFocus(),
    );
    expect(await within(detail).findByText('核对角色')).toBeVisible();
  });
});
