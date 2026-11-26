import { useEffect, useState } from 'react';

/**
 * 条件挂载的浮层退出后返回原按钮，不抢走新浮层或新页面已经取得的焦点。
 */
export function useReturnFocus() {
  const [trigger] = useState(() => document.activeElement);

  useEffect(() => {
    // 原入口在浮层首次渲染前记录，卸载后等待新界面完成焦点分配。
    return () => {
      requestAnimationFrame(() => {
        // 仅补偿组件卸载后落到页面根部的焦点，已移除入口不再回访。
        if (
          document.activeElement === document.body &&
          trigger instanceof HTMLElement &&
          trigger.isConnected
        ) {
          trigger.focus({ preventScroll: true });
        }
      });
    };
  }, [trigger]);
}
