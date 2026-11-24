import { PlusOutlined } from '@ant-design/icons';
import {
  Alert,
  App,
  Button,
  Form,
  Input,
  InputNumber,
  Pagination,
  Select,
  Space,
  Tabs,
  Tag,
} from 'antd';
import { useMutation, useQuery, useQueryClient, type UseQueryResult } from '@tanstack/react-query';
import { useEffect, useRef, useState } from 'react';
import { Link, useLocation, useNavigate } from 'react-router';
import { api, mutation, queryString } from '../../shared/api';
import { ReadState, readErrorMessage } from '../../shared/Records';
import { RecordTable } from '../../shared/RecordTable';
import { allowed, useSession } from '../../shared/session';
import { SourcePicker, SourceUnavailable, useSourceSelection } from '../datasource/CollectionPage';
import type { Page } from '../iam/types';
import type { ReleaseTestReturn } from './types';
import { KnowledgeTestSuggestions } from './KnowledgeTestSuggestions';
import { useKnowledgeDraft } from './useKnowledgeDraft';
import './knowledge.css';

/** 题单中准备的实际判定条件。 */
type TestInput = {
  /** 确切的文档及修订。 */
  documentId: string;
  documentRevisionId: string;
  /** 题目与判定类型。 */
  question: string;
  coverage: 'CAPABILITY' | 'BOUNDARY';
  mode: 'NUMERIC' | 'EXPLANATION';
  /** 独立预期和来源。 */
  expectedCount?: number;
  expectedExplanation?: string;
  evidence?: string;
  /** 数值题使用的实际治理身份和过滤值。 */
  countRule?: {
    metricId?: string;
    relationId?: string;
    categoryId?: string;
    categoryValue?: string;
  };
};

/** 同源题单读取摘要和当前内容。 */
type TestRow = {
  /** 题目稳定身份及归属。 */
  id: string;
  documentId: string;
  /** 可审核题干、版本和实际状态。 */
  question: string;
  version: number;
  revisionId: string;
  state: 'DRAFT' | 'REVIEWED' | 'REVIEW';
};

/**
 * 同一份题目详情同时用于确定题单范围和渲染编辑器。
 */
type TestDetail = {
  /** 题目、审核状态与关联文档身份。 */
  test: TestRow;
  /** 当次修订保存的判定字段。 */
  content: Omit<TestInput, 'documentId'>;
};

