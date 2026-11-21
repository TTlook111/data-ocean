import { Alert, Button, Collapse, Form, Grid, Input, Modal, Steps, Table, Tag } from 'antd';
import { keepPreviousData, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useRef, useState } from 'react';
import { useLocation, useNavigate, useParams, useSearchParams } from 'react-router';
import { api, queryString } from '../../shared/api';
import { displayTime, ReadState } from '../../shared/Records';
import { allowed, useSession } from '../../shared/session';
import { ReleaseScopeAdjustment } from './ReleaseScopeAdjustment';
import {
  PublicationResult,
  PublishReleaseDialog,
  type PublicationOutcome,
} from './ReleasePublicationPanel';
import {
  ValidationQuestionDialog,
  ValidationReport,
  ValidationSummary,
  type ValidationDetail,
  type ValidationQuestion,
  type ValidationRun,
} from './ReleaseValidationPanel';
import {
  releaseStates,
  type ReleaseRevisionReturn,
  type ReleaseTask,
  type ReleaseTestReturn,
} from './types';
import { useReleaseCommand } from './useReleaseCommand';
import './knowledge.css';

/** 实际构建记录，即使候选之后再次构建也保留。 */
type Build = {
  /** 索引代次和工作者共同标识。 */
  id: string;
  /** 当前执行状态。 */
  state: 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELED';
  /** 可披露的稳定失败分类。 */
  failureCode: string | null;
  /** Strong 回读的实际片段条数。 */
  verifiedChunks: number | null;
  /** 已核验模型输出维数。 */
  vectorDimension: number | null;
  /** 任务接收时间。 */
  createdAt: string;
};

/** 任务详情提供准备状态、可选候选和已保存构建历史。 */
type BuildDetail = {
  /** 构建所属数据源。 */
  sourceName: string;
  /** 可读确认修订，从一开始。 */
  documentRevisionNumber: number;
  /** 原基础生效绑定对应的可读修订。 */
  baseRevisionNumber: number | null;
  /** 稳定任务编号、可选候选和业务阶段。 */
  task: ReleaseTask;
  /** 准备阶段的实时题数，冻结后显示候选固定的题数。 */
  reviewedTests: number;
  /** 首次构建使用的最新预检摘要。 */
  inputFingerprint: string | null;
  /** 首次构建前需要处理的问题。 */
  issues: string[];
  /** 当前候选从最近一轮开始的构建状态。 */
  builds: Build[];
  /** 已被替换的候选及其构建记录。 */
  history: HistoricalCandidate[];
};

/**
 * 已归档候选必须以原文档、修订及构建快照回看。
 */
type HistoricalCandidate = {
  id: string;
  title: string;
  documentId: string;
  documentRevisionId: string;
  documentRevisionNumber: number;
  status: ReleaseTask['status'];
  builds: Build[];
};

/** 执行状态和可披露失败分类沿用业务术语。 */
const buildStates: Record<Build['state'], string> = {
  QUEUED: '等待构建',
  RUNNING: '构建中',
  SUCCEEDED: '构建完成',
  FAILED: '构建失败',
  CANCELED: '已取消',
};

/** 故障说明贴近可以继续办理的操作。 */
const failureReasons: Record<string, string> = {
  MODEL_AUTH: '向量服务鉴权失败，请检查模型配置。',
  MODEL_RATE_LIMIT: '向量服务暂时限流，请稍后重新构建。',
  MODEL_FORMAT: '向量服务返回不完整或维数不一致，请检查模型配置。',
  MODEL_RESULT_UNKNOWN: '未取得可核实的向量服务响应。',
  INDEX_UNAVAILABLE: 'Milvus 暂不可用，请恢复连接后重新构建。',
  INDEX_READBACK_MISMATCH: '索引回读与固定片段或治理依据不一致。',
  INPUT_STALE: '候选依据或当前权限已变化，请返回重新核对。',
  BUILD_INTERRUPTED: '工作者执行中断，原尝试结果无法确认。',
  BUILD_TIMEOUT: '本次构建超过等待时限。',
};

