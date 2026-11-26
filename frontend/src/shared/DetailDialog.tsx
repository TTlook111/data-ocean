import { Modal } from 'antd';
import type { ReactNode } from 'react';
import { useReturnFocus } from './useReturnFocus';

/**
 * 在列表上集中查阅一条记录，以正文决定高度，长内容在弹窗内滚动。
 */
export function DetailDialog({
  title,
  width = 640,
  close,
  actions,
  children,
}: {
  /** 当前记录的名称或标题。 */
  title: ReactNode;
  /** 正常桌面窗口的最大阅读宽度。 */
  width?: number;
  /** 关闭并回到触发位置。 */
  close: () => void;
  /** 存在后续任务时提供操作。 */
  actions?: ReactNode;
  /** 记录信息与证据。 */
  children: ReactNode;
}) {
  useReturnFocus();
  return (
    <Modal
      centered
      open
      className="record-detail-dialog"
      width={width}
      title={title}
      onCancel={close}
      footer={actions || null}
    >
      {children}
    </Modal>
  );
}
