import { App, Form } from 'antd';
import type { FormInstance } from 'antd';
import { useEffect, useRef } from 'react';

/**
 * 表单仅在存在实际改动时确认放弃，异步初值与恢复原值不视为草稿。
 */
export function useFormClose(
  form: FormInstance,
  close: () => void,
  busy = false,
  protect = true,
  dirty?: () => boolean,
) {
  const values = Form.useWatch((value) => value, { form, preserve: true });
  const initial = useRef<string | null>(null);
  const confirming = useRef(false);
  const { modal } = App.useApp();

  useEffect(() => {
    // 用户开始输入前允许接口填充初值，不覆盖已输入内容的比较基准。
    if (values !== undefined && (initial.current === null || !form.isFieldsTouched()))
      initial.current = JSON.stringify(form.getFieldsValue(true));
  }, [form, values]);

  /**
   * 关闭、取消、遮罩和 Esc 共用确认，继续编辑和 Esc 保留输入。
   */
  async function requestClose() {
    if (busy || confirming.current) return;
    const changed =
      protect &&
      (dirty
        ? dirty()
        : form.isFieldsTouched() && initial.current !== JSON.stringify(form.getFieldsValue(true)));
    if (changed) {
      confirming.current = true;
      const discard = await modal.confirm({
        centered: true,
        title: '有未保存的修改',
        content: '放弃本次修改？',
        okText: '放弃修改',
        cancelText: '继续编辑',
        focusable: { autoFocusButton: 'cancel' },
        keyboard: true,
      });
      confirming.current = false;
      if (!discard) return;
    }
    close();
  }
  return requestClose;
}
