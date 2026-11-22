import type { CSSProperties } from 'react';
import styles from './LoadingIndicator.module.css';

/**
 * 等待图形沿用外层控件的尺寸和前景色，由外层提供可访问的状态名称。
 */
export function LoadingIndicator({
  className,
  style,
}: {
  /** Spin 传入尺寸及语义样式的类名。 */
  className?: string;
  /** Spin 传入的指示器内联样式。 */
  style?: CSSProperties;
}) {
  // 轨道和转动的弧线共用一个装饰元素，避免重复播报。
  return (
    <span
      className={className ? `${styles.indicator} ${className}` : styles.indicator}
      style={style}
      aria-hidden="true"
    />
  );
}

/**
 * 控件之外的局部等待提示，保留可见文案及状态播报。
 */
export function LoadingStatus({
  children,
}: {
  /** 由业务场景提供的当前等待状态。 */
  children: string;
}) {
  // 状态文字与指示器作为同一行显示，辅助技术只读取文字。
  return (
    <span className={styles.status} role="status">
      <LoadingIndicator />
      {children}
    </span>
  );
}
