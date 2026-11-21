import { Alert, App, Button, Form, Input, Select, Space, Tabs, Tag } from 'antd';
import { ArrowRightOutlined, PlusOutlined } from '@ant-design/icons';
import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useRef, useState } from 'react';
import { Link, useLocation, useNavigate, useParams } from 'react-router';
import { api, mutation, queryString } from '../../shared/api';
import { RecordTable } from '../../shared/RecordTable';
import { ReadState, displayTime, readErrorMessage } from '../../shared/Records';
import { allowed, useSession } from '../../shared/session';
import { useSourceSelection } from '../datasource/CollectionPage';
import type { Page } from '../iam/types';
import {
  documentStates,
  factKindNames,
  type FactOption,
  type KnowledgeDocument,
  type KnowledgeFact,
  type KnowledgeRow,
} from './types';
import { useKnowledgeDraft } from './useKnowledgeDraft';
import './knowledge.css';

/** 表单保存实际正文，依据按确切修订选择。 */
type Draft = {
  /** 业务事项名。 */
  title: string;
  /** 用户拟支持的业务用途。 */
  purpose: string;
  /** 已确认的排除和限制。 */
  exclusions: string;
  /** 每行一个代表性问题。 */
  questions: string;
  /** 本修订明确引用的其他正式文档身份。 */
  requiredDocuments: string[];
  /** 正文内各节与实际证据。 */
  sections: { title: string; body: string; referenceKeys: string[] }[];
};

/** 加载当前可读文档或准备独立的新草稿。 */
export function KnowledgeDocumentPage() {
  const { id } = useParams();
  const { params, setParams, sources } = useSourceSelection('KNOWLEDGE');
  const navigate = useNavigate();
  const location = useLocation();
  const source = params.get('source');
  // 详情只采用实际路由所属源，直达页面时不会误显示默认来源。
  const sourceName = sources.data?.find((item) => item.id === source)?.name;
  const revisionId = params.get('revisionId');
  const tab = params.get('tab') ?? 'body';
  const path = '/knowledge/sources/' + source + '/documents';
  const existing = id !== 'new';
  const document = useQuery({
    queryKey: ['knowledge-document', source, id, revisionId],
    enabled: Boolean(source && id && existing),
    // 编辑期间不因切回窗口或网络恢复覆盖用户打开的修订，提交仍核验版本。
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
    queryFn: () => api<KnowledgeDocument>(path + '/' + id + '?' + queryString({ revisionId })),
  });
  const navigation = location.state as {
    listSearch?: string;
    /** 文档列表的行操作可以直接进入草稿编辑。 */
    editDocument?: boolean;
  } | null;
  const listSearch = navigation?.listSearch ?? '?' + queryString({ source });

  if (!source) {
    return (
      <Alert
        title="请从知识文档列表选择数据源"
        type="warning"
        action={<Button onClick={() => navigate('/knowledge')}>返回列表</Button>}
      />
    );
  }

  if (!existing)
    return <DocumentEditor source={source} sourceName={sourceName} listSearch={listSearch} />;
  return (
    <ReadState
      loading={document.isPending}
      error={document.error}
      retry={() => void document.refetch()}
    >
      {document.data && (
        <DocumentEditor
          key={document.data.revisionId}
          source={source}
          sourceName={sourceName}
          current={document.data}
          listSearch={listSearch}
          startEditing={Boolean(navigation?.editDocument) && !revisionId}
          tab={tab}
          switchTab={(tab) =>
            setParams(queryString({ source, revisionId, tab }), {
              replace: true,
              state: { ...(location.state as object | null), editDocument: false },
            })
          }
          showRevision={(revision) =>
            setParams(queryString({ source, revisionId: revision }), {
              state: { ...(location.state as object | null), editDocument: false },
            })
          }
        />
      )}
    </ReadState>
  );
}

