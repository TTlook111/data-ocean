import { useState } from 'react';
import { App, ConfigProvider } from 'antd';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { describe, expect, it, vi } from 'vitest';
import { Application } from './Application';

/**
 * 仅控制身份服务是否暂不可用，安全边界和页面挂载使用真实应用组件。
 */
const service = vi.hoisted(() => ({ unavailable: false }));

// 模拟已经认证且只有角色页查看权的会话，不执行任何外部写入。
vi.mock('../shared/api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../shared/api')>()),
  api: vi.fn(async (path: string) => {
    if (path === '/auth/me') {
      if (service.unavailable) throw new Error('服务连接暂不可用');
      return {
        userId: '1',
        username: 'test',
        displayName: '验收人员',
        version: 1,
        mustChangePassword: false,
        permissionCodes: ['roles.view'],
      };
    }
    return { displayName: '验收人员', primaryDepartment: null };
  }),
}));

// 草稿组件只用于观察应用是否卸载子树，不复制正式角色编辑业务。
vi.mock('../modules/iam/RolesPage', () => ({
  RolesPage: () => null,
  /**
   * 本地输入会在卸载后丢失，因此能直接暴露登录故障处理的回归。
   */
  RoleEditor: function Draft() {
    const [text, setText] = useState('');
    return (
      <input aria-label="角色草稿" value={text} onChange={(event) => setText(event.target.value)} />
    );
  },
}));

/**
 * 暂时无法复核时遮挡旧展示并暂停操作，恢复连接后保留用户尚未提交的输入。
 */
describe('临时身份服务故障', () => {
  /**
   * 只重新核验登录，不把连接重试变成业务重放或表单重建。
   */
  it('重新连接后保留原编辑草稿', async () => {
    service.unavailable = false;
    const cache = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const router = createMemoryRouter([{ path: '*', element: <Application /> }], {
      initialEntries: ['/iam/roles/new'],
    });
    render(
      <ConfigProvider theme={{ token: { motion: false } }}>
        <App>
          <QueryClientProvider client={cache}>
            <RouterProvider router={router} />
          </QueryClientProvider>
        </App>
      </ConfigProvider>,
    );
    fireEvent.change(await screen.findByLabelText('角色草稿'), { target: { value: '未保存角色' } });
    service.unavailable = true;
    await act(async () => {
      await cache.invalidateQueries({ queryKey: ['session'] });
    });
    await screen.findByText('暂时无法核验登录');
    expect(screen.getByLabelText('角色草稿')).toHaveValue('未保存角色');
    service.unavailable = false;
    fireEvent.click(screen.getByRole('button', { name: '重新连接' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(screen.getByLabelText('角色草稿')).toHaveValue('未保存角色');
  });
});