/** 题单通过实际已确认修订准备预期和依据。 */
export function KnowledgeTestPage() {
  const { params, setParams, sources, selected } = useSourceSelection('TEST_CASES');
  const location = useLocation();
  const navigate = useNavigate();
  const open = params.get('testId') ?? undefined;
  const [suggesting, setSuggesting] = useState(false);
  const focusTest = (location.state as { focusTest?: string } | null)?.focusTest;
  const restored = useRef<string>(undefined);
  const session = useSession();
  const taskReturn = (location.state as { releaseTestReturn?: ReleaseTestReturn } | null)
    ?.releaseTestReturn;
  const documentFilter = taskReturn?.document.id ?? params.get('documentId') ?? undefined;
  const source = !taskReturn || selected?.id === params.get('source') ? selected?.id : undefined;
  const path = '/knowledge/sources/' + source + '/test-cases';
  const size = Number(params.get('pageSize') ?? 10);
  const page = Number(params.get('pageNum') ?? 1);
  const reviewingQueue = Boolean(open && open !== 'new');
  const detail = useQuery({
    queryKey: ['knowledge-test', source, open],
    enabled: Boolean(source && reviewingQueue),
    // 进入题目后保留当前待审修订，后台校验写入版本。
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
    queryFn: () => api<TestDetail>(path + '/' + open),
  });
  const queueDocument = documentFilter ?? detail.data?.test.documentId;
  const queuePage = Number(params.get('queuePageNum') ?? (documentFilter ? page : 1));
  const listDocument = reviewingQueue ? queueDocument : documentFilter;
  const listPage = reviewingQueue ? queuePage : page;
  const list = useQuery({
    queryKey: ['knowledge-tests', source, listPage, size, listDocument],
    enabled: Boolean(source && (!reviewingQueue || queueDocument)),
    queryFn: () =>
      api<Page<TestRow>>(
        path +
          '?' +
          queryString({
            pageNum: listPage,
            pageSize: size,
            documentId: listDocument,
          }),
      ),
  });
  const documents = useQuery({
    queryKey: ['knowledge-test-documents', source],
    enabled: Boolean(source) && !taskReturn,
    queryFn: () =>
      api<Page<{ id: string; title: string; revisionId: string }>>(
        path + '/document-options?pageSize=100',
      ),
  });
  // 任务自带明确的目标修订，关联题不用在通用文档选项的第一页查找。
  const docChoices = taskReturn ? [taskReturn.document] : (documents.data?.items ?? []);
  const suggestionDocuments = documentFilter
    ? docChoices.filter((doc) => doc.id === documentFilter)
    : docChoices;
  const maySuggest = allowed(session, 'test-cases.edit') && allowed(session, 'knowledge.view');
  const queueName = docChoices.find((doc) => doc.id === queueDocument)?.title;
  const queueRows = list.data?.items ?? [];
  const activeQuestion = detail.data?.test;
  // 从总单后面几页打开题目时，当前题也保留在同文档队列中。
  const visibleQueue =
    activeQuestion?.documentId === queueDocument &&
    activeQuestion &&
    !queueRows.some((row) => row.id === activeQuestion.id)
      ? [activeQuestion, ...queueRows]
      : queueRows;

  useEffect(() => {
    if (open || !focusTest || !list.isSuccess || restored.current === location.key) return;
    restored.current = location.key;
    // 关闭办理区后恢复题单刚才使用的入口。
    (
      document.getElementById('knowledge-test-open-' + focusTest) ??
      document.getElementById('knowledge-test-open-new')
    )?.focus();
  }, [open, focusTest, list.isSuccess, location.key]);

  /** 同一地址保留题目、来源和筛选，切换由当前编辑器保护。 */
  function openTest(id: string, replace = false) {
    if (id === open) {
      document.getElementById('knowledge-test-heading')?.focus();
      return;
    }
    setParams(
      queryString({
        source,
        documentId: documentFilter,
        pageNum: page,
        pageSize: size,
        testId: id,
        queuePageNum: reviewingQueue ? queuePage : undefined,
      }),
      { replace, state: location.state },
    );
  }

  return (
    <main className="record-page" aria-label="标准题单">
      {!open && (
        <>
          {/* 概览页使用原型的来源、主操作和独立文档筛选。 */}
          <div className="panel ds-toolbar knowledge-source-toolbar knowledge-test-toolbar">
            <SourcePicker
              withLabel
              sources={taskReturn ? (source && selected ? [selected] : []) : sources.data}
              selected={source ? selected : undefined}
              choose={(next) => {
                // 记录及筛选始终属于一个数据源。
                setParams(queryString({ source: next, pageSize: size }));
              }}
            />
            <Select
              aria-label="关联文档"
              className="knowledge-document-select"
              showSearch={{ optionFilterProp: 'label' }}
              placeholder="全部文档"
              allowClear={!taskReturn}
              value={documentFilter}
              options={docChoices.map((doc) => ({ value: doc.id, label: doc.title }))}
              loading={documents.isPending && Boolean(source)}
              disabled={!!taskReturn || !docChoices.length}
              onChange={(documentId) =>
                setParams(queryString({ source, documentId, pageSize: size }), {
                  state: location.state,
                })
              }
            />
            {/* 办理相关题后直接回到原任务，本页仍沿用题单工具栏。 */}
            <Space size={8} wrap>
              {taskReturn && allowed(session, 'releases.view') && (
                <Button
                  onClick={() =>
                    navigate('/releases/' + taskReturn.taskId + taskReturn.search, {
                      state: { releaseFocus: 'release-prepare-tests' },
                    })
                  }
                >
                  返回发布任务
                </Button>
              )}
              {source && allowed(session, 'test-cases.edit') && (
                <Button
                  id="knowledge-test-open-new"
                  type="primary"
                  icon={<PlusOutlined aria-hidden="true" />}
                  onClick={() => openTest('new')}
                  disabled={!docChoices.length || selected?.lifecycle !== 'ENABLED'}
                >
                  新增标准题
                </Button>
              )}
              {source && maySuggest && (
                <Button
                  onClick={() => setSuggesting(true)}
                  disabled={!suggestionDocuments.length || selected?.lifecycle !== 'ENABLED'}
                >
                  生成候选题
                </Button>
              )}
            </Space>
          </div>
          {/* 标准题建议沿用当前文档筛选，采纳后从原题单打开草稿。 */}
          {source && maySuggest && suggesting && suggestionDocuments.length > 0 && (
            <KnowledgeTestSuggestions
              key={`${source}:${documentFilter ?? ''}`}
              source={source}
              documents={suggestionDocuments}
              close={() => setSuggesting(false)}
              openTest={(id) => {
                setSuggesting(false);
                openTest(id);
              }}
            />
          )}
        </>
      )}
      <ReadState
        error={sources.error}
        loading={sources.isPending}
        retry={() => void sources.refetch()}
      >
        {source && selected ? (
          <>
            {open ? (
              <div
                className={
                  open === 'new' ? 'knowledge-test-editor' : 'panel knowledge-test-workspace'
                }
              >
                {open !== 'new' && (
                  <aside className="knowledge-test-queue" aria-label="题目队列">
                    <header>
                      <h2>关联题目</h2>
                      {list.data && <span>共 {list.data.total} 道</span>}
                    </header>
                    {queueName && (
                      <p className="knowledge-queue-document cell-text" title={queueName}>
                        {queueName}
                      </p>
                    )}
                    <ReadState
                      loading={(detail.isPending || list.isPending) && !detail.error}
                      error={detail.error ?? list.error}
                      retry={() => void (detail.error ? detail.refetch() : list.refetch())}
                    >
                      <nav aria-label="切换标准题">
                        {visibleQueue.map((row) => (
                          <button
                            type="button"
                            key={row.id}
                            aria-current={open === row.id ? 'true' : undefined}
                            onClick={() => openTest(row.id)}
                          >
                            <strong>{row.question}</strong>
                            <span>{testStates[row.state]}</span>
                          </button>
                        ))}
                        {list.data?.total === 0 && (
                          <p className="knowledge-action-note">暂无相关题目</p>
                        )}
                      </nav>
                      {(list.data?.total ?? 0) > size && (
                        <Pagination
                          current={queuePage}
                          pageSize={size}
                          total={list.data?.total}
                          showSizeChanger={false}
                          simple={{ readOnly: true }}
                          onChange={(pageNum) => {
                            // 队列分页独立，面包屑回到原题单的页码。
                            const next = new URLSearchParams(params);
                            next.set('queuePageNum', String(pageNum));
                            setParams(next, { state: location.state });
                          }}
                        />
                      )}
                    </ReadState>
                  </aside>
                )}
                <div className="knowledge-test-editor">
                  {/* 文档选项到齐后再挂载题目表单，读取期间不显示内部编号。 */}
                  <ReadState
                    loading={!taskReturn && documents.isPending}
                    error={taskReturn ? null : documents.error}
                    retry={() => void documents.refetch()}
                  >
                    <TestEditor
                      key={source + ':' + open}
                      source={source}
                      sourceName={selected.name}
                      id={open}
                      detail={detail}
                      documents={docChoices}
                      preferredDocument={documentFilter}
                      opened={(id) => openTest(id, true)}
                    />
                  </ReadState>
                </div>
              </div>
            ) : (
              <section className="panel records-panel knowledge-records" aria-label="标准题列表">
                <RecordTable
                  rowKey="id"
                  dataSource={list.data?.items}
                  loading={list.isPending}
                  error={list.error}
                  retry={() => void list.refetch()}
                  emptyDescription={
                    !docChoices.length && !documents.isPending && !documents.error
                      ? '确认关联文档后可新增标准题'
                      : '暂无标准题'
                  }
                  filtered={!taskReturn && Boolean(documentFilter)}
                  onReset={() => setParams(queryString({ source, pageSize: size }))}
                  tableLayout="fixed"
                  flexibleColumns={[0, 1]}
                  columns={[
                    {
                      title: '题目',
                      width: 420,
                      dataIndex: 'question',
                      render: (name, row) => (
                        <Button
                          type="link"
                          id={'knowledge-test-open-' + row.id}
                          className="knowledge-record-link"
                          title={name as string}
                          aria-current={open === row.id ? 'true' : undefined}
                          onClick={() => openTest(row.id)}
                        >
                          {name}
                        </Button>
                      ),
                    },
                    {
                      title: '关联文档',
                      width: 192,
                      render: (_, row) =>
                        docChoices.find((doc) => doc.id === row.documentId)?.title ?? '—',
                    },
                    {
                      title: '状态',
                      width: 104,
                      render: (_, row) => (
                        <Tag color={row.state === 'REVIEWED' ? 'success' : undefined}>
                          {testStates[row.state]}
                        </Tag>
                      ),
                    },
                    {
                      title: '操作',
                      width: 112,
                      fixed: 'right',
                      className: 'record-actions-cell',
                      render: (_, row) => (
                        <Button type="link" onClick={() => openTest(row.id)}>
                          {row.state !== 'REVIEWED' && allowed(session, 'test-cases.review')
                            ? '核对'
                            : '查看'}
                        </Button>
                      ),
                    },
                  ]}
                  pagination={{
                    current: page,
                    pageSize: size,
                    total: list.data?.total,
                    onChange: (pageNum, pageSize) =>
                      setParams(
                        queryString({ source, documentId: documentFilter, pageNum, pageSize }),
                        { state: location.state },
                      ),
                  }}
                />
              </section>
            )}
            {!taskReturn && documents.error && (
              <Alert type="error" title={readErrorMessage(documents.error)} />
            )}
          </>
        ) : (
          <SourceUnavailable />
        )}
      </ReadState>
    </main>
  );
}

