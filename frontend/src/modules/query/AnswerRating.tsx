import {
  CheckCircleOutlined,
  ClockCircleOutlined,
  DislikeOutlined,
  LikeOutlined,
} from '@ant-design/icons';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Button, Form, Input } from 'antd';
import { useEffect, useId, useRef, useState, type ComponentRef, type ReactNode } from 'react';
import { api, ApiError } from '../../shared/api';
import type { FeedbackNotice } from '../feedback/types';
import { FeedbackNoticeLine } from './FeedbackNotices';
import { QueryIconButton } from './QueryIconButton';
import type { Snapshot } from './types';
import styles from './query.module.css';

/** 当前评价与后台入队分开回读。 */
interface Rating {
  /** 本人最后一次有效评价。 */
  rating: 'NONE' | 'LIKE' | 'DISLIKE';
  /** 首次评价前没有版本。 */
  version: number | null;
  /** 当前点踩仍可回看的原因。 */
  reason: string | null;
  /** 原回答的反馈受理与入队状态。 */
  queueState: 'NONE' | 'RECEIVED' | 'QUEUED';
  /** 已入队时可核对的事项编号。 */
  issueId: string | null;
  /** 结案后仅本人有权看到的站内提示。 */
  notice?: FeedbackNotice | null;
}

/** 回包不明时核对原命令和原输入。 */
interface Attempt {
  /** 可由服务端去重的原提交编号。 */
  command: string;
  /** 提交时看到的版本和已经规范化的原因。 */
  body: { action: 'LIKE' | 'DISLIKE' | 'REVOKE'; reason?: string; expectedVersion: number | null };
}

/** 评价读取资格随原回答失权、失效或过期变化。 */
function unavailable(error: unknown) {
  return error instanceof ApiError && [401001, 401002, 403003, 404001, 409005].includes(error.code);
}

