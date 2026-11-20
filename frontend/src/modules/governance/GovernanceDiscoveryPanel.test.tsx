import { App, ConfigProvider } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { expect, it, vi } from 'vitest';
import { api, mutation } from '../../shared/api';
import { GovernanceDiscoveryPanel } from './GovernanceDiscoveryPanel';

// 组件测试用合成响应覆盖人审操作，避免在网络故障时将模型替身写入平台。
vi.mock('../../shared/api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../shared/api')>()),
  api: vi.fn(),
  mutation: vi.fn(),
}));
vi.mock('../../shared/session', () => ({
  useSession: () => ({}),
  allowed: () => true,
}));

/**
 * 分开核对两个页签的真实表单行为，运行只使用合成响应。
 */
it.each([
  ['RELATION', '关联', '查看采集外键'],
  ['METRIC', '指标', '查看结构依据'],
] as const)(
  '在%s页签回读依据并明确提交人工确认',
  async (kind, label, structure) => {
    const path = '/governance/sources/2/objects/1/candidates';
    let exists = false;
    let reviewed = false;
    const definition = {
      name: '合成候选',
      meaning: '测试口径',
      rule: '基于 products.product_id',
      usage: '合成源',
      pairs: [],
    };
    vi.mocked(mutation).mockImplementation(async () => {
      // 模拟持久任务受理，下次任务查询回读实际终态。
      exists = true;
      return { requestId: 'fixture-run' };
    });
    vi.mocked(api).mockImplementation(async (url, options) => {
      // 区分实际候选、模型任务和资料原文入口。
      if (url.endsWith('/generations/fixture-run')) return { state: 'SUCCEEDED' };
      if (url.startsWith('/business-notes/9?')) {
        return {
          content: { title: '合成业务资料', revision: 1, body: '明确的测试定义', attachments: [] },
        };
      }
      if (url === path + '/1' && options?.method === 'PUT') {
        // 确认必须明确由人工按钮触发并带回原有并发版本。
        expect(options.body).toMatchObject({ name: '人工核对候选', action: 'CONFIRM', version: 1 });
        reviewed = true;
        return { state: 'CONFIRMED' };
      }
      if (!url.startsWith(path + '?')) throw Error('不属于当前测试的接口');
      return {
        pageNum: 1,
        total: exists ? 1 : 0,
        items: exists
          ? [
              {
                id: '1',
                version: reviewed ? 2 : 1,
                state: reviewed ? 'CONFIRMED' : 'CANDIDATE',
                snapshotId: '15',
                definition: reviewed ? { ...definition, name: '人工核对候选' } : definition,
                basis: {
                  foreignKey: kind === 'RELATION',
                  structures: [
                    {
                      fieldId: '2',
                      objectRevision: '4',
                      fieldRevision: '8',
                      label: 'products.product_id',
                      type: 'bigint',
                    },
                  ],
                  notes: [{ noteId: '9', revisionId: '10', revision: 1, title: '合成业务资料' }],
                },
              },
            ]
          : [],
      };
    });
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const router = createMemoryRouter([
      {
        path: '/',
        element: <GovernanceDiscoveryPanel kind={kind} path={path} source="2" />,
      },
    ]);
    render(
      <ConfigProvider locale={zhCN} theme={{ token: { motion: false } }}>
        <App>
          <QueryClientProvider client={client}>
            <RouterProvider router={router} />
          </QueryClientProvider>
        </App>
      </ConfigProvider>,
    );
    fireEvent.click(await screen.findByRole('button', { name: '发现 AI ' + label }));
    expect(await screen.findByText('待确认')).toBeVisible();
    fireEvent.click(screen.getByRole('button', { name: structure }));
    expect(
      await within(screen.getByRole('dialog')).findByText('products.product_id'),
    ).toBeVisible();
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: /关闭|Close/ }));
    fireEvent.click(screen.getByRole('button', { name: '合成业务资料 · 第 1 版' }));
    expect(await screen.findByText('明确的测试定义')).toBeVisible();
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: /关闭|Close/ }));
    fireEvent.change(screen.getByLabelText(label + '名称'), { target: { value: '人工核对候选' } });
    fireEvent.click(screen.getByRole('button', { name: '确认治理' }));
    await waitFor(() => expect(reviewed).toBe(true));
    expect(await screen.findByText('已确认')).toBeVisible();
  },
  15000,
);
