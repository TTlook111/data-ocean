import { Alert, Button, Descriptions, Empty, Tabs } from 'antd';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useRef, useState } from 'react';
import { Link, useLocation, useNavigate, useParams } from 'react-router';
import { api } from '../../shared/api';
import { ActionMenuButton } from '../../shared/ActionMenuButton';
import { displayTime, ReadState, Status } from '../../shared/Records';
import { FeedbackActionDialog } from './FeedbackActionDialog';
import {
  FeedbackResolutionDialog,
  impactLabels,
  type ResolutionAction,
} from './FeedbackResolutionDialog';
import {
  directions,
  originLabels,
  statusLabels,
  type Activity,
  type IssueDetail,
  type QueryIncident,
} from './types';
import './feedback.css';

/** 处理与回告分别显示事实名称，旧记录继续按原动作读取。 */
const activityLabels: Record<Activity['action'], string> = {
  ASSIGNED: '分派事项',
  REVIEWED: '记录核查',
  RESULT_SUBMITTED: '提交处理结果',
  VERIFIED: '人工复验通过',
  RECHECK_FAILED: '人工复验未通过',
  CLOSED: '办理结案',
};

/** 记录正文在不同操作中有不同来源，按当时保存的业务含义标注。 */
const activityNoteLabels: Record<Activity['action'], string> = {
  ASSIGNED: '分派依据',
  REVIEWED: '核查依据',
  RESULT_SUBMITTED: '处理说明',
  VERIFIED: '实际结果',
  RECHECK_FAILED: '实际结果',
  CLOSED: '结案依据',
};

const noticeLabels: Record<string, string> = {
  QUEUED: '待投递',
  DELIVERED: '已投递',
  RESTRICTED: '已投递受限提示',
  SUPPRESSED: '接收账号不可用',
};

/**
 * 操作、时间和办理字段按各自层级呈现，受限正文由服务端省略。
 */
function IssueHistory({ detail }: { detail: IssueDetail }) {
  const notice = detail.resolution?.notice;
  return (
    <>
      {/* 回告送达情况与各次操作记录独立展示。 */}
      {notice && (
        <div className="feedback-notice-summary" role="status">
          <strong>回告状态</strong>
          <span>{noticeLabels[notice.state]}</span>
          {notice.readAt && <time dateTime={notice.readAt}>已读 {displayTime(notice.readAt)}</time>}
        </div>
      )}
      {detail.activities.length ? (
        <ol className="feedback-entries feedback-history-list" aria-label="操作记录">
          {detail.activities.map((entry) => (
            <li key={entry.version}>
              {/* 操作名称和发生时间构成每条记录的入口。 */}
              <div className="feedback-history-heading">
                <strong>{activityLabels[entry.action]}</strong>
                <time dateTime={entry.occurredAt}>{displayTime(entry.occurredAt)}</time>
              </div>
              {/* 明确区分操作者、接手负责人和自由文本依据。 */}
              <dl className="feedback-history-fields">
                <div>
                  <dt>操作人</dt>
                  <dd>{entry.actor}</dd>
                </div>
                {entry.assignee && (
                  <div>
                    <dt>负责人</dt>
                    <dd>{entry.assignee}</dd>
                  </div>
                )}
                {entry.note && (
                  <div>
                    <dt>{activityNoteLabels[entry.action]}</dt>
                    <dd className="feedback-text">{entry.note}</dd>
                  </div>
                )}
                {entry.conclusion && (
                  <div>
                    <dt>结案结论</dt>
                    <dd className="feedback-text">{entry.conclusion}</dd>
                  </div>
                )}
              </dl>
            </li>
          ))}
        </ol>
      ) : (
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无处理记录" />
      )}
    </>
  );
}

