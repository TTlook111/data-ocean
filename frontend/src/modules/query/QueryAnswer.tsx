import { BookOutlined, DownloadOutlined, ReloadOutlined, TableOutlined } from '@ant-design/icons';
import { Alert, Button } from 'antd';
import { useId, useState } from 'react';
import type { Snapshot } from './types';
import { TrendPanel } from './TrendChart';
import { trendFromResult } from './trend';
import styles from './query.module.css';

/**
 * 只展示当前可核验的请求和真实数据库结果。
 */
export const statusText: Record<string, string> = {
  QUEUED: '等待处理',
  RETRIEVING: '正在检索已发布知识',
  GENERATING: '正在确定查询条件',
  CHECKING: '正在复核口径和查询权限',
  EXECUTING: '正在查询数据',
  FINALIZING: '正在核对查询结果',
  WAITING: '需要补充条件',
  PERMISSION_DENIED: '当前权限不允许交付所请求的信息。',
  OUT_OF_SCOPE: '该问题超出了当前问数范围。',
  KNOWLEDGE_INSUFFICIENT: '已发布知识还不足以确认本次查询口径。',
  RESOURCE_EXCEEDED: '本次查询超过可用资源或处理时限。',
  TECHNICAL_FAILURE: '本次查询未能完成。',
  USER_CANCELLED: '问数已取消。',
  WAIT_EXPIRED: '补充条件的等待时间已到，请重新提问。',
};

/**
 * 这些阶段允许用户请求停止并订阅进度。
 */
export function activeState(state?: string) {
  return Boolean(
    state &&
    ['QUEUED', 'RETRIEVING', 'GENERATING', 'CHECKING', 'EXECUTING', 'FINALIZING'].includes(state),
  );
}

/**
 * 解释继续订阅，它的状态变化不影响首屏结果。
 */
export function explaining(row?: Snapshot) {
  return row?.explanation?.status === 'QUEUED' || row?.explanation?.status === 'GENERATING';
}

const dateTime = new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'medium' });

/**
 * 业务结果先于细节和同版证据，拒绝和取数未知各自说明。
 */
