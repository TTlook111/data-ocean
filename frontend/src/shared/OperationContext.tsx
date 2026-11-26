import { ExclamationCircleOutlined } from '@ant-design/icons';
import type { ReactNode } from 'react';

/**
 * 短任务弹窗中的明确操作对象，将名称、当前状态和必要后果与输入区分开。
 */
export function OperationContext({
  icon,
  title,
  children,
  warning,
}: {
  /** 与对象类型对应的现有图标。 */
  icon: ReactNode;
  /** 当前正在处理的完整对象名称。 */
  title: string;
  /** 状态与识别对象所需的少量资料。 */
  children?: ReactNode;
  /** 危险操作必须知道的实际后果，普通操作不增加说明。 */
  warning?: string;
}) {
  // 对象摘要与后果组成一个紧凑阅读区，长名称保留全文并自然换行。
  return (
    <section className="operation-context" aria-label="操作对象">
      <div className="operation-object">
        <span className="operation-object-icon" aria-hidden="true">
          {icon}
        </span>
        <div className="operation-object-content">
          <strong className="operation-object-name">{title}</strong>
          <div className="operation-object-meta">{children}</div>
        </div>
      </div>
      {/* 只有真实危险后果才占用这一层，不重复讲解正常业务规则。 */}
      {warning && (
        <p className="operation-consequence">
          <ExclamationCircleOutlined aria-hidden="true" />
          <span>{warning}</span>
        </p>
      )}
    </section>
  );
}
