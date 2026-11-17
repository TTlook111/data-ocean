import { App, ConfigProvider, Form, Input } from 'antd';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { PersonEditor } from './UserDialogs';
import { SessionContext } from '../../shared/session';

// 选项请求使用空数据，测试只核对标签、控件及校验目标的关联。
vi.mock('../../shared/api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../shared/api')>()),
  /**
   * 不执行真实新增或预览命令。
   */
  api: vi.fn(async () => []),
}));

/**
 * 页面查询和编辑弹窗同时包含姓名字段，标签必须定位到各自的输入框。
 */
describe('人员表单标签', () => {
  /**
   * 重现同名字段共存的情况，防止弹窗标签或首个错误定位到背景查询区。
   */
  it('同名查询字段存在时，编辑标签仍关联弹窗内的姓名控件', () => {
    const cache = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <ConfigProvider theme={{ token: { motion: false } }}>
        <App>
          <QueryClientProvider client={cache}>
            <SessionContext.Provider
              value={{
                // 只测试资料编辑控件，无角色授予能力。
                userId: 'test-user',
                // 当前验收登录名。
                username: 'test',
                // 当前展示名称。
                displayName: '验收人员',
                // 当前人员版本。
                version: 1,
                // 已完成必要改密。
                mustChangePassword: false,
                // 此处不开放管理动作。
                permissionCodes: [],
                // 当前安全修订。
                securityVersion: '1',
              }}
            >
              {/* 同名背景字段保留默认标识，暴露编辑器缺少命名空间时的标签冲突。 */}
              <Form>
                <Form.Item name="displayName" label="查询姓名">
                  <Input />
                </Form.Item>
              </Form>
              <PersonEditor person={null} close={vi.fn()} />
            </SessionContext.Provider>
          </QueryClientProvider>
        </App>
      </ConfigProvider>,
    );
    const field = screen.getByLabelText('姓名', { exact: true });
    expect(field.closest('[role="dialog"]')).not.toBeNull();
    expect(field).not.toBe(screen.getByLabelText('查询姓名'));
  });
});
