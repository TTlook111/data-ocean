import { useState } from 'react';
import { App, ConfigProvider } from 'antd';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { describe, expect, it, vi } from 'vitest';
import { api, ApiError } from '../shared/api';
import { Application } from './Application';

// jsdom 缺少原生模态方法，组件测试仅替代开关，真正的焦点隔离由浏览器检查。
Object.defineProperties(HTMLDialogElement.prototype, {
  showModal: {
    configurable: true,
    value: function showModal(this: HTMLDialogElement) {
      this.open = true;
    },
  },
  close: {
    configurable: true,
    value: function close(this: HTMLDialogElement) {
      this.open = false;
    },
  },
});

// 路由测试使用画布不可用时的静态回退，鱼群操控由浏览器检查。
vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(null);

/**
 * 仅控制身份服务是否暂不可用，安全边界和页面挂载使用真实应用组件。
 */
const service = vi.hoisted(() => ({ unavailable: false, logoutFailure: false }));

// 模拟已经认证且只有角色页查看权的会话，不执行任何外部写入。
vi.mock('../shared/api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../shared/api')>()),
  api: vi.fn(async (path: string) => {
    // 注销失败不能先清空身份或静默吞掉界面反馈。
    if (path === '/auth/logout' && service.logoutFailure) throw new Error('退出暂不可用');
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
 * 按实际路由挂载应用，分别检查不存在的地址和真实权限限制。
 */
it('404 保留未知地址并可经问数首页进入有权访问的后台', async () => {
  service.unavailable = false;
  const cache = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const router = createMemoryRouter([{ path: '*', element: <Application /> }], {
    initialEntries: ['/iam/users/unknown'],
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
  await screen.findByRole('heading', { name: '这条航线走丢了' });
  expect(router.state.location.pathname).toBe('/iam/users/unknown');
  expect(screen.queryByRole('menu', { name: '后台管理菜单' })).not.toBeInTheDocument();
  // 独立状态页不触发登录探测，进入有效地址后才读取身份。
  expect(api).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole('button', { name: '返回首页' }));
  await waitFor(() => expect(router.state.location.pathname).toBe('/query'));
  expect(api).toHaveBeenCalledWith('/auth/me');
  expect(await screen.findByText('当前账号暂无问数权限。')).toBeVisible();
  expect(api).not.toHaveBeenCalledWith('/query/sources');
  fireEvent.click(await screen.findByRole('button', { name: /用户菜单/ }));
  fireEvent.click(await screen.findByRole('menuitem', { name: '后台管理' }));
  await waitFor(() => expect(router.state.location.pathname).toBe('/iam/roles'));

  // 确实存在的页面仍按原功能权限限制访问。
  await act(() => router.navigate('/iam/users'));
  expect(await screen.findByText('当前无权查看此页面')).toBeInTheDocument();
});

/** 登录完成后以问数为落点，权限只限制实际操作。 */
it('普通登录直接进入问数工作台', async () => {
  vi.mocked(api).mockImplementationOnce(async () => {
    throw new ApiError(401001, '请先登录');
  });
  const cache = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const router = createMemoryRouter([{ path: '*', element: <Application /> }], {
    initialEntries: ['/login'],
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
  fireEvent.change(await screen.findByPlaceholderText('请输入登录名'), {
    target: { value: 'test' },
  });
  fireEvent.change(screen.getByPlaceholderText('请输入密码'), {
    target: { value: 'test-password' },
  });
  fireEvent.click(screen.getByRole('button', { name: /^登\s*录$/ }));
  await waitFor(() => expect(router.state.location.pathname).toBe('/query'));
  expect(await screen.findByRole('heading', { name: '问数工作台' })).toBeVisible();
  expect(api).not.toHaveBeenCalledWith('/query/sources');
});

/**
 * 暂时无法复核时遮挡旧展示并暂停操作，恢复连接后保留用户尚未提交的输入。
 */
describe('临时身份服务故障', () => {
  /**
   * 退出失败保留当前页面和会话展示，并向使用者明确说明可以重试。
   */
  it('退出失败时保留身份并提示原因', async () => {
    service.unavailable = false;
    service.logoutFailure = true;
    const cache = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const router = createMemoryRouter([{ path: '*', element: <Application /> }], {
      initialEntries: ['/iam/roles'],
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
    fireEvent.click(await screen.findByRole('button', { name: /用户菜单/ }));
    fireEvent.click(await screen.findByText('退出登录'));
    await screen.findByText('退出暂不可用');
    expect(cache.getQueryData(['session'])).not.toBeNull();
    expect(screen.queryByRole('heading', { name: '登录' })).not.toBeInTheDocument();
    service.logoutFailure = false;
  });

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
