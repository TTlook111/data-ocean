import { Button, Empty, Form, Input, Modal, Select, Space, Tabs, Tag } from 'antd';
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
import { releaseStates, type ReleaseCandidate, type ReleaseRevisionReturn } from './types';
import './knowledge.css';

/** 核对一次候选的文档修订、审核题与就绪信息。 */
type ReleaseCheck = {
  /** 被核对文档的实际归属。 */
  documentId: string;
  /** 确切确认正文名称。 */
  documentTitle: string;
  /** 仍有效的确认修订。 */
  documentRevisionId: string | null;
  /** 已审核并适用的题目数量。 */
  reviewedTests: number;
  /** 当前有效的向量配置。 */
  embeddingRevisionId: string | null;
  /** 本次预检固定的整个候选输入。 */
  inputFingerprint: string | null;
  /** 真实前置阻断。 */
  issues: string[];
};

/** 来源栏、页签、筛选和任务结果沿用发布原型，准备在独立确认区办理。 */
export function KnowledgeReleasePage() {
  const { params, setParams, sources, selected } = useSourceSelection('KNOWLEDGE_RELEASES');
  const navigate = useNavigate();
  const location = useLocation();
  const session = useSession();
  const [form] = Form.useForm();
  const rememberedFocus = useRef<string>(undefined);
  const [search, setSearch] = useState('');
  const source = selected?.id;
  const sameSource = !params.get('source') || params.get('source') === source;
  const doc = sameSource ? params.get('documentId') : null;
  const preparing = Boolean(
    source && sameSource && params.has('prepare') && allowed(session, 'releases.build'),
  );
  const target = preparing ? (params.get('prepareDocument') ?? doc) : doc;
  const root = '/knowledge/sources/' + source;
  const pageNum = Number(params.get('pageNum')) || 1;
  const pageSize = Number(params.get('pageSize')) || 10;
  const keyword = sameSource ? params.get('keyword') : null;
  const status = sameSource ? params.get('status') : null;
  const documents = useInfiniteQuery({
    queryKey: ['knowledge-release-options', source, search],
    enabled: Boolean(source),
    initialPageParam: 1,
    queryFn: ({ pageParam }) =>
      api<Page<{ id: string; title: string }>>(
        root +
          '/release-preflight/document-options?' +
          queryString({ keyword: search, pageNum: pageParam, pageSize: 20 }),
      ),
    getNextPageParam: ({ pageNum, pageSize, total }) =>
      pageNum * pageSize < total ? pageNum + 1 : undefined,
  });
  const inspection = useQuery({
    queryKey: ['knowledge-release-preflight', source, target],
    enabled: Boolean(source && target),
    queryFn: () =>
      api<ReleaseCheck>(root + '/release-preflight?' + queryString({ documentId: target })),
  });
  const result = inspection.data;
  const options = documents.data?.pages.flatMap((page) => page.items) ?? [];
  const candidates = useQuery({
    queryKey: ['release-candidates', source, doc, keyword, status, pageNum, pageSize],
    enabled: Boolean(source),
    queryFn: () =>
      api<Page<ReleaseCandidate>>(
        root + '/releases?' + queryString({ documentId: doc, keyword, status, pageNum, pageSize }),
      ),
  });
  const focus = (location.state as { releaseFocus?: string } | null)?.releaseFocus;
  useEffect(() => {
    // 资料或任务返回后聚焦原入口，列表刷新保持键盘位置。
    if (focus && candidates.isSuccess && rememberedFocus.current !== location.key) {
      rememberedFocus.current = location.key;
      document.getElementById(focus)?.focus();
    }
  }, [candidates.isSuccess, focus, location.key]);

  /** 更新部分地址条件，保持同一来源和列表上下文。 */
  function update(values: Record<string, string | number | null>) {
    setParams(queryString({ ...Object.fromEntries(params), source, ...values }));
  }

  /** 任务详情接收原筛选和页码，从原型规定的独立页办理构建。 */
  function openBuild(id: string) {
    navigate(
      '/releases/' +
        id +
        '?' +
        queryString({ source, documentId: doc, keyword, status, pageNum, pageSize }),
    );
  }

  /** 回看确切的确认修订，关闭详情时返回原入口。 */
  function revision(documentId: string, revisionId: string, focus: string) {
    const releaseReturn: ReleaseRevisionReturn = { search: location.search, focus };
    navigate('/knowledge/' + documentId + '?' + queryString({ source, revisionId }), {
      state: { releaseReturn },
    });
  }

  /** 文档选择器通过已确认标题远程搜索，滚动时继续加载。 */
  function documentPicker(id: string | null, name: string, choose: (value: string | null) => void) {
    const title =
      result?.documentId === id
        ? result.documentTitle
        : options.find((row) => row.id === id)?.title;
    return (
      <Select
        className="release-document-select"
        aria-label={name}
        placeholder={name}
        allowClear={name === '发布文档'}
        labelInValue
        value={id && title ? { value: id, label: title } : undefined}
        showSearch={{ filterOption: false, onSearch: setSearch }}
        loading={documents.isFetching}
        options={options.map((item) => ({ value: item.id, label: item.title }))}
        onOpenChange={(open) => {
          if (!open) setSearch('');
        }}
        onChange={(value) => {
          setSearch('');
          choose(value?.value ?? null);
        }}
        onPopupScroll={(event) => {
          // 读取下一页而不替换已经加载的选择。
          const list = event.currentTarget;
          if (
            documents.hasNextPage &&
            !documents.isFetching &&
            list.scrollHeight - list.scrollTop - list.clientHeight < 32
          )
            void documents.fetchNextPage();
        }}
      />
    );
  }

  /** 实际前置问题只链接到有权限的现有操作页。 */
  function follow(issue: string) {
    if (issue === '知识检索向量化节点未分配可用模型' && allowed(session, 'models.view'))
      return { label: '打开模型配置', path: '/models?tab=nodes&node=rag-embedding' };
    if (issue.startsWith('缺少已审核') && allowed(session, 'test-cases.view'))
      return {
        label: '打开相关题单',
        path: '/test-cases?' + queryString({ source, documentId: target }),
      };
    if (
      (issue.includes('文档修订') || issue.includes('治理依据')) &&
      allowed(session, 'knowledge.view')
    )
      return {
        label: '查看文档',
        path: '/knowledge/' + target + '?' + queryString({ source, tab: 'facts' }),
      };
    return null;
  }

  // 同一办理入口对应的缺项归在一组，避免重复按钮。
  const groups = new Map<string, { issues: string[]; action: ReturnType<typeof follow> }>();
  for (const issue of result?.issues ?? []) {
    const action = follow(issue);
    const key = action?.path ?? issue;
    const group = groups.get(key) ?? { issues: [], action };
    group.issues.push(issue);
    groups.set(key, group);
  }

  /** 列表清空时返回同源首面。 */
  function reset() {
    form.resetFields();
    form.setFieldsValue({ keyword: undefined, status: undefined });
    update({ documentId: null, keyword: null, status: null, pageNum: 1 });
  }

  return (
    <main className="record-page knowledge-release-page" aria-label="发布与版本">
      <div className="panel ds-toolbar">
        <SourcePicker
          withLabel
          sources={sources.data}
          selected={selected}
          choose={(value) => {
            // 保留窗口尺寸与分页设置，清除另一来源的实际查询。
            setSearch('');
            form.setFieldsValue({ keyword: undefined, status: undefined });
            setParams(queryString({ source: value, pageSize }));
          }}
        />
      </div>
      <ReadState
        loading={sources.isPending}
        error={sources.error}
        retry={() => void sources.refetch()}
      >
        {source ? (
          <>
            {/* 正式生效由后续切换接入，此处只提供真实的发布任务。 */}
            <Tabs
              className="release-task-tabs"
              activeKey="tasks"
              items={[
                { key: 'tasks', label: '发布任务' },
                { key: 'active', label: '当前生效', disabled: true },
              ]}
              tabBarExtraContent={
                allowed(session, 'releases.build') && (
                  <Button
                    type="primary"
                    onClick={() => update({ prepare: 1, prepareDocument: doc })}
                  >
                    创建发布任务
                  </Button>
                )
              }
            />
            <div className="panel query-panel release-task-filters">
              <Form
                layout="inline"
                form={form}
                initialValues={{ keyword: keyword ?? undefined, status: status ?? undefined }}
                onFinish={(values) =>
                  update({
                    keyword: values.keyword ?? null,
                    status: values.status ?? null,
                    pageNum: 1,
                  })
                }
              >
                <Form.Item name="keyword">
                  <Input aria-label="发布编号" placeholder="发布编号" allowClear />
                </Form.Item>
                <Form.Item>
                  {documentPicker(doc, '发布文档', (value) =>
                    update({ documentId: value, pageNum: 1 }),
                  )}
                </Form.Item>
                <Form.Item name="status">
                  <Select
                    aria-label="发布阶段"
                    placeholder="全部状态"
                    allowClear
                    options={Object.entries(releaseStates).map(([value, label]) => ({
                      value,
                      label,
                    }))}
                  />
                </Form.Item>
                <Space>
                  <Button htmlType="submit">查询</Button>
                  <Button onClick={reset}>重置</Button>
                </Space>
              </Form>
            </div>
            {/* 使用真实任务的阶段和文档归属，无文档过滤时仍是当前源全部任务。 */}
            <section className="panel records-panel knowledge-records" aria-label="发布任务列表">
              <RecordTable<ReleaseCandidate>
                rowKey="id"
                dataSource={candidates.data?.items}
                loading={candidates.isPending}
                error={candidates.error}
                retry={() => void candidates.refetch()}
                filtered={Boolean(doc || keyword || status)}
                onReset={reset}
                emptyDescription="暂无发布任务"
                tableLayout="fixed"
                flexibleColumns={[0]}
                columns={[
                  {
                    title: '文档 / 发布任务',
                    render: (_, row) => (
                      <div className="knowledge-candidate-identity">
                        <Button
                          id={'release-build-' + row.id}
                          type="link"
                          className="knowledge-record-link"
                          title={row.title}
                          onClick={() => openBuild(row.id)}
                        >
                          {row.title}
                        </Button>
                        <small className="cell-text muted" title={'发布编号 ' + row.id}>
                          {row.id}
                        </small>
                      </div>
                    ),
                  },
                  {
                    title: '阶段',
                    width: 96,
                    render: (_, row) => (
                      <Tag
                        color={
                          row.status === 'BUILD_FAILED'
                            ? 'error'
                            : row.status === 'BUILDING'
                              ? 'processing'
                              : undefined
                        }
                      >
                        {releaseStates[row.status]}
                      </Tag>
                    ),
                  },
                  { title: '创建时间', width: 152, render: (_, row) => displayTime(row.createdAt) },
                  {
                    title: '操作',
                    width: 176,
                    render: (_, row) => (
                      <Space size={8}>
                        <Button type="link" onClick={() => openBuild(row.id)}>
                          {row.status === 'PREPARED' || row.status === 'BUILD_FAILED'
                            ? '继续构建'
                            : '查看任务'}
                        </Button>
                        {allowed(session, 'knowledge.view') && (
                          <Button
                            id={'release-candidate-revision-' + row.id}
                            type="link"
                            title={'修订编号 ' + row.documentRevisionId}
                            onClick={() =>
                              revision(
                                row.documentId,
                                row.documentRevisionId,
                                'release-candidate-revision-' + row.id,
                              )
                            }
                          >
                            查看修订
                          </Button>
                        )}
                      </Space>
                    ),
                  },
                ]}
                pagination={{
                  current: candidates.data?.pageNum ?? pageNum,
                  pageSize,
                  total: candidates.data?.total,
                  onChange: (pageNum, pageSize) => update({ pageNum, pageSize }),
                }}
              />
            </section>
          </>
        ) : (
          <SourceUnavailable />
        )}
      </ReadState>
      <Modal
        open={preparing}
        destroyOnHidden
        centered
        width={720}
        title="创建发布任务"
        footer={null}
        onCancel={() => update({ prepare: null, prepareDocument: null })}
      >
        {/* 只有用户主动创建任务时，核对独立的目标文档与门槛。 */}
        <div className="release-modal-source">
          {documentPicker(target, '选择已确认文档', (value) =>
            update({ prepareDocument: value ?? '' }),
          )}
          <Button
            disabled={!target}
            loading={inspection.isFetching}
            onClick={() => void inspection.refetch()}
          >
            重新核对
          </Button>
        </div>
        {source && target && (
          <ReleasePreparation
            key={`${session.userId}:${source}:${target}`}
            userId={session.userId}
            source={source}
            documentId={target}
            documentRevisionId={result?.documentRevisionId ?? null}
            inputFingerprint={inspection.error ? null : (result?.inputFingerprint ?? null)}
            showStatus={Boolean(result && !inspection.error)}
            canPrepare
            checking={inspection.isFetching}
            onConflict={() => void inspection.refetch()}
            onCreated={openBuild}
          />
        )}
        <ReadState
          loading={Boolean(target && inspection.isPending)}
          error={inspection.error}
          retry={() => void inspection.refetch()}
        >
          {source && target && result ? (
            <section className="release-modal-preflight" aria-label="发布准备核对">
              <dl className="knowledge-preflight-summary">
                <div>
                  <dt>文档确认修订</dt>
                  <dd>{result.documentRevisionId ? '已确认' : '待确认'}</dd>
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
              {groups.size > 0 && (
                <ul className="knowledge-issue-list" aria-label="待处理事项">
                  {[...groups].map(([key, { issues, action }]) => (
                    <li key={key} className="knowledge-issue">
                      <strong>{issues.join('；')}</strong>
                      {action && (
                        <Button onClick={() => navigate(action.path)}>{action.label}</Button>
                      )}
                    </li>
                  ))}
                </ul>
              )}
            </section>
          ) : (
            <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="选择文档查看发布准备" />
          )}
        </ReadState>
      </Modal>
    </main>
  );
}
