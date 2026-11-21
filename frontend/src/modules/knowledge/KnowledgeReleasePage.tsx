import { Button, Empty, Form, Input, Modal, Select, Space, Tabs, Tag } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { useEffect, useRef } from 'react';
import { Navigate, useLocation, useNavigate } from 'react-router';
import { api, queryString } from '../../shared/api';
import { RecordTable } from '../../shared/RecordTable';
import { displayTime, ReadState } from '../../shared/Records';
import { allowed, useSession } from '../../shared/session';
import { SourcePicker, SourceUnavailable, useSourceSelection } from '../datasource/CollectionPage';
import type { Page } from '../iam/types';
import { ReleasePreparation } from './ReleasePreparation';
import { KnowledgeEffectiveVersions } from './KnowledgeEffectiveVersions';
import { ReleaseDocumentPicker, ReleaseScopeSummary } from './ReleaseScopeFields';
import { releaseStates, type ReleaseRevisionReturn, type ReleaseTask } from './types';
import { useReleaseScopeCheck } from './useReleaseScopeCheck';
import './knowledge.css';

/** 来源栏、页签、筛选和任务结果沿用发布原型，准备在独立确认区办理。 */
export function KnowledgeReleasePage() {
  const { params, setParams, sources, selected } = useSourceSelection('KNOWLEDGE_RELEASES');
  const navigate = useNavigate();
  const location = useLocation();
  const session = useSession();
  const [form] = Form.useForm();
  const rememberedFocus = useRef<string>(undefined);
  const source = selected?.id;
  const sameSource = !params.get('source') || params.get('source') === source;
  const active = params.get('tab') === 'active';
  const legacyBindingId = active ? params.get('bindingId') : null;
  const doc = sameSource ? params.get('documentId') : null;
  const preparing = Boolean(
    source && sameSource && !active && params.has('prepare') && allowed(session, 'releases.build'),
  );
  const target = active ? null : preparing ? (params.get('prepareDocument') ?? doc) : doc;
  const root = '/knowledge/sources/' + source;
  const pageNum = Number(params.get('pageNum')) || 1;
  const pageSize = Number(params.get('pageSize')) || 10;
  const keyword = sameSource ? params.get('keyword') : null;
  const status = sameSource ? params.get('status') : null;
  const inspection = useReleaseScopeCheck(source, target);
  const result = inspection.data;
  const tasks = useQuery({
    queryKey: ['release-tasks', source, doc, keyword, status, pageNum, pageSize],
    enabled: Boolean(source) && !active,
    queryFn: () =>
      api<Page<ReleaseTask>>(
        root + '/releases?' + queryString({ documentId: doc, keyword, status, pageNum, pageSize }),
      ),
  });
  const focus = (location.state as { releaseFocus?: string } | null)?.releaseFocus;
  useEffect(() => {
    // 资料或任务返回后聚焦原入口，列表刷新保持键盘位置。
    if (focus && tasks.isSuccess && rememberedFocus.current !== location.key) {
      rememberedFocus.current = location.key;
      (document.getElementById(focus) ?? document.getElementById('release-task-list'))?.focus();
    }
  }, [tasks.isSuccess, focus, location.key]);

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

  /** 实际前置问题只链接到有权限的现有操作页。 */
  function follow(issue: string) {
    if (issue === '知识检索向量化节点未分配可用模型' && allowed(session, 'models.view'))
      return { label: '打开模型配置', path: '/models?tab=nodes&node=rag-embedding' };
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
    const key = issue.startsWith('缺少已审核') ? 'tests' : (action?.path ?? issue);
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

  if (legacyBindingId) {
    // 已分享的版本地址继续指向同一绑定，同时进入原型的独立详情。
    const search = new URLSearchParams(params);
    search.delete('bindingId');
    return (
      <Navigate
        replace
        to={{
          pathname: '/releases/versions/' + encodeURIComponent(legacyBindingId),
          search: search.toString(),
        }}
        state={location.state}
      />
    );
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
            form.setFieldsValue({ keyword: undefined, status: undefined });
            setParams(
              queryString({
                source: value,
                pageSize,
                tab: active ? 'active' : null,
                versionPageSize: active ? params.get('versionPageSize') : null,
              }),
            );
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
            {/* 两个页签继续共用原型来源栏和当前源。 */}
            <Tabs
              className="release-task-tabs"
              activeKey={active ? 'active' : 'tasks'}
              onChange={(tab) =>
                update({ tab: tab === 'active' ? 'active' : null, bindingId: null })
              }
              items={[
                { key: 'tasks', label: '发布任务' },
                { key: 'active', label: '当前生效' },
              ]}
              tabBarExtraContent={
                !active &&
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
            {active ? (
              <KnowledgeEffectiveVersions
                key={source}
                source={source}
                params={params}
                update={update}
              />
            ) : (
              <>
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
                      <ReleaseDocumentPicker
                        key={source}
                        source={source}
                        documentId={doc}
                        title={result?.documentId === doc ? result.documentTitle : undefined}
                        label="发布文档"
                        clearable
                        choose={(value) => update({ documentId: value, pageNum: 1 })}
                      />
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
                <section
                  id="release-task-list"
                  tabIndex={-1}
                  className="panel records-panel knowledge-records"
                  aria-label="发布任务列表"
                >
                  <RecordTable<ReleaseTask>
                    rowKey="id"
                    dataSource={tasks.data?.items}
                    loading={tasks.isPending}
                    error={tasks.error}
                    retry={() => void tasks.refetch()}
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
                        title: '固定候选',
                        width: 124,
                        ellipsis: true,
                        render: (_, row) => row.candidateId ?? '尚未构建',
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
                      {
                        title: '创建时间',
                        width: 152,
                        render: (_, row) => displayTime(row.createdAt),
                      },
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
                      current: tasks.data?.pageNum ?? pageNum,
                      pageSize,
                      total: tasks.data?.total,
                      onChange: (pageNum, pageSize) => update({ pageNum, pageSize }),
                    }}
                  />
                </section>
              </>
            )}
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
          {source && (
            <ReleaseDocumentPicker
              source={source}
              documentId={target}
              title={result?.documentTitle}
              label="选择已确认文档"
              choose={(value) => update({ prepareDocument: value ?? '' })}
            />
          )}
          <Button
            disabled={!target}
            loading={inspection.isFetching}
            onClick={() => void inspection.refetch()}
          >
            重新核对
          </Button>
        </div>
        <ReadState
          loading={Boolean(target && inspection.isPending)}
          error={inspection.error}
          retry={() => void inspection.refetch()}
        >
          {source && target && result ? (
            <section className="release-modal-preflight" aria-label="发布准备核对">
              <ReleaseScopeSummary result={result} />
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
        {/* 先核对目标范围，再保存任务。准备相关题单从保存后的任务办理。 */}
        {source && target && (
          <ReleasePreparation
            key={`${session.userId}:${source}:${target}`}
            userId={session.userId}
            source={source}
            documentId={target}
            documentRevisionId={
              !inspection.error && result?.documentReady ? result.documentRevisionId : null
            }
            checking={inspection.isFetching}
            onConflict={() => void inspection.refetch()}
            onCreated={openBuild}
          />
        )}
      </Modal>
    </main>
  );
}