export function QueryAnswer({
  row,
  onRequery,
  onDownload,
  onDownloadChart,
  onExplainAgain,
  onStopExplanation,
}: {
  /** 当前已授权的轮次。 */
  row: Snapshot;
  /** 基于原问题重新发起一条查询。 */
  onRequery?: () => void;
  /** 重新核权后导出本次可见数据。 */
  onDownload: () => void;
  /** 按实际结果与当前授权导出图表图片。 */
  onDownloadChart?: () => void;
  /** 对同一结果创建新的解释修订。 */
  onExplainAgain?: () => void;
  /** 停止当前解释，保留已交付的数据。 */
  onStopExplanation?: () => void;
}) {
  const [panel, setPanel] = useState<'data' | 'basis' | null>(null);
  const panelId = useId();
  const result = row.result;
  if (!result) {
    const info =
      row.state === 'RESULT_DELIVERED' ? '本次结果已超过保留期。' : statusText[row.state];
    return (
      <Alert
        showIcon
        type={row.state === 'PERMISSION_DENIED' ? 'warning' : 'info'}
        title={info}
        description={row.executionState === 'UNKNOWN' ? '源端执行状态待核实。' : undefined}
        action={
          row.executionState !== 'UNKNOWN' && onRequery ? (
            <Button size="small" icon={<ReloadOutlined />} onClick={onRequery}>
              重新查询
            </Button>
          ) : undefined
        }
      />
    );
  }
  const metric =
    result.columns.length === 1 && result.columns[0].name === 'value' && result.rows.length === 1;
  const trend = trendFromResult(result);
  const explanation = row.explanation?.status === 'COMPLETED' ? row.explanation.text : null;
  const hasBasis = Boolean(row.evidence || result.scope || row.sql);
  const table = (
    <div className={styles.tableScroll}>
      <table className={styles.table}>
        <thead>
          <tr>
            {result.columns.map((column, index) => (
              <th scope="col" key={index}>
                {column.label}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {result.rows.map((values, index) => (
            <tr key={index}>
              {values.map((value, position) => (
                <td key={position}>{value ?? '空值'}</td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
      {!result.rows.length && <p className={styles.emptyRows}>当前条件下没有记录。</p>}
      {result.truncated && <p className={styles.emptyRows}>结果已截断，显示前 2,000 行。</p>}
    </div>
  );
  return (
    <article className={styles.answer} aria-label="查询结果" aria-live="polite">
      <div className={styles.answerIdentity}>
        <img src="/dataocean-mark.png" alt="" />
        <span>DataOcean</span>
      </div>
      {/* 单指标先读名称和真实数值，普通结果直接呈现表格。 */}
      {metric ? (
        <div className={styles.metric}>
          <h2>
            <span>{result.columns[0].label}</span>
            <strong>{result.rows[0][0] ?? '暂无数据'}</strong>
            {result.unit && result.rows[0][0] !== null && <small>{result.unit}</small>}
          </h2>
        </div>
      ) : trend ? (
        <TrendPanel trend={trend} />
      ) : (
        table
      )}
      {/* 解释就绪前用实际执行口径说明结果，原始口径始终可在依据中核对。 */}
      {(explanation || result.scope) && (
        <p className={styles.explanation}>{explanation || result.scope}</p>
      )}
      {explaining(row) && (
        <div className={styles.explanationState} role="status">
          正在生成结果说明
          {onStopExplanation && (
            <Button type="text" size="small" onClick={onStopExplanation}>
              停止说明
            </Button>
          )}
        </div>
      )}
      {(row.explanation?.status === 'FAILED' || row.explanation?.status === 'STOPPED') && (
        <div className={styles.explanationState}>
          {row.explanation.status === 'FAILED' ? '结果说明生成失败' : '已停止说明'}
          {onExplainAgain && (
            <Button type="link" size="small" onClick={onExplainAgain}>
              重试说明
            </Button>
          )}
        </div>
      )}
      <div className={styles.metadata}>
        {row.executedAt && <span>查询于 {dateTime.format(new Date(row.executedAt))}</span>}
        <span>
          {result.dataUpdatedAt
            ? '数据更新于 ' + dateTime.format(new Date(result.dataUpdatedAt))
            : '数据更新时间未知'}
        </span>
      </div>
      <div className={styles.answerActions} role="group" aria-label="结果操作">
        {metric && (
          <Button
            type="text"
            size="small"
            icon={<TableOutlined aria-hidden />}
            aria-expanded={panel === 'data'}
            aria-controls={panelId + '-data'}
            onClick={() => setPanel(panel === 'data' ? null : 'data')}
          >
            数据明细
          </Button>
        )}
        {hasBasis && (
          <Button
            type="text"
            size="small"
            icon={<BookOutlined aria-hidden />}
            aria-expanded={panel === 'basis'}
            aria-controls={panelId + '-basis'}
            onClick={() => setPanel(panel === 'basis' ? null : 'basis')}
          >
            查询依据
          </Button>
        )}
        <Button
          type="text"
          size="small"
          icon={<DownloadOutlined aria-hidden />}
          onClick={onDownload}
        >
          导出 CSV
        </Button>
        {trend && onDownloadChart && (
          <Button type="text" size="small" onClick={onDownloadChart}>
            导出图表
          </Button>
        )}
        {onRequery && (
          <Button
            type="text"
            size="small"
            icon={<ReloadOutlined aria-hidden />}
            onClick={onRequery}
          >
            重新查询
          </Button>
        )}
      </div>
      {/* 可展开内容共享一块阅读区，窄窗可在对话内滚动。 */}
      {metric && (
        <div id={panelId + '-data'} className={styles.answerPanel} hidden={panel !== 'data'}>
          {table}
        </div>
      )}
      {hasBasis && (
        <div id={panelId + '-basis'} className={styles.answerPanel} hidden={panel !== 'basis'}>
          <div className={styles.basis}>
            {row.evidence && (
              <div className={styles.basisVersion}>
                <strong>{row.evidence.title}</strong>
                <span>
                  r{row.evidence.revision} · 片段 {row.evidence.fragment + 1}
                </span>
              </div>
            )}
            {result.scope && <p>{result.scope}</p>}
            {row.sql && (
              <details className={styles.sql}>
                <summary>查看 SQL</summary>
                <pre>{row.sql}</pre>
              </details>
            )}
          </div>
        </div>
      )}
    </article>
  );
}
