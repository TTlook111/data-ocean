import { ReloadOutlined } from '@ant-design/icons';
import { Button, Empty, Select, Tag } from 'antd';
import { useInfiniteQuery, useQuery } from '@tanstack/react-query';
import { useEffect, useRef, useState } from 'react';
import { useLocation, useNavigate } from 'react-router';
import { api, queryString } from '../../shared/api';
import { RecordTable } from '../../shared/RecordTable';
import { displayTime, ReadState } from '../../shared/Records';
import { allowed, useSession } from '../../shared/session';
import { SourcePicker, SourceUnavailable, useSourceSelection } from '../datasource/CollectionPage';
import type { Page } from '../iam/types';
import { ReleasePreparation } from './ReleasePreparation';
import type { ReleaseRevisionReturn } from './types';
import './knowledge.css';

/** 构建前确认的文档、题单和模型查验结果。 */
type ReleaseCheck = {
  /** 当前选择的确认正文名称。 */
  documentTitle: string;
  /** 当前核对的具体文档确认。 */
  documentRevisionId: string | null;
  /** 针对同一文档修订的当前审核结果数。 */
  reviewedTests: number;
  /** 系统选择的向量配置修订。 */
  embeddingRevisionId: string | null;
  /** 核对通过的完整发布依据。 */
  inputFingerprint: string | null;
  /** 真实前置条件不满足的原因。 */
  issues: string[];
};

/** 列表只回读已经保存的冻结记录。 */
type ReleaseCandidate = {
  /** 候选标识。 */
  id: string;
  /** 准备时使用的文档名称。 */
  title: string;
  /** 冻结的文档确认编号。 */
  documentRevisionId: string;
  /** 当前阶段由服务端返回。 */
  status: 'PREPARED';
  /** 实际创建时间。 */
  createdAt: string;
};

