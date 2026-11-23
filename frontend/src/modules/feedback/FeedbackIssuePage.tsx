import { Alert, Button, Descriptions, Empty, Space, Tabs } from 'antd';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useRef, useState } from 'react';
import { Link, useLocation, useNavigate, useParams } from 'react-router';
import { api } from '../../shared/api';
import { ActionMenuButton } from '../../shared/ActionMenuButton';
import { displayTime, ReadState, Status } from '../../shared/Records';
import { FeedbackActionDialog, type FeedbackAction } from './FeedbackActionDialog';
import { FeedbackLifecycleDialog, type LifecycleAction } from './FeedbackLifecycleDialog';
import {
  FeedbackResolutionDialog,
  impactLabels,
  type ResolutionAction,
} from './FeedbackResolutionDialog';
import {
  closeReasons,
  currentTaskFollowUp,
  directions,
  followUpActions,
  isSupplementPending,
  originLabels,
  statusLabels,
  type Activity,
  type FeedbackTask,
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
  TASK_ADDED: '新增处理任务',
  TASK_ASSIGNED: '重新分派任务',
  TASK_REVIEWED: '记录任务核查',
  TASK_NO_CHANGE: '确认无需变更',
  TASK_CANCELLED: '取消任务',
  TASK_RESTORED: '恢复办理',
  SOURCE_FOLLOW_UP: '记录源数据跟进',
  SOURCE_WAITING: '等待源系统修正或同步',
  TECHNICAL_FOLLOW_UP: '记录技术跟进',
  TECHNICAL_WAITING: '等待修复生效',
  SUPPLEMENT_ASKED: '请求用户补充',
  SUPPLEMENT_ADDED: '收到用户补充',
  SUPPLEMENT_ENDED: '处置补充请求',
  MERGED: '合并到主事项',
  MEMBER_ADDED: '接收重复事项',
  UNMERGED: '纠正合并',
  MEMBER_REMOVED: '解除原事项关联',
  REOPENED: '重开事项',
};

/** 记录正文在不同操作中有不同来源，按当时保存的业务含义标注。 */
const activityNoteLabels: Record<Activity['action'], string> = {
  ASSIGNED: '分派依据',
  REVIEWED: '核查依据',
  RESULT_SUBMITTED: '处理说明',
  VERIFIED: '实际结果',
  RECHECK_FAILED: '实际结果',
  CLOSED: '结案依据',
  TASK_ADDED: '任务范围',
  TASK_ASSIGNED: '转派依据',
  TASK_REVIEWED: '核查依据',
  TASK_NO_CHANGE: '退出依据',
  TASK_CANCELLED: '取消依据',
  TASK_RESTORED: '恢复依据',
  SOURCE_FOLLOW_UP: '已做工作',
  SOURCE_WAITING: '已做工作',
  TECHNICAL_FOLLOW_UP: '已做工作',
  TECHNICAL_WAITING: '已做工作',
  SUPPLEMENT_ASKED: '补充请求',
  SUPPLEMENT_ADDED: '接收结果',
  SUPPLEMENT_ENDED: '处置结果',
  MERGED: '合并依据',
  MEMBER_ADDED: '合并依据',
  UNMERGED: '纠正依据',
  MEMBER_REMOVED: '纠正依据',
  REOPENED: '重开依据',
};

/**
 * 页面以主事项或确切的任务编号打开处理弹窗。
 */
type WorkAction = { kind: FeedbackAction | ResolutionAction; taskId?: number };

/**
 * 任务独立呈现进度，主事项状态仍用于整体复验和结案。
 */
