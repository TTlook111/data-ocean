import { App, ConfigProvider } from 'antd';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { api, mutation } from '../../shared/api';
import { RoleAssignmentDialog } from './UserDialogs';
import type { Person } from './types';

// 仅模拟本次角色选项与写请求，真实权限仍由独立接口用例验收。
vi.mock('../../shared/api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../shared/api')>()),
  api: vi.fn(),
  mutation: vi.fn(async () => ({ targetId: '1' })),
}));

/**
 * 初始已选项在切换搜索结果后仍须保留，停用普通角色允许移出。
 */
const existingRole = {
  id: '10',
  version: 2,
  name: '原有角色',
  lifecycle: 'DISABLED',
  containsProtected: false,
  selectable: false,
  selected: true,
};

/**
 * 新搜索结果使用不同角色，防止只验证同一组选项的表面勾选。
 */
const candidateRole = {
  id: '20',
  version: 4,
  name: '新角色',
  lifecycle: 'ENABLED',
  containsProtected: false,
  selectable: true,
  selected: false,
};

/**
 * 人员版本只来自详情，用于提交并发检查。
 */
const person: Person = {
  id: '1',
  version: 3,
  username: 'test',
  displayName: '测试人员',
  primaryDepartment: null,
  secondaryDepartments: [],
  roles: [existingRole],
  displayStatus: 'ENABLED',
};

/**
 * 场景独立设置选项结果，不在测试之间复用查询缓存。
 */
beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(api).mockImplementation(
    async <T,>(path: string): Promise<T> =>
      (path.includes('q=new') ? [candidateRole] : [existingRole]) as T,
  );
});

/**
 * 复用实际弹窗，关闭动效避免将动画等待当作组件行为。
 */
function openAssignment() {
  const cache = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <ConfigProvider theme={{ token: { motion: false } }}>
      <App>
        <QueryClientProvider client={cache}>
          <RoleAssignmentDialog person={person} close={vi.fn()} />
        </QueryClientProvider>
      </App>
    </ConfigProvider>,
  );
}

/**
 * 覆盖曾导致已有角色无法移出、跨搜索选择丢失的完整提交内容。
 */
describe('个人角色分配', () => {
  /**
   * 已停用的普通角色只能保留或移出，不被组件锁死。
   */
  it('可以移出已有停用普通角色', async () => {
    openAssignment();
    fireEvent.click(await screen.findByRole('checkbox', { name: '原有角色（停用）' }));
    fireEvent.click(screen.getByRole('button', { name: /保\s*存/ }));
    await waitFor(() => expect(mutation).toHaveBeenCalled());
    expect(vi.mocked(mutation).mock.calls[0][2]).toEqual({ expectedVersion: 3, roles: [] });
  });

  /**
   * 搜索结果不包含旧角色时，新增勾选不能无意取消旧分配。
   */
  it('搜索后勾选保留不可见的原有角色及其版本', async () => {
    openAssignment();
    await screen.findByRole('checkbox', { name: '原有角色（停用）' });
    const search = screen.getByRole('searchbox', { name: '搜索个人角色' });
    fireEvent.change(search, { target: { value: 'new' } });
    fireEvent.keyDown(search, { key: 'Enter', code: 'Enter' });
    fireEvent.click(await screen.findByRole('checkbox', { name: '新角色' }));
    fireEvent.click(screen.getByRole('button', { name: /保\s*存/ }));
    await waitFor(() => expect(mutation).toHaveBeenCalled());
    expect(vi.mocked(mutation).mock.calls[0][2]).toEqual({
      expectedVersion: 3,
      roles: [
        { roleId: '10', version: 2 },
        { roleId: '20', version: 4 },
      ],
    });
  });
});
