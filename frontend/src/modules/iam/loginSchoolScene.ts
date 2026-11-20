import {
  Color,
  DirectionalLight,
  DynamicDrawUsage,
  ExtrudeGeometry,
  Group,
  HemisphereLight,
  InstancedMesh,
  Mesh,
  MeshBasicMaterial,
  MeshStandardMaterial,
  NeutralToneMapping,
  Object3D,
  OrthographicCamera,
  RingGeometry,
  Scene,
  Shape,
  SRGBColorSpace,
  WebGLRenderer,
} from 'three';

/** 水面中鱼群可以归位的形态。 */
type Formation = 'anchor' | 'wave';

/** 正交场景中一个二维位置。 */
type Point = {
  /** 横向位置，单位为场景坐标。 */
  x: number;
  /** 纵向位置，单位为场景坐标。 */
  y: number;
};

/** 指针或键盘施加的局部水流。 */
type Current = Point & {
  /** 是否持续抓住水面。 */
  held: boolean;
  /** 掠过影响的停止时刻，单位为 performance.now 的毫秒。 */
  expiry: number;
};

/** 用距离和经过时间区分轻点与拖动。 */
type Press = Point & {
  /** 原生指针编号。 */
  id: number;
  /** 按下的原生时间戳，单位为毫秒。 */
  time: number;
  /** 是否超过轻点允许的移动范围。 */
  moved: boolean;
};

/**
 * 从项目标识和波浪轮廓生成两种均匀的鱼群归位坐标。
 */
function createFormations(logo: HTMLImageElement): Record<Formation, Point[]> {
  const mask = document.createElement('canvas');
  mask.width = 560;
  mask.height = 410;
  const context = mask.getContext('2d', { willReadFrequently: true });
  if (!context) throw new Error('当前环境无法生成鱼群轮廓');
  const draw = context;

  /**
   * 错开相邻采样行，让鱼群分布略微打散而仍可辨识轮廓。
   */
  function sample(shapeWidth: number, shapeHeight: number) {
    const pixels = draw.getImageData(0, 0, mask.width, mask.height).data;
    const points: Point[] = [];
    for (let column = 5; column < mask.width; column += 6) {
      for (let row = 5 + (column % 4); row < mask.height; row += 6) {
        // 只从轮廓内取样，透明区域保留为空白。
        if (pixels[(row * mask.width + column) * 4 + 3] < 155) continue;
        const shift = Math.sin(column * 1.73 + row * 0.83);
        points.push({
          x: (column / mask.width - 0.5) * shapeWidth + shift * 0.035,
          y: (0.5 - row / mask.height) * shapeHeight + shift * 0.027,
        });
      }
    }
    return points;
  }

  // 读取开发和生产页面共用的原始品牌图形。
  draw.drawImage(logo, 155, 9, 250, 392);
  const anchor = sample(12.08, 8.1);

  // 波峰和两道水纹形成横向展开的第二个形态。
  draw.clearRect(0, 0, mask.width, mask.height);
  draw.fill(
    new Path2D(
      'M26 240 C109 269 152 228 207 159 C260 94 310 78 347 110 C377 136 375 173 352 201 C356 166 324 140 292 153 C246 172 221 229 165 257 C109 284 58 267 26 250Z',
    ),
  );
  draw.lineCap = 'round';
  draw.lineWidth = 17;
  draw.stroke(
    new Path2D(
      'M61 286 C144 311 205 264 261 227 C316 190 346 251 407 253 C456 253 487 226 528 220',
    ),
  );
  draw.lineWidth = 10;
  draw.stroke(new Path2D('M154 321 C223 310 258 263 317 270 C366 277 413 304 480 274'));
  return { anchor, wave: sample(11.6, 9.14) };
}

/**
 * 通过实例化绘制鱼群，给鼠标和键盘提供同一套水流反馈，返回场景清理函数。
 */
