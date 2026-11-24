import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert, Button, Descriptions, Form, Input, Space } from 'antd';
import { useRef } from 'react';
import { Link, useLocation, useNavigate, useParams } from 'react-router';
import { api, mutation, queryString } from '../../shared/api';
import { LoadingStatus } from '../../shared/LoadingIndicator';
import { readErrorMessage, ReadState } from '../../shared/Records';
import { allowed, useSession } from '../../shared/session';
import { SourceUnavailable, useSourceSelection } from '../datasource/CollectionPage';
import { KnowledgeDraftGeneration } from './KnowledgeDraftGeneration';
import './knowledge.css';

/**
 * 原规划发起人可读取的任务进度和已核验建议。
 */
type KnowledgePlan = {
  /** 本轮规划状态。 */
  state: 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED';
  /** 失败时用于界面反馈的稳定代码。 */
  failureCode: string | null;
  /** 规划候选及引用本轮快照的依据。 */
  documents: Array<{
    /** 建议文档名称。 */
    title: string;
    /** 业务职责和适用条件。 */
    purpose: string;
    /** 代表性问题。 */
    questions: string[];
    /** 本文档不支持的范围。 */
    exclusions: string[];
    /** 尚缺的治理定义。 */
    gaps: string[];
    /** 同源事实快照序号。 */
    basis: number[];
    /** 匹配已有文档时可回看原依据。 */
    existingDocumentId: string | null;
  }>;
  /** 快照内可回指的确切确认名称。 */
  facts: Array<{ number: number; label: string }>;
  /** 同源现有文档的匹配身份。 */
  existingDocuments: Array<{ id: string; title: string }>;
  /** 各建议当前可回看的实际正文任务。 */
  draftTasks: Array<{ suggestionIndex: number; id: string }>;
};

/** 规划失败按可复核的实际结果提示。 */
const failureReasons: Record<string, string> = {
  PLAN_INPUT_UNAVAILABLE: '来源、治理依据或模型配置已变化，请核对后重新发起。',
  MODEL_RESULT_UNKNOWN: '暂未取得可确认的模型结果，请在调用记录中核对。',
  MODEL_FAILED: '模型未能完成规划，请检查模型调用记录。',
  INVALID_PLAN: '模型返回的建议引用范围无效，请重新发起规划。',
  QUEUE_EXPIRED: '规划排队已超时，请重新发起。',
};

/**
 * 从源发起正文前的规划，并按统一命令查询和回看政策保留任务定位。
 */
