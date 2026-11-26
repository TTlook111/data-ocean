import { useEffect, useId, useRef, type KeyboardEvent, type PointerEvent } from 'react';
import styles from './StatusPage.module.css';

/** 水面的输入位置与按住状态供渲染循环读取。 */
type Current = {
  /** 横向坐标，单位为 CSS 像素。 */
  x: number;
  /** 纵向坐标，单位为 CSS 像素。 */
  y: number;
  /** 按下时聚拢鱼群，掠过时只拨开附近的鱼。 */
  held: boolean;
};

/** 每条鱼在字形中有一个归位坐标，速度用于表现惯性。 */
type Fish = {
  /** 归位横坐标。 */
  homeX: number;
  /** 归位纵坐标。 */
  homeY: number;
  /** 当前横坐标。 */
  x: number;
  /** 当前纵坐标。 */
  y: number;
  /** 横向速度。 */
  vx: number;
  /** 纵向速度。 */
  vy: number;
  /** 个体游动的相位。 */
  phase: number;
};

/**
 * 从 404 字面或中断的电波轮廓中取样，分别作为两个场景的栖位。
 */
function createSchool(width: number, height: number, signal: boolean) {
  const mask = document.createElement('canvas');
  mask.width = Math.ceil(width / 2);
  mask.height = Math.ceil(height / 2);
  const brush = mask.getContext('2d', { willReadFrequently: true })!;
  const centerX = mask.width / 2;
  const centerY = mask.height * 0.48;
  const size = Math.min(mask.width * 0.42, mask.height * 0.85, 250);

  if (signal) {
    // 对称的两段波形在中间留出断点。
    const period = Math.min(size, centerX * 0.245);
    const rise = size * 0.22;
    brush.lineWidth = Math.max(9, size * 0.12);
    brush.lineCap = 'round';
    brush.beginPath();
    brush.moveTo(centerX - period * 2.55, centerY);
    brush.quadraticCurveTo(
      centerX - period * 2,
      centerY - rise * 0.25,
      centerX - period * 1.75,
      centerY,
    );
    brush.bezierCurveTo(
      centerX - period * 0.86,
      centerY + rise * 2,
      centerX - period * 1.42,
      centerY - rise * 2,
      centerX - period * 0.28,
      centerY - rise,
    );
    brush.moveTo(centerX + period * 0.28, centerY + rise);
    brush.bezierCurveTo(
      centerX + period * 1.42,
      centerY + rise * 2,
      centerX + period * 0.86,
      centerY - rise * 2,
      centerX + period * 1.75,
      centerY,
    );
    brush.quadraticCurveTo(
      centerX + period * 2.02,
      centerY + rise * 0.25,
      centerX + period * 2.55,
      centerY,
    );
    brush.stroke();
  } else {
    // 根据字体实际字面上下边缘居中，避免数字在不同系统中偏上。
    brush.font = `900 ${size}px "Arial Black", "Segoe UI", sans-serif`;
    brush.textAlign = 'center';
    const digits = brush.measureText('404');
    brush.fillText(
      '404',
      centerX,
      centerY + (digits.actualBoundingBoxAscent - digits.actualBoundingBoxDescent) / 2,
    );
  }

  const pixels = brush.getImageData(0, 0, mask.width, mask.height).data;
  const school: Fish[][] = [[], [], []];
  const step = Math.max(3, Math.round(size / 52));

  // 三组鱼分别绘制为三条复合路径，避免逐个切换绘制状态。
  for (let y = step; y < mask.height; y += step) {
    for (let x = step; x < mask.width; x += step) {
      // 仅采样轮廓内的点，错开网格让鱼群分布更自然。
      if (pixels[(y * mask.width + x) * 4 + 3] < 150) continue;
      const phase = (x * 0.83 + y * 1.72) % (Math.PI * 2);
      const homeX = x * 2 + Math.sin(phase) * step * 0.65;
      const homeY = y * 2 + Math.cos(phase) * step * 0.65;
      school[((x * 17 + y * 13) % 11) % school.length].push({
        homeX,
        homeY,
        x: homeX,
        y: homeY,
        vx: 0,
        vy: 0,
        phase,
      });
    }
  }
  return school;
}

/**
 * 鱼群有归位的弹力，近处的水流可以把它们推开或卷进漩涡。
 */