/** 在同一个回答操作区显示评价、编辑及实际入队状态。 */
export function AnswerRating({
  answerId,
  answerRevision,
  sourceId,
  resultActions,
  queryTime,
  enabled = true,
}: NonNullable<Snapshot['answerRef']> & {
  /** 原数据源用于定位本次已交付结果的缓存。 */
  sourceId: string;
  /** 明细和导出，与评价共享操作行。 */
  resultActions: ReactNode;
  /** 对应原查询的时间，使用工具行的辅助位置。 */
  queryTime?: ReactNode;
  /** 历史答复由悬停或聚焦触发第一次评价读取。 */
  enabled?: boolean;
}) {
  const cache = useQueryClient();
  const path = '/query/requests/' + answerId + '/answers/' + answerRevision + '/rating';
  const rating = useQuery({
    queryKey: ['answer-rating', answerId, answerRevision],
    queryFn: () => api<Rating>(path),
    enabled,
    // 暂时读失败时暂停轮询，由用户恢复读取。
    refetchInterval: ({ state }) => {
      if (state.error) return false;
      return state.data?.queueState === 'RECEIVED' ? 1500 : state.data?.issueId ? 5000 : false;
    },
    retry: false,
  });
  const restricted = unavailable(rating.error);
  const data = restricted ? undefined : rating.data;
  const readFailed = rating.isError && !restricted;
  const [editor, setEditor] = useState<{ reason: string; version: number | null } | null>(null);
  const [error, setError] = useState<{ message: string; field?: 'reason' } | null>(null);
  const [uncertain, setUncertain] = useState<Attempt>();
  const [busy, setBusy] = useState(false);
  const active = useRef(false);
  const field = useRef<ComponentRef<typeof Input.TextArea>>(null);
  const trigger = useRef<ComponentRef<typeof Button>>(null);
  const fieldId = useId();
  const changedVersion = Boolean(editor && data && editor.version !== data.version);
  const blocked = busy || Boolean(uncertain) || rating.isError;

  useEffect(() => {
    if (restricted) {
      // 评价先收到失权或到期时，请工作台重核同一回答。
      void cache.invalidateQueries({
        queryKey: ['workbench-request', sourceId, answerId],
        exact: true,
      });
    }
  }, [answerId, cache, restricted, sourceId]);

  /** 退出编辑，焦点回到原回答的点踩入口。 */
  function close() {
    setEditor(null);
    setError(null);
    requestAnimationFrame(() => trigger.current?.focus({ preventScroll: true }));
  }

  /** 已知冲突回读，回包未知时保留并核对原提交。 */
  async function send(attempt: Attempt, retry = false) {
    if (active.current) return;
    active.current = true;
    setBusy(true);
    setError(null);

    /** 回执只按本回答的原命令读取。 */
    const lookup = () => api(path + '/commands/' + attempt.command);
    try {
      if (retry) {
        // 仅当原命令没有提交记录时才用同一编号重试。
        const found = await lookup().then(
          () => true,
          (failure: unknown) => {
            if (failure instanceof ApiError && failure.code === 404001) return false;
            throw failure;
          },
        );
        if (found) {
          await finish();
          return;
        }
      }
      await api(path, { method: 'POST', ...attempt });
      await finish();
    } catch (failure) {
      if (failure instanceof ApiError && failure.code < 500000 && failure.code !== 409004) {
        // 权威拒绝后保留可修正的输入，失权则先收起旧原因。
        setUncertain(undefined);
        const message = failure.fields.find((value) => value.field === 'reason')?.message;
        setError(message ? { message, field: 'reason' } : { message: failure.message });
        if (failure.code === 409001) {
          // 旧表单继续使用打开时的版本，刷新仅为展示冲突。
          if (editor) setError(null);
          void rating.refetch();
        } else if (unavailable(failure) && failure.code !== 409005) {
          // 当前访问资格依服务端重新确认。
          setEditor(null);
          void rating.refetch();
        }
      } else {
        // 区分确实未受理的服务拒绝与还不能核对的回包。
        const committed = await lookup().then(
          () => true,
          (lookupFailure: unknown) =>
            lookupFailure instanceof ApiError && lookupFailure.code === 404001 ? false : null,
        );
        if (committed) {
          await finish();
        } else if (committed === false && failure instanceof ApiError && failure.code === 503001) {
          // 服务明确返回不可用且没有原回执，保留草稿并允许再次提交。
          setUncertain(undefined);
          setError({ message: failure.message });
        } else {
          setUncertain(attempt);
          setError({ message: '暂时无法确认评价是否提交' });
        }
      }
    } finally {
      active.current = false;
      setBusy(false);
    }
  }

  /** 刷新实际状态，关闭编辑后恢复触发按钮焦点。 */
  async function finish() {
    setUncertain(undefined);
    await rating.refetch();
    if (editor) {
      // 恢复按钮可用后再归还焦点。
      setBusy(false);
      close();
    }
  }

  /** 提交使用打开表单时的版本，轮询不能覆盖旧草稿的基准。 */
  function submit(action: Attempt['body']['action'], fromEditor = false) {
    if (!data || blocked || (fromEditor && changedVersion)) return;
    const text = editor?.reason.trim();
    if (action === 'DISLIKE' && !text) {
      // 用原有表单校验提示并聚焦到错误字段。
      setError({ message: '请填写点踩原因', field: 'reason' });
      field.current?.focus();
      return;
    }
    void send({
      command: crypto.randomUUID(),
      body: {
        action,
        reason: action === 'DISLIKE' ? text : undefined,
        expectedVersion: fromEditor && editor ? editor.version : data.version,
      },
    });
  }

  return (
    <div className={styles.answerFooter} data-rating-open={Boolean(editor) || undefined}>
      {/* 结果动作与评价并排，窄窗在自然间距处换行。 */}
      <div className={styles.answerToolbar}>
        {resultActions}
        {data && (
          <div className={styles.ratingControls} role="group" aria-label="回答评价">
            <QueryIconButton
              label={data.rating === 'LIKE' ? '已点赞' : '点赞'}
              icon={<LikeOutlined aria-hidden />}
              aria-pressed={data.rating === 'LIKE'}
              disabled={blocked || Boolean(editor)}
              onClick={() => submit(data.rating === 'LIKE' ? 'REVOKE' : 'LIKE')}
            />
            <QueryIconButton
              ref={trigger}
              label={data.rating === 'DISLIKE' ? '已点踩' : '点踩'}
              icon={<DislikeOutlined aria-hidden />}
              aria-pressed={data.rating === 'DISLIKE'}
              aria-expanded={Boolean(editor)}
              aria-controls={editor ? fieldId + '-form' : undefined}
              disabled={blocked}
              onClick={() => {
                // 再次点击直接关闭，重新打开才读取已保存原因。
                if (editor) {
                  close();
                  return;
                }
                setEditor({ reason: data.reason ?? '', version: data.version });
                setError(null);
                requestAnimationFrame(() => field.current?.focus());
              }}
            />
          </div>
        )}
        {readFailed && (
          <Button
            size="small"
            type="link"
            loading={rating.isFetching}
            onClick={() => void rating.refetch()}
          >
            重试读取评价
          </Button>
        )}
        {queryTime}
      </div>
      {/* 保留同一播报区域，状态只来自已成功的回读。 */}
      {!data?.notice && (
        <div className={styles.ratingStatus} role="status">
          {data?.queueState === 'RECEIVED' && (
            <>
              <ClockCircleOutlined aria-hidden />
              反馈已收到
            </>
          )}
          {data?.queueState === 'QUEUED' && (
            <>
              <CheckCircleOutlined aria-hidden />
              已进入问题队列 · #{data.issueId}
            </>
          )}
        </div>
      )}
      {data?.notice && <FeedbackNoticeLine notice={data.notice} />}
      {/* 内联编辑不覆盖查询结果，取消和撤销使用不同动作。 */}
      {data && editor && (
        <Form
          id={fieldId + '-form'}
          className={styles.ratingForm}
          layout="vertical"
          onFinish={() => submit('DISLIKE', true)}
        >
          <Form.Item
            label="点踩原因"
            htmlFor={fieldId}
            required
            validateStatus={error?.field ? 'error' : undefined}
          >
            <Input.TextArea
              id={fieldId}
              ref={field}
              maxLength={500}
              autoSize={{ minRows: 2, maxRows: 5 }}
              aria-required
              aria-invalid={Boolean(error?.field)}
              aria-describedby={error?.field ? fieldId + '-error' : undefined}
              disabled={busy || Boolean(uncertain)}
              value={editor.reason}
              onChange={(event) => {
                // 修正字段时清除校验错误，命令回执错误独立保留。
                setEditor({ ...editor, reason: event.target.value });
                if (error?.field) setError(null);
              }}
              onKeyDown={(event) => {
                // 等待核对的原命令不能由 Escape 抹掉。
                if (event.key === 'Escape' && !busy && !uncertain) close();
              }}
            />
          </Form.Item>
          {error?.field && (
            <p className={styles.ratingError} id={fieldId + '-error'} role="alert">
              {error.message}
            </p>
          )}
          {changedVersion && (
            <div className={styles.ratingNotice} role="status">
              <span>评价已在其他页面更新</span>
              <Button
                type="link"
                size="small"
                disabled={blocked}
                onClick={() => {
                  // 用户确认以最新版本继续，保留尚未提交的原因。
                  setEditor({ ...editor, version: data.version });
                  field.current?.focus({ preventScroll: true });
                }}
              >
                按最新版本继续
              </Button>
            </div>
          )}
          <div className={styles.ratingFormActions}>
            {data.rating === 'DISLIKE' && (
              <Button
                type="text"
                size="small"
                disabled={blocked || changedVersion}
                onClick={() => submit('REVOKE', true)}
              >
                撤销点踩
              </Button>
            )}
            <div className={styles.ratingSubmitActions}>
              <Button size="small" disabled={busy || Boolean(uncertain)} onClick={close}>
                取消
              </Button>
              <Button
                size="small"
                type="primary"
                htmlType="submit"
                loading={busy}
                disabled={blocked || changedVersion}
              >
                {data.rating === 'DISLIKE' ? '保存修改' : '提交反馈'}
              </Button>
            </div>
          </div>
        </Form>
      )}
      {/* 需要处理时在原答复旁显示命令结果核对入口。 */}
      {data && error && !error.field && (
        <p className={styles.ratingError} role="alert">
          {error.message}
        </p>
      )}
      {data && uncertain && (
        <Button
          size="small"
          loading={busy}
          disabled={rating.isError}
          onClick={() => void send(uncertain, true)}
        >
          核对并重试
        </Button>
      )}
    </div>
  );
}