/** 发布任务独立详情承接原型的阶段、范围、记录和底部操作。 */
export function KnowledgeReleaseBuildPage() {
  const { id } = useParams();
  const [params] = useSearchParams();
  const location = useLocation();
  const navigate = useNavigate();
  const source = params.get('source');
  const path = `/knowledge/sources/${source}/releases/${id}`;
  const [connected, setConnected] = useState<string>();
  const detail = useQuery({
    queryKey: ['release-build', source, id],
    enabled: Boolean(source && id),
    queryFn: () => api<BuildDetail>(path),
    refetchOnMount: 'always',
    refetchInterval: (query) => {
      // 构建使用事件回读，逐题验证使用同一任务的有界轮询。
      const stage = query.state.data?.task.status;
      return stage === 'VALIDATING' || (stage === 'BUILDING' && connected !== path) ? 2500 : false;
    },
  });
  const active = detail.data?.task.status === 'BUILDING' && !detail.error;
  const refetch = detail.refetch;
  useEffect(() => {
    // 进度事件只触发授权详情回读。断线不重新提交原任务。
    if (!active || typeof EventSource === 'undefined') return;
    const stream = new EventSource('/api/v1' + path + '/events');
    stream.addEventListener('knowledge-build.updated', () => {
      setConnected(path);
      void refetch();
    });
    stream.onerror = () => {
      // 由同一任务的查询快照承接丢失的阶段，终态则停止轮询。
      setConnected(undefined);
      stream.close();
      void refetch();
    };
    return () => stream.close();
  }, [active, path, refetch]);
  const focus = (location.state as { releaseFocus?: string } | null)?.releaseFocus;
  const restoredFocus = useRef<string>(undefined);
  useEffect(() => {
    // 等待回到任务后的新状态，再恢复到原链接或下一步。
    if (detail.isSuccess && !detail.isFetching && focus && restoredFocus.current !== location.key) {
      restoredFocus.current = location.key;
      (document.getElementById(focus) ?? document.getElementById('release-task-heading'))?.focus();
    }
  }, [detail.isSuccess, detail.isFetching, focus, location.key]);

  /** 返回后恢复原列表范围、页码和触发位置。 */
  function back() {
    navigate(
      '/releases?' +
        queryString({
          source,
          documentId: params.get('documentId'),
          keyword: params.get('keyword'),
          status: params.get('status'),
          pageNum: params.get('pageNum'),
          pageSize: params.get('pageSize'),
        }),
      { state: { releaseFocus: 'release-build-' + id } },
    );
  }

  /** 阅读原确认修订，返回目标仍然是当前任务。 */
  function revision(documentId: string, documentRevisionId: string, focus: string) {
    const releaseReturn: ReleaseRevisionReturn = {
      search: location.search,
      focus,
      taskId: id,
    };
    navigate(
      '/knowledge/' + documentId + '?' + queryString({ source, revisionId: documentRevisionId }),
      { state: { releaseReturn } },
    );
  }

  /**
   * 将补题限制在该任务的文档，完成后恢复原任务和列表查询。
   */
  function openTests(row: BuildDetail) {
    const { id, documentId, documentRevisionId, title } = row.task;
    const releaseTestReturn: ReleaseTestReturn = {
      taskId: id,
      search: location.search,
      document: { id: documentId, title, revisionId: documentRevisionId },
    };
    navigate('/test-cases?' + queryString({ source, documentId }), {
      state: { releaseTestReturn },
    });
  }

  return (
    <main className="record-page" aria-label="发布任务详情">
      {!source ? (
        <Alert
          type="warning"
          title="请从发布任务列表选择数据源"
          action={<Button onClick={back}>返回列表</Button>}
        />
      ) : (
        <ReadState
          loading={detail.isPending}
          error={detail.error}
          retry={() => void detail.refetch()}
        >
          {detail.data && id && (
            <BuildWorkspace
              key={`${source}:${id}`}
              source={source}
              path={path}
              data={detail.data}
              back={back}
              openRevision={revision}
              openTests={() => openTests(detail.data!)}
              focus={focus}
              checking={detail.isFetching}
              refresh={async () => {
                // 只有实际回读成功，范围核对才可以采纳新的任务版本。
                const result = await detail.refetch();
                return result.isSuccess ? result.data.task.version : undefined;
              }}
            />
          )}
        </ReadState>
      )}
    </main>
  );
}