function moveFish(fish: Fish, current: Current | null, radius: number, frame: number) {
  let pullX = 0;
  let pullY = 0;
  let spring = 0.034;

  if (current) {
    // 握住的涡流作用范围更大，向心力和侧向力同时作用。
    const dx = current.x - fish.x;
    const dy = current.y - fish.y;
    const distance = Math.max(18, Math.hypot(dx, dy));
    const reach = current.held ? radius : 104;
    const strength = Math.max(0, 1 - distance / reach);

    if (current.held) {
      spring -= strength * 0.024;
      pullX = ((dx * 3.9 - dy * 5.2) / distance) * strength;
      pullY = ((dy * 3.9 + dx * 5.2) / distance) * strength;
    } else {
      // 略过附近时向外拨水，保持较远字形稳定。
      pullX = (-dx / distance) * strength * 2.9;
      pullY = (-dy / distance) * strength * 2.9;
    }
  }

  const damping = Math.pow(0.87, frame);
  fish.vx = (fish.vx + ((fish.homeX - fish.x) * spring + pullX) * frame) * damping;
  fish.vy = (fish.vy + ((fish.homeY - fish.y) * spring + pullY) * frame) * damping;
  fish.x += fish.vx * frame;
  fish.y += fish.vy * frame;
}

/**
 * 404 和断线各有一个字形，水面用同一套物理反馈接收指针或键盘。
 */
