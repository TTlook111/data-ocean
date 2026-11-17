import { App, ConfigProvider } from 'antd';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { AccountPage } from './AccountPage';
import { api, mutation } from '../../shared/api';

// 本组组件测试只模拟本人读取，不执行真实账号写入。
vi.mock('../../shared/api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../shared/api')>()),
  api: vi.fn(async () => ({
    id: '1',
    version: 1,
    displayName: '测试人员',
    username: 'test',
    displayStatus: 'ENABLED',
    primaryDepartment: null,
    secondaryDepartments: [],
    roles: [],
    phone: null,
    email: null,
  })),
  mutation: vi.fn(async () => ({ targetId: '1', version: 2 })),
}));

/**
 * 每个场景使用独立查询缓存，核对联系方式的草稿保护行为。
 */
function renderAccount() {
  const cache = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  // jsdom 不执行原生动画，关闭动效以核对表单状态和卸载结果。
  return render(
    <ConfigProvider theme={{ token: { motion: false } }}>
      <App>
        <QueryClientProvider client={cache}>
          <AccountPage expired={vi.fn()} />
        </QueryClientProvider>
      </App>
    </ConfigProvider>,
  );
}

/**
 * 联系方式没有实际变化时不能提交，放弃提示默认保留草稿。
 */
describe('本人联系方式', () => {
  /**
   * 首次挂载前的回填不能丢失，编辑手机号须保留已有邮箱。
   */
  it('首次打开显示已有联系方式，单字段保存保留另一字段', async () => {
    vi.mocked(api).mockResolvedValueOnce({
      id: '1',
      version: 1,
      displayName: '测试人员',
      username: 'test',
      displayStatus: 'ENABLED',
      primaryDepartment: null,
      secondaryDepartments: [],
      roles: [],
      phone: '13800138000',
      email: 'kept@example.test',
    });
    renderAccount();
    await screen.findByText('测试人员');
    fireEvent.click(screen.getByRole('button', { name: '编辑联系方式' }));
    expect(screen.getByLabelText('手机号')).toHaveValue('13800138000');
    expect(screen.getByLabelText('邮箱')).toHaveValue('kept@example.test');
    fireEvent.click(screen.getByRole('button', { name: /取\s*消/ }));
    await waitFor(() => expect(screen.queryByLabelText('邮箱')).not.toBeInTheDocument());
    fireEvent.click(screen.getByRole('button', { name: '编辑联系方式' }));
    expect(screen.getByLabelText('手机号')).toHaveValue('13800138000');
    expect(screen.getByLabelText('邮箱')).toHaveValue('kept@example.test');
    fireEvent.change(screen.getByLabelText('手机号'), { target: { value: '13800138001' } });
    await waitFor(() => expect(screen.getByRole('button', { name: /保\s*存/ })).toBeEnabled());
    fireEvent.click(screen.getByRole('button', { name: /保\s*存/ }));
    await waitFor(() => expect(mutation).toHaveBeenCalled());
    expect(vi.mocked(mutation).mock.calls[0][2]).toEqual({
      expectedVersion: 1,
      phone: '13800138001',
      email: 'kept@example.test',
    });
  });

  /**
   * 修改后恢复原值属于无变化，取消时直接退出。
   */
  it('恢复原值后取消不再确认放弃', async () => {
    renderAccount();
    await screen.findByText('测试人员');
    fireEvent.click(screen.getByRole('button', { name: '编辑联系方式' }));
    fireEvent.change(screen.getByLabelText('邮箱'), { target: { value: 'draft@example.test' } });
    fireEvent.change(screen.getByLabelText('邮箱'), { target: { value: '' } });
    expect(screen.getByRole('button', { name: /保\s*存/ })).toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: /取\s*消/ }));
    await waitFor(() => expect(screen.queryByLabelText('邮箱')).not.toBeInTheDocument());
    expect(screen.queryByRole('button', { name: '继续编辑' })).not.toBeInTheDocument();
  });

  /**
   * 只打开表单不会产生可保存的修改。
   */
  it('没有实际修改时禁用保存', async () => {
    renderAccount();
    await screen.findByText('测试人员');
    fireEvent.click(screen.getByRole('button', { name: '编辑联系方式' }));
    expect(screen.getByRole('button', { name: /保\s*存/ })).toBeDisabled();
  });

  /**
   * 取消已改动的联系方式须确认，继续编辑保留原输入。
   */
  it('取消真实改动时确认并保留草稿', async () => {
    renderAccount();
    await screen.findByText('测试人员');
    fireEvent.click(screen.getByRole('button', { name: '编辑联系方式' }));
    fireEvent.change(screen.getByLabelText('邮箱'), { target: { value: 'draft@example.test' } });
    fireEvent.click(screen.getByRole('button', { name: /取\s*消/ }));
    fireEvent.click(await screen.findByRole('button', { name: '继续编辑' }));
    await waitFor(() => expect(screen.getByLabelText('邮箱')).toHaveValue('draft@example.test'));
  });
});
