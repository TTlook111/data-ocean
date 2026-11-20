import { App, ConfigProvider } from 'antd';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { expect, it, vi } from 'vitest';
import { api, ApiError, mutation } from '../../shared/api';
import { GovernanceEditor } from './GovernanceEditor';
import type { Item } from './types';

vi.mock('../../shared/api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../shared/api')>()),
  api: vi.fn(),
  mutation: vi.fn(),
}));

vi.mock('../../shared/session', () => ({
  useSession: () => ({}),
  allowed: () => false,
}));

/**
 * 固定打开时的表说明，模拟另一位编辑者先保存而产生的版本冲突。
 */
const original: Item = {
  target: { objectId: '1', kind: 'TABLE', targetId: '1' },
  itemId: '2',
  version: 1,
  structureBasis: { snapshotId: '3', objectRevisionId: '4' },
  state: 'DRAFT',
  pendingReview: false,
  currentRevision: {
    revisionId: '5',
    revision: 1,
    content: { purpose: '原用途', rowGrain: '原粒度' },
    origin: 'MANUAL',
    evidenceRefs: [],
  },
  latestConfirmation: null,
  gaps: [],
  impacts: [],
  blocked: false,
  availability: 'AVAILABLE',
  displayName: '商品',
  sourceComment: null,
  actions: {
    canEdit: true,
    canSuggest: false,
    canConfirm: false,
    canSupplement: false,
    canForbid: false,
    canAcknowledgeRemoval: false,
  },
};

it.each([false, true])('重读并发版本时保护已有改动（同字段冲突：%s）', async (overlap) => {
  const path = '/fixture/item';
  const writing = vi.mocked(mutation);
  writing.mockReset();
  writing
    .mockRejectedValueOnce(new ApiError(409001, '条目版本已变化'))
    .mockResolvedValue(undefined);
  vi.mocked(api).mockResolvedValue({
    ...original,
    version: 2,
    currentRevision: {
      ...original.currentRevision,
      content: { purpose: overlap ? '他人用途' : '原用途', rowGrain: '他人粒度' },
    },
  });
  const router = createMemoryRouter([
    { path: '/', element: <GovernanceEditor item={original} source="7" path={path} name="商品" /> },
  ]);
  render(
    <ConfigProvider theme={{ token: { motion: false } }}>
      <App>
        <QueryClientProvider client={new QueryClient()}>
          <RouterProvider router={router} />
        </QueryClientProvider>
      </App>
    </ConfigProvider>,
  );

  fireEvent.change(screen.getByLabelText('业务用途'), { target: { value: '我的用途' } });
  fireEvent.click(screen.getByRole('button', { name: '保存草稿' }));
  fireEvent.click(await screen.findByRole('button', { name: '重新读取版本' }));
  if (overlap) {
    // 同字段冲突等待用户确认，后台更新不能静默获得新版本。
    expect(await screen.findByRole('dialog')).toHaveTextContent('他人用途');
    expect(screen.getByLabelText('一行代表什么')).toHaveValue('原粒度');
    fireEvent.click(screen.getByRole('button', { name: '保留我的修改' }));
  }

  await waitFor(() => expect(screen.getByLabelText('一行代表什么')).toHaveValue('他人粒度'));
  expect(screen.getByLabelText('业务用途')).toHaveValue('我的用途');
  fireEvent.click(screen.getByRole('button', { name: '保存草稿' }));
  expect(writing.mock.lastCall?.[2]).toMatchObject({
    precondition: { expectedVersion: 2 },
    draft: { content: { purpose: '我的用途', rowGrain: '他人粒度' } },
  });
});
