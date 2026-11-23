import { InfoCircleOutlined } from '@ant-design/icons';
import { Button, Tooltip } from 'antd';
import { useId, useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import { FeedbackChart } from './FeedbackChart';
import { periodLabel, valueText } from './types';
import type { Measure, Point, Series } from './types';

/**
 * 图表、可展开数据表和统计下钻共用同一组数值。
 */
export function FeedbackMetricPanel({
  title,
  points,
  total,
  series,
  onDrill,
  layout,
  footer,
}: {
  /** 图表及数据表标题。 */
  title: string;
  /** 按期间粒度返回的明细统计。 */
  points: Point[];
  /** 本期总量和期末数。 */
  total: Point;
  /** 当前图负责显示的指标。 */
  series: Series[];
  /** 有权查看反馈事项时允许下钻贡献记录。 */
  onDrill?: (measure: Measure, point: Point) => void;
  /** 原型中的通栏主图、辅助趋势或右侧环图。 */
  layout: 'hero' | 'backlog' | 'ring' | 'duration';
  /** 主图进入既有工作队列的入口。 */
  footer?: ReactNode;
}) {
  const [expanded, setExpanded] = useState(false);
  const id = useId();
  const ring = layout === 'ring';
  const seriesPoints = useMemo(() => (ring ? [total] : points), [ring, total, points]);
  const ringCount = total.resolved + total.otherClosed + total.merged;
  const duration = layout === 'duration';
  const noDurationSamples = duration && total.averageResolutionHours === null;
  const historyGap =
    layout === 'backlog' && points.some((point) => point.awaitingVerification === null);

  /**
   * 数值为零或无可核对历史时只显示读数。
   */
  function reading(item: Series, point: Point, headline = false) {
    const value = point[item.measure];
    const text = valueText(value, item.measure);
    const number = headline ? <strong>{text}</strong> : text;
    return onDrill && value !== null && (value > 0 || item.measure === 'averageResolutionHours') ? (
      <Button
        className="feedback-overview-number"
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
    <section className={`feedback-overview-panel is-${layout}`} aria-labelledby={id}>
      {/* 标题、图例和数据表开关使用同一行，窄窗口自动换行。 */}
      <header>
        {/* 单位贴近标题，历史覆盖提示按需查看。 */}
        <div className="feedback-overview-panel-title">
          <h2 id={id}>{title}</h2>
          <span>{duration ? '小时' : '个'}</span>
          {historyGap && (
            <Tooltip title="历史待验证状态缺失的时段留空" trigger={['hover', 'focus']}>
              <Button
                type="text"
                size="small"
                className="feedback-overview-info"
                icon={<InfoCircleOutlined />}
                aria-label="历史待验证状态缺失的时段留空"
              />
            </Tooltip>
          )}
        </div>
        <div className="feedback-overview-panel-actions">
          {!ring && (
            <ul className="feedback-overview-legend" aria-label="图例">
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
            className="feedback-overview-data-toggle"
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
      {!ring && !noDurationSamples && (
        <ul className="feedback-overview-headlines">
          {series.map((item) => (
            <li key={item.measure}>
              {reading(item, total, true)}
              <span>{duration ? '小时平均解决' : '个' + item.label}</span>
            </li>
          ))}
        </ul>
      )}
      {noDurationSamples ? (
        <div className="feedback-overview-no-samples">所选期间暂无已解决样本</div>
      ) : (
        <div className={ring ? 'feedback-overview-ring' : undefined}>
          <FeedbackChart title={title} points={seriesPoints} series={series} ring={ring} />
          {ring && (
            <div className="feedback-overview-ring-label">
              <strong>{ringCount.toLocaleString('zh-CN')}</strong>
              <span>个期间结案</span>
            </div>
          )}
        </div>
      )}
      {ring && (
        <ul className="feedback-overview-ring-legend" aria-label="结案分布与占比">
          {series.map((item) => (
            <li key={item.measure}>
              <span className="feedback-overview-swatch" style={{ backgroundColor: item.color }} />
              <span>{item.label}</span>
              {reading(item, total)}
              <span className="feedback-overview-share">
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
          className="feedback-overview-data"
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
      {expanded && historyGap && (
        <p className="feedback-overview-coverage">历史待验证状态缺失的时段留空</p>
      )}
      {footer && <footer>{footer}</footer>}
    </section>
  );
}
