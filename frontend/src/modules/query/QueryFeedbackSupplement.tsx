import { Alert, App, Button, Form, Input, Modal } from 'antd';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useRef, useState } from 'react';
import { api, ApiError, mutation } from '../../shared/api';
import { displayTime } from '../../shared/Records';
import { isSupplementPending, statusLabels, type FeedbackSupplementEntry } from '../feedback/types';
import styles from './query.module.css';

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
  const [uncertain, setUncertain] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const attempt = useRef<{ command: string; body: object } | null>(null);
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
  const value = state.data;
  if (state.isError || !value || !Array.isArray(value.entries)) return null;
  const pending = value.entries.find(isSupplementPending);
  const requestEnded = Boolean(draft?.pending && draft.pending.id !== pending?.id);
  const canSubmit = value.canSubmit && !requestEnded;

  /**
   * 表单保存用户原话，版本冲突后须主动核对当前请求。
   */
  async function submit() {
    if (!draft || busy || stale || (!canSubmit && !uncertain) || !answer.trim()) return;
    const request = attempt.current ?? {
      command: crypto.randomUUID(),
      body: {
        expectedVersion: draft.version,
        supplementId: draft.pending?.id,
        answer: answer.trim(),
      },
    };
    attempt.current = request;
    setError(null);
    setBusy(true);
    try {
      await mutation(path, 'POST', request.body, 'query.view', request.command);
      await cache.invalidateQueries({ queryKey: key });
      attempt.current = null;
      setUncertain(false);
      setDraft(undefined);
      setAnswer('');
      message.success('已提交补充');
    } catch (failure) {
      if (failure instanceof ApiError) {
        // 明确失败可改草稿，版本冲突则保留原文重新读取。
        attempt.current = null;
        setUncertain(false);
        if (failure.code === 409001) setStale(true);
        setError(failure.message);
      } else {
        setUncertain(true);
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
    <section className={styles.noticeLine} aria-label="反馈进度与业务补充">
      <div className={styles.noticeMeta}>
        <span>反馈进度</span>
        <strong>{statusLabels[value.status] ?? value.status}</strong>
        {value.canSubmit && (
          <Button
            type="link"
            size="small"
            onClick={() => {
              setDraft({ version: value.version, pending });
              setError(null);
              setStale(false);
            }}
          >
            {pending ? '回复补充请求' : '补充业务说明'}
          </Button>
        )}
      </div>
      {/* 只呈现当前原问题的补充，不列其他原问题或后台事项编号。 */}
      {value.entries.map((entry) => (
        <div key={entry.id}>
          {entry.prompt && <p>{entry.prompt}</p>}
          {entry.answeredAt && (
            <>
              {entry.answer && <p>{entry.answer}</p>}
              <time dateTime={entry.answeredAt}>已补充 {displayTime(entry.answeredAt)}</time>
            </>
          )}
          {/* 人工结束等待只展示对提问人可见的结果。 */}
          {entry.disposedAt && (
            <time dateTime={entry.disposedAt}>
              {entry.disposition === 'WITHDRAWN' ? '已撤回补充请求' : '补充请求已结束'}{' '}
              {displayTime(entry.disposedAt)}
            </time>
          )}
        </div>
      ))}
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
