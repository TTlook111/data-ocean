import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api, mutation, resetCsrf } from './api';

/**
 * 每个场景独立保存令牌与请求队列，不读取浏览器的真实会话。
 */
beforeEach(resetCsrf);

/**
 * 恢复 Fetch，避免请求替身影响其他组件用例。
 */
afterEach(() => vi.unstubAllGlobals());

/**
 * 代理正文与服务端会话提交可能失败，真实写入结果须按稳定命令核实。
 */
describe('请求失败边界', () => {
  /**
   * 非 JSON 代理错误只能成为可理解的连接失败，不能展示解析实现细节。
   */
  it('空代理正文返回中文连接反馈', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('', { status: 502 })));
    await expect(api('/health')).rejects.toThrow('服务连接暂不可用');
  });

  /**
   * 业务提交后若会话保存失败返回 503，核实原命令能找回已提交事实，不能再次执行写入。
   */
  it('503 后查询已提交命令而不重放写入', async () => {
    const request = vi
      .fn()
      .mockResolvedValueOnce(
        Response.json({
          code: 200,
          message: '成功',
          data: { headerName: 'X-CSRF-TOKEN', token: 'synthetic' },
        }),
      )
      .mockResolvedValueOnce(
        Response.json({ code: 503001, message: '必要存储不可用', data: null }, { status: 503 }),
      )
      .mockResolvedValueOnce(
        Response.json({
          code: 200,
          message: '成功',
          data: { state: 'COMMITTED', target: { id: '12', version: 2 } },
        }),
      );
    vi.stubGlobal('fetch', request);
    const result = await mutation<{ targetId: string; version: number }>(
      '/iam/users',
      'POST',
      {},
      'users.create',
      'synthetic-command',
    );
    expect(result).toMatchObject({ targetId: '12', version: 2 });
    expect(request).toHaveBeenCalledTimes(3);
    expect(request.mock.calls[2][0]).toContain(
      '/commands/synthetic-command?operationCode=users.create',
    );
  });
});
