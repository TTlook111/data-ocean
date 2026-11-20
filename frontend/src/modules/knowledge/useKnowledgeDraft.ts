import { App } from 'antd';
import { useEffect, useRef, useState } from 'react';
import { useBeforeUnload, useBlocker } from 'react-router';

/** 文档与题单的编辑入口共用离开保护，提交期间保留实际办理位置。 */
export function useKnowledgeDraft() {
  const { modal } = App.useApp();
  const [changed, setChanged] = useState(false);
  const dirty = useRef(false);
  const writing = useRef(false);
  const confirming = useRef(false);
  const blocker = useBlocker(() => dirty.current || writing.current);

  useBeforeUnload((event) => {
    // 浏览器刷新和关闭沿用实际编辑及提交状态。
    if (dirty.current || writing.current) event.preventDefault();
  });

  useEffect(() => {
    if (blocker.state !== 'blocked') return;
    if (writing.current || confirming.current) {
      // 服务器尚未给出提交结果时不切换编辑对象。
      blocker.reset();
      return;
    }
    let accepted = false;
    const dialog = modal.confirm({
      ...discardOptions,
      onOk: () => {
        accepted = true;
      },
      onCancel: () => blocker.reset(),
      afterClose: () => {
        // 弹窗恢复焦点后再切页，避免旧按钮抢走新编辑区焦点。
        if (accepted) {
          dirty.current = false;
          blocker.proceed();
        }
      },
    });
    return () => dialog.destroy();
  }, [blocker, modal]);

  /** 从同一处同步按钮状态和即时导航保护。 */
  function change(next: boolean) {
    dirty.current = next;
    setChanged(next);
  }

  /** 请求发出后阻止离开，已知失败后仍保留原输入。 */
  function startWrite() {
    writing.current = true;
  }

  /** 只在收到实际成功回执后消除未保存状态。 */
  function finishWrite(committed: boolean) {
    writing.current = false;
    if (committed) change(false);
  }

  /** 处理留在同一地址的取消编辑。 */
  async function cancel(reset: () => void) {
    if (writing.current || confirming.current) return;
    if (dirty.current) {
      confirming.current = true;
      try {
        if (!(await modal.confirm(discardOptions))) return;
      } finally {
        confirming.current = false;
      }
    }
    change(false);
    reset();
  }

  return { changed, change, startWrite, finishWrite, cancel };
}

/** 默认动作保留编辑内容，Esc 沿用继续编辑。 */
const discardOptions = {
  centered: true,
  title: '有未保存的修改',
  content: '放弃本次修改？',
  okText: '放弃修改',
  cancelText: '继续编辑',
  focusable: { autoFocusButton: 'cancel' as const },
};
