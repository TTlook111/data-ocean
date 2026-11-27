import { MessageOutlined } from '@ant-design/icons';
import { Alert, App, Button, Form, Input, Modal, Tag } from 'antd';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { api, ApiError } from '../../shared/api';
import { displayTime } from '../../shared/Records';
import { isSupplementPending, statusLabels, type FeedbackSupplementEntry } from '../feedback/types';
import styles from './query.module.css';
import { useFeedbackCommand } from '../feedback/useFeedbackCommand';

/**
 * 当前原问题上本人可见的补充和处理进度。
 */
type SupplementState = {
  /** 本次补充写入使用的原事项版本。 */
  version: number;
  /** 主事项当前进度。 */
  status: string;
  /** 当前能否补充或回复。 */
  canSubmit: boolean;
  /** 本人原问题的请求与回复。 */
  entries: FeedbackSupplementEntry[];
};

/**
 * 提醒需补充、处理中和已解决使用工作台已有的状态色。
 */
const progressColors: Record<string, string> = {
  PENDING_SUPPLEMENT: 'warning',
  IN_PROGRESS: 'processing',
  RESOLVED: 'success',
};

/**
 * 原问题有可见反馈时就近显示补充，普通问数没有额外面板。
 */
export function QueryFeedbackSupplement({
  requestId,
  awaitIntake,
}: {
  requestId: string;
  awaitIntake: boolean;
}) {
  const path = `/query/requests/${requestId}/feedback-supplements`;
  const key = ['feedback-supplement', requestId];
  const cache = useQueryClient();
  const { message } = App.useApp();
  const [draft, setDraft] = useState<{ version: number; pending?: FeedbackSupplementEntry }>();
  const [answer, setAnswer] = useState('');
  const [busy, setBusy] = useState(false);
  const [stale, setStale] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const state = useQuery({
    queryKey: key,
    queryFn: () => api<SupplementState | null>(path),
    retry: false,
    refetchOnWindowFocus: 'always',
    refetchInterval: (query) => {
      // 待处理和等待入队的原问就近刷新，失权时停止主动重试。
      if (query.state.error) return false;
      return query.state.data
        ? ['RESOLVED', 'CLOSED_OTHER'].includes(query.state.data.status)
          ? false
          : 6000
        : awaitIntake
          ? 6000
          : false;
    },
  });
  const { uncertain, send } = useFeedbackCommand(state.refetch);
  const value = state.data;
  if (state.isError || !value || !Array.isArray(value.entries)) return null;
  const pending = value.entries.find(isSupplementPending);
  // 当前待回复排在前面，已结束记录继续保留原展示顺序。
  const entries = pending
    ? [pending, ...value.entries.filter((entry) => entry !== pending)]
    : value.entries;
  const requestEnded = Boolean(draft?.pending && draft.pending.id !== pending?.id);
  const canSubmit = value.canSubmit && !requestEnded;

  /**
   * 表单保存用户原话，版本冲突后须主动核对当前请求。
   */
  async function submit() {
    if (!draft || busy || stale || (!canSubmit && !uncertain) || !answer.trim()) return;
    const body = {
      expectedVersion: draft.version,
      supplementId: draft.pending?.id,
      answer: answer.trim(),
    };
    setError(null);
    setBusy(true);
    try {
      await send(path, body, 'query.view');
      await cache.invalidateQueries({ queryKey: key });
      setDraft(undefined);
      setAnswer('');
      message.success('已提交补充');
    } catch (failure) {
      if (failure instanceof ApiError) {
        // 明确失败可改草稿，版本冲突则保留原文重新读取。
        if (failure.code === 409001) setStale(true);
        setError(failure.message);
      } else {
        setError('提交结果暂未确认，可核对并重试');
      }
    } finally {
      setBusy(false);
    }
  }

  /**
   * 显式重读后保留草稿，用户核对新的待补充内容。
   */
  async function reload() {
    const current = (await state.refetch()).data;
    const latestPending = current?.entries.find(isSupplementPending);
    if (draft?.pending && draft.pending.id !== latestPending?.id) {
      // 已结束请求的草稿不能转交给另一次补充请求。
      return;
    }
    if (current?.canSubmit) {
      setDraft({
        version: current.version,
        pending: latestPending,
      });
      setStale(false);
      setError(null);
    }
  }

  return (
    <section className={styles.feedbackProgress} aria-label="反馈进度与业务补充">
      {/* 主事项进度和当前可办动作固定在卡片顶部。 */}
      <header className={styles.feedbackProgressHeader}>
        <div className={styles.feedbackProgressTitle}>
          <MessageOutlined aria-hidden />
          <strong>反馈进度</strong>
        </div>
        <div className={styles.feedbackProgressActions}>
          <Tag className={styles.feedbackProgressTag} color={progressColors[value.status]}>
            {statusLabels[value.status] ?? value.status}
          </Tag>
          {value.canSubmit && (
            <Button
              size="small"
              onClick={() => {
                // 只打开当前可回复请求或本人主动补充的表单。
                setDraft({ version: value.version, pending });
                setError(null);
                setStale(false);
              }}
            >
              {pending ? '回复补充请求' : '补充业务说明'}
            </Button>
          )}
        </div>
      </header>
      {/* 补充记录先说明结果，已结束要求使用历史标签。 */}
      {entries.length > 0 && (
        <ul className={styles.feedbackProgressEntries} aria-label="补充记录">
          {entries.map((entry) => {
            // 结束时间反映实际处置，待回复时沿用请求时间。
            const time = entry.disposedAt ?? entry.answeredAt ?? entry.requestedAt;
            const entryStatus = entry.disposedAt
              ? entry.disposition === 'WITHDRAWN'
                ? '补充请求已撤回'
                : '补充请求已结束'
              : entry.answeredAt
                ? '已补充'
                : '补充请求';
            const ended = !isSupplementPending(entry);

            return (
              <li key={entry.id} className={styles.feedbackProgressEntry}>
                <div className={styles.feedbackProgressEvent}>
                  <strong>{entryStatus}</strong>
                  {time && <time dateTime={time}>{displayTime(time)}</time>}
                </div>
                {entry.prompt && (
                  <p className={styles.feedbackProgressText}>
                    {ended && <span>原补充要求</span>}
                    {entry.prompt}
                  </p>
                )}
                {entry.answeredAt && entry.answer && (
                  <p className={styles.feedbackProgressText}>
                    {entry.prompt && <span>你的补充</span>}
                    {entry.answer}
                  </p>
                )}
              </li>
            );
          })}
        </ul>
      )}
      {draft && (
        <Modal
          centered
          open
          title="补充业务说明"
          okText={uncertain ? '核对并重试' : '提交补充'}
          cancelText="取消"
          mask={{ closable: false }}
          confirmLoading={busy}
          okButtonProps={{ disabled: stale || (!canSubmit && !uncertain) || !answer.trim() }}
          onOk={() => void submit()}
          onCancel={() => {
            if (!busy) setDraft(undefined);
          }}
        >
          {draft.pending?.prompt && <p>{draft.pending.prompt}</p>}
          {requestEnded && <Alert type="info" showIcon title="这条补充请求已结束" />}
          {error && (
            <Alert
              type="warning"
              title={error}
              showIcon
              action={
                stale && !requestEnded ? (
                  <Button size="small" onClick={() => void reload()}>
                    读取最新请求
                  </Button>
                ) : undefined
              }
            />
          )}
          <Form layout="vertical">
            <Form.Item label="业务补充说明" htmlFor="query-feedback-supplement">
              <Input.TextArea
                id="query-feedback-supplement"
                rows={4}
                maxLength={1000}
                disabled={busy || uncertain || requestEnded}
                value={answer}
                onChange={(event) => setAnswer(event.target.value)}
              />
            </Form.Item>
          </Form>
        </Modal>
      )}
    </section>
  );
}