/** 发布与版本入口按实际核对结果定位可办理的阻断事项。 */
export function KnowledgeReleasePage() {
  const { params, setParams, sources, selected } = useSourceSelection('KNOWLEDGE_RELEASES');
  const navigate = useNavigate();
  const location = useLocation();
  const session = useSession();
  const rememberedFocus = useRef<string>(undefined);
  const [keyword, setKeyword] = useState('');
  const source = selected?.id;
  // 地址中的来源已不可用时，先恢复来源选择，不能拿原文档套到另一来源上。
  const requestedSource = params.get('source');
  const doc = requestedSource && requestedSource !== source ? null : params.get('documentId');
  const root = '/knowledge/sources/' + source;
  const path = root + '/release-preflight';
  const pageNum = Number(params.get('pageNum')) || 1;
  const pageSize = Number(params.get('pageSize')) || 10;
  const documents = useInfiniteQuery({
    queryKey: ['knowledge-release-options', source, keyword],
    enabled: Boolean(source),
    initialPageParam: 1,
    queryFn: ({ pageParam }) =>
      api<Page<{ id: string; title: string }>>(
        path + '/document-options?' + queryString({ keyword, pageNum: pageParam, pageSize: 20 }),
      ),
    getNextPageParam: ({ pageNum, pageSize, total }) =>
      pageNum * pageSize < total ? pageNum + 1 : undefined,
  });
  const inspection = useQuery({
    queryKey: ['knowledge-release-preflight', source, doc],
    enabled: Boolean(source && doc),
    queryFn: () => api<ReleaseCheck>(path + '?' + queryString({ documentId: doc })),
  });
  const result = inspection.data;
  const confirmedRevision = result?.documentRevisionId;
  const options = documents.data?.pages.flatMap((page) => page.items) ?? [];
  const selectedTitle = result?.documentTitle ?? options.find((option) => option.id === doc)?.title;
  const candidates = useQuery({
    queryKey: ['release-candidates', source, doc, pageNum, pageSize],
    enabled: Boolean(source && doc),
    queryFn: () =>
      api<Page<ReleaseCandidate>>(
        root + '/releases?' + queryString({ documentId: doc, pageNum, pageSize }),
      ),
  });
  const focus = (location.state as { releaseFocus?: string } | null)?.releaseFocus;
  useEffect(() => {
    // 列表和预检回读后恢复原入口；候选已移出本页时退回核对区域。
    if (!focus || rememberedFocus.current === location.key || !candidates.isSuccess || !result)
      return;
    rememberedFocus.current = location.key;
    (
      document.getElementById(focus) ?? document.getElementById('release-preflight-revision')
    )?.focus();
  }, [candidates.isSuccess, focus, location.key, result]);

  /** 核对和候选记录都打开实际确认的历史修订。 */
  function openRevision(revisionId: string, focus: string) {
    const releaseReturn: ReleaseRevisionReturn = { search: location.search, focus };
    navigate('/knowledge/' + doc + '?' + queryString({ source, revisionId }), {
      state: { releaseReturn },
    });
  }

  /** 页面只提供对应现行权限、实际已接通的处理入口。 */
  function follow(issue: string) {
    if (issue === '知识检索向量化节点未分配可用模型' && allowed(session, 'models.view')) {
      return {
        label: '打开模型配置',
        path: '/models?' + queryString({ tab: 'nodes', node: 'rag-embedding' }),
      };
    }
    if (issue.startsWith('缺少已审核') && allowed(session, 'test-cases.view')) {
      return {
        label: '打开相关题单',
        path: '/test-cases?' + queryString({ source, documentId: doc }),
      };
    }
    if (
      (issue.includes('文档修订') || issue.includes('治理依据')) &&
      allowed(session, 'knowledge.view')
    ) {
      return {
        label: '查看文档',
        path: '/knowledge/' + doc + '?' + queryString({ source, tab: 'facts' }),
      };
    }
    return null;
  }

  // 同一个处理入口对应多个缺项时，保留全部原因并只显示一个按钮。
  const groups = new Map<string, { issues: string[]; action: ReturnType<typeof follow> }>();
  for (const issue of result?.issues ?? []) {
    const action = follow(issue);
    const key = action?.path ?? issue;
    const group = groups.get(key) ?? { issues: [], action };
    group.issues.push(issue);
    groups.set(key, group);
  }

  return (
    <main className="record-page knowledge-release-page" aria-label="发布与版本">
      <div className="panel query-panel knowledge-context-toolbar">
        <SourcePicker
          sources={sources.data}
          selected={selected}
          choose={(value) => {
            // 切换源后重新选择确认文档，不复用上一源的查询。
            setKeyword('');
            setParams(queryString({ source: value }));
          }}
        />
        <div className="knowledge-context-controls">
          <Select
            aria-label="核对文档"
            className="knowledge-document-select"
            placeholder={doc && inspection.isPending ? '正在读取文档' : '选择已确认文档'}
            labelInValue
            value={doc && selectedTitle ? { value: doc, label: selectedTitle } : undefined}
            loading={documents.isFetching}
            disabled={!source}
            options={options.map((item) => ({ value: item.id, label: item.title }))}
            showSearch={{ filterOption: false, onSearch: setKeyword }}
            notFoundContent={
              documents.isFetching
                ? '正在查找文档'
                : documents.error
                  ? '文档选项暂不可用'
                  : '没有匹配的确认文档'
            }
            onChange={({ value }) => {
              // 按实际选中的确认文档查验，查询词只用于选择器。
              setKeyword('');
              setParams(queryString({ source, documentId: value }));
            }}
            onOpenChange={(open) => {
              // 从完整目录开始下次选择，回读不受上次搜索影响。
              if (!open) setKeyword('');
            }}
            onPopupScroll={(event) => {
              // 向下浏览会读取下一批已确认文档。
              const list = event.currentTarget;
              if (
                documents.hasNextPage &&
                !documents.isFetching &&
                list.scrollHeight - list.scrollTop - list.clientHeight < 32
              ) {
                void documents.fetchNextPage();
              }
            }}
          />
          <Button
            icon={<ReloadOutlined aria-hidden="true" />}
            disabled={!doc || !source}
            loading={inspection.isFetching}
            onClick={() => void inspection.refetch()}
          >
            重新核对
          </Button>
        </div>
      </div>
      <ReadState
        loading={sources.isPending}
        error={sources.error}
        retry={() => void sources.refetch()}
      >
        {source ? (
          <>
            {/* 准备核对归属当前文档，候选创建仍由服务端再次核验。 */}
            <section
              className="panel knowledge-preflight"
              aria-label="发布准备核对"
              aria-busy={inspection.isFetching}
            >
              {/* 原命令核实独立于预检读取，预检失败时仍然能找回已完成的提交。 */}
              {doc && (
                <ReleasePreparation
                  key={`${session.userId}:${source}:${doc}`}
                  userId={session.userId}
                  source={source}
                  documentId={doc}
                  documentRevisionId={result?.documentRevisionId ?? null}
                  inputFingerprint={inspection.error ? null : (result?.inputFingerprint ?? null)}
                  showStatus={Boolean(result && !inspection.error)}
                  canPrepare={allowed(session, 'releases.build')}
                  checking={inspection.isFetching}
                  beforeSubmit={() =>
                    setParams(queryString({ source, documentId: doc, pageSize }), { replace: true })
                  }
                  onConflict={() => void inspection.refetch()}
                />
              )}
              <ReadState
                loading={doc ? inspection.isPending : documents.isPending}
                error={doc ? inspection.error : documents.error}
                retry={() => {
                  // 文档已选时单独核实发布依据，避免下拉查询干扰候选阅读。
                  if (doc) void inspection.refetch();
                  else void documents.refetch();
                }}
              >
                {result ? (
                  <>
                    <dl className="knowledge-preflight-summary">
                      <div>
                        <dt>文档确认修订</dt>
                        <dd>
                          {confirmedRevision ? (
                            allowed(session, 'knowledge.view') ? (
                              <Button
                                id="release-preflight-revision"
                                type="link"
                                className="knowledge-record-link"
                                title={'修订编号 ' + confirmedRevision}
                                onClick={() =>
                                  openRevision(confirmedRevision, 'release-preflight-revision')
                                }
                              >
                                查看确认修订
                              </Button>
                            ) : (
                              <span title={confirmedRevision}>已确认</span>
                            )
                          ) : (
                            '待确认'
                          )}
                        </dd>
                      </div>
                      <div>
                        <dt>相关已审核题目</dt>
                        <dd>{result.reviewedTests} 道</dd>
                      </div>
                      <div>
                        <dt>知识检索向量化</dt>
                        <dd>{result.embeddingRevisionId ? '已分配' : '未就绪'}</dd>
                      </div>
                    </dl>
                    {result.issues.length > 0 && (
                      <section aria-label="待处理事项">
                        <ul className="knowledge-issue-list">
                          {[...groups].map(([key, { issues, action }]) => (
                            <li key={key} className="knowledge-issue">
                              <div>
                                <strong>
                                  {issues.length === 1 ? issues[0] : '相关标准题待补齐'}
                                </strong>
                                {issues.length > 1 && (
                                  <ul>
                                    {issues.map((issue) => (
                                      <li key={issue}>{issue}</li>
                                    ))}
                                  </ul>
                                )}
                              </div>
                              {action && (
                                <Button onClick={() => navigate(action.path)}>
                                  {action.label}
                                </Button>
                              )}
                            </li>
                          ))}
                        </ul>
                      </section>
                    )}
                  </>
                ) : (
                  <Empty
                    image={Empty.PRESENTED_IMAGE_SIMPLE}
                    description={
                      !keyword && documents.data?.pages[0]?.total === 0
                        ? '暂无已确认文档'
                        : '选择文档查看发布准备'
                    }
                  >
                    {!keyword &&
                      documents.data?.pages[0]?.total === 0 &&
                      allowed(session, 'knowledge.view') && (
                        <Button onClick={() => navigate('/knowledge?' + queryString({ source }))}>
                          前往知识文档
                        </Button>
                      )}
                  </Empty>
                )}
              </ReadState>
            </section>
            {/* 只展示本源和所选文档持久化的候选，保留服务端分页。 */}
            {doc && (
              <section className="panel records-panel knowledge-records" aria-label="发布候选">
                <header className="knowledge-candidates-heading">
                  <h2>发布候选</h2>
                </header>
                <RecordTable<ReleaseCandidate>
                  rowKey="id"
                  dataSource={candidates.data?.items}
                  loading={candidates.isPending}
                  error={candidates.error}
                  retry={() => void candidates.refetch()}
                  emptyDescription="暂无发布候选"
                  tableLayout="fixed"
                  flexibleColumns={[0]}
                  columns={[
                    {
                      title: '文档名称',
                      render: (_, row) => (
                        <div className="knowledge-candidate-identity">
                          <span className="cell-text" title={row.title}>
                            {row.title}
                          </span>
                          <small className="cell-text muted" title={'候选编号 ' + row.id}>
                            {row.id}
                          </small>
                        </div>
                      ),
                    },
                    {
                      title: '确认修订',
                      width: 112,
                      render: (_, row) =>
                        allowed(session, 'knowledge.view') ? (
                          <Button
                            id={'release-candidate-revision-' + row.id}
                            type="link"
                            className="knowledge-record-link"
                            title={'修订编号 ' + row.documentRevisionId}
                            onClick={() =>
                              openRevision(
                                row.documentRevisionId,
                                'release-candidate-revision-' + row.id,
                              )
                            }
                          >
                            查看修订
                          </Button>
                        ) : (
                          '已确认'
                        ),
                    },
                    {
                      title: '状态',
                      width: 96,
                      render: (_, row) => (
                        <Tag>{row.status === 'PREPARED' ? '待构建' : row.status}</Tag>
                      ),
                    },
                    {
                      title: '准备时间',
                      width: 160,
                      render: (_, row) => displayTime(row.createdAt),
                    },
                  ]}
                  pagination={{
                    current: candidates.data?.pageNum ?? pageNum,
                    pageSize,
                    total: candidates.data?.total,
                    onChange: (pageNum, pageSize) =>
                      setParams(queryString({ source, documentId: doc, pageNum, pageSize })),
                  }}
                />
              </section>
            )}
          </>
        ) : (
          <SourceUnavailable />
        )}
      </ReadState>
    </main>
  );
}