/** 对外显示人工复验实际状态，正文为空时保留分步操作事实。 */
function ResolutionDetails({ detail }: { detail: IssueDetail }) {
  const record = detail.resolution?.result;
  const checked = detail.resolution?.verification;
  return (
    <>
      {record && (
        <section className="feedback-work-section" aria-label="处理结果">
          <h3>处理结果</h3>
          <Descriptions
            size="small"
            column={1}
            items={[
              ...(record.knowledgeBindingId
                ? [
                    {
                      key: 'binding',
                      label: '正式发布版本',
                      children: (
                        <Link
                          to={`/releases/versions/${record.knowledgeBindingId}?source=${detail.issue.sourceId}`}
                        >
                          查看版本
                        </Link>
                      ),
                    },
                  ]
                : [
                    { key: 'impact', label: '修复影响范围', children: impactLabels[record.impact] },
                  ]),
              {
                key: 'summary',
                label: record.impact === 'KNOWLEDGE' ? '补齐的知识' : '修正说明',
                children: <span className="feedback-text">{record.summary ?? '受限'}</span>,
              },
              {
                key: 'check',
                label: record.impact === 'KNOWLEDGE' ? '原问题的覆盖依据' : '复查方式与结果',
                children: <span className="feedback-text">{record.checkMethod ?? '受限'}</span>,
              },
              ...(record.reference
                ? [{ key: 'reference', label: '相关处理记录', children: record.reference }]
                : []),
              ...(record.regression
                ? [{ key: 'regression', label: '相关场景测试', children: record.regression }]
                : []),
              { key: 'time', label: '提交时间', children: displayTime(record.submittedAt) },
            ]}
          />
        </section>
      )}
      {checked && (
        <section className="feedback-work-section" aria-label="人工复验">
          <h3>人工复验</h3>
          {detail.issue.status === 'PENDING_VERIFICATION' && !checked.valid && (
            <Alert type="warning" showIcon title="复验条件已变化，请重新登记" />
          )}
          <Descriptions
            size="small"
            column={1}
            items={[
              {
                key: 'decision',
                label: '复验结论',
                children: checked.decision === 'PASSED' ? '通过' : '未通过',
              },
              { key: 'scope', label: '复验场景', children: checked.testScope ?? '受限' },
              { key: 'expected', label: '期望结果', children: checked.expected ?? '受限' },
              {
                key: 'actual',
                label: '实际结果',
                children: <span className="feedback-text">{checked.actual ?? '受限'}</span>,
              },
              {
                key: 'basis',
                label: '数据与复验依据',
                children: <span className="feedback-text">{checked.dataBasis ?? '受限'}</span>,
              },
              ...(checked.recheckRequestId
                ? [
                    {
                      key: 'request',
                      label: '问数复验',
                      children: (
                        <Link
                          to={`/query?source=${detail.issue.sourceId}&request=${checked.recheckRequestId}`}
                        >
                          查看复验回答
                        </Link>
                      ),
                    },
                  ]
                : []),
            ]}
          />
        </section>
      )}
    </>
  );
}

/** 诊断来自固定服务端分类，详情不输出调用或数据库原始异常。 */
const diagnostics: Record<string, string> = {
  NO_PUBLISHED_KNOWLEDGE: '数据源暂无生效知识',
  INSUFFICIENT_KNOWLEDGE: '当前知识不足以确认查询口径',
  MODEL_CONFIGURATION_UNAVAILABLE: '问数模型配置不可用',
  QUERY_RUNTIME_FAILURE: '问数处理异常',
  QUERY_LEASE_EXPIRED: '处理租约已到期',
  SOURCE_EXECUTION_UNCERTAIN: '源端执行状态待核实',
};
const phaseLabels: Record<string, string> = {
  RETRIEVING: '检索知识',
  GENERATING: '确定查询条件',
  CHECKING: '核对口径和权限',
  EXECUTING: '执行查询',
  FINALIZING: '核对查询结果',
};
const executionLabels: Record<string, string> = {
  NOT_SUBMITTED: '未执行',
  RUNNING: '执行中',
  SUCCEEDED: '已执行',
  UNKNOWN: '待核实',
};

/** 自动来源提供实际失败节点、证据身份和执行状态，便于直接分派。 */
function QueryIncidents({ incidents = [] }: { incidents?: QueryIncident[] }) {
  return (
    <section className="feedback-evidence-section" aria-label="问数诊断">
      <h3>问数诊断</h3>
      <ul className="feedback-entries">
        {incidents.map((incident) => (
          <li key={incident.requestId}>
            <strong>{diagnostics[incident.diagnostic] ?? '问数未完成'}</strong>
            <Descriptions
              size="small"
              column={{ xs: 1, sm: 1, md: 2 }}
              items={[
                {
                  key: 'request',
                  label: '原问数编号',
                  children: <span className="feedback-text">{incident.requestId}</span>,
                },
                { key: 'time', label: '发生时间', children: displayTime(incident.failedAt) },
                { key: 'phase', label: '所在阶段', children: phaseLabels[incident.phase] ?? '—' },
                {
                  key: 'execution',
                  label: '源端执行',
                  children: executionLabels[incident.executionState] ?? '—',
                },
              ]}
            />
          </li>
        ))}
      </ul>
    </section>
  );
}

