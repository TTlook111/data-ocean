import { describe, expect, it } from 'vitest';
import { displayChangeValue, readErrorMessage } from './Records';
import { ApiError } from './api';

/**
 * 状态展示不能把用户实际命名为编码的对象误改成另一个名称。
 */
describe('差异值的业务展示', () => {
  /**
   * 数据源故障分类保留在接口中，界面原因使用可理解的恢复语言。
   */
  it('读取失败不向使用者展示后端存储术语', () => {
    expect(readErrorMessage(new ApiError(503001, '必要存储当前不可用，请稍后重试'))).toBe(
      '服务暂时不可用，请稍后重试',
    );
  });

  /**
   * 核对与审计共用状态口径，同时完整保留普通名称、编号与原文。
   */
  it('只翻译状态字段，保留同字名称和连续编号', () => {
    expect(displayChangeValue('ENABLED', 'lifecycle', '角色状态')).toBe('启用');
    expect(displayChangeValue('ENABLED', 'lifecycle', '账号状态')).toBe('正常');
    expect(displayChangeValue('ENABLED', 'name', '角色名称')).toBe('ENABLED');
    expect(displayChangeValue('ROOT-ENABLED', 'code', '部门编号')).toBe('ROOT-ENABLED');
    expect(displayChangeValue(null, 'name', '角色名称')).toBe('—');
  });
});
