import {
  BookOutlined,
  DownloadOutlined,
  PictureOutlined,
  ReloadOutlined,
  TableOutlined,
} from '@ant-design/icons';
import { Alert, Button, Spin } from 'antd';
import { useId, useState } from 'react';
import type { Snapshot } from './types';
import { TrendPanel } from './TrendChart';
import { AnswerRating } from './AnswerRating';
import { QueryIconButton } from './QueryIconButton';
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
  historical = false,
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
  /** 历史答复默认优先展示结果正文。 */
  historical?: boolean;
}) {
  const [panel, setPanel] = useState<'data' | 'basis' | null>(null);
  const [historyRatingRequested, setHistoryRatingRequested] = useState(false);
  const panelId = useId();
  const result = row.result;
  if (!result) {
    const info =
      row.state === 'RESULT_DELIVERED' ? '本次结果已超过保留期。' : statusText[row.state];
    return (
      <Alert
        className={historical ? styles.historyNotice : undefined}
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
  const explanation = row.explanation?.status === 'COMPLETED' ? row.explanation.text?.trim() : null;
  const thinking = explaining(row);
  const explanationStatus = row.explanation?.status;
  const block = row.explanation?.blockedReason;
  const canExplainAgain =
    !block &&
    (explanationStatus === 'NOT_GENERATED' ||
      explanationStatus === 'FAILED' ||
      explanationStatus === 'STOPPED');
  const waitingForAnswer = metric && thinking;
  const hasBasis = Boolean(row.evidence || result.scope || row.sql);
  // 历史评价仍只在第一次与对应回答交互时读取。
  const requestHistoryRating =
    historical && row.answerRef && !historyRatingRequested
      ? () => setHistoryRatingRequested(true)
      : undefined;
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
  const resultActions = (
    <div className={styles.answerActions} role="group" aria-label="结果操作">
      {metric && (
        <QueryIconButton
          label="数据明细"
          icon={<TableOutlined aria-hidden />}
          aria-expanded={panel === 'data'}
          aria-controls={panelId + '-data'}
          onClick={() => setPanel(panel === 'data' ? null : 'data')}
        />
      )}
      {hasBasis && (
        <QueryIconButton
          label="查询依据"
          icon={<BookOutlined aria-hidden />}
          aria-expanded={panel === 'basis'}
          aria-controls={panelId + '-basis'}
          onClick={() => setPanel(panel === 'basis' ? null : 'basis')}
        />
      )}
      <QueryIconButton
        label="导出 CSV"
        icon={<DownloadOutlined aria-hidden />}
        onClick={onDownload}
      />
      {trend && onDownloadChart && (
        <QueryIconButton
          label="导出图表"
          icon={<PictureOutlined aria-hidden />}
          onClick={onDownloadChart}
        />
      )}
      {onRequery && (
        <QueryIconButton
          label="重新查询"
          icon={<ReloadOutlined aria-hidden />}
          onClick={onRequery}
        />
      )}
    </div>
  );
  // 查询时间只占工具行的辅助位置，不插入回答正文或直播区域。
  const queryTime = row.executedAt && (
    <time className={styles.queryTime} dateTime={row.executedAt}>
      {dateTime.format(new Date(row.executedAt))}
    </time>
  );
  return (
    <article
      className={styles.answer}
      aria-label="查询结果"
      data-details-open={panel !== null || undefined}
      onPointerEnter={requestHistoryRating}
      onFocus={requestHistoryRating}
    >
      {/* 只播报结果变化，评价表单在独立的区域处理反馈。 */}
      <div aria-live={historical ? undefined : 'polite'}>
        {/* 单值答复优先显示完整句子，无法生成时仍交付已核验的数值。 */}
        {metric && !thinking && !explanation && (
          <h2 className={styles.metric}>
            <span>{result.columns[0].label}</span>
            <strong>{result.rows[0][0] ?? '暂无数据'}</strong>
            {result.unit && result.rows[0][0] !== null && <span>{result.unit}</span>}
          </h2>
        )}
        {/* 多行结果继续展示表格或趋势，完整解读随结果交付。 */}
        {!metric && (trend ? <TrendPanel trend={trend} /> : table)}
        {explanation && <p className={styles.explanation}>{explanation}</p>}
        {/* 已交付结果的说明等待使用行内动效，停止入口留在原处。 */}
        {thinking && (
          <div className={styles.explanationState} role="status">
            <Spin size="small" aria-hidden="true" />
            思考中
            {onStopExplanation && (
              <Button type="text" size="small" onClick={onStopExplanation}>
                停止说明
              </Button>
            )}
          </div>
        )}
        {/* 后端返回的原因优先显示，存在限制时没有生成或重试动作。 */}
        {(block || explanationStatus === 'UNAVAILABLE') && (
          <div className={styles.explanationState}>{block ?? '当前数据源未启用结果说明'}</div>
        )}
        {canExplainAgain && (
          <div className={styles.explanationState}>
            {explanationStatus === 'NOT_GENERATED'
              ? '尚未生成结果说明'
              : explanationStatus === 'FAILED'
                ? '结果说明生成失败'
                : '已停止说明'}
            {onExplainAgain && (
              <Button type="link" size="small" onClick={onExplainAgain}>
                {explanationStatus === 'NOT_GENERATED' ? '生成说明' : '重试说明'}
              </Button>
            )}
          </div>
        )}
      </div>
      {/* 工具占位固定，鼠标移开或重试说明时保留已有草稿和面板。 */}
      <div hidden={waitingForAnswer}>
        {row.answerRef ? (
          <AnswerRating
            key={row.answerRef.answerId + ':' + row.answerRef.answerRevision}
            {...row.answerRef}
            sourceId={row.sourceId}
            resultActions={resultActions}
            queryTime={queryTime}
            enabled={!historical || historyRatingRequested}
          />
        ) : (
          <div className={styles.answerFooter}>
            <div className={styles.answerToolbar}>
              {resultActions}
              {queryTime}
            </div>
          </div>
        )}
        {/* 明细和依据仍在原答复附近展开。 */}
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
      </div>
    </article>
  );
}