/** 范围、固定修订和构建记录共用同一任务快照。 */
function BuildWorkspace({
  source,
  path,
  data,
  back,
  openRevision,
  openTests,
  focus,
  checking,
  refresh,
}: {
  /** 当前任务已经核对的来源。 */
  source: string;
  /** 原任务的实际 API 路径。 */
  path: string;
  /** 构建详情的持久快照。 */
  data: BuildDetail;
  /** 返回原发布任务列表。 */
  back: () => void;
  /** 阅读任务选择的确认正文。 */
  openRevision: (documentId: string, revisionId: string, focusId: string) => void;
  /** 打开本批关联的标准题单。 */
  openTests: () => void;
  /** 旧修订返回时恢复展开的候选及焦点。 */
  focus?: string;
  /** 正在重新检查首建输入，避免从缓存直接提交。 */
  checking: boolean;
  /** 回读候选并返回实际任务版本，失败时保留原版本。 */
  refresh: () => Promise<number | undefined>;
}) {
  const session = useSession();
  const viewport = Grid.useBreakpoint();
  const cache = useQueryClient();
  const navigate = useNavigate();
  const row = data.task;
  const latest = data.builds[0];
  const [cancelOpen, setCancelOpen] = useState(false);
  const [adjustOpen, setAdjustOpen] = useState(false);
  const [publishOpen, setPublishOpen] = useState(false);
  const [selectedRun, selectRun] = useState<string>();
  const [questionDialog, openQuestion] = useState<{
    row: ValidationQuestion;
    run: ValidationRun;
    judge: boolean;
    trigger?: string;
  }>();
  const canBuild = allowed(session, 'releases.build');
  const publishRole = allowed(session, 'releases.publish');
  const awaitingResult = row.status === 'PUBLISH_RECONCILE';
  const released = row.status === 'PUBLISHED';
  const publicationPhase = awaitingResult || released;
  const canPublish =
    publishRole && row.status === 'READY_TO_PUBLISH' && allowed(session, 'knowledge.view');
  const canBuildWork = canBuild && !publicationPhase;
  const canTest = allowed(session, 'releases.test');
  const canReadKnowledge = allowed(session, 'knowledge.view');
  const canJudge = allowed(session, 'test-cases.judge') && canReadKnowledge;
  const needsBuild = row.status === 'PREPARED' || row.status === 'BUILD_FAILED';
  const fingerprint = row.fingerprint ?? data.inputFingerprint;
  const needsTests = data.issues.some((issue) => issue.startsWith('缺少已审核'));
  const canPrepareTests = needsTests && allowed(session, 'test-cases.view');
  const failed = row.status === 'BUILD_FAILED';
  const stage =
    row.status === 'BUILDING' ? buildStates[latest?.state ?? 'QUEUED'] : releaseStates[row.status];
  const publication = useQuery({
    queryKey: ['release-publication', source, row.id, row.version],
    enabled: publicationPhase,
    queryFn: () => api<PublicationOutcome>(path + '/publication'),
  });
  const activeVersion = publication.data?.active;
  const canReconcile = awaitingResult && publishRole && publication.data?.canReconcile;

  const validationPath = path + '/validations';
  const hasValidationArea =
    (!!row.candidateId && !['PREPARED', 'BUILDING', 'BUILD_FAILED'].includes(row.status)) ||
    data.history.length > 0;
  const validation = useQuery({
    queryKey: ['release-validation', source, row.id, row.version, selectedRun],
    enabled: hasValidationArea,
    placeholderData: keepPreviousData,
    queryFn: () =>
      api<ValidationDetail>(validationPath + '?' + queryString({ runId: selectedRun })),
  });
  const report = validation.data;
  const currentRun = report?.history.find((item) => item.candidateId === row.candidateId)?.id;
  const actingOnCurrent = !selectedRun || selectedRun === currentRun;
  const activeQuestions =
    actingOnCurrent && !validation.isPlaceholderData && report?.run?.id === currentRun
      ? (report?.questions ?? [])
      : [];
  const pendingAnswers = activeQuestions.filter(
    (item) => item.state === 'PENDING_JUDGMENT' && !item.judgment,
  );
  const failedAnswer = activeQuestions.find(
    (item) =>
      item.judgment?.decision === 'FAILED' ||
      ['FAILED', 'EXECUTION_ERROR', 'CONDITIONS_INVALID'].includes(item.state),
  );
  const testing = row.status === 'VALIDATING';
  const canRestart =
    canTest &&
    latest?.state === 'SUCCEEDED' &&
    [
      'READY_FOR_VALIDATION',
      'AWAITING_JUDGMENT',
      'VALIDATION_FAILED',
      'VALIDATION_ERROR',
      'READY_TO_PUBLISH',
    ].includes(row.status);

  /**
   * 验证写入后同步回读任务版本、逐题结果及原列表。
   */
  async function refreshValidation() {
    await Promise.all([
      cache.invalidateQueries({ queryKey: ['release-build', source, row.id] }),
      cache.invalidateQueries({ queryKey: ['release-validation', source, row.id] }),
      cache.invalidateQueries({ queryKey: ['release-tasks', source] }),
    ]);
  }

  /**
   * 发布写入只回读实际结果及当前生效列表，不能由回包猜测阶段。
   */
  async function refreshPublication() {
    await Promise.all([
      refreshValidation(),
      cache.invalidateQueries({ queryKey: ['release-publication', source, row.id] }),
      cache.invalidateQueries({ queryKey: ['release-bindings', source] }),
      cache.invalidateQueries({ queryKey: ['release-binding-detail'] }),
    ]);
  }

  /**
   * 确认弹窗关闭后归还操作焦点。
   */
  function closePublish() {
    setPublishOpen(false);
    requestAnimationFrame(() => {
      (
        document.getElementById('release-publish-action') ??
        document.getElementById('release-task-heading')
      )?.focus();
    });
  }

  const reconcile = useReleaseCommand({
    key: 'dataocean:reconcile-publish:' + session.userId + ':' + source + ':' + row.id,
    path: path + '/reconciliations',
    operationCode: 'releases.publish',
    parse: (payload) => {
      // 核实结果也只恢复原版本请求。
      const value = payload as Record<string, unknown>;
      return typeof value.expectedVersion === 'number'
        ? { expectedVersion: value.expectedVersion }
        : undefined;
    },
    committed: refreshPublication,
    conflict: () => void refreshPublication(),
  });

  /**
   * 锁定实际浏览的运行和题目，历史选择不允许写入。
   */
  function showQuestion(item: ValidationQuestion, judge: boolean) {
    if (report?.run) {
      openQuestion({ row: item, run: report.run, judge, trigger: document.activeElement?.id });
    }
  }

  /**
   * 返回原结果按钮，办理后消失的主按钮转回任务标题。
   */
  function closeQuestion() {
    const trigger = questionDialog?.trigger;
    openQuestion(undefined);
    requestAnimationFrame(() => {
      (
        (trigger && document.getElementById(trigger)) ||
        document.getElementById('release-task-heading')
      )?.focus();
    });
  }

  const testCommand = useReleaseCommand({
    key: 'dataocean:validate-release:' + session.userId + ':' + source + ':' + row.id,
    path: validationPath,
    operationCode: 'releases.test',
    parse: (body) => {
      // 网络重试仍用原构建和版本。
      const value = body as Record<string, unknown>;
      return typeof value.expectedVersion === 'number' && typeof value.buildId === 'string'
        ? { expectedVersion: value.expectedVersion, buildId: value.buildId }
        : undefined;
    },
    committed: async () => {
      selectRun(undefined);
      await refreshValidation();
    },
    conflict: () => void refreshValidation(),
  });
  const command = useReleaseCommand({
    key: `dataocean:build-release:${session.userId}:${source}:${row.id}`,
    path: path + '/builds',
    parse: (input) => {
      // 恢复时使用打开提交时的并发版本，而不是当前页面的新版本。
      const value = input as Record<string, unknown>;
      return typeof value.expectedVersion === 'number' && typeof value.fingerprint === 'string'
        ? { expectedVersion: value.expectedVersion, fingerprint: value.fingerprint }
        : undefined;
    },
    committed: async () => {
      await Promise.all([
        cache.invalidateQueries({ queryKey: ['release-build', source, row.id] }),
        cache.invalidateQueries({ queryKey: ['release-tasks', source] }),
      ]);
    },
    conflict: refresh,
  });
  const recovering = canBuild && command.pending;
  const buildLabel = failed ? '重新构建候选' : '构建候选';
  const description = latest?.failureCode
    ? (failureReasons[latest.failureCode] ?? '本次构建未完成。')
    : null;

  /**
   * 对话框卸载后归还键盘焦点，已变更阶段时回到任务标题。
   */
  function closeAdjustment() {
    setAdjustOpen(false);
    requestAnimationFrame(() => {
      (
        document.getElementById('release-adjust-scope') ??
        document.getElementById('release-task-heading')
      )?.focus();
    });
  }

  return (
    <article className="panel knowledge-build-workspace" aria-label="发布任务">
      {/* 页头显示稳定任务编号、所属源和当前阶段。 */}
      <header className="knowledge-build-heading">
        <div className="knowledge-build-identity">
          <h1 id="release-task-heading" tabIndex={-1} title={row.title}>
            {row.id}
          </h1>
          <span>{data.sourceName}</span>
        </div>
        <div className="knowledge-build-header-actions">
          <Button onClick={back}>返回发布与版本</Button>
          <Tag color={failed ? 'error' : row.status === 'BUILDING' ? 'processing' : undefined}>
            {stage}
          </Tag>
        </div>
      </header>
      <div className="knowledge-build-content">
        {/* 原型的阶段顺序保持完整，索引完成后定位到相关验证。 */}
        <Steps
          className="knowledge-build-stages"
          titlePlacement={viewport.lg ? 'horizontal' : 'vertical'}
          size="small"
          current={
            released
              ? 5
              : awaitingResult
                ? 4
                : row.status === 'READY_TO_PUBLISH'
                  ? 3
                  : [
                        'READY_FOR_VALIDATION',
                        'VALIDATING',
                        'AWAITING_JUDGMENT',
                        'VALIDATION_FAILED',
                        'VALIDATION_ERROR',
                      ].includes(row.status)
                    ? 2
                    : !row.candidateId || row.status === 'PREPARED'
                      ? 0
                      : 1
          }
          status={
            failed || ['VALIDATION_FAILED', 'VALIDATION_ERROR'].includes(row.status)
              ? 'error'
              : undefined
          }
          items={['范围核对', '候选构建', '相关验证', '发布检查', '切换完成'].map((title) => ({
            title,
          }))}
        />
        {publicationPhase && (
          <ReadState
            loading={publication.isPending}
            error={publication.error}
            retry={() => void publication.refetch()}
          >
            {publication.data && <PublicationResult outcome={publication.data} />}
          </ReadState>
        )}
        {!publicationPhase && report && actingOnCurrent && <ValidationSummary data={report} />}
        {!currentRun && !publicationPhase && (
          <section className="knowledge-build-readiness" aria-label="发布准备">
            <div>
              <h2>发布准备</h2>
              {row.status !== 'CANCELED' || row.candidateId ? (
                <span className="muted">相关标准题已审核 {data.reviewedTests} 道</span>
              ) : null}
            </div>
            {row.candidateId ? (
              <Tag color="success">审核题单已固定</Tag>
            ) : row.status === 'PREPARED' ? (
              <Tag color={fingerprint ? 'success' : 'warning'}>
                {checking ? '核对中' : fingerprint ? '可构建' : '待处理'}
              </Tag>
            ) : null}
          </section>
        )}
        {/* 阻断贴近范围核对，补题主操作在固定底栏。 */}
        {data.issues.length > 0 && <Alert type="warning" showIcon title={data.issues.join('；')} />}
        <section className="knowledge-build-section" aria-label="本批文档与修订">
          <h2>本批文档与修订</h2>
          <Table<ReleaseTask>
            size="middle"
            rowKey="id"
            pagination={false}
            scroll={{ x: 530 }}
            dataSource={[row]}
            columns={[
              { title: '文档', dataIndex: 'title', ellipsis: true },
              {
                title: '发布前',
                width: 94,
                render: () =>
                  data.baseRevisionNumber
                    ? 'r' + data.baseRevisionNumber
                    : row.baseBindingId
                      ? '待核对'
                      : '未发布',
              },
              { title: '目标修订', width: 100, render: () => 'r' + data.documentRevisionNumber },
              { title: '纳入依据', width: 102, render: () => '本次选择' },
              {
                title: '操作',
                width: 116,
                render: () =>
                  allowed(session, 'knowledge.view') ? (
                    <Button
                      id="build-document-revision"
                      type="link"
                      className="knowledge-record-link"
                      onClick={() =>
                        openRevision(
                          row.documentId,
                          row.documentRevisionId,
                          'build-document-revision',
                        )
                      }
                    >
                      查看文档
                    </Button>
                  ) : (
                    '—'
                  ),
              },
            ]}
          />
          {row.candidateId && <p className="muted">固定候选 {row.candidateId}</p>}
        </section>
        {hasValidationArea && (
          <ReadState
            loading={validation.isPending}
            error={validation.error}
            retry={() => void validation.refetch()}
          >
            {report && (
              <ValidationReport
                data={report}
                activeRun={currentRun}
                selected={selectedRun}
                select={selectRun}
                open={showQuestion}
                canJudge={
                  canJudge &&
                  row.status !== 'CANCELED' &&
                  !testing &&
                  actingOnCurrent &&
                  !validation.isPlaceholderData
                }
              />
            )}
          </ReadState>
        )}
        {/* 历史固定输入独立回看，调整后立即可见。 */}
        {data.history.length > 0 && (
          <section className="knowledge-build-section" aria-label="历史候选">
            <h2>历史候选</h2>
            <Collapse
              defaultActiveKey={
                focus?.startsWith('release-history-document-')
                  ? [focus.slice('release-history-document-'.length)]
                  : undefined
              }
              items={data.history.map((previous) => {
                // 展开时按旧候选身份阅读原修订及实际构建。
                const trigger = 'release-history-document-' + previous.id;
                return {
                  key: previous.id,
                  label: (
                    <div className="knowledge-candidate-identity">
                      <strong>
                        {previous.title} · r{previous.documentRevisionNumber}
                      </strong>
                      <small className="muted">
                        {previous.id} · {releaseStates[previous.status]}
                      </small>
                    </div>
                  ),
                  children: (
                    <div className="knowledge-build-section">
                      {allowed(session, 'knowledge.view') && (
                        <div>
                          <Button
                            id={trigger}
                            type="link"
                            className="knowledge-record-link"
                            onClick={() =>
                              openRevision(
                                previous.documentId,
                                previous.documentRevisionId,
                                trigger,
                              )
                            }
                          >
                            查看原修订
                          </Button>
                        </div>
                      )}
                      {previous.builds.length > 0 && <BuildTable builds={previous.builds} />}
                    </div>
                  ),
                };
              })}
            />
          </section>
        )}
        {/* 只展示真实构建结果，后续验证保持待办阶段。 */}
        <section className="knowledge-build-section" aria-label="处理记录">
          <h2>处理记录</h2>
          <p className="knowledge-build-created">创建发布任务 · {displayTime(row.createdAt)}</p>
          {latest && (
            <>
              {description && <Alert type="error" title={description} showIcon />}
              <BuildTable builds={data.builds} />
            </>
          )}
        </section>
        {canBuild && command.note && (
          <Alert type={recovering ? 'warning' : 'error'} title={command.note} showIcon />
        )}
        {canTest && testCommand.note && (
          <Alert
            type={testCommand.pending ? 'warning' : 'error'}
            title={testCommand.note}
            showIcon
          />
        )}
        {awaitingResult && reconcile.note && (
          <Alert type={reconcile.pending ? 'warning' : 'error'} title={reconcile.note} showIcon />
        )}
      </div>
      {/* 原型把本阶段唯一主操作固定在办理区域底部。 */}
      {(canBuildWork ||
        canPublish ||
        canReconcile ||
        (released && !!activeVersion) ||
        canPrepareTests ||
        (canTest && (testCommand.pending || canRestart)) ||
        (canJudge && pendingAnswers.length > 0) ||
        !!failedAnswer) &&
        row.status !== 'CANCELED' && (
          <footer className="knowledge-actions knowledge-build-actions">
            {canBuildWork && (
              <Button
                danger
                type="text"
                disabled={Boolean(recovering) || command.busy}
                onClick={() => setCancelOpen(true)}
              >
                取消任务
              </Button>
            )}
            {canBuildWork && row.status !== 'BUILDING' && !testing && (
              <Button
                id="release-adjust-scope"
                disabled={Boolean(recovering) || command.busy}
                onClick={() => setAdjustOpen(true)}
              >
                调整范围
              </Button>
            )}
            {canPublish && (
              <Button
                id="release-publish-action"
                type="primary"
                disabled={checking}
                onClick={() => setPublishOpen(true)}
              >
                确认发布
              </Button>
            )}
            {canReconcile && (
              <Button
                type="primary"
                loading={reconcile.busy}
                disabled={checking && !reconcile.pending}
                onClick={() => {
                  if (reconcile.pending) reconcile.resolve();
                  else reconcile.submit({ expectedVersion: row.version });
                }}
              >
                {reconcile.pending ? reconcile.action : '核实实际生效入口'}
              </Button>
            )}
            {released && activeVersion && (
              <Button
                type="primary"
                onClick={() =>
                  navigate(
                    '/releases?' +
                      queryString({
                        source,
                        tab: 'active',
                        bindingId: activeVersion.id,
                      }),
                  )
                }
              >
                查看当前生效
              </Button>
            )}
            {canTest && (testCommand.pending || canRestart) && (
              <Button
                type={
                  !failedAnswer && !pendingAnswers.length && row.status === 'READY_FOR_VALIDATION'
                    ? 'primary'
                    : 'default'
                }
                loading={testCommand.busy}
                disabled={!testCommand.pending && checking}
                onClick={() => {
                  if (testCommand.pending) testCommand.resolve();
                  else if (latest)
                    testCommand.submit({ buildId: latest.id, expectedVersion: row.version });
                }}
              >
                {testCommand.pending
                  ? testCommand.action
                  : currentRun
                    ? '重新运行验证'
                    : '运行验证'}
              </Button>
            )}
            {failedAnswer ? (
              <Button
                id="release-validation-failure"
                type="primary"
                onClick={() => showQuestion(failedAnswer, false)}
              >
                {row.status === 'VALIDATION_ERROR' ? '查看异常题' : '查看未通过题'}
              </Button>
            ) : (
              canJudge &&
              pendingAnswers.length > 0 && (
                <Button
                  id="release-validation-judge"
                  type="primary"
                  onClick={() => showQuestion(pendingAnswers[0], true)}
                >
                  处理 {pendingAnswers.length} 道待裁定题
                </Button>
              )
            )}
            {recovering ? (
              <Button
                type="primary"
                loading={command.busy}
                aria-label={command.action}
                onClick={command.resolve}
              >
                {command.action}
              </Button>
            ) : canPrepareTests ? (
              <Button id="release-prepare-tests" type="primary" onClick={openTests}>
                准备相关题单
              </Button>
            ) : (
              canBuild &&
              needsBuild && (
                <Button
                  type="primary"
                  loading={command.busy}
                  disabled={!fingerprint || checking}
                  onClick={() =>
                    fingerprint && command.submit({ expectedVersion: row.version, fingerprint })
                  }
                >
                  {buildLabel}
                </Button>
              )
            )}
          </footer>
        )}
      {cancelOpen && (
        <CancelTask
          source={source}
          path={path}
          row={row}
          onClose={() => setCancelOpen(false)}
          refresh={refresh}
        />
      )}
      {adjustOpen && (
        <ReleaseScopeAdjustment
          source={source}
          path={path}
          task={row}
          revision={data.documentRevisionNumber}
          checking={checking}
          onClose={closeAdjustment}
          refresh={refresh}
        />
      )}
      {publishOpen && (
        <PublishReleaseDialog
          source={source}
          id={row.id}
          path={path}
          version={row.version}
          ready={canPublish}
          close={closePublish}
          refresh={refreshPublication}
        />
      )}
      {questionDialog && report?.canReadResults && (
        <ValidationQuestionDialog
          key={questionDialog.run.id + ':' + questionDialog.row.id}
          row={questionDialog.row}
          run={questionDialog.run}
          judge={questionDialog.judge && canJudge}
          canReadEvidence={report.canReadEvidence && canReadKnowledge}
          path={validationPath}
          version={row.version}
          close={closeQuestion}
          refresh={refreshValidation}
        />
      )}
    </article>
  );
}

