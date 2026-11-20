import { ReloadOutlined } from '@ant-design/icons';
import { Button, Empty, Select, Tag } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { useNavigate } from 'react-router';
import { api, queryString } from '../../shared/api';
import { ReadState } from '../../shared/Records';
import { allowed, useSession } from '../../shared/session';
import { SourcePicker, SourceUnavailable, useSourceSelection } from '../datasource/CollectionPage';
import type { Page } from '../iam/types';
import './knowledge.css';

/** 构建前确认的文档、题单和模型查验结果。 */
type ReleaseCheck = {
  /** 当前核对的具体文档确认。 */
  documentRevisionId: string | null;
  /** 针对同一文档修订的当前审核结果数。 */
  reviewedTests: number;
  /** 系统选择的向量配置修订。 */
  embeddingRevisionId: string | null;
  /** 真实前置条件不满足的原因。 */
  issues: string[];
};

/** 发布与版本入口按实际核对结果定位可办理的阻断事项。 */
export function KnowledgeReleasePage() {
  const { params, setParams, sources, selected } = useSourceSelection('KNOWLEDGE_RELEASES');
  const navigate = useNavigate();
  const session = useSession();
  const doc = params.get('documentId');
  const source = selected?.id;
  const path = '/knowledge/sources/' + source + '/release-preflight';
  const documents = useQuery({
    queryKey: ['knowledge-release-options', source],
    enabled: Boolean(source),
    queryFn: () =>
      api<Page<{ id: string; title: string }>>(path + '/document-options?pageSize=100'),
  });
  const inspection = useQuery({
    queryKey: ['knowledge-release-preflight', source, doc],
    enabled: Boolean(source && doc),
    queryFn: () => api<ReleaseCheck>(path + '?' + queryString({ documentId: doc })),
  });
  const result = inspection.data;

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
    <main className="record-page" aria-label="发布与版本">
      <div className="panel query-panel knowledge-context-toolbar">
        <SourcePicker
          sources={sources.data}
          selected={selected}
          choose={(value) => setParams(queryString({ source: value }))}
        />
        <div className="knowledge-context-controls">
          <Select
            aria-label="核对文档"
            className="knowledge-document-select"
            placeholder="选择已确认文档"
            value={doc ?? undefined}
            loading={documents.isFetching}
            disabled={!documents.data?.items.length}
            options={documents.data?.items.map((item) => ({ value: item.id, label: item.title }))}
            showSearch={{ optionFilterProp: 'label' }}
            onChange={(documentId) => setParams(queryString({ source, documentId }))}
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
          <section
            className="panel knowledge-preflight"
            aria-label="发布准备核对"
            aria-busy={inspection.isFetching}
          >
            <ReadState
              loading={documents.isPending || Boolean(doc && inspection.isPending)}
              error={documents.error ?? inspection.error}
              retry={() => {
                if (documents.error) void documents.refetch();
                else void inspection.refetch();
              }}
            >
              {result ? (
                <>
                  <header className="knowledge-heading-main knowledge-preflight-title">
                    <h2>发布准备</h2>
                    <Tag color={result.issues.length > 0 ? 'orange' : 'green'}>
                      {result.issues.length > 0 ? '待处理' : '构建前置检查通过'}
                    </Tag>
                  </header>
                  <dl className="knowledge-preflight-summary">
                    <div>
                      <dt>文档确认修订</dt>
                      <dd>
                        {result.documentRevisionId ? (
                          allowed(session, 'knowledge.view') ? (
                            <Button
                              type="link"
                              className="knowledge-record-link"
                              title={'修订编号 ' + result.documentRevisionId}
                              onClick={() =>
                                navigate(
                                  '/knowledge/' +
                                    doc +
                                    '?' +
                                    queryString({
                                      source,
                                      revisionId: result.documentRevisionId,
                                    }),
                                )
                              }
                            >
                              查看确认修订
                            </Button>
                          ) : (
                            <span title={result.documentRevisionId}>已确认</span>
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
                              <Button onClick={() => navigate(action.path)}>{action.label}</Button>
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
                    documents.data?.total === 0 ? '暂无已确认文档' : '选择文档查看发布准备'
                  }
                >
                  {documents.data?.total === 0 && allowed(session, 'knowledge.view') && (
                    <Button onClick={() => navigate('/knowledge?' + queryString({ source }))}>
                      前往知识文档
                    </Button>
                  )}
                </Empty>
              )}
            </ReadState>
          </section>
        ) : (
          <SourceUnavailable />
        )}
      </ReadState>
    </main>
  );
}