/** 读取当前题目并以实际修订初始化编辑区。 */
function TestEditor({
  source,
  sourceName,
  id,
  detail,
  documents,
  preferredDocument,
  opened,
}: {
  source: string;
  /** 独立详情显示来源而不重新展示列表选择器。 */
  sourceName: string;
  id: string;
  /** 当前题目详细读取与队列共用。 */
  detail: UseQueryResult<TestDetail>;
  preferredDocument?: string;
  documents: { id: string; title: string; revisionId: string }[];
  opened: (id: string) => void;
}) {
  const path = '/knowledge/sources/' + source + '/test-cases';
  if (id === 'new')
    return (
      <TestForm
        source={source}
        sourceName={sourceName}
        path={path}
        documents={documents}
        preferredDocument={preferredDocument}
        opened={opened}
      />
    );
  return (
    <ReadState loading={detail.isPending} error={detail.error} retry={() => void detail.refetch()}>
      {detail.data && (
        <TestForm
          key={detail.data.test.revisionId}
          source={source}
          sourceName={sourceName}
          path={path}
          current={detail.data}
          documents={documents}
          opened={opened}
        />
      )}
    </ReadState>
  );
}

/** 同一表单分别执行保存和审核，期望条件始终可独立回看。 */
function TestForm({
  source,
  sourceName,
  path,
  current,
  documents,
  preferredDocument,
  opened,
}: {
  source: string;
  /** 本题已经固定的来源名称。 */
  sourceName: string;
  path: string;
  preferredDocument?: string;
  current?: { test: TestRow; content: Omit<TestInput, 'documentId'> };
  documents: { id: string; title: string; revisionId: string }[];
  opened: (id: string) => void;
}) {
  const session = useSession();
  const { message } = App.useApp();
  const cache = useQueryClient();
  const [form] = Form.useForm<TestInput>();
  const draft = useKnowledgeDraft();
  const heading = useRef<HTMLHeadingElement>(null);
  const initial: Partial<TestInput> = current
    ? { ...current.content, documentId: current.test.documentId }
    : { mode: 'NUMERIC', coverage: 'CAPABILITY', documentId: preferredDocument };
  const mode = Form.useWatch('mode', form) ?? initial.mode;
  const docId = Form.useWatch('documentId', form) ?? initial.documentId;
  const editRevision =
    Form.useWatch('documentRevisionId', { form, preserve: true }) ?? initial.documentRevisionId;
  const doc = documents.find(({ id }) => id === docId);
  const revisionId = current ? editRevision : doc?.revisionId;
  const outdated = Boolean(current && doc && revisionId !== doc.revisionId);
  const [error, setError] = useState<Error>();
  const [step, setStep] = useState(current ? 'expected' : 'scope');
  const commandRef = useRef<{ body: string; id: string }>(undefined);
  const editing = allowed(session, 'test-cases.edit');
  const reviewing = allowed(session, 'test-cases.review');
  const basis = useQuery({
    queryKey: ['knowledge-test-basis', source, doc?.id, revisionId],
    enabled: Boolean(doc && revisionId && mode === 'NUMERIC' && !outdated),
    queryFn: () =>
      api<{ id: string; kind: string; label: string }[]>(
        path + '/document-options/' + doc?.id + '/facts?' + queryString({ revisionId }),
      ),
  });
  const save = useMutation({
    onMutate: draft.startWrite,
    mutationFn: async (input: { action: 'save' | 'review'; values?: TestInput }) => {
      const review = input.action === 'review';
      const payload = review
        ? { expectedVersion: current?.test.version, revisionId: current?.test.revisionId }
        : {
            ...input.values,
            countRule: input.values?.mode === 'NUMERIC' ? input.values?.countRule : undefined,
            documentRevisionId: revisionId,
            expectedVersion: current?.test.version,
          };
      const signature = JSON.stringify({ action: input.action, payload });
      if (commandRef.current?.body !== signature) {
        // 重试同一输入继续使用原命令。
        commandRef.current = { body: signature, id: crypto.randomUUID() };
      }
      const url = current ? path + '/' + current.test.id + (review ? '/reviews' : '/draft') : path;
      return mutation(
        url,
        current && !review ? 'PUT' : 'POST',
        payload,
        review ? 'test-cases.review' : 'test-cases.edit',
        commandRef.current.id,
      );
    },
    onSuccess: async (response, input) => {
      draft.finishWrite(true);
      setError(undefined);
      await Promise.all([
        cache.invalidateQueries({ queryKey: ['knowledge-tests', source] }),
        cache.invalidateQueries({ queryKey: ['knowledge-test', source, response.targetId] }),
      ]);
      if (response.targetId) {
        // 正式写入或回查的明确编号用于定位题目。
        opened(response.targetId);
      }
      void message.success(input.action === 'review' ? '题目已审核' : '题目草稿已保存');
    },
    onError: (failure, input) => {
      draft.finishWrite(false);
      // 审核拒绝时把计算条件和库内依据留在同一页签。
      if (input.action === 'review' && current?.content.mode === 'NUMERIC') setStep('expected');
      setError(failure);
    },
  });

  useEffect(() => {
    // 行内表单打开后定位标题，键盘用户可直接进入当前题。
    heading.current?.focus();
  }, []);

  useEffect(() => {
    // 固定底栏提交后回到可见的错误，窄窗口也能直接读到原因。
    if (error) document.getElementById('knowledge-test-error')?.focus();
  }, [error]);

  /** 只允许选取文档实际采用的类型与确切治理身份。 */
  function options(kind: string) {
    return (
      basis.data
        ?.filter((fact) => fact.kind === kind)
        .map((fact) => ({ value: fact.id, label: fact.label })) ?? []
    );
  }

  /** 更新依据前由用户明确选择，并清除需要重选的数值字段身份。 */
  function useCurrentRevision() {
    form.setFieldValue('documentRevisionId', doc?.revisionId);
    form.setFieldValue('countRule', undefined);
    draft.change(true);
    setError(undefined);
  }

  const missing = current ? reviewNeeds(current.content) : [];
  const needsReview = current && current.test.state !== 'REVIEWED' && reviewing;

  return (
    <section className="panel knowledge-document" aria-label="标准题详情">
      <header className="knowledge-heading">
        <div className="knowledge-heading-main">
          <h2 id="knowledge-test-heading" ref={heading} tabIndex={-1}>
            {current?.test.question ?? '新增标准题'}
          </h2>
          <small className="knowledge-source-name">{sourceName}</small>
          {current && (
            <Tag color={current.test.state === 'REVIEWED' ? 'success' : undefined}>
              {testStates[current.test.state]}
            </Tag>
          )}
        </div>
        {current && doc && (
          <div className="knowledge-test-context">
            <span>关联文档</span>
            {allowed(session, 'knowledge.view') ? (
              <Link to={'/knowledge/' + doc.id + '?' + queryString({ source })}>{doc.title}</Link>
            ) : (
              <span>{doc.title}</span>
            )}
          </div>
        )}
      </header>
      <Form
        form={form}
        layout="vertical"
        className="knowledge-content knowledge-edit"
        disabled={save.isPending}
        scrollToFirstError={{ focus: true }}
        onValuesChange={(_, values) => {
          draft.change(testDraftKey(values) !== testDraftKey(initial));
          setError(undefined);
        }}
        initialValues={initial}
        onFinish={(values) => save.mutate({ action: 'save', values })}
        onFinishFailed={({ errorFields }) => {
          // 预期页提交时，范围页的字段错误在原位置提示。
          setStep('scope');
          const field = errorFields[0]?.name;
          if (field) requestAnimationFrame(() => form.scrollToField(field, { focus: true }));
        }}
      >
        <Form.Item name="documentRevisionId" hidden>
          <Input />
        </Form.Item>
        {outdated && (
          <Alert
            type="warning"
            title="关联文档已有新的确认修订"
            action={editing && <Button onClick={useCurrentRevision}>重新绑定依据</Button>}
          />
        )}
        {error && (
          <div id="knowledge-test-error" tabIndex={-1}>
            <Alert type="error" title={readErrorMessage(error)} />
          </div>
        )}
        {/* 两个页签都保留已注册表单，切换时不清除任何输入。 */}
        <Tabs
          className="knowledge-test-tabs"
          activeKey={step}
          onChange={setStep}
          items={[
            {
              key: 'scope',
              label: '问题与范围',
              forceRender: true,
              children: (
                <>
                  {/* 已绑定文档由页头提供，新建时才能选择。 */}
                  <Form.Item
                    name="documentId"
                    hidden={Boolean(current)}
                    label="关联文档"
                    rules={[{ required: true, message: '请选择已确认文档' }]}
                  >
                    <Select
                      disabled={Boolean(current) || !editing}
                      options={documents.map((item) => ({ value: item.id, label: item.title }))}
                    />
                  </Form.Item>
                  <Form.Item
                    name="question"
                    label="题目"
                    rules={[{ required: true, whitespace: true, message: '请填写题目' }]}
                  >
                    <Input.TextArea
                      maxLength={300}
                      autoSize={{ minRows: 1, maxRows: 3 }}
                      disabled={!editing}
                    />
                  </Form.Item>
                  <div className="knowledge-test-options">
                    <Form.Item name="coverage" label="覆盖类型">
                      <Select
                        disabled={!editing}
                        options={[
                          { value: 'CAPABILITY', label: '关键能力' },
                          { value: 'BOUNDARY', label: '使用边界' },
                        ]}
                      />
                    </Form.Item>
                    <Form.Item name="mode" label="判定方式">
                      <Select
                        disabled={!editing}
                        options={[
                          { value: 'NUMERIC', label: '去重计数（自动）' },
                          { value: 'EXPLANATION', label: '按依据人工裁定' },
                        ]}
                      />
                    </Form.Item>
                  </div>
                </>
              ),
            },
            {
              key: 'expected',
              label: '预期与判定',
              forceRender: true,
              children: (
                <>
                  <div className="knowledge-test-judgment">
                    <section aria-label="判定条件">
                      {mode === 'NUMERIC' ? (
                        <>
                          <Form.Item name="expectedCount" label="期望去重数">
                            <InputNumber min={0} precision={0} disabled={!editing} />
                          </Form.Item>
                          {/* 指标与关联共同决定计数口径，筛选字段和取值并排核对。 */}
                          <div className="knowledge-test-options">
                            <Form.Item name={['countRule', 'metricId']} label="去重指标">
                              <Select options={options('METRIC')} disabled={!editing} />
                            </Form.Item>
                            <Form.Item name={['countRule', 'relationId']} label="使用关联">
                              <Select options={options('RELATION')} disabled={!editing} />
                            </Form.Item>
                          </div>
                          <div className="knowledge-test-options">
                            <Form.Item name={['countRule', 'categoryId']} label="筛选字段">
                              <Select options={options('COLUMN')} disabled={!editing} />
                            </Form.Item>
                            <Form.Item name={['countRule', 'categoryValue']} label="筛选值">
                              <Input maxLength={80} disabled={!editing} />
                            </Form.Item>
                          </div>
                        </>
                      ) : (
                        <Form.Item name="expectedExplanation" label="判定要点">
                          <Input.TextArea rows={3} maxLength={3000} disabled={!editing} />
                        </Form.Item>
                      )}
                    </section>
                    <section aria-label="判定依据" className="knowledge-test-evidence">
                      <Form.Item name="evidence" label="数据与判定依据">
                        <Input.TextArea rows={6} maxLength={1000} disabled={!editing} />
                      </Form.Item>{' '}
                    </section>
                  </div>
                </>
              ),
            },
          ]}
        />
        {basis.error && <Alert type="warning" title={readErrorMessage(basis.error)} />}
        <footer className="knowledge-actions">
          {needsReview && (draft.changed || missing.length > 0 || outdated) && (
            <span id="knowledge-review-reason" className="knowledge-action-note">
              {draft.changed
                ? '保存草稿后审核'
                : outdated
                  ? '重新核对文档依据后审核'
                  : '审核前需补齐' + missing.join('、')}
            </span>
          )}
          <Space wrap>
            {editing && (
              <Button
                htmlType="submit"
                type={
                  needsReview && !draft.changed && !missing.length && !outdated
                    ? 'default'
                    : 'primary'
                }
                disabled={!doc || !draft.changed}
                loading={save.isPending}
              >
                保存草稿
              </Button>
            )}
            {needsReview && (
              <Button
                type={!draft.changed && !missing.length && !outdated ? 'primary' : 'default'}
                disabled={draft.changed || missing.length > 0 || outdated}
                loading={save.isPending}
                aria-describedby={
                  draft.changed || missing.length > 0 || outdated
                    ? 'knowledge-review-reason'
                    : undefined
                }
                onClick={() => save.mutate({ action: 'review' })}
              >
                审核当前题目
              </Button>
            )}
          </Space>
        </footer>
      </Form>
    </section>
  );
}