/** 原问证据区保留历史真实引用，受限、缺失和已知内容分别显示。 */
function IssueEvidence({ detail }: { detail: IssueDetail }) {
  const question = detail.question;
  const automatic = detail.issue.origin.startsWith('QUERY_');
  if (detail.evidenceRestricted || !question) {
    return (
      <>
        <Alert showIcon type="info" title="当前无权查看原问题与业务证据" className="form-alert" />
        {automatic && <QueryIncidents incidents={detail.incidents} />}
      </>
    );
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
      {automatic ? (
        <QueryIncidents incidents={detail.incidents} />
      ) : (
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
      )}
      {(!automatic || question.evidence) && (
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
                label: automatic ? '原问数编号' : '原回答编号',
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
      )}
    </>
  );
}

/** 正式队列详情展示当前责任、办理入口和按权限返回的同一历史证据。 */
export function FeedbackIssuePage() {
  const { id } = useParams();
  const navigate = useNavigate();
  const location = useLocation();
  const cache = useQueryClient();
  const [action, setAction] = useState<'assign' | 'review' | ResolutionAction>();
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
  const resolution = row.resolution;
  const next = resolution?.canResolve
    ? { key: 'resolve' as const, label: '验证结案并回告' }
    : resolution?.canVerify
      ? {
          key: 'verify' as const,
          label: resolution.result?.impact === 'KNOWLEDGE' ? '用原问题复验' : '登记人工复验',
        }
      : resolution?.canSubmitResult
        ? { key: 'result' as const, label: '提交处理结果' }
        : resolution?.canSubmitKnowledge
          ? { key: 'knowledge' as const, label: '提交治理结果' }
          : null;
  const secondary = [
    ...(row.canAssign
      ? [{ key: 'assign' as const, label: issue.assigneeId ? '重新分派' : '分派负责人' }]
      : []),
    ...(row.canReview ? [{ key: 'review' as const, label: '记录核查' }] : []),
    ...(resolution?.canCloseOther ? [{ key: 'explain' as const, label: '说明并结案' }] : []),
  ];
  return (
    <article className="panel feedback-detail" aria-labelledby="feedback-detail-title">
      {/* 页首固定责任和状态，业务正文在各自页签内阅读。 */}
      <header className="feedback-detail-header">
        <div className="feedback-heading">
          <div className="feedback-identity">
            <h2 id="feedback-detail-title" tabIndex={-1}>
              {issue.summary ?? '事项 #' + issue.id}
            </h2>
            {issue.summary && <span className="feedback-meta">事项 #{issue.id}</span>}
          </div>
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
              children: originLabels[issue.origin] ?? issue.origin,
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
                <h3>核查结论</h3>
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
                {resolution?.canSubmitKnowledge && (
                  <section className="feedback-work-section" aria-label="知识治理">
                    <h3>知识治理</h3>
                    <div className="feedback-work-links">
                      <Link to={`/knowledge?source=${issue.sourceId}`}>知识文档</Link>
                      <Link to={`/test-cases?source=${issue.sourceId}`}>标准题单</Link>
                      <Link to={`/releases?source=${issue.sourceId}`}>发布与版本</Link>
                    </div>
                  </section>
                )}
                <ResolutionDetails detail={row} />
              </section>
            ),
          },
          { key: 'evidence', label: '原问题与证据', children: <IssueEvidence detail={row} /> },
          {
            key: 'history',
            label: '记录与回告',
            children: <IssueHistory detail={row} />,
          },
        ]}
      />
      {/* 分派资格与主负责人办理权由详情明确返回，动作始终可从底栏找到。 */}
      <footer className="feedback-detail-footer">
        <Button onClick={back}>返回队列</Button>
        <div className="feedback-detail-actions">
          {next && secondary.length > 1 ? (
            <ActionMenuButton
              placement="topRight"
              menu={{
                items: secondary,
                onClick: ({ key }) => setAction(secondary.find((item) => item.key === key)?.key),
              }}
            >
              更多
            </ActionMenuButton>
          ) : (
            secondary.map((item) => (
              <Button
                key={item.key}
                type={
                  !next && (item.key === 'review' || secondary.length === 1) ? 'primary' : 'default'
                }
                onClick={() => setAction(item.key)}
              >
                {item.label}
              </Button>
            ))
          )}
          {next && (
            <Button type="primary" onClick={() => setAction(next.key)}>
              {next.label}
            </Button>
          )}
        </div>
      </footer>
      {(action === 'assign' || action === 'review') && (
        <FeedbackActionDialog
          kind={action}
          detail={row}
          refresh={refresh}
          close={() => setAction(undefined)}
        />
      )}
      {action && action !== 'assign' && action !== 'review' && (
        <FeedbackResolutionDialog
          kind={action}
          detail={row}
          refresh={refresh}
          close={() => setAction(undefined)}
        />
      )}
    </article>
  );
}