export function CurrentScene({ signal }: { signal: boolean }) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const current = useRef<Current | null>(null);
  const invalidate = useRef(() => {});
  const hintId = useId();

  useEffect(() => {
    // 初始化失败时保留 HTML 字形和恢复入口。
    const canvas = canvasRef.current!;
    const drawing = canvas.getContext('2d');
    if (!drawing) return;
    const ctx = drawing;

    const motion = matchMedia('(prefers-reduced-motion: reduce)');
    // 水面沿用低饱和海蓝色，断线场景留少量暖色标记。
    const colors = signal ? ['#b36c44', '#477d92', '#1b5067'] : ['#55918a', '#35788d', '#19596b'];
    let school: Fish[][] = [];
    let width = 0;
    let height = 0;
    let previous = 0;
    let request = 0;

    /**
     * 将当前运动状态绘制到画面，减少动画时只响应明确输入。
     */
    function paint(now: number) {
      request = 0;
      const reduced = motion.matches;
      const input = current.current;
      const frame = Math.min(2, Math.max(0.2, (now - previous) / 16.67));
      previous = now;
      ctx.clearRect(0, 0, width, height);

      if (input?.held) {
        // 漩涡有两道水纹，按钮和场景之外的文字不受变形。
        ctx.beginPath();
        ctx.strokeStyle = signal ? '#94553766' : '#17687866';
        ctx.lineWidth = 1;
        const radius = reduced ? 48 : 49 + Math.sin(now / 210) * 9;
        ctx.arc(input.x, input.y, radius, 0, Math.PI * 2);
        ctx.moveTo(input.x + radius * 1.5, input.y);
        ctx.arc(input.x, input.y, radius * 1.5, 0, Math.PI * 2);
        ctx.stroke();
      }

      // 分批画鱼尾，游动方向和光泽随速度改变。
      school.forEach((group, index) => {
        ctx.beginPath();
        ctx.strokeStyle = colors[index];
        ctx.lineWidth = 1.35 + index * 0.42;
        ctx.lineCap = 'round';
        for (const fish of group) {
          if (!reduced) moveFish(fish, input, Math.min(width * 0.42, 540), frame);
          const drift = reduced ? 0 : Math.sin(now / 530 + fish.phase);
          const speed = Math.abs(fish.vx) + Math.abs(fish.vy);
          const angle =
            !reduced && speed > 0.55 ? Math.atan2(fish.vy, fish.vx) : 0.34 + drift * 0.58;
          const x = reduced ? fish.homeX : fish.x + drift * 1.5;
          const y = reduced ? fish.homeY : fish.y + drift * 1.8;
          const size = 2.15 + index * 0.38 + (reduced ? 0 : Math.min(2.2, speed * 0.22));
          const tailX = Math.cos(angle) * size;
          const tailY = Math.sin(angle) * size;
          ctx.moveTo(x - tailX, y - tailY);
          ctx.quadraticCurveTo(x - tailY * 0.3, y + tailX * 0.3, x + tailX, y + tailY);
        }
        ctx.stroke();
      });

      if (!reduced) request = requestAnimationFrame(paint);
    }

    /**
     * 在静态模式按需绘制，普通模式复用已经安排的下一帧。
     */
    function schedule() {
      if (!request) request = requestAnimationFrame(paint);
    }

    /**
     * 只在尺寸改变时重算取样并匹配屏幕像素密度。
     */
    function resize() {
      width = canvas.clientWidth;
      height = canvas.clientHeight;
      if (!width || !height) return;
      const density = Math.min(devicePixelRatio, 2);
      canvas.width = Math.round(width * density);
      canvas.height = Math.round(height * density);
      ctx.setTransform(density, 0, 0, density, 0, 0);
      school = createSchool(width, height, signal);
      canvas.dataset.ready = 'true';
      canvas.tabIndex = 0;
      schedule();
    }

    const observer = new ResizeObserver(resize);
    observer.observe(canvas);
    motion.addEventListener('change', schedule);
    invalidate.current = schedule;
    resize();

    return () => {
      // 离开故障页后立即停掉整套画面更新。
      cancelAnimationFrame(request);
      observer.disconnect();
      motion.removeEventListener('change', schedule);
      invalidate.current = () => {};
    };
  }, [signal]);

  /**
   * 将鼠标位置转换为水面内位置，拖动越过边缘时仍保持控制。
   */
  function updatePointer(event: PointerEvent<HTMLCanvasElement>) {
    const bounds = event.currentTarget.getBoundingClientRect();
    current.current = {
      x: event.clientX - bounds.left,
      y: event.clientY - bounds.top,
      held: event.buttons === 1,
    };
    invalidate.current();
  }

  /**
   * 在水面按下时捕获指针，松手和取消都能终止漩涡。
   */
  function beginDrag(event: PointerEvent<HTMLCanvasElement>) {
    if (event.button !== 0) return;
    event.currentTarget.setPointerCapture(event.pointerId);
    updatePointer(event);
  }

  /**
   * 松开、离开或失去焦点后，鱼群自行归位。
   */
  function release() {
    current.current = null;
    invalidate.current();
  }

  /**
   * 空格生成漩涡，按住时可用方向键移动它。
   */
  function steer(event: KeyboardEvent<HTMLCanvasElement>) {
    const canvas = event.currentTarget;
    if (event.code === 'Space') {
      // 键盘漩涡从场景中心产生，不滚动页面。
      event.preventDefault();
      current.current ??= { x: canvas.clientWidth / 2, y: canvas.clientHeight * 0.48, held: true };
      current.current.held = true;
    } else if (current.current?.held && event.key.startsWith('Arrow')) {
      // 用方向键推动漩涡，保持在可操作水域。
      event.preventDefault();
      const dx = Number(event.key === 'ArrowRight') - Number(event.key === 'ArrowLeft');
      const dy = Number(event.key === 'ArrowDown') - Number(event.key === 'ArrowUp');
      current.current.x = Math.max(0, Math.min(canvas.clientWidth, current.current.x + dx * 24));
      current.current.y = Math.max(0, Math.min(canvas.clientHeight, current.current.y + dy * 24));
    }
    invalidate.current();
  }

  return (
    <div className={styles.scene}>
      {/* 实际页面内容由 HTML 承载，画布只负责可选的水面互动。 */}
      <canvas
        ref={canvasRef}
        tabIndex={-1}
        role="region"
        aria-label={signal ? '信号波形水面' : '404 鱼群水面'}
        aria-describedby={hintId}
        onPointerDown={beginDrag}
        onPointerMove={updatePointer}
        onPointerUp={release}
        onPointerCancel={release}
        onPointerLeave={release}
        onLostPointerCapture={release}
        onBlur={release}
        onKeyDown={steer}
        onKeyUp={(event) => {
          // 焦点留在水面，松开空格就归位。
          if (event.code === 'Space') release();
        }}
      />
      <span className={styles.fallback} aria-hidden="true">
        {signal ? '≈' : '404'}
      </span>
      <p className={styles.hint} id={hintId}>
        按住拖动水面 · <kbd>空格</kbd> + <kbd>方向键</kbd>
      </p>
    </div>
  );
}
