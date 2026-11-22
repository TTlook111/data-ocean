import { queryOptions, useQuery } from '@tanstack/react-query';
import { Alert, Button, Spin } from 'antd';
import type { ComponentProps } from 'react';
import { api, ApiError } from '../../shared/api';
import { QueryAnswer, activeState, explaining, statusText } from './QueryAnswer';
import type { Snapshot } from './types';
import styles from './query.module.css';

/** 当前与历史轮次共享缓存及实时核权，读取不会再次执行查询。 */
export function queryTurnOptions(source: string, id: string) {
  return queryOptions({
    queryKey: ['workbench-request', source, id],
    queryFn: () => api<Snapshot>('/query/requests/' + id),
    retry: false,
    refetchInterval: (query) =>
      activeState(query.state.data?.state) || explaining(query.state.data) ? 1800 : 4500,
    refetchOnWindowFocus: 'always',
  });
}

/** 一个轮次按原序展示问题、澄清、补充和当前可查看的答复。 */
export function QueryTurn({
  row,
  onSelect,
  ...actions
}: Omit<ComponentProps<typeof QueryAnswer>, 'historical'> & {
  /** 点选历史问题可把输入框和重查动作定位到该轮。 */
  onSelect?: () => void;
}) {
  const waiting = row.state === 'WAITING';
  const running = activeState(row.state);
  return (
    <section className={styles.turn} aria-label={onSelect ? '历史问答' : '当前轮次'}>
      {/* 历史问题保留定位入口，正文仍显示对应的真实答案。 */}
      {onSelect ? (
        <button type="button" className={styles.questionLink} onClick={onSelect}>
          {row.question}
        </button>
      ) : (
        <p className={styles.question}>{row.question}</p>
      )}
      {/* 澄清正文与结果左对齐，当前等待补充时播报追问。 */}
      {row.clarification && (
        <p className={styles.clarification} role={waiting && !onSelect ? 'status' : undefined}>
          {row.clarification}
        </p>
      )}
      {row.supplement && <p className={styles.question}>{row.supplement}</p>}
      {running && (
        <div className={styles.activity} role={onSelect ? undefined : 'status'}>
          <Spin size="small" />
          {row.cancelRequested
            ? '正在核实停止状态'
            : row.runVersion > 1 && !row.supplement
              ? '恢复中 · ' + statusText[row.state]
              : '思考中'}
        </div>
      )}
      {!running && !waiting && (
        <QueryAnswer row={row} {...actions} historical={Boolean(onSelect)} />
      )}
    </section>
  );
}

/** 历史快照读取必须按原请求核验来源和会话。 */
export function HistoricalQueryTurn({
  id,
  source,
  conversationId,
  onSelect,
  onDownload,
  onDownloadChart,
}: {
  /** 会话列表中的原轮次编号。 */
  id: string;
  /** 当前实际打开的数据源。 */
  source: string;
  /** 当前会话，避免切换时使用到别的会话快照。 */
  conversationId: string;
  /** 将工作台切到本轮以继续提问或重查。 */
  onSelect: () => void;
  /** 每轮导出重新读取本轮结果。 */
  onDownload: () => void;
  /** 每轮趋势图导出沿用相同核权。 */
  onDownloadChart: () => void;
}) {
  const detail = useQuery(queryTurnOptions(source, id));
  const row = detail.data;
  if (detail.isPending) {
    // 在时间线原来的位置等待，后续轮次不会替换此轮。
    return <Spin size="small" aria-label="正在读取历史问答" />;
  }
  if (
    detail.isError ||
    row?.id !== id ||
    row.sourceId !== source ||
    row.conversationId !== conversationId
  ) {
    // 当前校验失败时，缓存里的旧问题和答案都不继续显示。
    const restricted =
      !detail.isError ||
      (detail.error instanceof ApiError && [403003, 404001, 409005].includes(detail.error.code));
    return (
      <Alert
        showIcon
        type={restricted ? 'warning' : 'error'}
        title={restricted ? '当前权限不足，无法读取该轮问数。' : '暂时无法读取该轮问数。'}
        action={
          restricted ? undefined : (
            <Button size="small" onClick={() => void detail.refetch()}>
              重试
            </Button>
          )
        }
      />
    );
  }
  return (
    <QueryTurn
      row={row}
      onSelect={onSelect}
      onDownload={onDownload}
      onDownloadChart={onDownloadChart}
    />
  );
}
