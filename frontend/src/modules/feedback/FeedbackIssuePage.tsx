import { Alert, Button, Descriptions, Empty, Tabs } from 'antd';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useRef, useState } from 'react';
import { useLocation, useNavigate, useParams } from 'react-router';
import { api } from '../../shared/api';
import { displayTime, ReadState, Status } from '../../shared/Records';
import { FeedbackActionDialog } from './FeedbackActionDialog';
import { directions, statusLabels, type IssueDetail } from './types';
import './feedback.css';

/** 原问证据区保留历史真实引用，受限、缺失和已知内容分别显示。 */
function IssueEvidence({ detail }: { detail: IssueDetail }) {
  const question = detail.question;
  if (detail.evidenceRestricted || !question) {
    return <Alert showIcon type="info" title="当前无权查看原问题与业务证据" />;
  }
  return (
    <>
      {/* 先展示原问题和用户反馈，再给出用于核对的执行依据。 */}
      <section className="feedback-evidence-section" aria-label="原问题">
        <h3>原问题</h3>
        <p className="feedback-text">{question.question ?? '原问题受限'}</p>
        {question.supplement && (
          <Descriptions
            size="small"
            column={1}
            items={[
              {
                key: 'supplement',
                label: '用户补充',
                children: <span className="feedback-text">{question.supplement}</span>,
              },
            ]}
          />
        )}
      </section>
      <section className="feedback-evidence-section" aria-label="点踩原因">
        <h3>点踩原因</h3>
        {detail.reports.length ? (
          <ul className="feedback-entries">
            {detail.reports.map((report) => (
              <li key={report.id}>
                <p className="feedback-text">{report.reason ?? '原文受限或已到期'}</p>
                <time dateTime={report.occurredAt}>{displayTime(report.occurredAt)}</time>
              </li>
            ))}
          </ul>
        ) : (
          <p className="feedback-meta">暂无点踩记录</p>
        )}
      </section>
      <section className="feedback-evidence-section" aria-label="当次查询依据">
        <h3>当次查询依据</h3>
        {question.incomplete && (
          <Alert showIcon type="info" title="证据不完整" className="form-alert" />
        )}
        <Descriptions
          size="small"
          column={{ xs: 1, sm: 1, md: 2 }}
          items={[
            {
              key: 'source',
              label: '知识依据',
              span: 'filled',
              children: question.evidence
                ? `${question.evidence.title} · r${question.evidence.revision} · 片段 ${question.evidence.fragment + 1}`
                : '—',
            },
            {
              key: 'scope',
              label: '查询口径',
              span: 'filled',
              children: <span className="feedback-text">{question.scope ?? '—'}</span>,
            },
            {
              key: 'answer',
              label: '原回答编号',
              children: <span className="feedback-text">{question.answerId}</span>,
            },
            { key: 'at', label: '查询时间', children: displayTime(question.executedAt) },
          ]}
        />
        {question.sql && (
          <details className="feedback-sql">
            <summary>查看 SQL</summary>
            <pre>{question.sql}</pre>
          </details>
        )}
      </section>
    </>
  );
}

