import type { FormInstance } from 'antd';
import { describe, expect, it, vi } from 'vitest';
import { ApiError } from './api';
import { applyFormError } from './formErrors';

/**
 * 保留真实字段注册边界，仅替代 DOM 滚动与表单写入以检查服务端错误映射。
 */
function formFixture() {
  return {
    /** 当前页面实际注册的字段。 */
    getFieldsValue: () => ({ username: '', primaryDepartmentId: '' }),
    /** 记录就近字段错误。 */
    setFields: vi.fn(),
    /** 记录首个错误的定位目标。 */
    scrollToField: vi.fn(),
  };
}

/**
 * 错误必须定位到正确输入，未知字段保留整体反馈，不能静默吞掉拒绝。
 */
describe('服务端表单错误', () => {
  /**
   * 业务重名错误没有字段列表，仍可由页面声明准确目标。
   */
  it('登录名冲突显示在登录名输入并聚焦', async () => {
    const form = formFixture();
    expect(
      applyFormError(form as unknown as FormInstance, new ApiError(409101, '登录名已存在'), {
        409101: 'username',
      }),
    ).toBeNull();
    expect(form.setFields).toHaveBeenCalledWith([{ name: 'username', errors: ['登录名已存在'] }]);
    await vi.waitFor(() =>
      expect(form.scrollToField).toHaveBeenCalledWith('username', { focus: true }),
    );
  });

  /**
   * 接口提案和归属的层级映射到前端展开后的真实字段。
   */
  it('嵌套接口字段映射到当前表单的归属输入', async () => {
    const form = formFixture();
    const error = new ApiError(400001, '参数校验失败', [
      { field: 'proposal.membership.primaryDepartmentId', message: '请选择有效部门' },
    ]);
    expect(applyFormError(form as unknown as FormInstance, error)).toBeNull();
    await vi.waitFor(() =>
      expect(form.scrollToField).toHaveBeenCalledWith('primaryDepartmentId', { focus: true }),
    );
  });

  /**
   * 无法映射的错误不能隐藏，也不能创建一个不存在的界面字段。
   */
  it('保留未知字段的整体反馈', () => {
    const form = formFixture();
    const error = new ApiError(409001, '对象状态已变化', [
      { field: 'internalVersion', message: '版本已变化' },
    ]);
    expect(applyFormError(form as unknown as FormInstance, error)).toBe('对象状态已变化');
    expect(form.setFields).not.toHaveBeenCalled();
  });
});
