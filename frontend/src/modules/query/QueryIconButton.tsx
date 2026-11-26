import { Button, Tooltip } from 'antd';
import type { ComponentPropsWithRef, ReactNode } from 'react';
import styles from './query.module.css';

/**
 * 问数工具按钮复用原生按钮语义，悬停和键盘聚焦时都显示用途。
 */
export function QueryIconButton({
  label,
  icon,
  className,
  ...button
}: Omit<ComponentPropsWithRef<typeof Button>, 'children' | 'type' | 'size' | 'icon'> & {
  /** 按钮的可访问名称，同时用作悬浮提示。 */
  label: string;
  /** 仅用于视觉识别的图标。 */
  icon: ReactNode;
}) {
  return (
    <Tooltip title={label} trigger={['hover', 'focus']} mouseEnterDelay={0.25}>
      <Button
        {...button}
        className={className ? `${styles.iconButton} ${className}` : styles.iconButton}
        type="text"
        size="small"
        aria-label={label}
        icon={icon}
      />
    </Tooltip>
  );
}
