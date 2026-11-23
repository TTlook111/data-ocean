import { InfoCircleOutlined } from '@ant-design/icons';
import { Button, Tooltip } from 'antd';
import { useId, useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import { OverviewChart } from './OverviewChart';
import { periodLabel, valueText } from './types';
import type { DashboardPoint, DashboardSeries } from './types';
import './overview.css';

/**
 * 图表、可展开数据表和统计下钻共用同一组数值。
 */
export function OverviewPanel<M extends string>({
  title,
  points,
  total,
  series,
  onDrill,
  layout,
  unit,
  emptyText,
  note,
  ringLabel,
  ringSurface,
  footer,
}: {
  /** 图表及数据表标题。 */
  title: string;
  /** 按期间粒度返回的明细统计。 */
  points: DashboardPoint<M>[];
  /** 本期总量和期末数。 */
  total: DashboardPoint<M>;
  /** 当前图负责显示的指标。 */
  series: DashboardSeries<M>[];
  /** 具备原模块明细权限时允许下钻。 */
  onDrill?: (measure: M, point: DashboardPoint<M>) => void;
  /** 原型中的通栏主图、辅助趋势或右侧环图。 */
  layout: 'hero' | 'backlog' | 'ring' | 'duration' | 'pending' | 'relations';
  /** 图表的业务单位。 */
  unit: string;
  /** 没有有效样本时的反馈。 */
  emptyText?: string;
  /** 按需查看的口径及历史覆盖说明。 */
  note?: string;
  /** 环图中心的合计说明。 */
  ringLabel?: string;
  /** 环图分割线与面板共用的底色。 */
  ringSurface?: string;
  /** 主图进入既有工作队列的入口。 */
  footer?: ReactNode;
}) {
  const [expanded, setExpanded] = useState(false);
  const id = useId();
  const ring = layout === 'ring';
  const seriesPoints = useMemo(() => (ring ? [total] : points), [ring, total, points]);
  const ringCount = ring ? series.reduce((sum, item) => sum + (total[item.measure] ?? 0), 0) : 0;
  const noSamples = emptyText && series.every((item) => total[item.measure] === null);

  /**
   * 数值为零或无可核对历史时只显示读数。
   */
  function reading(item: DashboardSeries<M>, point: DashboardPoint<M>, headline = false) {
    const value = point[item.measure];
    const text = valueText(value, item.decimals);
    const number = headline ? <strong>{text}</strong> : text;
    return onDrill && value !== null && (value > 0 || item.decimals) ? (
      <Button
        className="overview-number"
        type="link"
        aria-label={`查看${item.label}明细，${periodLabel(point)}，${text}`}
        onClick={() => onDrill(item.measure, point)}
      >
        {number}
      </Button>
    ) : (
      number
    );
  }

  return (
    <section className={`overview-panel is-${layout}`} aria-labelledby={id}>
      {/* 标题、图例和数据表开关使用同一行，窄窗口自动换行。 */}
      <header>
        {/* 单位贴近标题，历史覆盖提示按需查看。 */}
        <div className="overview-panel-title">
          <h2 id={id}>{title}</h2>
          <span>{unit}</span>
          {note && (
            <Tooltip title={note} trigger={['hover', 'focus']}>
              <Button
                type="text"
                size="small"
                className="overview-info"
                icon={<InfoCircleOutlined />}
                aria-label={note}
              />
            </Tooltip>
          )}
        </div>
        <div className="overview-panel-actions">
          {!ring && (
            <ul className="overview-legend" aria-label="图例">
              {series.map((item) => (
                <li key={item.measure}>
                  <span className={item.dashed ? 'is-dashed' : ''} style={{ color: item.color }} />
                  {item.label}
                </li>
              ))}
            </ul>
          )}
          <Button
            type="text"
            size="small"
            className="overview-data-toggle"
            aria-label={`${expanded ? '收起' : '查看'}${title}数据`}
            aria-expanded={expanded}
            aria-controls={expanded ? id + '-data' : undefined}
            onClick={() => setExpanded((open) => !open)}
          >
            {expanded ? '收起数据' : '查看数据'}
          </Button>
        </div>
      </header>

      {/* 重要读数贴近对应图形，日期缺口和无解决样本保留不同语义。 */}
      {!ring && !noSamples && (
        <ul className="overview-headlines">
          {series.map((item) => (
            <li key={item.measure}>
              {reading(item, total, true)}
              <span>{item.summaryLabel ?? unit + item.label}</span>
            </li>
          ))}
        </ul>
      )}
      {noSamples ? (
        <div className="overview-no-samples">{emptyText}</div>
      ) : (
        <div className={ring ? 'overview-ring' : undefined}>
          <OverviewChart
            title={title}
            points={seriesPoints}
            series={series}
            ring={ring}
            ringSurface={ringSurface}
          />
          {ring && (
            <div className="overview-ring-label">
              <strong>{ringCount.toLocaleString('zh-CN')}</strong>
              <span>{ringLabel}</span>
            </div>
          )}
        </div>
      )}
      {ring && (
        <ul className="overview-ring-legend" aria-label={title + '与占比'}>
          {series.map((item) => (
            <li key={item.measure}>
              <span className="overview-swatch" style={{ backgroundColor: item.color }} />
              <span>{item.label}</span>
              {reading(item, total)}
              <span className="overview-share">
                {ringCount
                  ? (((total[item.measure] ?? 0) / ringCount) * 100).toFixed(1) + '%'
                  : '—'}
              </span>
            </li>
          ))}
        </ul>
      )}

      {/* 数据表逐点保留原始期间，并提供与图表一致的下钻入口。 */}
      {expanded && (
        <div
          className="overview-data"
          id={id + '-data'}
          role="region"
          aria-label={title + '数据区'}
          tabIndex={0}
        >
          {/* 固定表头让长期间的数值在区块内回看。 */}
          <table aria-label={title + '数据'}>
            <thead>
              <tr>
                <th scope="col">期间</th>
                {series.map((item) => (
                  <th scope="col" key={item.measure}>
                    {item.label}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {seriesPoints.map((point) => (
                <tr key={point.fromDate}>
                  <th scope="row">{periodLabel(point)}</th>
                  {series.map((item) => (
                    <td key={item.measure}>{reading(item, point)}</td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {expanded && note && <p className="overview-coverage">{note}</p>}
      {footer && <footer>{footer}</footer>}
    </section>
  );
}
