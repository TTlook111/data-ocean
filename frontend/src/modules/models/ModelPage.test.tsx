import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { App, ConfigProvider } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import { MemoryRouter } from 'react-router';
import { expect, it, vi } from 'vitest';
import { api } from '../../shared/api';
import { ModelPage } from './ModelPage';

// 保留真实选择器和确认框，使用受控响应核对请求发生的时机。
vi.mock('../../shared/api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../shared/api')>()),
  api: vi.fn(),
}));

// 页面以有模型分配权限的验收角色进入。
vi.mock('../../shared/session', () => ({ useSession: () => ({}), allowed: () => true }));

/**
 * 选择和取消都不产生分配请求，确认后才提交选中模型及原并发版本。
 */
it.each([
  ['intent', '问题理解', '1', '原生成模型', 'COHERE_RERANK'],
  ['question-suggestion', '标准题建议', null, '未分配', 'COHERE_RERANK'],
  ['history-summary', '历史对话摘要', null, '未分配', 'COHERE_RERANK'],
  ['rag-rerank', '候选知识重排', null, '未分配', 'COHERE_RERANK'],
  ['rag-rerank', '候选知识重排', null, '未分配', 'JSON_RERANK'],
] as const)('%s 确认后更新模型', async (node, label, initial, previousName, protocol) => {
  const capability = node === 'rag-rerank' ? 'RERANK' : 'GENERATION';
  const modelName = node === 'rag-rerank' ? '新重排模型' : '新生成模型';
  let selected: string | null = initial;
  let version = 3;
  const save = vi.fn(({ modelId }: { modelId: string }) => {
    // 模拟服务端写入后提供新的分配快照。
    selected = modelId;
    version += 1;
    return true;
  });
  vi.mocked(api).mockImplementation(async (url, options) => {
    // 确认框的前后状态及写入记录由各自的正式接口提供。
    if (url === '/models/nodes') {
      return [
        { code: node, connected: true, capability, modelId: selected, version },
        {
          code: 'feedback-similarity',
          connected: false,
          capability: 'EMBEDDING',
          modelId: null,
          version: 1,
        },
      ];
    }
    if (url === '/models/options') {
      return [
        { id: '1', name: '原生成模型', protocol: 'OPENAI_CHAT_COMPLETIONS', enabled: true },
        { id: '2', name: '新生成模型', protocol: 'OPENAI_CHAT_COMPLETIONS', enabled: true },
        { id: '3', name: '新重排模型', protocol, enabled: true },
      ];
    }
    if (url === `/models/nodes/${node}` && options?.method === 'PUT') {
      return save(options.body as { modelId: string });
    }
    throw new Error(`未预期的验收接口 ${url}`);
  });
  const cache = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <ConfigProvider locale={zhCN} theme={{ token: { motion: false } }}>
      {/* 与正式页面相同的浮层上下文可保留确认行为。 */}
      <App>
        <QueryClientProvider client={cache}>
          <MemoryRouter initialEntries={['/models?tab=nodes']}>
            <ModelPage />
          </MemoryRouter>
        </QueryClientProvider>
      </App>
    </ConfigProvider>,
  );

  const picker = await screen.findByRole('combobox', { name: `为${label}分配模型` });
  await waitFor(() => expect(picker).toBeEnabled());
  expect(screen.getByText('暂无独立调用')).toBeInTheDocument();
  expect(
    screen.queryByRole('combobox', { name: '为反馈问题向量化分配模型' }),
  ).not.toBeInTheDocument();
  // 只检查选择器的展示值，避开组件为读屏保留的重复文本。
  const displayedModel = picker.closest('.ant-select')?.querySelector('.ant-select-content');
  fireEvent.mouseDown(picker);
  fireEvent.click(
    await screen.findByText(modelName, { selector: '.ant-select-item-option-content' }),
  );
  let confirmation = within(await screen.findByRole('dialog'));
  expect(confirmation.getByText(previousName)).toBeInTheDocument();
  expect(confirmation.getByText(modelName)).toBeInTheDocument();
  expect(displayedModel).toHaveTextContent(previousName);
  expect(displayedModel).not.toHaveTextContent(modelName);
  expect(save).not.toHaveBeenCalled();
  fireEvent.click(await confirmation.findByRole('button', { name: /取\s*消/u }));
  await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
  expect(save).not.toHaveBeenCalled();
  expect(displayedModel).toHaveTextContent(previousName);

  fireEvent.mouseDown(picker);
  fireEvent.click(
    await screen.findByText(modelName, { selector: '.ant-select-item-option-content' }),
  );
  confirmation = within(await screen.findByRole('dialog'));
  fireEvent.click(await confirmation.findByRole('button', { name: '确认分配' }));
  await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
  expect(save).toHaveBeenCalledExactlyOnceWith({
    modelId: node === 'rag-rerank' ? '3' : '2',
    version: 3,
  });
  expect(displayedModel).toHaveTextContent(modelName);
});