/** 当前审核状态在列表与编辑区表达一致。 */
const testStates = { DRAFT: '草稿', REVIEWED: '已审核', REVIEW: '待复核' };

/** 审核操作使用已保存内容，缺项关联到当前按钮。 */
function reviewNeeds(content: Omit<TestInput, 'documentId'>) {
  const missing: string[] = [];
  if (!content.evidence?.trim()) missing.push('数据与判定依据');
  if (content.mode === 'EXPLANATION') {
    if (!content.expectedExplanation?.trim()) missing.push('判定要点');
  } else {
    if (content.expectedCount == null) missing.push('期望去重数');
    const rule = content.countRule;
    if (!rule?.metricId || !rule.relationId || !rule.categoryId || !rule.categoryValue?.trim())
      missing.push('去重指标、关联及筛选条件');
  }
  return missing;
}

/** 表单按当前判定方式比较，切换后保留输入但只保存有效条件。 */
function testDraftKey(input: Partial<TestInput>) {
  const rule = input.countRule;
  return JSON.stringify([
    input.documentId,
    input.documentRevisionId,
    input.question ?? '',
    input.mode,
    input.coverage,
    input.evidence ?? '',
    input.mode === 'NUMERIC'
      ? [
          input.expectedCount ?? null,
          rule?.metricId,
          rule?.relationId,
          rule?.categoryId,
          rule?.categoryValue,
        ]
      : (input.expectedExplanation ?? ''),
  ]);
}
