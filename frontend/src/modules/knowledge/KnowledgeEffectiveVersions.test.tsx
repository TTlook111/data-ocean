import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, render, screen } from '@testing-library/react';
import { createMemoryRouter, RouterProvider, useSearchParams } from 'react-router';
import { expect, it, vi } from 'vitest';
import { api } from '../../shared/api';
import { KnowledgeEffectiveVersions } from './KnowledgeEffectiveVersions';

// 列表请求用空响应，回退行为仍由实际表单和路由共同处理。
vi.mock('../../shared/api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../shared/api')>()),
  api: vi.fn(),
}));

/** 同一生效列表随浏览器历史提供不同的已应用筛选。 */
function ActiveList() {
  const [params] = useSearchParams();
  return <KnowledgeEffectiveVersions source="1" params={params} update={vi.fn()} />;
}

/** 返回历史筛选时，用户看到的条件和实际请求必须一致。 */
it('返回筛选历史时同步可见状态及名称', async () => {
  vi.mocked(api).mockResolvedValue({ items: [], total: 0, pageNum: 1, pageSize: 10 });
  const queryClient = new QueryClient();
  const router = createMemoryRouter([{ path: '/releases', element: <ActiveList /> }], {
    initialEntries: [
      '/releases',
      '/releases?versionAvailability=AVAILABLE',
      '/releases?versionAvailability=UNVERIFIED&versionKeyword=销售',
    ],
  });
  render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
  expect(await screen.findByText('待核实')).toBeVisible();
  expect(screen.getByRole('textbox', { name: '文档名称' })).toHaveValue('销售');

  // 通过真实路由回退，同一列表保持挂载。
  await act(() => router.navigate(-1));

  expect(await screen.findByText('正常')).toBeVisible();
  expect(screen.queryByText('待核实')).not.toBeInTheDocument();
  expect(screen.getByRole('textbox', { name: '文档名称' })).toHaveValue('');
  expect(vi.mocked(api)).toHaveBeenLastCalledWith(
    expect.stringContaining('availability=AVAILABLE'),
  );

  // 更早的历史没有状态条件，恢复默认选项并省略接口参数。
  await act(() => router.navigate(-1));
  expect(await screen.findByText('全部状态')).toBeVisible();
  expect(vi.mocked(api)).toHaveBeenLastCalledWith(expect.not.stringContaining('availability='));
});