export function KnowledgePlanPage() {
  const { id } = useParams();
  const { params, sources } = useSourceSelection('KNOWLEDGE');
  const source = params.get('source') ?? '';
  const selected = sources.data?.find((item) => item.id === source);
  const session = useSession();
  const navigate = useNavigate();
  const cache = useQueryClient();
  const location = useLocation();
  const command = useRef<{ source: string; goal: string; key: string } | null>(null);
  const mayPlan = allowed(session, 'knowledge.generate');
  const starting = id === 'new';
  const endpoint = '/knowledge/sources/' + source + '/plans';
  const plan = useQuery({
    queryKey: ['knowledge-plan', source, id],
    enabled: mayPlan && Boolean(selected && id && !starting),
    queryFn: () => api<KnowledgePlan>(endpoint + '/' + id),
    refetchInterval: (query) => {
      // 只轮询未完成任务，终态用原编号回看。
      const state = query.state.data?.state;
      return state === 'QUEUED' || state === 'RUNNING' ? 1500 : false;
    },
  });
  const begin = useMutation({
    mutationFn: async (goal: string) => {
      // 请求回包不明时再次操作先核对原命令。
      if (command.current?.source !== source || command.current.goal !== goal) {
        command.current = { source, goal, key: crypto.randomUUID() };
      }
      const response = await mutation(
        endpoint,
        'POST',
        { goal },
        'knowledge.generate',
        command.current.key,
      );
      if (!response.targetId) {
        throw new Error('暂未取得规划编号，请再次核对本次提交');
      }
      return response.targetId;
    },
    onSuccess: (key) => {
      // 把实际任务编号放进地址，刷新后继续回看。
      navigate('/knowledge/plans/' + key + '?' + queryString({ source }), {
        replace: true,
        state: location.state,
      });
    },
  });

  if (!mayPlan) {
    // 页面和后台接口分别核对生成权限。
    return <Alert type="warning" title="当前账号没有生成知识文档的权限" />;
  }

  const report = plan.data;
  const factNames = new Map(report?.facts.map((fact) => [fact.number, fact.label]));
  const documents = new Map(
    report?.existingDocuments.map((document) => [document.id, document.title]),
  );
  const draftTasks = new Map(
    report?.draftTasks?.map(({ suggestionIndex, id }) => [suggestionIndex, id]),
  );
  return (
    <main className="record-page" aria-label="知识文档规划">
      {/* 源与本轮操作分居左右，返回沿用后台面包屑。 */}
      <div className="panel ds-toolbar">
        {selected && (
          <Space>
            <span className="table-secondary">数据源</span>
            <strong>{selected.name}</strong>
          </Space>
        )}
        {!starting && report && ['FAILED', 'SUCCEEDED'].includes(report.state) && (
          <Button
            onClick={() => {
              // 终态之后的重新规划是用户主动发起的新任务。
              command.current = null;
              begin.reset();
              navigate('/knowledge/plans/new?' + queryString({ source }), {
                state: location.state,
              });
            }}
          >
            重新规划
          </Button>
        )}
      </div>
      <ReadState
        loading={sources.isPending}
        error={sources.error}
        retry={() => void sources.refetch()}
      >
        {!selected ? (
          <SourceUnavailable />
        ) : starting ? (
          <section className="panel knowledge-plan-surface" aria-label="发起知识文档规划">
            {/* 目标可选，输入留空时从当前源的确认范围提出建议。 */}
            <Form<{ goal?: string }>
              layout="vertical"
              onFinish={({ goal }) => begin.mutate(goal?.trim() ?? '')}
            >
              <Form.Item name="goal" label="业务目标">
                <Input.TextArea
                  rows={3}
                  maxLength={300}
                  placeholder="选填，想优先整理哪些业务口径？"
                />
              </Form.Item>
              {begin.error && <Alert type="error" showIcon title={readErrorMessage(begin.error)} />}
              <Button
                type="primary"
                htmlType="submit"
                loading={begin.isPending}
                disabled={selected.lifecycle !== 'ENABLED'}
              >
                生成规划
              </Button>
            </Form>
          </section>
        ) : (
          <section className="panel knowledge-plan-surface" aria-label="生成前确认">
            {/* 等待或失败时保留原轮次，只有真实建议可以回看。 */}
            <ReadState
              loading={plan.isPending}
              error={plan.error}
              retry={() => void plan.refetch()}
            >
              {report?.state === 'QUEUED' || report?.state === 'RUNNING' ? (
                <LoadingStatus>
                  {report.state === 'QUEUED' ? '等待规划' : '正在规划知识文档'}
                </LoadingStatus>
              ) : report?.state === 'FAILED' ? (
                <Alert
                  type="error"
                  showIcon
                  title={
                    (report.failureCode && failureReasons[report.failureCode]) ||
                    '本次规划未能完成。'
                  }
                />
              ) : report?.state === 'SUCCEEDED' && report.documents.length === 0 ? (
                <Alert type="info" showIcon title="当前治理依据下未形成文档建议" />
              ) : (
                report?.documents.map((document, index) => (
                  <article key={index} className="knowledge-plan-suggestion">
                    {/* 每份建议保留职责、缺项和确切同源依据。 */}
                    <h2>{document.title}</h2>
                    <Descriptions
                      column={1}
                      items={[
                        { key: 'purpose', label: '用途', children: document.purpose },
                        {
                          key: 'questions',
                          label: '可回答的问题',
                          children: document.questions.join('、') || '—',
                        },
                        {
                          key: 'exclusions',
                          label: '不支持的范围',
                          children: document.exclusions.join('、') || '—',
                        },
                        {
                          key: 'gaps',
                          label: '待补依据',
                          children: document.gaps.join('、') || '—',
                        },
                        {
                          key: 'basis',
                          label: '治理依据',
                          children: document.basis
                            .map((number) => factNames.get(number))
                            .join('、'),
                        },
                        ...(document.existingDocumentId
                          ? [
                              {
                                key: 'existing',
                                label: '已有文档',
                                children: (
                                  <Link
                                    to={
                                      '/knowledge/' +
                                      document.existingDocumentId +
                                      '?' +
                                      queryString({ source })
                                    }
                                  >
                                    {documents.get(document.existingDocumentId)}
                                  </Link>
                                ),
                              },
                            ]
                          : []),
                      ]}
                    />
                    {/* 每篇独立核对、生成和采纳，成功结果随规划回看。 */}
                    <KnowledgeDraftGeneration
                      endpoint={`${endpoint}/${id}/draft-generations`}
                      source={source}
                      suggestionIndex={index}
                      suggestion={document}
                      facts={report.facts}
                      taskId={draftTasks.get(index)}
                      mayEdit={allowed(session, 'knowledge.edit')}
                      started={(taskId) => {
                        // 保留其他文档各自的办理位置，仅更新本篇回执。
                        cache.setQueryData<KnowledgePlan>(
                          ['knowledge-plan', source, id],
                          (current) =>
                            current && {
                              ...current,
                              draftTasks: [
                                ...(current.draftTasks ?? []).filter(
                                  (task) => task.suggestionIndex !== index,
                                ),
                                { suggestionIndex: index, id: taskId },
                              ],
                            },
                        );
                      }}
                    />
                  </article>
                ))
              )}
            </ReadState>
          </section>
        )}
      </ReadState>
    </main>
  );
}
