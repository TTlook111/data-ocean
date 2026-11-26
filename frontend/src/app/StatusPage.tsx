import { useLayoutEffect, useRef, type ReactNode } from 'react';
import { ArrowLeftOutlined, ReloadOutlined } from '@ant-design/icons';
import { Button } from 'antd';
import { CurrentScene } from './CurrentScene';
import styles from './StatusPage.module.css';

/**
 * 全屏状态展示必要原因和恢复入口，交互画面占据整条页面中轴。
 */
function StatusPage({
  variant,
  title,
  detail,
  action,
}: {
  /** 确定画面字形和配色。 */
  variant: 'connection' | 'not-found';
  /** 当前状态标题，也是全屏核验遮挡的可访问名称。 */
  title: string;
  /** 后续可用的操作提示或实时核验结果。 */
  detail: string;
  /** 主操作固定在首屏。 */
  action: ReactNode;
}) {
  return (
    <main className={styles.page} data-variant={variant}>
      {/* 顶部只保留产品归属，状态和操作集中在同一区域。 */}
      <header className={styles.header}>
        <div className={styles.brand}>
          <img src="/dataocean-mark.png" alt="" width={23} height={26} />
          <strong>DataOcean</strong>
        </div>
      </header>
      <div className={styles.copy}>
        <h1 id={variant === 'connection' ? 'connection-failure-title' : undefined}>{title}</h1>
        <p aria-live={variant === 'connection' ? 'polite' : undefined}>{detail}</p>
        {action}
      </div>
      {/* 水面在视觉上位于中间，键盘可先读状态、操作主按钮，再进入玩法。 */}
      <CurrentScene signal={variant === 'connection'} />
    </main>
  );
}

/**
 * 未知路由独占整个视口，首页由当前登录账号的实际权限决定。
 */
export function NotFoundPage({ onHome }: { onHome: () => void }) {
  return (
    <StatusPage
      variant="not-found"
      title="这条航线走丢了"
      detail="页面可能已移动，或地址有误。"
      action={
        <Button className={styles.action} icon={<ArrowLeftOutlined aria-hidden />} onClick={onHome}>
          返回首页
        </Button>
      }
    />
  );
}

/**
 * 重新核验时独占视口，原生顶层让已打开的业务浮层也暂停接受输入。
 */
function ConnectionCurtain({ children }: { children: ReactNode }) {
  const dialogRef = useRef<HTMLDialogElement>(null);

  useLayoutEffect(() => {
    // 保留业务草稿的挂载，核验完成后由浏览器恢复原焦点。
    const dialog = dialogRef.current!;
    dialog.showModal();
    return () => dialog.close();
  }, []);

  return (
    <dialog
      ref={dialogRef}
      className={styles.curtain}
      aria-labelledby="connection-failure-title"
      onCancel={(event) => {
        // Esc 不允许跳过登录核验。
        event.preventDefault();
      }}
    >
      {children}
    </dialog>
  );
}

/**
 * 首次故障和临时断线共用同一全屏页，重连只发起身份核验。
 */
export function ConnectionFailure({
  overlay = false,
  detail,
  retrying,
  onRetry,
}: {
  /** 已有挂载的页面时阻断交互并保留其草稿。 */
  overlay?: boolean;
  /** 当前可公开的错误原因。 */
  detail: string;
  /** 核验请求的加载状态。 */
  retrying: boolean;
  /** 重新发起同一身份请求。 */
  onRetry: () => void;
}) {
  const page = (
    <StatusPage
      variant="connection"
      title="暂时无法核验登录"
      detail={retrying ? '正在重新核验登录…' : detail}
      action={
        <Button
          className={styles.action}
          loading={retrying}
          icon={<ReloadOutlined aria-hidden />}
          onClick={onRetry}
        >
          重新连接
        </Button>
      }
    />
  );

  return overlay ? <ConnectionCurtain>{page}</ConnectionCurtain> : page;
}
