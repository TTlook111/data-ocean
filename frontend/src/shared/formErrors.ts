import type { FormInstance } from 'antd';
import { ApiError } from './api';
import { readErrorMessage } from './Records';

/**
 * 将服务端字段错误落到当前表单并聚焦首项，其余错误保留为可读的整体反馈。
 */
export function applyFormError(
  form: FormInstance,
  failure: unknown,
  fieldByCode: Record<number, string> = {},
): string | null {
  // 仅关联当前已注册字段，接口对象的 proposal、membership 层级不进入界面字段名。
  if (failure instanceof ApiError) {
    const names = new Set(Object.keys(form.getFieldsValue()));
    const fields = failure.fields
      .map((item) => ({
        // 前端简单表单已将接口中的资料与归属展开为独立字段。
        name: item.field.replace(/^proposal\./u, '').replace(/^membership\./u, ''),
        // 保留服务端允许披露的具体校验原因。
        errors: [item.message],
      }))
      .filter((item) => names.has(item.name));

    // 重名等业务冲突没有 fieldErrors，由所属页面显式声明对应输入字段。
    const target = fieldByCode[failure.code];
    if (!fields.length && target && names.has(target)) {
      fields.push({ name: target, errors: [failure.message] });
    }
    if (fields.length) {
      // 与客户端校验使用相同的字段反馈及首次错误定位，保留全部有效输入。
      form.setFields(fields);
      const firstField = fields[0].name;
      requestAnimationFrame(() => {
        // 等待提交状态结束、输入恢复可用后再聚焦，避免 disabled 表单吞掉焦点。
        form.scrollToField(firstField, { focus: true });
      });
      return null;
    }
  }
  return readErrorMessage(failure instanceof Error ? failure : new Error('操作未完成'));
}