/** 草稿编辑和历史查看沿用同一文档上下文。 */
function DocumentEditor({
  source,
  sourceName,
  current,
  listSearch,
  startEditing = false,
  tab = 'body',
  switchTab,
  showRevision,
}: {
  source: string;
  sourceName?: string;
  current?: KnowledgeDocument;
  listSearch: string;
  /** 允许列表的有权编辑入口直达当前文档草稿。 */
  startEditing?: boolean;
  tab?: string;
  switchTab?: (tab: string) => void;
  showRevision?: (revision?: string) => void;
}) {
  const session = useSession();
  const cache = useQueryClient();
  const navigate = useNavigate();
  const { message, modal } = App.useApp();
  const [form] = Form.useForm<Draft>();
  const [editing, setEditing] = useState(
    !current || (startEditing && allowed(session, 'knowledge.edit')),
  );
  const [chapter, setChapter] = useState(0);
  const [draftPane, setDraftPane] = useState<'info' | 'documents' | number>('info');
  const draftSections = Form.useWatch('sections', { form, preserve: true }) as
    Draft['sections'] | undefined;
  const [basisSection, setBasisSection] = useState<number>();
  const draft = useKnowledgeDraft();
  const heading = useRef<HTMLHeadingElement>(null);
  const commandRef = useRef<{ payload: string; command: string }>(undefined);
  const [error, setError] = useState<Error>();
  const [dependencyKeyword, setDependencyKeyword] = useState('');
  const historic = Boolean(current && current.document.headRevisionId !== current.revisionId);
  const chosen = current?.references.filter((ref) => ref.selected) ?? [];
  const approve = current?.document.state === 'DRAFT' && allowed(session, 'knowledge.confirm');
  const missing = approve ? confirmationNeeds(current) : [];
  const chapterIndex = Math.min(chapter, Math.max(0, (current?.content.sections.length ?? 1) - 1));
  const section = current?.content.sections[chapterIndex];
  const sectionReferences =
    current?.references.filter((ref) => ref.sectionIndex === chapterIndex) ?? [];
  // 依据数量按当前章节的真实条目统计，详细内容仍沿用依据页签。
  const referenceCounts = new Map<string, number>();
  for (const ref of sectionReferences) {
    const kind = ref.target.kind;
    referenceCounts.set(kind, (referenceCounts.get(kind) ?? 0) + 1);
  }
  const options = useInfiniteQuery({
    queryKey: ['knowledge-facts', source],
    enabled: editing && allowed(session, 'knowledge.edit'),
    initialPageParam: 1,
    queryFn: ({ pageParam }) =>
      api<Page<FactOption>>(
        '/knowledge/sources/' +
          source +
          '/facts?' +
          queryString({ pageNum: pageParam, pageSize: 30 }),
      ),
    getNextPageParam: (last) =>
      last.pageNum * last.pageSize < last.total ? last.pageNum + 1 : undefined,
  });
  const choices = new Map<string, KnowledgeFact>();
  // 历史选择可显示原修订，当前选择器只追加实际有效的修订。
  chosen.forEach((ref) => {
    const fact = { target: ref.target, revisionId: ref.factRevisionId, label: ref.label };
    choices.set(factKey(fact), fact);
  });
  options.data?.pages.forEach((page) =>
    page.items.forEach(({ fact }) => {
      if (fact) choices.set(factKey(fact), fact);
    }),
  );
  const documentOptions = useInfiniteQuery({
    queryKey: ['knowledge-document-dependencies', source, dependencyKeyword],
    enabled: editing && allowed(session, 'knowledge.edit'),
    initialPageParam: 1,
    queryFn: ({ pageParam }) =>
      api<Page<KnowledgeRow>>(
        '/knowledge/sources/' +
          source +
          '/documents?' +
          queryString({ keyword: dependencyKeyword, pageNum: pageParam, pageSize: 30 }),
      ),
    getNextPageParam: (page) =>
      page.pageNum * page.pageSize < page.total ? page.pageNum + 1 : undefined,
  });
  // 已保存的引用在搜索和分页后继续显示名称。
  const documents = new Map((current?.requiredDocuments ?? []).map(({ id, title }) => [id, title]));
  documentOptions.data?.pages.forEach(({ items }) =>
    items.forEach(({ id, title }) => {
      if (id !== current?.document.id) documents.set(id, title);
    }),
  );
  const initial: Draft = {
    title: current?.content.title ?? '',
    purpose: current?.content.purpose ?? '',
    exclusions: current?.content.exclusions ?? '',
    questions: current?.content.questions.join('\n') ?? '',
    requiredDocuments: current?.requiredDocuments?.map(({ id }) => id) ?? [],
    sections: current?.content.sections.map((section, index) => ({
      ...section,
      referenceKeys: chosen
        .filter((ref) => ref.sectionIndex === index)
        .map((ref) =>
          factKey({ target: ref.target, revisionId: ref.factRevisionId, label: ref.label }),
        ),
    })) ?? [{ title: '用途与口径', body: '', referenceKeys: [] }],
  };
  const path =
    '/knowledge/sources/' + source + '/documents' + (current ? '/' + current.document.id : '');
  const save = useMutation({
    onMutate: draft.startWrite,
    mutationFn: async ({ action, values }: { action: 'save' | 'confirm'; values?: Draft }) => {
      const body =
        action === 'confirm'
          ? {
              revisionId: current?.revisionId,
              expectedVersion: current?.document.version,
            }
          : {
              ...values,
              questions: (values?.questions ?? '')
                .split('\n')
                .map((line) => line.trim())
                .filter(Boolean),
              sections: values?.sections.map(({ title, body: text, referenceKeys }) => ({
                title,
                body: text,
                facts: (referenceKeys ?? []).map((key) => {
                  const fact = choices.get(key);
                  if (!fact) throw new Error('请重新核对章节依据');
                  return { ...fact.target, revisionId: fact.revisionId };
                }),
              })),
              expectedVersion: current?.document.version,
            };
      const payload = JSON.stringify({ action, body });
      if (commandRef.current?.payload !== payload) {
        // 相同输入的网络重试保持原命令。
        commandRef.current = { payload, command: crypto.randomUUID() };
      }
      return mutation<{ targetId: string }>(
        action === 'confirm' ? path + '/confirmations' : current ? path + '/draft' : path,
        current && action === 'save' ? 'PUT' : 'POST',
        body,
        action === 'confirm' ? 'knowledge.confirm' : 'knowledge.edit',
        commandRef.current.command,
      );
    },
    onSuccess: async (result, { action }) => {
      // 已落库才清理离开保护并刷新阅读视图。
      draft.finishWrite(true);
      setError(undefined);
      // 消耗列表的直达编辑状态，刷新新修订后回到阅读视图。
      if (startEditing) switchTab?.(tab);
      await Promise.all([
        cache.invalidateQueries({ queryKey: ['knowledge-documents', source] }),
        cache.invalidateQueries({ queryKey: ['knowledge-document-dependencies', source] }),
        cache.invalidateQueries({ queryKey: ['knowledge-document', source, result.targetId] }),
      ]);
      setEditing(false);
      if (!current)
        navigate('/knowledge/' + result.targetId + '?' + queryString({ source }), {
          replace: true,
          state: { listSearch },
        });
      void message.success(action === 'confirm' ? '文档已确认' : '草稿已保存');
    },
    onError: (failure) => {
      draft.finishWrite(false);
      setError(failure);
    },
  });

  useEffect(() => {
    // 详情进入后直接从文档标题开始阅读。
    heading.current?.focus();
  }, []);

  /** 返回原列表时让查询与入口按钮一并恢复。 */
  function back() {
    navigate('/knowledge' + listSearch, {
      state: { focusDocument: current?.document.id ?? 'new' },
    });
  }

  /** 取消当前草稿，保留原正文和页签。 */
  function cancel() {
    if (!current) {
      back();
      return;
    }
    void draft.cancel(() => {
      form.resetFields();
      setDraftPane('info');
      setEditing(false);
      setError(undefined);
      // 取消直达编辑后，同一文档的历史切换不再自动打开表单。
      if (startEditing) switchTab?.(tab);
      requestAnimationFrame(() => document.getElementById('knowledge-edit-document')?.focus());
    });
  }

  /** 焦点定位到新添加的章节名称。 */
  function addSection(add: (value: Draft['sections'][number]) => void, index: number) {
    add({ title: '', body: '', referenceKeys: [] });
    setDraftPane(index);
    requestAnimationFrame(() => form.scrollToField(['sections', index, 'title'], { focus: true }));
  }

  /** 依据链接回到 C 批同源、同类型的治理条目。 */
  function openFact(ref: KnowledgeDocument['references'][number]) {
    const { objectId, kind, targetId } = ref.target;
    const discovery = kind === 'RELATION' || kind === 'METRIC';
    navigate(
      '/governance/' +
        objectId +
        '?' +
        queryString({
          source,
          entry: kind === 'COLUMN' ? targetId : 'table',
          section: kind === 'RELATION' ? 'relations' : kind === 'METRIC' ? 'metrics' : undefined,
          candidate: discovery ? targetId : undefined,
        }),
    );
  }

  return (
    <section
      className="panel knowledge-document knowledge-document-detail"
      aria-label="知识文档详情"
    >
      <header className="knowledge-heading">
        <div className="knowledge-heading-main">
          <div className="knowledge-title">
            <h1 ref={heading} tabIndex={-1}>
              {current?.content.title ?? '新增文档'}
            </h1>
            {sourceName && <small className="knowledge-source-name">{sourceName}</small>}
          </div>
          {current && (
            <Tag
              color={!historic && current.document.state === 'CONFIRMED' ? 'success' : undefined}
            >
              {historic ? '历史修订' : documentStates[current.document.state]}
            </Tag>
          )}
        </div>
        {historic && <Button onClick={() => showRevision?.()}>返回当前修订</Button>}
      </header>
      {editing && allowed(session, 'knowledge.edit') ? (
        <Form
          form={form}
          layout="vertical"
          initialValues={initial}
          disabled={save.isPending}
          onValuesChange={(_, values) => {
            // 恢复原内容后不再提示放弃修改。
            draft.change(draftKey(values) !== draftKey(initial));
            setError(undefined);
          }}
          onFinish={(values) => save.mutate({ action: 'save', values })}
          className="knowledge-edit knowledge-draft-form"
          onFinishFailed={({ errorFields }) => {
            // 必填字段可能在其他分区，先打开该分区再聚焦输入。
            const name = errorFields[0]?.name;
            if (!name) return;
            setDraftPane(
              name[0] === 'sections' && typeof name[1] === 'number'
                ? name[1]
                : name[0] === 'requiredDocuments'
                  ? 'documents'
                  : 'info',
            );
            requestAnimationFrame(() => form.scrollToField(name, { focus: true }));
          }}
        >
          <div className="knowledge-draft-workspace">
            <Form.List name="sections">
              {(fields, { add, remove }) => (
                <>
                  {/* 沿实际文档结构切换，每节字段仍注册在同一份表单。 */}
                  <nav className="knowledge-draft-nav" aria-label="文档编辑分区">
                    <Button
                      type="text"
                      aria-current={draftPane === 'info' ? 'page' : undefined}
                      onClick={() => setDraftPane('info')}
                    >
                      文档职责
                    </Button>
                    <Button
                      type="text"
                      aria-current={draftPane === 'documents' ? 'page' : undefined}
                      onClick={() => setDraftPane('documents')}
                    >
                      引用文档
                    </Button>
                    <div className="knowledge-draft-chapters">
                      {/* 添加动作归入章节组标题，列表只承载可切换的章节。 */}
                      <div className="knowledge-draft-chapter-header">
                        <strong>章节</strong>
                        <Button
                          type="text"
                          size="small"
                          icon={<PlusOutlined />}
                          disabled={fields.length >= 12}
                          onClick={() => addSection(add, fields.length)}
                        >
                          添加章节
                        </Button>
                      </div>
                      {fields.map(({ key, name }) => {
                        // 新增或清空标题时，用章节位置定位当前录入项。
                        const title =
                          (draftSections?.[name]?.title ?? initial.sections[name]?.title)?.trim() ||
                          '第 ' + (name + 1) + ' 节';
                        return (
                          <Button
                            key={key}
                            className="knowledge-chapter-link"
                            type="text"
                            title={title}
                            aria-current={draftPane === name ? 'page' : undefined}
                            onClick={() => setDraftPane(name)}
                          >
                            <span className="knowledge-chapter-title">{title}</span>
                          </Button>
                        );
                      })}
                    </div>
                  </nav>
                  {fields.map(({ key, name }) => (
                    <section
                      className="knowledge-section knowledge-draft-pane"
                      hidden={draftPane !== name}
                      key={key}
                      aria-label={'第 ' + (name + 1) + ' 节'}
                    >
                      <Form.Item
                        name={[name, 'title']}
                        label="章节名称"
                        rules={[{ required: true, whitespace: true, message: '请填写章节名称' }]}
                      >
                        <Input maxLength={100} />
                      </Form.Item>
                      <Form.Item name={[name, 'body']} label="章节正文">
                        <Input.TextArea rows={5} maxLength={6000} />
                      </Form.Item>
                      <Form.Item name={[name, 'referenceKeys']} label="已确认治理依据">
                        <Select
                          mode="multiple"
                          showSearch
                          optionFilterProp="label"
                          maxCount={24}
                          placeholder="从当前来源选择已确认的治理定义"
                          loading={options.isPending || options.isFetchingNextPage}
                          options={[...choices].map(([value, fact]) => ({
                            value,
                            label:
                              fact.target.kind === 'TABLE'
                                ? fact.label
                                : fact.label + ' · ' + factKindNames[fact.target.kind],
                          }))}
                          onPopupScroll={(event) => {
                            // 保留已选内容，滚动到底继续读取同源依据。
                            const node = event.currentTarget;
                            if (
                              options.hasNextPage &&
                              !options.isFetchingNextPage &&
                              node.scrollTop + node.clientHeight >= node.scrollHeight - 12
                            )
                              void options.fetchNextPage();
                          }}
                        />
                      </Form.Item>
                      {fields.length > 1 && (
                        <Button
                          type="link"
                          danger
                          aria-label={'移除第 ' + (name + 1) + ' 节'}
                          onClick={() => {
                            const section = form.getFieldValue([
                              'sections',
                              name,
                            ]) as Draft['sections'][number];
                            // 已填写的章节先明确用户的移除决定。
                            if (section.title || section.body || section.referenceKeys?.length) {
                              void modal.confirm({
                                title: '移除本节？',
                                content: section.title || '本节已有内容，移除后不会随草稿保存。',
                                okText: '移除',
                                cancelText: '保留',
                                okButtonProps: { danger: true },
                                focusable: { autoFocusButton: 'cancel' },
                                onOk: () => {
                                  remove(name);
                                  setDraftPane(Math.max(0, name - 1));
                                },
                              });
                            } else {
                              remove(name);
                              setDraftPane(Math.max(0, name - 1));
                            }
                          }}
                        >
                          移除本节
                        </Button>
                      )}
                    </section>
                  ))}
                </>
              )}
            </Form.List>
            <section
              className="knowledge-draft-pane"
              hidden={draftPane !== 'info'}
              aria-label="文档职责"
            >
              {/* 编辑区保留用途、实际边界、代表性问题及各节证据。 */}
              <Form.Item
                name="title"
                label="文档名称"
                rules={[{ required: true, whitespace: true, message: '请填写文档名称' }]}
              >
                <Input maxLength={120} />
              </Form.Item>
              <Form.Item name="purpose" label="用途">
                <Input.TextArea rows={2} maxLength={1000} />
              </Form.Item>
              <Form.Item name="exclusions" label="适用边界">
                <Input.TextArea rows={2} maxLength={1000} />
              </Form.Item>
              <Form.Item name="questions" label="代表性问题">
                <Input.TextArea rows={2} placeholder="每行填写一个问题" />
              </Form.Item>
            </section>
            <section
              className="knowledge-draft-pane"
              hidden={draftPane !== 'documents'}
              aria-label="引用文档"
            >
              {/* 文档间的必要引用与每节的治理事实分开录入。 */}
              <Form.Item name="requiredDocuments" label="必要引用文档">
                <Select
                  mode="multiple"
                  showSearch
                  filterOption={false}
                  maxCount={24}
                  placeholder="选择必要引用的同源文档"
                  onSearch={setDependencyKeyword}
                  options={[...documents].map(([id, title]) => ({ value: id, label: title }))}
                  loading={documentOptions.isFetching}
                  onPopupScroll={(event) => {
                    // 长列表滚到底再读取后续当前源文档。
                    const node = event.currentTarget;
                    if (
                      documentOptions.hasNextPage &&
                      !documentOptions.isFetchingNextPage &&
                      node.scrollTop + node.clientHeight >= node.scrollHeight - 12
                    ) {
                      void documentOptions.fetchNextPage();
                    }
                  }}
                />
              </Form.Item>
              {documentOptions.error && (
                <Alert
                  type="warning"
                  title={readErrorMessage(documentOptions.error)}
                  action={<Button onClick={() => void documentOptions.refetch()}>重试</Button>}
                />
              )}
            </section>
          </div>
          {options.error && <Alert type="error" title={readErrorMessage(options.error)} />}
          {error && <Alert type="error" title={readErrorMessage(error)} />}
          <footer className="knowledge-actions">
            {draft.changed && <span className="knowledge-action-note">有未保存的修改</span>}
            <Space>
              <Button onClick={cancel}>取消</Button>
              <Button
                type="primary"
                htmlType="submit"
                disabled={!draft.changed}
                loading={save.isPending}
              >
                保存草稿
              </Button>
            </Space>
          </footer>
        </Form>
      ) : current ? (
        <>
          <Tabs
            activeKey={tab}
            onChange={(next) => {
              // 直接打开依据页签时包含全部章节。
              setBasisSection(undefined);
              switchTab?.(next);
            }}
            tabBarExtraContent={
              tab === 'body' &&
              allowed(session, 'test-cases.view') && (
                <Link
                  className="knowledge-related-tests"
                  to={'/test-cases?' + queryString({ source, documentId: current.document.id })}
                >
                  相关标准题 <ArrowRightOutlined aria-hidden="true" />
                </Link>
              )
            }
            items={[
              { key: 'body', label: '正文' },
              { key: 'facts', label: '范围与依据' },
              { key: 'history', label: '修订与发布' },
            ]}
          />
          <article
            className={
              tab === 'body'
                ? 'knowledge-content knowledge-body-read'
                : 'knowledge-content knowledge-detail-tab'
            }
          >
            {tab === 'body' && section && (
              <div className="knowledge-reader">
                {/* 多章节才需要目录定位，当前章节标题在正文内提供。 */}
                {current.content.sections.length > 1 && (
                  <nav aria-label="文档章节">
                    {current.content.sections.map((item, index) => (
                      <Button
                        key={index}
                        type="text"
                        aria-current={chapterIndex === index ? 'true' : undefined}
                        onClick={() => setChapter(index)}
                      >
                        {item.title}
                      </Button>
                    ))}
                  </nav>
                )}
                <div className="knowledge-reading-pane">
                  <div className="knowledge-reading-layout">
                    <section
                      className="knowledge-section knowledge-reading-section"
                      aria-label={section.title}
                    >
                      <h2>{section.title}</h2>
                      <p>{section.body || '本节暂无正文'}</p>
                    </section>
                    {/* 正文旁只展示本节实际引用，保留原有下钻范围。 */}
                    {sectionReferences.length > 0 && (
                      <aside className="knowledge-reading-basis" aria-label="本节治理依据">
                        <h3>本节治理依据</h3>
                        <dl>
                          {[...referenceCounts].map(([kind, count]) => (
                            <div key={kind}>
                              <dt>{factKindNames[kind] ?? kind}</dt>
                              <dd>{count} 项</dd>
                            </div>
                          ))}
                        </dl>
                        <Button
                          type="link"
                          className="knowledge-basis-link"
                          aria-label={
                            '查看本节关联的治理依据（' + sectionReferences.length + ' 项）'
                          }
                          onClick={() => {
                            // 从正文跳转时先展示当前章节的引用。
                            setBasisSection(chapterIndex);
                            switchTab?.('facts');
                          }}
                        >
                          查看 {sectionReferences.length} 项依据{' '}
                          <ArrowRightOutlined aria-hidden="true" />
                        </Button>
                      </aside>
                    )}
                  </div>
                </div>
              </div>
            )}
            {tab === 'facts' && (
              <>
                <dl className="knowledge-facts-summary">
                  <div>
                    <dt>用途</dt>
                    <dd>{current.content.purpose || '—'}</dd>
                  </div>
                  <div>
                    <dt>适用边界</dt>
                    <dd>{current.content.exclusions || '—'}</dd>
                  </div>
                  <div>
                    <dt>代表性问题</dt>
                    <dd>{current.content.questions.join('、') || '—'}</dd>
                  </div>
                </dl>
                {Boolean(current.requiredDocuments?.length) && (
                  <section aria-label="必要引用文档">
                    <h2>必要引用文档</h2>
                    <ul>
                      {current.requiredDocuments.map((link) => (
                        <li key={link.id}>{link.title}</li>
                      ))}
                    </ul>
                  </section>
                )}
                <section aria-label="治理依据">
                  <div className="knowledge-basis-heading">
                    <h2>
                      {basisSection === undefined
                        ? '治理依据'
                        : (current.content.sections[basisSection]?.title ?? '本节') + ' · 治理依据'}
                    </h2>
                    {basisSection !== undefined && (
                      <Button type="link" onClick={() => setBasisSection(undefined)}>
                        查看全部依据
                      </Button>
                    )}
                  </div>
                  {/* 四列沿整块工作区展开，长修订编号保留原值。 */}
                  <RecordTable<KnowledgeDocument['references'][number]>
                    className="knowledge-detail-table"
                    rowKey={(ref) =>
                      [ref.sectionIndex, ref.target.kind, ref.target.targetId].join(':')
                    }
                    dataSource={current.references.filter(
                      (ref) => basisSection === undefined || ref.sectionIndex === basisSection,
                    )}
                    pagination={false}
                    flexibleColumns={[0, 1, 2]}
                    scroll={{ x: 620 }}
                    emptyDescription="暂无治理依据"
                    columns={[
                      {
                        title: '依据',
                        width: 220,
                        render: (_, ref) => (
                          <div className="knowledge-reference-title">
                            <span className="cell-text" title={ref.label}>
                              {ref.label}
                            </span>
                            <small className="table-secondary">
                              {current.content.sections[ref.sectionIndex]?.title}
                            </small>
                          </div>
                        ),
                      },
                      {
                        title: '引用关系',
                        width: 118,
                        render: (_, ref) => <Tag>{ref.selected ? '主动引用' : '必要依赖'}</Tag>,
                      },
                      { title: '修订', dataIndex: 'factRevisionId', width: 156, ellipsis: true },
                      ...(allowed(session, 'governance.view')
                        ? [
                            {
                              title: '操作',
                              width: 112,
                              className: 'knowledge-table-action',
                              render: (
                                _: unknown,
                                ref: KnowledgeDocument['references'][number],
                              ) => (
                                <Button
                                  type="link"
                                  aria-label={'查看治理：' + ref.label}
                                  onClick={() => openFact(ref)}
                                >
                                  查看治理
                                </Button>
                              ),
                            },
                          ]
                        : []),
                    ]}
                  />
                </section>
              </>
            )}
            {tab === 'history' && (
              <RecordTable<KnowledgeDocument['history'][number]>
                className="knowledge-detail-table knowledge-revision-table"
                rowKey="id"
                dataSource={current.history}
                pagination={false}
                flexibleColumns={[0, 1, 2]}
                emptyDescription="暂无修订"
                scroll={{ x: 520 }}
                columns={[
                  {
                    title: '版本',
                    width: 144,
                    render: (_, revision) => `第 ${revision.number} 版`,
                  },
                  {
                    title: '修订时间',
                    width: 148,
                    render: (_, revision) => displayTime(revision.createdAt),
                  },
                  {
                    title: '状态',
                    width: 118,
                    render: (_, revision) =>
                      revision.id === current.document.confirmedRevisionId ? (
                        <Tag color="success">已确认</Tag>
                      ) : (
                        '—'
                      ),
                  },
                  {
                    title: '操作',
                    width: 112,
                    className: 'knowledge-table-action',
                    render: (_, revision) =>
                      revision.id === current.revisionId ? (
                        <span className="muted">当前查看</span>
                      ) : (
                        <Button
                          type="link"
                          onClick={() =>
                            showRevision?.(
                              revision.id === current.document.headRevisionId
                                ? undefined
                                : revision.id,
                            )
                          }
                        >
                          查看修订
                        </Button>
                      ),
                  },
                ]}
              />
            )}
            {current.document.state === 'REVIEW' && (
              <Alert type="warning" title="治理依据需要重新核对" />
            )}
            {error && <Alert type="error" title={readErrorMessage(error)} />}
            {!historic && (
              <footer className="knowledge-actions">
                {missing.length > 0 && (
                  <span id="knowledge-confirm-reason" className="knowledge-action-note">
                    确认前需补齐{missing.join('、')}
                  </span>
                )}
                <Space wrap>
                  {allowed(session, 'knowledge.edit') && (
                    <Button
                      id="knowledge-edit-document"
                      type={approve && missing.length ? 'primary' : 'default'}
                      disabled={save.isPending}
                      onClick={() => {
                        setDraftPane('info');
                        setEditing(true);
                        setError(undefined);
                        requestAnimationFrame(() => form.focusField('title'));
                      }}
                    >
                      编辑草稿
                    </Button>
                  )}
                  {approve && (
                    <Button
                      type={missing.length ? 'default' : 'primary'}
                      loading={save.isPending}
                      disabled={Boolean(missing.length)}
                      aria-describedby={missing.length ? 'knowledge-confirm-reason' : undefined}
                      onClick={() =>
                        void modal.confirm({
                          title: '确认当前修订？',
                          content: '请核对各节正文和治理依据。',
                          okText: '确认当前修订',
                          cancelText: '继续核对',
                          focusable: { autoFocusButton: 'cancel' },
                          onOk: () => save.mutateAsync({ action: 'confirm' }),
                        })
                      }
                    >
                      确认文档
                    </Button>
                  )}
                  {current.document.state === 'CONFIRMED' && allowed(session, 'releases.view') && (
                    <Button
                      type="primary"
                      onClick={() =>
                        navigate(
                          '/releases?' +
                            queryString({
                              source,
                              documentId: current.document.id,
                              prepare: 1,
                            }),
                        )
                      }
                    >
                      核对发布准备
                    </Button>
                  )}
                </Space>
              </footer>
            )}
          </article>
        </>
      ) : (
        <Alert type="warning" title="当前无权创建知识文档" />
      )}
    </section>
  );
}

/** 用准确修订区分重新选择前后的同一治理目标。 */
function factKey(fact: KnowledgeFact) {
  return [fact.target.kind, fact.target.targetId, fact.revisionId].join(':');
}

/** 根据已保存内容反馈确认前缺项，服务端仍核对真实依据和版本。 */
function confirmationNeeds(current: KnowledgeDocument) {
  const needs: string[] = [];
  if (!current.content.purpose.trim()) needs.push('用途');
  if (!current.content.questions.length) needs.push('代表性问题');
  if (current.content.sections.some(({ body }) => !body.trim())) needs.push('章节正文');
  if (
    current.content.sections.some(
      (_, index) => !current.references.some((ref) => ref.selected && ref.sectionIndex === index),
    )
  )
    needs.push('逐节治理依据');
  return needs;
}

/** 使用显式字段顺序比较草稿，切换时不会把表单注册次序当成修改。 */
function draftKey(values: Draft) {
  return JSON.stringify([
    values.title,
    values.purpose,
    values.exclusions,
    values.questions,
    [...(values.requiredDocuments ?? [])].sort(),
    values.sections?.map(({ title, body, referenceKeys }) => [title, body, referenceKeys ?? []]),
  ]);
}