/**
 * 当前候选与旧候选按相同列位展示实际构建代次。
 */
function BuildTable({ builds }: { builds: Build[] }) {
  return (
    <Table<Build>
      size="middle"
      rowKey="id"
      pagination={false}
      scroll={{ x: 550 }}
      dataSource={builds}
      columns={[
        { title: '构建代次', dataIndex: 'id', ellipsis: true },
        { title: '状态', width: 105, render: (_, build) => buildStates[build.state] },
        { title: '核验片段', width: 100, render: (_, build) => build.verifiedChunks ?? '—' },
        { title: '提交时间', width: 155, render: (_, build) => displayTime(build.createdAt) },
      ]}
    />
  );
}

/** 取消未生效任务需要填写原因，回包不明仍可核实原取消命令。 */
function CancelTask({
  source,
  path,
  row,
  onClose,
  refresh,
}: {
  /** 当前任务所属源。 */
  source: string;
  /** 发布任务 API 路径。 */
  path: string;
  /** 取消时必须符合打开弹窗的当前任务版本。 */
  row: ReleaseTask;
  /** 返回原任务。 */
  onClose: () => void;
  /** 关闭后按实际后台状态回读。 */
  refresh: () => void;
}) {
  const [baseline] = useState(row);
  const [form] = Form.useForm<{ reason: string }>();
  const cache = useQueryClient();
  const session = useSession();
  const command = useReleaseCommand({
    key: `dataocean:cancel-release:${session.userId}:${source}:${baseline.id}`,
    path: path + '/cancellations',
    parse: (body) => {
      // 已知取消依据用于核实原命令，不会替换为新版本。
      const value = body as Record<string, unknown>;
      return typeof value.reason === 'string' &&
        typeof value.expectedVersion === 'number' &&
        (value.fingerprint === null || typeof value.fingerprint === 'string')
        ? {
            reason: value.reason,
            expectedVersion: value.expectedVersion,
            fingerprint: value.fingerprint,
          }
        : undefined;
    },
    committed: async () => {
      await Promise.all([
        cache.invalidateQueries({ queryKey: ['release-build', source, baseline.id] }),
        cache.invalidateQueries({ queryKey: ['release-tasks', source] }),
      ]);
      onClose();
    },
    conflict: refresh,
  });
  return (
    <Modal
      open
      centered
      title="取消发布任务"
      okText={command.pending ? command.action : '确认取消'}
      cancelText="继续办理"
      okButtonProps={{ danger: true }}
      confirmLoading={command.busy}
      onCancel={() => {
        if (!command.busy) onClose();
      }}
      onOk={() => {
        if (command.pending) command.resolve();
        else form.submit();
      }}
    >
      {/* 取消的后果和原因只在实际操作时出现。 */}
      <Alert type="warning" showIcon title="此任务将停止办理，当前生效版本保持使用。" />
      {command.note && (
        <Alert type={command.pending ? 'warning' : 'error'} title={command.note} showIcon />
      )}
      <Form
        form={form}
        layout="vertical"
        disabled={Boolean(command.pending) || command.busy}
        initialValues={{ reason: command.pending?.body.reason }}
        onFinish={({ reason }) =>
          command.submit({
            reason: reason.trim(),
            expectedVersion: baseline.version,
            fingerprint: baseline.fingerprint,
          })
        }
      >
        <Form.Item
          name="reason"
          label="取消原因"
          rules={[{ required: true, whitespace: true, message: '请填写取消原因' }]}
          className="knowledge-cancel-reason"
        >
          <Input.TextArea rows={3} maxLength={500} />
        </Form.Item>
      </Form>
    </Modal>
  );
}