const taskStatusLabels: Record<FeedbackTask['status'], string> = {
  IN_PROGRESS: '在办',
  SUBMITTED: '已提交',
  NO_CHANGE: '无需变更',
  CANCELLED: '已取消',
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
  const rounds = detail.lifecycle?.noticeRounds ?? [];
  const showRounds =
    rounds.length > 1 || rounds.some((round) => round.total > 1) || (!notice && rounds.length > 0);
  return (
    <>
      {/* 回告送达情况与各次操作记录独立展示。 */}
      {notice && !showRounds && (
        <div className="feedback-notice-summary" role="status">
          <strong>回告状态</strong>
          <span>{noticeLabels[notice.state]}</span>
          {notice.readAt && <time dateTime={notice.readAt}>已读 {displayTime(notice.readAt)}</time>}
        </div>
      )}
      {showRounds && (
        <section className="feedback-notice-summary" aria-label="按轮次查看回告">
          {rounds.map((round) => (
            <div key={round.round}>
              <strong>第 {round.round} 轮回告</strong>
              <span>
                送达 {round.delivered}/{round.total}，已读 {round.readCount}
              </span>
              {round.pending > 0 && <span>待投递 {round.pending}</span>}
              {round.restricted > 0 && <span>受限 {round.restricted}</span>}
              {round.suppressed > 0 && <span>无法投递 {round.suppressed}</span>}
            </div>
          ))}
        </section>
      )}
      {detail.activities.length ? (
        <ol className="feedback-entries feedback-history-list" aria-label="操作记录">
          {detail.activities.map((entry) => (
            <li key={entry.version}>
              {/* 操作名称和发生时间构成每条记录的入口。 */}
              <div className="feedback-history-heading">
                <strong>
                  {activityLabels[entry.action]}
                  {entry.taskId != null && ` · 任务 #${entry.taskId}`}
                  {entry.completionRound &&
                    entry.completionRound > 1 &&
                    ` · 第 ${entry.completionRound} 轮`}
                </strong>
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
                {entry.relatedIssueId && (
                  <div>
                    <dt>关联事项</dt>
                    <dd>
                      <Link to={`/feedback/${entry.relatedIssueId}`}>#{entry.relatedIssueId}</Link>
                    </dd>
                  </div>
                )}
                {entry.note && (
                  <div>
                    <dt>{activityNoteLabels[entry.action]}</dt>
                    <dd className="feedback-text">{entry.note}</dd>
                  </div>
                )}
                {/* 任务退出保留当时的影响判断与跟进安排。 */}
                {entry.taskId != null && entry.rootCause && (
                  <div>
                    <dt>
                      {entry.action === 'TASK_REVIEWED'
                        ? '核实根因'
                        : followUpActions.has(entry.action)
                          ? '下一步安排'
                          : '剩余影响及安排'}
                    </dt>
                    <dd className="feedback-text">{entry.rootCause}</dd>
                  </div>
                )}
                {/* 各轮次回显当时的结案分类，后续重开不会覆盖。 */}
                {entry.closureCode && (
                  <div>
                    <dt>结案结果</dt>
                    <dd>
                      {entry.closureCode === 'RESOLVED'
                        ? statusLabels.RESOLVED
                        : closeReasons[entry.closureCode]}
                    </dd>
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

/**
 * 在处理详情显示原问题补充、主从关系和可用的人工动作。
 */
function LifecyclePanel({
  detail,
  search,
  onAction,
}: {
  detail: IssueDetail;
  search: string;
  onAction: (action: LifecycleAction, supplementId?: string) => void;
}) {
  const lifecycle = detail.lifecycle;
  if (!lifecycle) return null;
  const actions: { key: LifecycleAction; label: string; visible: boolean }[] = [
    { key: 'supplement', label: '请求用户补充', visible: lifecycle.supplementTargets.length > 0 },
    { key: 'merge', label: '合并重复事项', visible: lifecycle.canMerge },
    { key: 'unmerge', label: '纠正合并', visible: lifecycle.canUnmerge },
    { key: 'reopen', label: '凭新证据重开', visible: lifecycle.canReopen },
  ];
  return (
    <>
      {/* 关系只提供原事项入口，原始证据由各详情独立核权。 */}
      {(lifecycle.mainIssueId || lifecycle.memberIssueIds.length > 0) && (
        <section className="feedback-work-section" aria-label="合并关联">
          <h3>合并关联</h3>
          {lifecycle.mainIssueId && (
            <Link to={`/feedback/${lifecycle.mainIssueId}${search}`}>
              查看主事项 #{lifecycle.mainIssueId}
            </Link>
          )}
          <Space wrap>
            {lifecycle.memberIssueIds.map((id) => (
              <Link key={id} to={`/feedback/${id}${search}`}>
                原事项 #{id}
              </Link>
            ))}
          </Space>
        </section>
      )}
      {/* 组内补充分条展示，负责人可以结束仍待回复的请求。 */}
      {lifecycle.supplements.length > 0 && (
        <section className="feedback-work-section" aria-label="用户补充">
          <h3>用户补充</h3>
          <ul className="feedback-entries">
            {lifecycle.supplements.map((item) => {
              // 实际回复和人工处置分别标注，未办结请求仍显示等待。
              const status =
                item.disposition === 'WITHDRAWN'
                  ? '已撤回补充请求'
                  : item.disposition === 'UNAVAILABLE'
                    ? '无法取得补充'
                    : item.answeredAt
                      ? '已收到补充'
                      : '等待用户补充';
              const eventAt = item.disposedAt ?? item.answeredAt ?? item.requestedAt;
              return (
                <li key={item.id}>
                  <div className="feedback-task-heading">
                    <div>
                      <strong>{status}</strong>
                      <span className="feedback-meta"> · 原事项 #{item.issueId}</span>
                    </div>
                    {lifecycle.canDisposeSupplement && isSupplementPending(item) && (
                      <Button onClick={() => onAction('dispose-supplement', item.id)}>
                        处置补充请求
                      </Button>
                    )}
                  </div>
                  {item.prompt && <div className="feedback-text">{item.prompt}</div>}
                  {item.answer && <div className="feedback-text">{item.answer}</div>}
                  {item.dispositionReason && (
                    <div className="feedback-text">处置原因：{item.dispositionReason}</div>
                  )}
                  <time dateTime={eventAt}>{displayTime(eventAt)}</time>
                </li>
              );
            })}
          </ul>
        </section>
      )}
      <Space wrap>
        {actions
          .filter((action) => action.visible)
          .map((action) => (
            <Button key={action.key} onClick={() => onAction(action.key)}>
              {action.label}
            </Button>
          ))}
      </Space>
    </>
  );
}

/** 对外显示人工复验实际状态，正文为空时保留分步操作事实。 */
function ResolutionDetails({ detail, task }: { detail: IssueDetail; task?: FeedbackTask }) {
  const resolution = task?.resolution ?? detail.resolution;
  const record = resolution?.result;
  const checked = resolution?.verification;
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
                label:
                  record.impact === 'KNOWLEDGE'
                    ? '补齐的知识'
                    : record.impact === 'SOURCE_DATA'
                      ? '修正的数据范围与内容'
                      : '修正说明',
                children: <span className="feedback-text">{record.summary ?? '受限'}</span>,
              },
              ...(record.impact === 'SOURCE_DATA'
                ? [
                    {
                      key: 'repairer',
                      label: '实际修正人或团队',
                      children: (
                        <span className="feedback-text">{record.sourceRepairer ?? '受限'}</span>
                      ),
                    },
                  ]
                : []),
              {
                key: 'check',
                label:
                  record.impact === 'KNOWLEDGE'
                    ? '原问题的覆盖依据'
                    : record.impact === 'SOURCE_DATA'
                      ? '只读复查方式与实际结果'
                      : '复查方式与结果',
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
              ...(record?.impact === 'SOURCE_DATA'
                ? [
                    {
                      key: 'method',
                      label: '复验方式',
                      children: checked.questionReplay ? '原问题只读问数' : '人工只读核对',
                    },
                  ]
                : []),
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

/**
 * 保留事项页原有分区，每个处理任务独立标注负责人、状态和可执行动作。
 */
function TaskWork({
  detail,
  onAction,
}: {
  detail: IssueDetail;
  onAction: (action: WorkAction) => void;
}) {
  const canAdd = detail.canAssign && Boolean(detail.issue.assigneeId);
  const work = detail.tasks ?? [];
  // 没有任务或分派能力时，单任务页面继续使用原有处理区。
  if (!canAdd && work.length === 0) return null;

  return (
    <section className="feedback-work-section" aria-label="处理任务">
      {/* 主事项负责人确定后可以继续增加处理任务。 */}
      <div className="feedback-task-heading">
        <h3>处理任务</h3>
        {canAdd && <Button onClick={() => onAction({ kind: 'add-task' })}>新增任务</Button>}
      </div>
      {work.length > 0 && (
        <ul className="feedback-entries" aria-label="事项任务">
          {work.map((task) => {
            // 每个任务只展示当前账号可用的动作。
            const resolution = task.resolution;
            const followUp = currentTaskFollowUp(detail.activities, task.id);
            const processLabel = resolution.result
              ? '修订处理结果'
              : followUp
                ? '更新进展'
                : '记录处理进展';
            const actions: { kind: WorkAction['kind']; label: string }[] = [
              ...(resolution.canSaveSourceData
                ? [{ kind: 'source' as const, label: processLabel }]
                : []),
              ...(resolution.canSubmitResult
                ? [{ kind: 'result' as const, label: processLabel }]
                : []),
              ...(task.canReview ? [{ kind: 'review' as const, label: '记录核查' }] : []),
              ...(resolution.canSubmitKnowledge
                ? [
                    {
                      kind: 'knowledge' as const,
                      label: task.status === 'SUBMITTED' ? '修订治理结果' : '提交治理结果',
                    },
                  ]
                : []),
              ...(resolution.canVerify
                ? [
                    {
                      kind: 'verify' as const,
                      label:
                        task.direction === 'KNOWLEDGE'
                          ? '用原问题复验'
                          : task.direction === 'SOURCE_DATA'
                            ? '登记只读复验'
                            : '登记人工复验',
                    },
                  ]
                : []),
              ...(task.canAssign ? [{ kind: 'assign-task' as const, label: '重新分派' }] : []),
              ...(task.canChangeStatus
                ? [
                    {
                      kind: 'task-status' as const,
                      label:
                        task.status === 'IN_PROGRESS' || task.status === 'SUBMITTED'
                          ? '退出任务'
                          : '恢复办理',
                    },
                  ]
                : []),
            ];
            return (
              <li key={task.id} aria-label={`任务 #${task.id} ${directions[task.direction]}`}>
                {/* 任务标题和负责人在收起处理详情时仍可见。 */}
                <div className="feedback-task-heading">
                  <div>
                    <strong>
                      {directions[task.direction]} · 任务 #{task.id}
                    </strong>
                    <div className="feedback-meta">{task.assigneeName}</div>
                  </div>
                  <Status
                    value={task.status}
                    label={
                      task.waitingExternal
                        ? task.direction === 'SOURCE_DATA'
                          ? '等待源系统修正或同步'
                          : '等待修复生效'
                        : task.status === 'SUBMITTED' && resolution.verification?.valid
                          ? '复验通过'
                          : taskStatusLabels[task.status]
                    }
                  />
                </div>
                {task.scope && <div className="feedback-text">{task.scope}</div>}
                {/* 已做工作与下一步并排展示，只使用各自可见的正文。 */}
                {(followUp?.note || followUp?.rootCause) && (
                  <dl className="feedback-task-progress">
                    {followUp.note && (
                      <div>
                        <dt>已做工作</dt>
                        <dd className="feedback-text">{followUp.note}</dd>
                      </div>
                    )}
                    {followUp.rootCause && (
                      <div>
                        <dt>下一步安排</dt>
                        <dd className="feedback-text">{followUp.rootCause}</dd>
                      </div>
                    )}
                  </dl>
                )}
                {actions.length > 0 && (
                  <Space wrap>
                    {actions.map(({ kind, label }) => (
                      <Button key={kind} onClick={() => onAction({ kind, taskId: task.id })}>
                        {label}
                      </Button>
                    ))}
                  </Space>
                )}
                {resolution.canSubmitKnowledge && (
                  <div className="feedback-work-links">
                    <Link to={`/knowledge?source=${detail.issue.sourceId}`}>知识文档</Link>
                    <Link to={`/test-cases?source=${detail.issue.sourceId}`}>标准题单</Link>
                    <Link to={`/releases?source=${detail.issue.sourceId}`}>发布与版本</Link>
                  </div>
                )}
                {/* 已提交的内容按任务独立回看，表单入口继续保留在任务上。 */}
                {(resolution.result || resolution.verification) && (
                  <details className="feedback-sql">
                    <summary>查看处理记录</summary>
                    <ResolutionDetails detail={detail} task={task} />
                  </details>
                )}
              </li>
            );
          })}
        </ul>
      )}
    </section>
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
  const [action, setAction] = useState<WorkAction>();
  const [lifecycleAction, setLifecycleAction] = useState<{
    kind: LifecycleAction;
    supplementId?: string;
  }>();
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
  const selectedTask = row.tasks?.find((task) => task.id === action?.taskId);
  const actionKind = action?.kind;
  const next = resolution?.canResolve
    ? { key: 'resolve' as const, label: '验证结案并回告' }
    : resolution?.canVerify
      ? {
          key: 'verify' as const,
          label: resolution.result?.impact === 'KNOWLEDGE' ? '用原问题复验' : '登记人工复验',
        }
      : resolution?.canSubmitResult
        ? { key: 'result' as const, label: resolution.result ? '修订处理结果' : '记录处理进展' }
        : resolution?.canSubmitKnowledge
          ? { key: 'knowledge' as const, label: '提交治理结果' }
          : null;
  const secondary = [
    ...(row.canAssign
      ? [{ key: 'assign' as const, label: issue.assigneeId ? '重新分派' : '分派负责人' }]
      : []),
    ...(row.canReview ? [{ key: 'review' as const, label: '记录核查' }] : []),
    ...(resolution?.canSubmitResult && next?.key !== 'result'
      ? [{ key: 'result' as const, label: '修订处理结果' }]
      : []),
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
                <LifecyclePanel
                  detail={row}
                  search={location.search}
                  onAction={(kind, supplementId) => setLifecycleAction({ kind, supplementId })}
                />
                <TaskWork detail={row} onAction={setAction} />
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
                onClick: ({ key }) => {
                  // 菜单仅使用当前可见的事项操作。
                  const item = secondary.find((entry) => entry.key === key);
                  if (item) setAction({ kind: item.key });
                },
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
                onClick={() => setAction({ kind: item.key })}
              >
                {item.label}
              </Button>
            ))
          )}
          {next && (
            <Button type="primary" onClick={() => setAction({ kind: next.key })}>
              {next.label}
            </Button>
          )}
        </div>
      </footer>
      {/* 事项关系与补充弹窗独立承接原页面上下文。 */}
      {lifecycleAction && (
        <FeedbackLifecycleDialog
          kind={lifecycleAction.kind}
          supplementId={lifecycleAction.supplementId}
          detail={row}
          refresh={refresh}
          close={() => setLifecycleAction(undefined)}
        />
      )}
      {/* 模态表单保留主事项版本，任务动作额外提交确切的任务编号。 */}
      {actionKind &&
        (actionKind === 'assign' ||
        actionKind === 'review' ||
        actionKind === 'add-task' ||
        actionKind === 'assign-task' ||
        actionKind === 'task-status' ? (
          <FeedbackActionDialog
            kind={actionKind}
            detail={row}
            task={selectedTask}
            refresh={refresh}
            close={() => setAction(undefined)}
          />
        ) : (
          // 证据失权后清掉弹窗内此前载入的受限正文。
          <FeedbackResolutionDialog
            key={String(row.evidenceRestricted)}
            kind={actionKind}
            detail={row}
            task={selectedTask}
            refresh={refresh}
            close={() => setAction(undefined)}
          />
        ))}
    </article>
  );
}
