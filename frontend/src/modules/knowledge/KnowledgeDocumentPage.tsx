import { Alert, App, Button, Form, Input, Select, Space, Tabs, Tag } from 'antd';
import { ArrowLeftOutlined } from '@ant-design/icons';
import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useRef, useState } from 'react';
import { useLocation, useNavigate, useParams } from 'react-router';
import { api, mutation, queryString } from '../../shared/api';
import { ReadState, displayTime, readErrorMessage } from '../../shared/Records';
import { allowed, useSession } from '../../shared/session';
import { useSourceSelection } from '../datasource/CollectionPage';
import type { Page } from '../iam/types';
import {
  documentStates,
  type FactOption,
  type KnowledgeDocument,
  type KnowledgeFact,
  type ReleaseRevisionReturn,
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
    releaseReturn?: ReleaseRevisionReturn;
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
          releaseReturn={navigation?.releaseReturn}
          tab={tab}
          switchTab={(tab) =>
            setParams(queryString({ source, revisionId, tab }), {
              replace: true,
              state: location.state,
            })
          }
          showRevision={(revision) =>
            setParams(queryString({ source, revisionId: revision }), { state: location.state })
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
  releaseReturn,
  tab = 'body',
  switchTab,
  showRevision,
}: {
  source: string;
  sourceName?: string;
  current?: KnowledgeDocument;
  listSearch: string;
  /** 候选发起的查阅返回原发布范围。 */
  releaseReturn?: ReleaseRevisionReturn;
  tab?: string;
  switchTab?: (tab: string) => void;
  showRevision?: (revision?: string) => void;
}) {
  const session = useSession();
  const cache = useQueryClient();
  const navigate = useNavigate();
  const { message, modal } = App.useApp();
  const [form] = Form.useForm<Draft>();
  const [editing, setEditing] = useState(!current);
  const [chapter, setChapter] = useState(0);
  const draft = useKnowledgeDraft();
  const heading = useRef<HTMLHeadingElement>(null);
  const commandRef = useRef<{ payload: string; command: string }>(undefined);
  const [error, setError] = useState<Error>();
  const historic = Boolean(current && current.document.headRevisionId !== current.revisionId);
  const chosen = current?.references.filter((ref) => ref.selected) ?? [];
  const approve = current?.document.state === 'DRAFT' && allowed(session, 'knowledge.confirm');
  const missing = approve ? confirmationNeeds(current) : [];
  const chapterIndex = Math.min(chapter, Math.max(0, (current?.content.sections.length ?? 1) - 1));
  const section = current?.content.sections[chapterIndex];
  const sectionReferences =
    current?.references.filter((ref) => ref.sectionIndex === chapterIndex) ?? [];
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
  const initial: Draft = {
    title: current?.content.title ?? '',
    purpose: current?.content.purpose ?? '',
    exclusions: current?.content.exclusions ?? '',
    questions: current?.content.questions.join('\n') ?? '',
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
      await Promise.all([
        cache.invalidateQueries({ queryKey: ['knowledge-documents', source] }),
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
    if (releaseReturn && allowed(session, 'releases.view')) {
      // 候选修订属于发布办理的回看，返回时恢复其筛选和行内入口。
      navigate(
        '/releases' +
          (releaseReturn.taskId ? '/' + releaseReturn.taskId : '') +
          releaseReturn.search,
        {
          state: { releaseFocus: releaseReturn.focus },
        },
      );
      return;
    }
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
      setEditing(false);
      setError(undefined);
      requestAnimationFrame(() => document.getElementById('knowledge-edit-document')?.focus());
    });
  }

  /** 焦点定位到新添加的章节名称。 */
  function addSection(add: (value: Draft['sections'][number]) => void, index: number) {
    add({ title: '', body: '', referenceKeys: [] });
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
        <Button
          type="text"
          icon={<ArrowLeftOutlined aria-hidden="true" />}
          onClick={back}
          disabled={save.isPending}
        >
          {releaseReturn && allowed(session, 'releases.view') ? '返回发布与版本' : '返回列表'}
        </Button>
        <div className="knowledge-heading-main">
          <div className="knowledge-title">
            <h1 ref={heading} tabIndex={-1}>
              {current?.content.title ?? '新增文档'}
            </h1>
            {sourceName && <small className="knowledge-source-name">{sourceName}</small>}
          </div>
          {current && (
            <Tag color={!historic && current.document.state === 'CONFIRMED' ? 'green' : undefined}>
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
          scrollToFirstError={{ focus: true }}
          disabled={save.isPending}
          onValuesChange={(_, values) => {
            // 恢复原内容后不再提示放弃修改。
            draft.change(draftKey(values) !== draftKey(initial));
            setError(undefined);
          }}
          onFinish={(values) => save.mutate({ action: 'save', values })}
          className="knowledge-content knowledge-edit"
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
          <Form.List name="sections">
            {(fields, { add, remove }) => (
              <>
                {fields.map(({ key, name }) => (
                  <section
                    className="knowledge-section"
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
                              onOk: () => remove(name),
                            });
                          } else {
                            remove(name);
                          }
                        }}
                      >
                        移除本节
                      </Button>
                    )}
                  </section>
                ))}
                <Button
                  disabled={fields.length >= 12}
                  onClick={() => addSection(add, fields.length)}
                >
                  添加章节
                </Button>
              </>
            )}
          </Form.List>
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
            onChange={switchTab}
            items={[
              { key: 'body', label: '正文' },
              { key: 'facts', label: '范围与依据' },
              { key: 'history', label: '修订与发布' },
            ]}
          />
          <article
            className={
              tab === 'body' ? 'knowledge-content knowledge-body-read' : 'knowledge-content'
            }
          >
            {tab === 'body' && section && (
              <div className="knowledge-reader">
                <nav aria-label="文档章节及题单">
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
                  {allowed(session, 'test-cases.view') && (
                    <Button
                      type="text"
                      onClick={() =>
                        navigate(
                          '/test-cases?' + queryString({ source, documentId: current.document.id }),
                        )
                      }
                    >
                      相关标准题
                    </Button>
                  )}
                </nav>
                <section
                  className="knowledge-section knowledge-reading-section"
                  aria-label={section.title}
                >
                  <h2>{section.title}</h2>
                  <p>{section.body || '本节暂无正文'}</p>
                  {sectionReferences.length > 0 && (
                    <details className="knowledge-section-basis">
                      <summary>本节治理依据 · {sectionReferences.length} 项</summary>
                      <ul>
                        {sectionReferences.map((ref) => (
                          <li key={ref.target.kind + ':' + ref.target.targetId}>
                            {ref.label} <small>修订 {ref.factRevisionId}</small>
                          </li>
                        ))}
                      </ul>
                    </details>
                  )}
                </section>
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
                <section aria-label="治理依据">
                  <h2>治理依据</h2>
                  {current.references.length === 0 && (
                    <p className="knowledge-action-note">本修订暂无治理依据</p>
                  )}
                  {current.references.map((ref) => (
                    <div
                      key={ref.sectionIndex + ':' + ref.target.kind + ':' + ref.target.targetId}
                      className="knowledge-reference knowledge-evidence-row"
                    >
                      <span className="knowledge-reference-title">
                        <strong>{ref.label}</strong>
                        <small>{current.content.sections[ref.sectionIndex]?.title}</small>
                      </span>
                      <Tag>{ref.selected ? '主动引用' : '必要依赖'}</Tag>
                      <small className="knowledge-reference-revision">
                        修订 {ref.factRevisionId}
                      </small>
                      {allowed(session, 'governance.view') && (
                        <Button
                          type="link"
                          aria-label={'查看治理：' + ref.label}
                          onClick={() => openFact(ref)}
                        >
                          查看治理
                        </Button>
                      )}
                    </div>
                  ))}
                </section>
              </>
            )}
            {tab === 'history' &&
              current.history.map((revision) => (
                <div className="knowledge-reference" key={revision.id}>
                  <Button
                    type="link"
                    disabled={revision.id === current.revisionId}
                    onClick={() =>
                      showRevision?.(
                        revision.id === current.document.headRevisionId ? undefined : revision.id,
                      )
                    }
                  >
                    第 {revision.number} 版
                  </Button>
                  <span>{displayTime(revision.createdAt)}</span>
                  {revision.id === current.document.confirmedRevisionId && (
                    <Tag color="green">已确认</Tag>
                  )}
                </div>
              ))}
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

// 使用既有业务名称表达依据类型。
const factKindNames: Record<string, string> = {
  TABLE: '表说明',
  COLUMN: '字段',
  RELATION: '关联',
  METRIC: '指标',
};

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
    values.sections?.map(({ title, body, referenceKeys }) => [title, body, referenceKeys ?? []]),
  ]);
}
