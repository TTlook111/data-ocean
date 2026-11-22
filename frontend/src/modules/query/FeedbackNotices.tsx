import { Alert, App, Button, Empty, Modal, Pagination } from 'antd';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { api, queryString } from '../../shared/api';
import { displayTime, ReadState } from '../../shared/Records';
import { useReturnFocus } from '../../shared/useReturnFocus';
import type { FeedbackNotice } from '../feedback/types';
import type { Page } from '../iam/types';
import styles from './query.module.css';

/** 展示受控回告，明确阅读动作后请求服务端核对当前可见信息。 */
export function FeedbackNoticeLine({
  notice,
  open,
}: {
  notice: FeedbackNotice;
  open?: (source: string, request: string) => void;
}) {
  const cache = useQueryClient();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const { message } = App.useApp();

  /** 用已核权的最新响应选择原回答，不用屏幕上的旧链接导航。 */
  async function read() {
    setBusy(true);
    setError(null);
    try {
      const current = await api<FeedbackNotice>('/feedback/notices/' + notice.id + '/read', {
        method: 'PUT',
      });
      await cache.invalidateQueries({ queryKey: ['feedback-notices'] });
      await cache.invalidateQueries({ queryKey: ['answer-rating'] });
      if (open && current.sourceId && current.requestId) open(current.sourceId, current.requestId);
      else message.success('已读');
    } catch {
      // 原记录仍可读，阅读状态保持在最近一次已确认的事实。
      setError('暂时无法记录已读');
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className={styles.noticeLine} aria-label="反馈回告">
      {/* 受限回告同样呈现模板，不输出原问的临时编号或内部处理内容。 */}
      <p>{notice.message}</p>
      <div className={styles.noticeMeta}>
        <time dateTime={notice.sentAt}>{displayTime(notice.sentAt)}</time>
        {notice.readAt && <span>已读</span>}
        {open && notice.requestId ? (
          <Button type="link" size="small" loading={busy} onClick={() => void read()}>
            查看原回答
          </Button>
        ) : (
          !notice.readAt && (
            <Button type="link" size="small" loading={busy} onClick={() => void read()}>
              标为已读
            </Button>
          )
        )}
      </div>
      {error && <Alert type="error" title={error} showIcon />}
    </section>
  );
}

/** 本人的产品内回告独立于当前数据源，所以失权时仍可读取通用提示。 */
export function FeedbackInbox({
  close,
  open,
}: {
  close: () => void;
  open: (source: string, request: string) => void;
}) {
  useReturnFocus();
  const [pageNum, setPageNum] = useState(1);
  const list = useQuery({
    queryKey: ['feedback-notices', pageNum],
    retry: false,
    queryFn: () =>
      api<Page<FeedbackNotice>>('/feedback/notices?' + queryString({ pageNum, pageSize: 10 })),
  });
  return (
    <Modal centered open title="反馈回告" width={580} footer={null} onCancel={close}>
      <ReadState loading={list.isPending} error={list.error} retry={() => void list.refetch()}>
        {list.data?.items.length ? (
          <>
            {/* 保留送达顺序和同一页面的读取状态。 */}
            {list.data.items.map((notice) => (
              <FeedbackNoticeLine key={notice.id} notice={notice} open={open} />
            ))}
            {list.data.total > 10 && (
              <Pagination
                current={pageNum}
                pageSize={10}
                total={list.data.total}
                onChange={setPageNum}
                showSizeChanger={false}
              />
            )}
          </>
        ) : (
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无反馈回告" />
        )}
      </ReadState>
    </Modal>
  );
}