export function startLoginSchool(
  canvas: HTMLCanvasElement,
  target: HTMLButtonElement,
  logo: HTMLImageElement,
  setReady: (ready: boolean) => void,
  setFormation: (formation: Formation) => void,
) {
  const forms = createFormations(logo);
  const count = Math.min(510, forms.anchor.length, forms.wave.length);
  if (!count) throw new Error('未从品牌图形取到有效的鱼群坐标');

  // 使用本地打包的 Three.js，透明画布叠加在浅海色水面上。
  const renderer = new WebGLRenderer({ canvas, alpha: true, antialias: true });
  renderer.setClearColor(0x000000, 0);
  renderer.outputColorSpace = SRGBColorSpace;
  renderer.toneMapping = NeutralToneMapping;
  const scene = new Scene();
  const camera = new OrthographicCamera(-7, 7, 5.45, -5.45, 0.1, 42);
  camera.position.z = 18;
  scene.add(new HemisphereLight(0xf8fffd, 0x7299a0, 1.27));
  const light = new DirectionalLight(0xffffff, 1.57);
  light.position.set(-4, 6, 8);
  scene.add(light);

  // 一个带倒角的鱼体几何由全部实例共用，避免每条鱼单独绘制。
  const silhouette = new Shape();
  silhouette.moveTo(0.61, 0);
  silhouette.quadraticCurveTo(0.25, 0.28, -0.23, 0.098);
  silhouette.lineTo(-0.63, 0.235);
  silhouette.lineTo(-0.5, 0);
  silhouette.lineTo(-0.63, -0.235);
  silhouette.lineTo(-0.23, -0.098);
  silhouette.quadraticCurveTo(0.25, -0.28, 0.61, 0);
  const geometry = new ExtrudeGeometry(silhouette, {
    depth: 0.115,
    bevelEnabled: true,
    bevelThickness: 0.024,
    bevelSize: 0.024,
    bevelSegments: 1,
    curveSegments: 4,
  });
  geometry.translate(0, 0, -0.06);
  const material = new MeshStandardMaterial({ roughness: 0.52, metalness: 0.07 });
  const school = new InstancedMesh(geometry, material, count);
  school.instanceMatrix.setUsage(DynamicDrawUsage);
  school.frustumCulled = false;
  scene.add(school);

  const motion = window.matchMedia('(prefers-reduced-motion: reduce)');
  const palette = ['#205e74', '#3c7d8c', '#5b9694', '#75a5a1'].map((color) => new Color(color));
  const fish = Array.from({ length: count }, (_, index) => {
    // 两种轮廓使用相同选取顺序，换形时产生整群流动。
    const homes = {
      anchor: forms.anchor[Math.floor(((index + 0.35) * forms.anchor.length) / count)],
      wave: forms.wave[Math.floor(((index + 0.35) * forms.wave.length) / count)],
    };
    const phase = (index * 1.618) % (2 * Math.PI);
    const spread = motion.matches ? 0 : 0.46 + 0.12 * Math.cos(phase);
    school.setColorAt(index, palette[(index * 13 + Math.floor(index / 17)) % palette.length]);
    return {
      homes,
      phase,
      size: 0.102 + 0.031 * (Math.sin(index * 4.13) + 1),
      x: homes.anchor.x * (1 + spread) + Math.cos(phase) * spread,
      y: homes.anchor.y * (1 + spread) + Math.sin(phase) * spread,
      vx: 0,
      vy: 0,
      angle: 0.17 + Math.sin(phase) * 0.48,
    };
  });
  if (school.instanceColor) school.instanceColor.needsUpdate = true;

  // 两圈细水纹跟随拖动位置，松手后归位动画继续。
  const ringMaterial = new MeshBasicMaterial({
    color: 0x407c85,
    transparent: true,
    opacity: 0.27,
    depthWrite: false,
  });
  const ringGeometry = new RingGeometry(0.98, 1, 64);
  const innerRing = new Mesh(ringGeometry, ringMaterial);
  const outerRing = new Mesh(ringGeometry, ringMaterial);
  const vortex = new Group();
  vortex.add(innerRing, outerRing);
  vortex.visible = false;
  scene.add(vortex);

  const transform = new Object3D();
  const events = new AbortController();
  const options = { signal: events.signal };
  let formation: Formation = 'anchor';
  let input: Current | null = null;
  let press: Press | null = null;
  let frame = 0;
  let previous = 0;
  let activeUntil = performance.now() + 1350;
  let contextReady = true;

  /**
   * 视野按实际水面比例调整，在较窄的窗口也保留完整形态。
   */
  function resize() {
    const width = Math.max(1, canvas.clientWidth);
    const height = Math.max(1, canvas.clientHeight);
    const aspect = width / height;
    const zoom = Math.max(5.2, 5.58 / aspect);
    camera.left = -zoom * aspect;
    camera.right = zoom * aspect;
    camera.top = zoom;
    camera.bottom = -zoom;
    camera.updateProjectionMatrix();
    renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 1.65));
    renderer.setSize(width, height, false);
    schedule();
  }

  /**
   * 合并同一帧内的输入，静止时不继续渲染。
   */
  function schedule() {
    if (!frame && contextReady) frame = requestAnimationFrame(paint);
  }

  /**
   * 每帧计算水流、归位与三维朝向。减少动态效果时直接绘制操作的结果。
   */
  function paint(time: number) {
    frame = 0;
    const dt = previous ? Math.min(2, Math.max(0.3, (time - previous) / 16.67)) : 1;
    previous = time;
    if (input && !input.held && time > input.expiry) input = null;
    const still = motion.matches;
    const damping = 0.88 ** dt;
    const atRest = !input && time >= activeUntil;
    vortex.visible = Boolean(input?.held);
    if (input?.held) {
      // 波纹标出可移动的漩涡中心。
      const rhythm = still ? 0 : Math.sin(time / 245) * 0.06;
      vortex.position.set(input.x, input.y, 0.3);
      innerRing.scale.setScalar(0.56 + rhythm);
      outerRing.scale.setScalar(0.89 - rhythm);
    }

    for (let index = 0; index < fish.length; index++) {
      const item = fish[index];
      const home = item.homes[formation];
      let pullX = 0;
      let pullY = 0;
      let spring = 0.043;
      if (input && (!still || input.held)) {
        // 掠过时拨开近鱼，按住时同时施加向心力与切向力。
        const dx = input.x - (still ? home.x : item.x);
        const dy = input.y - (still ? home.y : item.y);
        const distance = Math.max(0.22, Math.hypot(dx, dy));
        const reach = input.held ? Math.min(camera.right * 1.19, 8.5) : 1.75;
        const influence = Math.max(0, 1 - distance / reach);
        const inward = input.held ? 0.26 : -0.19;
        const turn = input.held ? 0.3 : 0;
        pullX = ((dx * inward - dy * turn) / distance) * influence;
        pullY = ((dy * inward + dx * turn) / distance) * influence;
        spring -= input.held ? influence * 0.034 : 0;
      }
      if (still || atRest) {
        // 静态和归位完成时保留最终位置，不持续占用帧。
        item.x = home.x + pullX * 7;
        item.y = home.y + pullY * 7;
        item.vx = 0;
        item.vy = 0;
      } else {
        // 速度带阻尼，松手后保持短暂惯性。
        item.vx = (item.vx + ((home.x - item.x) * spring + pullX) * dt) * damping;
        item.vy = (item.vy + ((home.y - item.y) * spring + pullY) * dt) * damping;
        item.x += item.vx * dt;
        item.y += item.vy * dt;
      }
      const speed = Math.hypot(item.vx, item.vy);
      const facing =
        speed > 0.025 ? Math.atan2(item.vy, item.vx) : 0.17 + Math.sin(item.phase) * 0.48;
      const turn = Math.atan2(Math.sin(facing - item.angle), Math.cos(facing - item.angle));
      item.angle = atRest || still ? facing : item.angle + turn * 0.26;
      const depth = 0.24 * Math.cos(item.phase) + (input?.held ? Math.min(0.27, speed * 0.7) : 0);
      transform.position.set(item.x, item.y, depth);
      transform.rotation.set(0.18 * Math.sin(item.phase), 0.2 * Math.cos(item.phase), item.angle);
      transform.scale.setScalar(item.size);
      transform.updateMatrix();
      school.setMatrixAt(index, transform.matrix);
    }
    school.instanceMatrix.needsUpdate = true;
    renderer.render(scene, camera);
    if (!still && (input?.held || time < activeUntil)) schedule();
  }

  /**
   * 把鼠标的屏幕坐标映射到当前正交水面。
   */
  function locate(event: PointerEvent, held: boolean): Current {
    const bounds = target.getBoundingClientRect();
    return {
      x: (2 * ((event.clientX - bounds.left) / bounds.width) - 1) * camera.right,
      y: (1 - 2 * ((event.clientY - bounds.top) / bounds.height)) * camera.top,
      held,
      expiry: performance.now() + 210,
    };
  }

  /**
   * 给当前反馈留出足够时间完成，并避免不操作时无限播放。
   */
  function wake(duration = 1200) {
    activeUntil = performance.now() + duration;
    schedule();
  }

  /**
   * 轻点或回车时在锚形和波浪之间切换。
   */
  function changeFormation() {
    formation = formation === 'anchor' ? 'wave' : 'anchor';
    setFormation(formation);
    wake(1550);
  }

  /**
   * 松开水面后保留目标形态，按当前状态完成归位。
   */
  function release() {
    press = null;
    input = null;
    wake();
  }

  /**
   * 捕获按住的指针，以便拖动到控件边缘也能正常松手。
   */
  target.addEventListener(
    'pointerdown',
    (event) => {
      if (event.button !== 0 || press) return;
      press = {
        id: event.pointerId,
        x: event.clientX,
        y: event.clientY,
        time: event.timeStamp,
        moved: false,
      };
      target.setPointerCapture(event.pointerId);
      input = locate(event, true);
      wake();
    },
    options,
  );

  /**
   * 连续移动水流，并记录实际发生过的拖动。
   */
  target.addEventListener(
    'pointermove',
    (event) => {
      if (press && press.id !== event.pointerId) return;
      if (!press && motion.matches) return;
      if (press && Math.hypot(event.clientX - press.x, event.clientY - press.y) >= 6)
        press.moved = true;
      input = locate(event, Boolean(press));
      wake();
    },
    options,
  );

  /**
   * 轻点换形，拖动结束只恢复当前形态。
   */
  target.addEventListener(
    'pointerup',
    (event) => {
      if (press?.id !== event.pointerId) return;
      const tap = !press.moved && event.timeStamp - press.time < 275;
      release();
      if (tap) changeFormation();
    },
    options,
  );

  /**
   * 指针被取消或失去捕获时清理当前漩涡。
   */
  function finishPointer(event: PointerEvent) {
    if (press?.id === event.pointerId) release();
  }

  target.addEventListener('pointercancel', finishPointer, options);
  target.addEventListener('lostpointercapture', finishPointer, options);

  /**
   * 只在未捕获拖动时停止游标掠过效果。
   */
  target.addEventListener(
    'pointerleave',
    () => {
      if (!press) release();
    },
    options,
  );

  /**
   * 原生按钮的回车激活使用同一个换形操作。
   */
  target.addEventListener(
    'click',
    (event) => {
      if (event.detail === 0) changeFormation();
    },
    options,
  );

  /**
   * 按住空格抓取，方向键移动漩涡，不滚动登录页。
   */
  target.addEventListener(
    'keydown',
    (event) => {
      if (event.code === 'Space') {
        // 键盘首次操作从水面中心开始。
        event.preventDefault();
        input ??= { x: 0, y: 0, held: false, expiry: 0 };
        input.held = true;
      } else {
        // 方向键只在抓取期间移动漩涡。
        const directions: Record<string, [number, number]> = {
          ArrowLeft: [-0.37, 0],
          ArrowRight: [0.37, 0],
          ArrowUp: [0, 0.37],
          ArrowDown: [0, -0.37],
        };
        const step = directions[event.key];
        if (!input?.held || !step) return;
        event.preventDefault();
        input.x = Math.max(camera.left + 0.35, Math.min(camera.right - 0.35, input.x + step[0]));
        input.y = Math.max(camera.bottom + 0.35, Math.min(camera.top - 0.35, input.y + step[1]));
      }
      wake();
    },
    options,
  );

  /**
   * 松开空格或移到其它控件后终止水流。
   */
  target.addEventListener(
    'keyup',
    (event) => {
      if (event.code === 'Space') release();
    },
    options,
  );
  target.addEventListener('blur', release, options);

  /**
   * WebGL 暂时不可用时停下渲染，让静态锚形接替画布。
   */
  canvas.addEventListener(
    'webglcontextlost',
    (event) => {
      event.preventDefault();
      contextReady = false;
      cancelAnimationFrame(frame);
      frame = 0;
      release();
      setReady(false);
    },
    options,
  );

  /**
   * Three.js 恢复上下文后重绘当前形态。
   */
  canvas.addEventListener(
    'webglcontextrestored',
    () => {
      contextReady = true;
      setReady(true);
      wake();
    },
    options,
  );

  const observer = new ResizeObserver(resize);
  observer.observe(canvas);
  motion.addEventListener('change', schedule, options);
  resize();
  setReady(true);

  return () => {
    // 退出登录页或组件重挂载时，及时释放监听、动画和 WebGL 资源。
    events.abort();
    observer.disconnect();
    cancelAnimationFrame(frame);
    school.dispose();
    geometry.dispose();
    material.dispose();
    ringGeometry.dispose();
    ringMaterial.dispose();
    renderer.dispose();
  };
}
