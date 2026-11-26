import { Button, Dropdown } from 'antd';
import type { ButtonProps, DropdownProps } from 'antd';
import { useRef, useState } from 'react';
import type { KeyboardEvent, ReactNode } from 'react';

/**
 * 文字操作菜单统一处理点击、键盘焦点以及触发按钮的打开状态。
 */
export function ActionMenuButton({
  menu,
  placement,
  type,
  className,
  label,
  children,
}: {
  /** 当前权限和对象状态可用的操作。 */
  menu: NonNullable<DropdownProps['menu']>;
  /** 根据列表行、顶栏或固定底栏确定弹出方向。 */
  placement: DropdownProps['placement'];
  /** 行内使用文字按钮，底栏使用描边按钮。 */
  type?: ButtonProps['type'];
  /** 用于账号等已有专属布局的入口。 */
  className?: string;
  /** 可选的完整读屏名称。 */
  label?: string;
  /** 可见文字或账号摘要。 */
  children: ReactNode;
}) {
  const [open, setOpen] = useState(false);
  const trigger = useRef<HTMLButtonElement | HTMLAnchorElement>(null);

  /**
   * Esc 关闭当前菜单，并把键盘位置还给原来的触发按钮。
   */
  function closeOnEscape(event: KeyboardEvent<HTMLElement>) {
    if (event.key === 'Escape' && open) {
      // 阻止外围抽屉抢先处理同一次按键。
      event.preventDefault();
      event.stopPropagation();
      setOpen(false);
      requestAnimationFrame(() => trigger.current?.focus({ preventScroll: true }));
    }
  }
  return (
    <Dropdown
      menu={{ ...menu, onKeyDown: closeOnEscape }}
      placement={placement}
      trigger={['click']}
      autoFocus
      open={open}
      onOpenChange={setOpen}
    >
      <Button
        ref={trigger}
        type={type}
        className={className}
        aria-label={label}
        aria-haspopup="menu"
        aria-expanded={open}
        onKeyDown={closeOnEscape}
      >
        {children}
      </Button>
    </Dropdown>
  );
}
