import { Segmented } from 'antd';
import { useLayoutEffect, useRef, useState } from 'react';
import type { Trend } from './trend';
import styles from './query.module.css';

const width = 820;
const height = 218;
const axisNumber = new Intl.NumberFormat('zh-CN', { maximumSignificantDigits: 4 });

/**
 * 使用实际日点绘制，空值断线，零值仍为有效点。
 */
export function drawTrend(canvas: HTMLCanvasElement, trend: Trend) {
  canvas.width = width;
  canvas.height = height;
  const ctx = canvas.getContext('2d');
  if (!ctx) return;
  ctx.fillStyle = '#fff';
  ctx.fillRect(0, 0, width, height);
  ctx.font = '13px "Segoe UI", "Microsoft YaHei", sans-serif';
  const values = trend.points.map(({ value }) => (value === null ? null : Number(value)));
  const observed = values.filter(
    (value): value is number => value !== null && Number.isFinite(value),
  );
  if (!observed.length) {
    ctx.fillStyle = '#5b6575';
    ctx.textAlign = 'center';
    ctx.fillText('该范围没有可绘制的数据', width / 2, height / 2);
    return;
  }
  // 以 1、2、5 的十进制倍数划分刻度，小数指标仍保留自己的量级。
  const min = Math.min(0, ...observed);
  const max = Math.max(0, ...observed);
  const rawSpan = max - min;
  const roughStep = rawSpan > 0 && Number.isFinite(rawSpan) ? rawSpan / 4 || rawSpan : 1;
  const magnitude = 10 ** Math.floor(Math.log10(roughStep)) || Number.MIN_VALUE;
  const multiplier = [1, 2, 5, 10].find((value) => value >= roughStep / magnitude) ?? 10;
  const tickSize = multiplier * magnitude;
  const candidateLow = Math.floor(min / tickSize) * tickSize;
  const candidateHigh = Math.max(candidateLow + tickSize, Math.ceil(max / tickSize) * tickSize);
  const rounded = candidateHigh > candidateLow && Number.isFinite(candidateHigh - candidateLow);
  const low = rounded ? candidateLow : min;
  const high = rounded ? candidateHigh : max;
  // 极端数值先等比例缩放，再计算纵向坐标，避免两端相减溢出。
  const scale = Math.max(Math.abs(low), Math.abs(high)) || 1;
  const span = high / scale - low / scale || 1;
  const left = 58;
  const right = width - 24;
  const top = 35;
  const bottom = height - 35;
  const steps = Math.max(1, trend.points.length - 1);
  ctx.fillStyle = '#273951';
  ctx.textAlign = 'left';
  ctx.fillText(trend.title + (trend.unit ? '（' + trend.unit + '）' : ''), left, 17, right - left);

  /** 将实际业务日位置映射到相同间距的横轴。 */
  const x = (index: number) =>
    trend.points.length === 1 ? (left + right) / 2 : left + (index / steps) * (right - left);
  /** 同一计量单位使用同一条数值轴。 */
  const y = (value: number) => bottom - ((value / scale - low / scale) / span) * (bottom - top);

  // 网格仅用于读值，轴刻度由真实数据范围决定。
  const gridSteps = rounded ? Math.round((high - low) / tickSize) : 4;
  for (let i = 0; i <= gridSteps; i++) {
    const ratio = i / gridSteps;
    const value = rounded ? low + tickSize * i : low * (1 - ratio) + high * ratio;
    ctx.strokeStyle = '#e5eaf1';
    ctx.beginPath();
    ctx.moveTo(left, y(value));
    ctx.lineTo(right, y(value));
    ctx.stroke();
    ctx.fillStyle = '#52647a';
    ctx.textAlign = 'right';
    ctx.fillText(axisNumber.format(value), left - 9, y(value) + 4);
  }

  // 长范围保留首尾标签，最多读取七个均匀刻度。
  const tick = Math.max(1, Math.ceil(steps / 6));
  trend.points.forEach(({ day }, index) => {
    if (index % tick !== 0 && index !== steps) return;
    if (index !== steps && steps - index < tick * 0.6) return;
    ctx.fillStyle = '#52647a';
    ctx.textAlign = 'center';
    ctx.fillText(day.slice(5), x(index), bottom + 22);
  });

  ctx.strokeStyle = '#2f63d5';
  ctx.lineWidth = 2;
  ctx.beginPath();
  let continuous = false;
  values.forEach((value, index) => {
    if (value === null || !Number.isFinite(value)) {
      continuous = false;
      return;
    }
    if (continuous) ctx.lineTo(x(index), y(value));
    else ctx.moveTo(x(index), y(value));
    continuous = true;
  });
  ctx.stroke();
  values.forEach((value, index) => {
    if (value === null || !Number.isFinite(value)) return;
    ctx.fillStyle = '#2f63d5';
    ctx.beginPath();
    ctx.arc(x(index), y(value), 3, 0, Math.PI * 2);
    ctx.fill();
  });
}

/**
 * 趋势图有可访问名称，精确值由同组数据表提供。
 */
export function TrendChart({ trend }: { trend: Trend }) {
  const ref = useRef<HTMLCanvasElement>(null);
  useLayoutEffect(() => {
    if (ref.current) drawTrend(ref.current, trend);
  }, [trend]);
  return (
    <canvas
      ref={ref}
      className={styles.trendCanvas}
      role="img"
      aria-label={trend.title + '趋势图'}
    />
  );
}

/**
 * 图和明细读取同一份结果，支持键盘切换。
 */
export function TrendPanel({ trend }: { trend: Trend }) {
  const [display, setDisplay] = useState<'chart' | 'table'>('chart');
  const start = trend.points[0]?.day;
  const end = trend.points.at(-1)?.day;
  return (
    <div className={styles.trendPanel}>
      <div className={styles.trendToolbar}>
        <Segmented<'chart' | 'table'>
          size="small"
          aria-label="图表显示方式"
          options={[
            { label: '趋势图', value: 'chart' },
            { label: '表格', value: 'table' },
          ]}
          value={display}
          onChange={setDisplay}
        />
        <span>{start === end ? start : start + ' 至 ' + end}</span>
      </div>
      {display === 'chart' ? (
        <TrendChart trend={trend} />
      ) : (
        <div className={styles.tableScroll}>
          <table className={styles.table}>
            <thead>
              <tr>
                <th scope="col">业务日期</th>
                <th scope="col">
                  {trend.title}
                  {trend.unit && '（' + trend.unit + '）'}
                </th>
              </tr>
            </thead>
            <tbody>
              {trend.points.map(({ day, value }) => (
                <tr key={day}>
                  <td>{day}</td>
                  <td>{value ?? '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
