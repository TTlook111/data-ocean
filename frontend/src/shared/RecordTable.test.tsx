import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { RecordTable } from './RecordTable';
import { ApiError } from './api';

/**
 * 验证失败与空结果仍保留列表结构，避免正常空态掩盖查询故障。
 */
describe('列表结果状态', () => {
  /**
   * 权限拒绝不能通过列名或零计数冒充普通空结果。
   */
  it('无权时不展示受限列或记录总数', () => {
    render(
      <RecordTable
        columns={[{ title: '姓名', dataIndex: 'name' }]}
        dataSource={[]}
        error={new ApiError(403001, '无权查看')}
        pagination={{ total: 0 }}
      />,
    );
    expect(screen.queryByRole('columnheader', { name: '姓名' })).not.toBeInTheDocument();
    expect(screen.queryByText('共 0 条')).not.toBeInTheDocument();
    expect(screen.getByText('当前无权查看这些记录')).toBeVisible();
  });

  /**
   * 查询失败时保留表头并提供原查询重试。
   */
  it('失败时保留表头和重试入口', () => {
    const retry = vi.fn();
    render(
      <RecordTable
        columns={[{ title: '姓名', dataIndex: 'name' }]}
        dataSource={[]}
        error={new Error('查询暂时失败')}
        retry={retry}
        pagination={{ total: 0 }}
      />,
    );
    expect(screen.getByRole('columnheader', { name: '姓名' })).toBeVisible();
    expect(screen.getByText('查询暂时失败')).toBeVisible();
    fireEvent.click(screen.getByRole('button', { name: /重\s*试/ }));
    expect(retry).toHaveBeenCalledOnce();
  });

  /**
   * 条件无匹配时直接提供重置入口，且与真实无记录区分。
   */
  it('无匹配时可清空条件并保留表头', () => {
    const reset = vi.fn();
    render(
      <RecordTable
        columns={[{ title: '姓名', dataIndex: 'name' }]}
        dataSource={[]}
        filtered
        onReset={reset}
        pagination={{ total: 0 }}
      />,
    );
    expect(screen.getByRole('columnheader', { name: '姓名' })).toBeVisible();
    expect(screen.getByText('没有匹配的记录')).toBeVisible();
    fireEvent.click(screen.getByRole('button', { name: /清空条件/ }));
    expect(reset).toHaveBeenCalledOnce();
  });

  /**
   * 结果减少后将越界页码校正到实际末页。
   */
  it('结果减少后回到有效末页', () => {
    const change = vi.fn();
    render(
      <RecordTable
        columns={[{ title: '姓名', dataIndex: 'name' }]}
        dataSource={[]}
        pagination={{ current: 3, pageSize: 10, total: 11, onChange: change }}
      />,
    );
    expect(change).toHaveBeenCalledWith(2, 10);
  });
});