/** 正式队列详情展示当前责任、办理入口和按权限返回的同一历史证据。 */
export function FeedbackIssuePage() {
  const { id } = useParams();
  const navigate = useNavigate();
  const location = useLocation();
  const cache = useQueryClient();
  const [action, setAction] = useState<'assign' | 'review'>();
  const focused = useRef<string | undefined>(undefined);
  const detail = useQuery({
    queryKey: ['feedback-issue', id],
    queryFn: () => api<IssueDetail>('/feedback/issues/' + id),
    enabled: Boolean(id),
    retry: false,
    refetchInterval: 4500,
    refetchOnWindowFocus: 'always',
  });
  const row = detail.isError ? undefined : detail.data;

  useEffect(() => {
    // 同一事项刷新证据时不抢焦点，仅进入详情时定位标题。
    if (!row || focused.current === row.issue.id) return;
    focused.current = row.issue.id;
    document.getElementById('feedback-detail-title')?.focus({ preventScroll: true });
  }, [row]);

  /** 返回列表延续筛选与页码，定位原事项。 */
  function back() {
    navigate('/feedback' + location.search, { state: { feedbackFocus: id } });
  }

  /** 保存或因权限变化重读时同步后台列表的实际状态。 */
  async function refresh() {
    await cache.invalidateQueries({ queryKey: ['feedback-issues'] });
    const current = await detail.refetch();
    return current.isSuccess ? current.data : undefined;
  }

  if (!row) {
    return (
      <section className="panel">
        <ReadState loading={detail.isPending} error={detail.error} retry={detail.refetch}>
          {null}
        </ReadState>
        <Button onClick={back}>返回队列</Button>
      </section>
    );
  }
  const issue = row.issue;
  const last = row.latestReview;
  return (
    <article className="panel feedback-detail" aria-labelledby="feedback-detail-title">
      {/* 页首固定责任和状态，业务正文在各自页签内阅读。 */}
      <header className="feedback-detail-header">
        <div className="feedback-heading">
          <h2 id="feedback-detail-title" tabIndex={-1}>
            事项 #{issue.id}
          </h2>
          <Status value={issue.status} label={statusLabels[issue.status]} />
        </div>
        <Descriptions
          size="small"
          column={{ xs: 1, sm: 2, md: 2, lg: 2, xl: 4, xxl: 4 }}
          items={[
            { key: 'source', label: '数据源', children: issue.sourceName },
            {
              key: 'origin',
              label: '来源',
              children: issue.origin === 'USER_DISLIKE' ? '问数点踩' : issue.origin,
            },
            { key: 'owner', label: '负责人', children: issue.assigneeName ?? '待分派' },
            { key: 'updated', label: '最近更新', children: displayTime(issue.updatedAt) },
          ]}
        />
      </header>
      <Tabs
        className="feedback-detail-tabs"
        items={[
          {
            key: 'work',
            label: '核查与处理',
            children: (
              <section aria-label="核查结论">
                <Descriptions
                  column={1}
                  items={[
                    {
                      key: 'direction',
                      label: '初步方向',
                      children: directions[row.direction as keyof typeof directions] ?? '待核实',
                    },
                    {
                      key: 'root',
                      label: '核实根因',
                      children: (
                        <span className="feedback-text">
                          {last?.rootCause ?? (row.evidenceRestricted && last ? '受限' : '待核实')}
                        </span>
                      ),
                    },
                    {
                      key: 'basis',
                      label: '核查依据',
                      children: (
                        <span className="feedback-text">{last?.note ?? (last ? '受限' : '—')}</span>
                      ),
                    },
                  ]}
                />
              </section>
            ),
          },
          { key: 'evidence', label: '原问题与证据', children: <IssueEvidence detail={row} /> },
          {
            key: 'history',
            label: '处理记录',
            children: row.activities.length ? (
              <ol className="feedback-entries">
                {row.activities.map((entry) => (
                  <li key={entry.version}>
                    <div className="feedback-history-line">
                      <strong>{entry.action === 'ASSIGNED' ? '分派事项' : '记录核查'}</strong>
                      <span>{entry.actor}</span>
                      <time dateTime={entry.occurredAt}>{displayTime(entry.occurredAt)}</time>
                    </div>
                    {entry.assignee && <p>负责人 {entry.assignee}</p>}
                    {entry.note && <p className="feedback-text">{entry.note}</p>}
                  </li>
                ))}
              </ol>
            ) : (
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无处理记录" />
            ),
          },
        ]}
      />
      {/* 分派资格与主负责人办理权由详情明确返回，动作始终可从底栏找到。 */}
      <footer className="feedback-detail-footer">
        <Button onClick={back}>返回队列</Button>
        <div className="feedback-detail-actions">
          {row.canAssign && (
            <Button
              type={row.canReview ? 'default' : 'primary'}
              onClick={() => setAction('assign')}
            >
              {issue.assigneeId ? '重新分派' : '分派负责人'}
            </Button>
          )}
          {row.canReview && (
            <Button type="primary" onClick={() => setAction('review')}>
              记录核查
            </Button>
          )}
        </div>
      </footer>
      {action && (
        <FeedbackActionDialog
          kind={action}
          detail={row}
          refresh={refresh}
          close={() => setAction(undefined)}
        />
      )}
    </article>
  );
}
