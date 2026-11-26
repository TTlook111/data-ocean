import { useEffect, useId, useRef, useState } from 'react';
import styles from './LoginPage.module.css';

/**
 * 登录页左侧提供可选的鱼群互动，图形能力不可用时显示品牌标识。
 */
export function LoginSchool() {
  const logoRef = useRef<HTMLImageElement>(null);
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const targetRef = useRef<HTMLButtonElement>(null);
  const [ready, setReady] = useState(false);
  const [formation, setFormation] = useState<'anchor' | 'wave' | null>(null);
  const hintId = useId();

  useEffect(() => {
    const logo = logoRef.current;
    const canvas = canvasRef.current;
    const target = targetRef.current;
    if (!logo || !canvas || !target || typeof logo.decode !== 'function') return;

    // 动效模块在表单挂载后独立加载，避免阻塞输入和登录。
    let disposed = false;
    let disposeScene: (() => void) | undefined;
    void Promise.all([import('./loginSchoolScene'), logo.decode()])
      .then(([{ startLoginSchool }]) => {
        if (disposed) return;
        disposeScene = startLoginSchool(canvas, target, logo, setReady, setFormation);
      })
      .catch((failure: unknown) => {
        if (!disposed) console.warn('登录页鱼群初始化失败，使用静态品牌标识。', failure);
      });

    return () => {
      // 避免异步加载完成后再初始化已退出的登录页。
      disposed = true;
      disposeScene?.();
    };
  }, []);

  return (
    <section className={styles.school} aria-label="探索 DataOcean 鱼群" data-ready={ready}>
      {/* 保留小号品牌定位，其余空间交给水面互动。 */}
      <header className={styles.brand}>
        <img ref={logoRef} src="/dataocean-mark.png" alt="" width={25} height={29} />
        <strong>DataOcean</strong>
      </header>
      {/* 独立的静态标识覆盖加载及 WebGL 不可用状态。 */}
      {!ready && <img className={styles.schoolFallback} src="/dataocean-mark.png" alt="" />}
      <canvas ref={canvasRef} className={styles.schoolCanvas} aria-hidden="true" />
      {/* 使用原生按钮支持 Tab、Enter 和有名称的整幅水面。 */}
      <button
        ref={targetRef}
        className={styles.schoolTarget}
        type="button"
        aria-label="切换鱼群形状"
        aria-describedby={hintId}
        disabled={!ready}
      />
      {/* 玩法说明和形态切换只向辅助技术播报。 */}
      <span id={hintId} className={styles.visuallyHidden}>
        回车换形。按住空格并使用方向键移动漩涡。
      </span>
      <span className={styles.visuallyHidden} role="status">
        {ready && formation && (formation === 'wave' ? '鱼群聚成波浪' : '鱼群聚成 DataOcean 锚形')}
      </span>
    </section>
  );
}
