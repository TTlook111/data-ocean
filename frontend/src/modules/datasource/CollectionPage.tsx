import { DetailDialog } from '../../shared/DetailDialog';
import {
  App,
  Button,
  Descriptions,
  Empty,
  Form,
  Input,
  Modal,
  Progress,
  Select,
  Space,
} from 'antd';
import {
  CheckOutlined,
  DatabaseOutlined,
  PlayCircleOutlined,
  SearchOutlined,
} from '@ant-design/icons';
import dayjs from 'dayjs';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router';
import { api, queryString } from '../../shared/api';
import { LoadingStatus } from '../../shared/LoadingIndicator';
import { displayTime, ReadState } from '../../shared/Records';
import { RecordTable } from '../../shared/RecordTable';
import { MoreFilters } from '../../shared/MoreFilters';
import { QueryDateRange } from '../../shared/QueryDateRange';
import { ReviewDialog } from '../../shared/ReviewDialog';
import type { ReviewSubmission } from '../../shared/ReviewDialog';
import { allowed, useSession } from '../../shared/session';
import type { Page, Preview } from '../iam/types';
import { DatasourceStatus, datasourceLabel, datasourceOptions } from './Presentation';
import type { CollectionTask, SourceOption } from './types';

/**
 * 采集记录和目录共用当前来源选择与 URL 状态。
 */
export function useSourceSelection(
  purpose:
    | 'CATALOG'
    | 'COLLECTIONS'
    | 'BUSINESS_NOTES'
    | 'GOVERNANCE'
    | 'KNOWLEDGE'
    | 'TEST_CASES'
    | 'KNOWLEDGE_RELEASES',
) {
  const [params, setParams] = useSearchParams();
  const sources = useQuery({
    queryKey: ['datasource-options', purpose],
    queryFn: () => api<SourceOption[]>('/datasources/options?purpose=' + purpose),
  });
  const selected =
    sources.data?.find((source) => source.id === params.get('source')) ??
    (['BUSINESS_NOTES', 'GOVERNANCE', 'KNOWLEDGE', 'TEST_CASES', 'KNOWLEDGE_RELEASES'].includes(
      purpose,
    )
      ? sources.data?.find((source) => source.lifecycle === 'ENABLED')
      : undefined) ??
    sources.data?.[0];
  return { params, setParams, sources, selected };
}

/**
 * 使用同一个只读的来源选择组件，切换源后恢复该源的记录范围。
 */
export function SourcePicker({
  sources,
  selected,
  choose,
  withLabel = false,
}: {
  sources?: SourceOption[];
  selected?: SourceOption;
  choose: (id: string) => void;
  /** 有独立来源栏的页面沿用原型的明示来源标签。 */
  withLabel?: boolean;
}) {
  const picker = (
    <Select
      className="ds-source-picker"
      showSearch
      optionFilterProp="label"
      placeholder="选择数据源"
      prefix={<DatabaseOutlined aria-hidden="true" />}
      aria-label="当前数据源"
      disabled={!sources?.length}
      value={selected?.id}
      options={sources?.map((source) => ({ value: source.id, label: source.name })) ?? []}
      onChange={choose}
    />
  );
  return withLabel ? (
    <div className="ds-source-context">
      <span>当前数据源</span>
      {picker}
    </div>
  ) : (
    picker
  );
}

/**
 * 当前没有可选源时保留数据源管理入口，按页面权限展示。
 */
export function SourceUnavailable() {
  const session = useSession();
  const navigate = useNavigate();
  return (
    <div className="panel ds-source-empty">
      <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无可选数据源">
        {allowed(session, 'sources.view') && (
          <Button onClick={() => navigate('/datasources')}>前往数据源管理</Button>
        )}
      </Empty>
    </div>
  );
}

/**
 * 采集和重试沿用一次真实采集范围与账号核对。
 */
export async function prepareCollection(
  source: SourceOption,
  done: (taskId: string) => void,
  retryOfTaskId?: string,
): Promise<ReviewSubmission> {
  const path = '/datasources/' + source.id;
  const proposal = { retryOfTaskId };
  const expectedVersion = source.version;
  const preview = await api<Preview>(path + '/impact-preview', {
    method: 'POST',
    body: { action: 'START_COLLECTION', expectedVersion, proposal },
  });
  return {
    title: retryOfTaskId ? '重新采集' : '发起采集',
    preview,
    path: path + '/collections',
    method: 'POST',
    operation: retryOfTaskId ? 'collections.retry' : 'collections.collect',
    body: { expectedVersion, retryOfTaskId },
    confirmText: '开始采集',
    successText: '采集任务已发起',
    done,
  };
}

/**
 * 任务记录显示服务器真实状态、完整进度与回看的后续操作。
 */
export function CollectionPage() {
  const session = useSession();
  const cache = useQueryClient();
  const { message } = App.useApp();
  const navigate = useNavigate();
  const { params, setParams, sources, selected } = useSourceSelection('COLLECTIONS');
  const [form] = Form.useForm();
  const [moreOpen, setMoreOpen] = useState(false);
  const [opened, setOpened] = useState<string | null>(params.get('task'));
  const [review, setReview] = useState<ReviewSubmission | null>(null);
  const [cancelling, setCancelling] = useState<CollectionTask | null>(null);
  const [reason, setReason] = useState('');
  const page = Number(params.get('pageNum') ?? 1);
  const size = Number(params.get('pageSize') ?? 10);
  const list = useQuery({
    queryKey: ['collection-list', selected?.id, params.toString()],
    queryFn: () =>
      api<Page<CollectionTask>>(
        '/datasources/' + selected?.id + '/collections?' + queryString(Object.fromEntries(params)),
      ),
    enabled: Boolean(selected),
    refetchInterval: (query) =>
      query.state.data?.items.some((task) => ['QUEUED', 'RUNNING'].includes(task.state))
        ? 1200
        : false,
  });
  const detail = useQuery({
    queryKey: ['collection', opened],
    queryFn: () => api<CollectionTask>('/collection-tasks/' + opened),
    enabled: Boolean(opened),
    refetchInterval: (query) =>
      ['QUEUED', 'RUNNING'].includes(query.state.data?.state ?? '') ? 1000 : false,
  });
  const active = ['QUEUED', 'RUNNING'].includes(detail.data?.state ?? '') && !detail.error;
  // 读回失败时不在底栏保留旧状态计算出的动作。
  const task = detail.error ? undefined : detail.data;
  const from = params.get('fromDate');
  const to = params.get('toDate');
  const hasFilters = ['taskNoKeyword', 'triggerKind', 'fromDate', 'toDate', 'state'].some((key) =>
    params.has(key),
  );

  /** 关闭由采集回执打开的详情时，刷新后也停留在原列表。 */
  function closeTask() {
    setOpened(null);
    if (params.has('task')) {
      const next = new URLSearchParams(params);
      next.delete('task');
      setParams(next, { replace: true });
    }
  }

  /**
   * 清空任务筛选时保留当前源和每页条数。
   */
  function reset() {
    setMoreOpen(false);
    form.setFieldsValue({
      taskNoKeyword: undefined,
      triggerKind: undefined,
      dates: undefined,
      state: undefined,
    });
    setParams({ source: selected?.id ?? '', pageSize: String(size) });
    form.focusField('taskNoKeyword');
  }

  useEffect(() => {
    // 订阅真实任务版本，事件只触发读取，同步失败时仍由查询回读原任务。
    if (!opened || !active) return;
    const stream = new EventSource('/api/v1/collection-tasks/' + opened + '/events');
    const updated = () => {
      void cache.invalidateQueries({ queryKey: ['collection', opened] });
      void cache.invalidateQueries({ queryKey: ['collection-list'] });
    };
    for (const name of [
      'collection.snapshot',
      'collection.progress',
      'task.cancellation',
      'collection.finished',
    ]) {
      // 只响应当前任务允许的状态事件。
      stream.addEventListener(name, updated);
    }
    stream.onerror = () => stream.close();
    return () => stream.close();
  }, [active, opened, cache]);

  /**
   * 从当前来源和原任务继续办理，操作成功后回读实际结果。
   */
  async function start(parent?: string) {
    if (!selected) return;
    try {
      setReview(
        await prepareCollection(
          selected,
          (taskId) => {
            setReview(null);
            setOpened(taskId);
            if (params.has('task')) {
              const next = new URLSearchParams(params);
              next.set('task', taskId);
              setParams(next, { replace: true });
            }
            void list.refetch();
          },
          parent,
        ),
      );
    } catch (error) {
      message.error((error as Error).message);
    }
  }

  /**
   * 取消使用实际任务版本，读进度后再说明后果。
   */
  async function cancel() {
    if (!cancelling) return;
    const task = await api<CollectionTask>('/collection-tasks/' + cancelling.taskId);
    const path = '/collection-tasks/' + task.taskId;
    const expectedVersion = task.version;
    try {
      const preview = await api<Preview>(path + '/impact-preview', {
        method: 'POST',
        body: { action: 'CANCEL_COLLECTION', expectedVersion, proposal: { reason } },
      });
      setCancelling(null);
      setReview({
        title: '核对取消采集',
        preview,
        path: path + '/cancel',
        method: 'POST',
        operation: 'collections.cancel',
        body: { reason, expectedVersion },
        danger: true,
        confirmText: '确认取消采集',
        done: () => {
          setReview(null);
          void list.refetch();
          void detail.refetch();
        },
      });
    } catch (error) {
      message.error((error as Error).message);
    }
  }

  return (
    <section className="record-page" aria-label="采集记录">
      {/* 来源、记录查询及主要操作沿用同一工作区。 */}
      <div className="panel ds-toolbar">
        <SourcePicker
          withLabel
          sources={sources.data}
          selected={selected}
          choose={(source) => {
            // 切换来源时清理原任务及未应用的筛选输入。
            setOpened(null);
            form.setFieldsValue({
              taskNoKeyword: undefined,
              triggerKind: undefined,
              dates: undefined,
              state: undefined,
            });
            setParams({ source });
          }}
        />
        {selected?.lifecycle === 'ENABLED' && allowed(session, 'collections.collect') && (
          <Button type="primary" icon={<PlayCircleOutlined />} onClick={() => void start()}>
            发起采集
          </Button>
        )}
      </div>
      <ReadState
        error={sources.error}
        loading={sources.isPending}
        retry={() => void sources.refetch()}
      >
        {selected ? (
          <>
            {/* 仅在存在可用来源时显示该源的记录筛选。 */}
            <div className="panel query-panel ds-query-panel compact-query">
              <Form
                name="collection-query"
                layout="inline"
                form={form}
                initialValues={{
                  ...Object.fromEntries(params),
                  dates:
                    from || to ? [from ? dayjs(from) : null, to ? dayjs(to) : null] : undefined,
                }}
                onFinish={() => {
                  // 保留未展开的日期条件，只有查询或重置才应用。
                  const values = form.getFieldsValue(true);
                  setMoreOpen(false);
                  // 将实际开始日区间放入查询参数，切页时保留已确认条件。
                  const range = values.dates;
                  setParams(
                    new URLSearchParams(
                      queryString({
                        source: selected?.id,
                        taskNoKeyword: values.taskNoKeyword,
                        triggerKind: values.triggerKind,
                        fromDate: range?.[0]?.format('YYYY-MM-DD'),
                        toDate: range?.[1]?.format('YYYY-MM-DD'),
                        state: values.state,
                        pageSize: size,
                      }).replace(/^\?/, ''),
                    ),
                  );
                  form.focusField('taskNoKeyword');
                }}
              >
                <Form.Item name="taskNoKeyword">
                  <Input
                    aria-label="采集编号"
                    placeholder="采集编号"
                    prefix={<SearchOutlined />}
                    allowClear
                  />
                </Form.Item>
                <Form.Item name="triggerKind">
                  <Select
                    aria-label="发起方式"
                    placeholder="发起方式"
                    allowClear
                    options={datasourceOptions(['MANUAL', 'RETRY', 'SCHEDULED'])}
                  />
                </Form.Item>
                {/* 日期收入更多筛选，常用的采集条件和操作保持一行。 */}
                <Form.Item name="state" className="status-query">
                  <Select
                    aria-label="任务状态"
                    placeholder="任务状态"
                    allowClear
                    options={datasourceOptions([
                      'QUEUED',
                      'RUNNING',
                      'SUCCEEDED',
                      'FAILED',
                      'CANCELLED',
                    ])}
                  />
                </Form.Item>
                <div className="query-tail-actions">
                  <Space>
                    <MoreFilters
                      open={moreOpen}
                      setOpen={setMoreOpen}
                      count={Number(Boolean(from || to))}
                    >
                      <Form.Item name="dates" className="query-more-date">
                        <QueryDateRange label="采集开始时间范围" />
                      </Form.Item>
                    </MoreFilters>
                    <Button htmlType="submit">查询</Button>
                    <Button onClick={reset}>重置</Button>
                  </Space>
                </div>
              </Form>
            </div>
            {/* 任务结果与分页沿用后台共用表格。 */}
            <section className="panel records-panel" aria-label="采集任务列表">
              <RecordTable<CollectionTask>
                rowKey="taskId"
                dataSource={list.data?.items}
                loading={list.isPending}
                error={list.error}
                retry={() => void list.refetch()}
                filtered={hasFilters}
                onReset={reset}
                emptyDescription="该数据源暂无采集记录"
                pagination={{
                  current: list.data?.pageNum ?? page,
                  pageSize: size,
                  total: list.data?.total ?? 0,
                  onChange: (pageNum, pageSize) =>
                    setParams({
                      ...Object.fromEntries(params),
                      source: selected?.id ?? '',
                      pageNum: String(pageNum),
                      pageSize: String(pageSize),
                    }),
                }}
                columns={[
                  {
                    title: '采集编号',
                    width: 188,
                    render: (_, item) => (
                      <Button
                        type="link"
                        className="cell-title ds-task-number"
                        title={item.taskNo}
                        onClick={() => setOpened(item.taskId)}
                      >
                        {item.taskNo}
                      </Button>
                    ),
                  },
                  {
                    title: '发起方式',
                    width: 110,
                    render: (_, item) => (
                      <>
                        {datasourceLabel(item.triggerKind)}
                        <small className="ds-secondary">{item.actor.name}</small>
                      </>
                    ),
                  },
                  {
                    title: '采集范围',
                    width: 190,
                    render: (_, item) => (
                      <span className="cell-text" title={item.scopeLabel}>
                        {item.scopeLabel}
                      </span>
                    ),
                  },
                  {
                    title: '开始时间',
                    width: 145,
                    render: (_, item) => displayTime(item.startedAt),
                  },
                  {
                    title: '任务状态',
                    width: 100,
                    render: (_, item) => <DatasourceStatus value={item.state} />,
                  },
                  {
                    title: '采集结果',
                    width: 190,
                    render: (_, item) =>
                      item.content?.failureMessage ??
                      item.stopProgress?.message ??
                      (item.state === 'SUCCEEDED'
                        ? item.content?.collectedObjectCount + ' 个对象'
                        : '—'),
                  },
                  {
                    title: '操作',
                    width: 130,
                    fixed: 'right',
                    className: 'record-actions-cell',
                    render: (_, item) => (
                      <Button type="link" onClick={() => setOpened(item.taskId)}>
                        详情
                      </Button>
                    ),
                  },
                ]}
              />
            </section>
          </>
        ) : (
          <SourceUnavailable />
        )}
      </ReadState>
      {/* 任务详情只显示本状态实际拥有的证据和操作。 */}
      {opened && (
        <DetailDialog
          title={task?.taskNo ?? '采集任务'}
          close={closeTask}
          actions={
            task && (
              <Space wrap>
                {/* 仅有变化时进入该次采集的变化结果，保留原列表查询与任务。 */}
                {task.state === 'SUCCEEDED' && (task.content?.changeCount ?? 0) > 0 && (
                  <Button
                    onClick={() => {
                      const previous = new URLSearchParams(params);
                      previous.set('source', task.sourceId);
                      previous.set('task', task.taskId);
                      const catalog = allowed(session, 'catalog.view');
                      navigate(
                        (catalog ? '/catalog?' : '/collections/changes?') +
                          queryString({
                            source: task.sourceId,
                            tab: catalog ? 'changes' : undefined,
                            taskId: task.taskId,
                          }),
                        { state: { collectionSearch: '?' + previous.toString() } },
                      );
                    }}
                  >
                    查看结构变化（{task.content?.changeCount}）
                  </Button>
                )}
                {task.state === 'SUCCEEDED' && allowed(session, 'catalog.view') && (
                  <Button
                    type="primary"
                    onClick={() => navigate('/catalog?source=' + task.sourceId)}
                  >
                    查看元数据目录
                  </Button>
                )}
                {['FAILED', 'CANCELLED'].includes(task.state) &&
                  allowed(session, 'collections.retry') && (
                    <Button type="primary" onClick={() => void start(task.taskId)}>
                      重新采集
                    </Button>
                  )}
                {['QUEUED', 'RUNNING'].includes(task.state) &&
                  !task.stopProgress &&
                  allowed(session, 'collections.cancel') && (
                    <Button
                      danger
                      onClick={() => {
                        // 取消核对基于当前详情任务，更新时拉取最新版本。
                        setCancelling(task);
                        setReason('');
                      }}
                    >
                      取消采集
                    </Button>
                  )}
              </Space>
            )
          }
        >
          <ReadState
            error={detail.error}
            loading={detail.isPending}
            retry={() => void detail.refetch()}
          >
            {task && <TaskInformation task={task} />}
          </ReadState>
        </DetailDialog>
      )}
      {cancelling && (
        <Modal
          centered
          open
          title="取消采集"
          onCancel={() => setCancelling(null)}
          okText="核对"
          okButtonProps={{ disabled: !reason.trim() }}
          onOk={() => void cancel()}
        >
          <Input.TextArea
            aria-label="取消原因"
            placeholder="填写取消原因"
            value={reason}
            maxLength={500}
            onChange={(event) => setReason(event.target.value)}
          />
        </Modal>
      )}
      {review && <ReviewDialog submission={review} close={() => setReview(null)} />}
    </section>
  );
}

/**
 * 保留成功、失败、排队、读取和等待停止之间的事实差异。
 */
function TaskInformation({ task }: { task: CollectionTask }) {
  const content = task.content;
  const counts = content?.counts;
  return (
    <>
      <Descriptions
        className="ds-detail-facts ds-task-facts"
        colon={false}
        column={2}
        size="small"
        items={[
          { key: 'state', label: '任务状态', children: <DatasourceStatus value={task.state} /> },
          {
            key: 'trigger',
            label: '发起方式',
            children: datasourceLabel(task.triggerKind) + ' · ' + task.actor.name,
          },
          { key: 'scope', label: '采集范围', children: task.scopeLabel, span: 2 },
          { key: 'account', label: '采集账号', children: task.account },
          { key: 'revision', label: '配置修订', children: task.configRevision },
          { key: 'start', label: '开始时间', children: displayTime(task.startedAt) },
          { key: 'end', label: '结束时间', children: displayTime(task.endedAt) },
        ]}
      />
      {/* 仅完整枚举后显示可靠总数和进度比例。 */}
      {task.state === 'RUNNING' && (
        <section className="ds-detail-block">
          <h3>当前进度</h3>
          <p>
            {counts?.totalCount ? (
              (task.stopProgress?.message ?? datasourceLabel(content?.phase))
            ) : (
              <LoadingStatus>
                {task.stopProgress?.message ??
                  (content?.phase ? datasourceLabel(content.phase) : '采集中')}
              </LoadingStatus>
            )}
          </p>
          {counts && (
            <p>
              已发现 {counts.discoveredCount} 个，已尝试读取 {counts.attemptedCount} 个
            </p>
          )}
          {!!counts?.totalCount && (
            <Progress percent={Math.floor((100 * counts.attemptedCount) / counts.totalCount)} />
          )}
        </section>
      )}
      {task.state === 'SUCCEEDED' && (
        <section className="ds-detail-block ds-task-result" aria-label="采集结果">
          <span className="ds-identity-icon" aria-hidden="true">
            <CheckOutlined />
          </span>
          <div>
            <h3 className="ds-section-heading">{datasourceLabel(content?.analysis)}</h3>
            <p>
              {content?.collectedObjectCount} 个对象 · 快照 {content?.snapshotId}
            </p>
          </div>
        </section>
      )}
      {content?.failureMessage && (
        <section className="ds-detail-block">
          <h3>失败原因</h3>
          <p>{content.failureMessage}</p>
        </section>
      )}
      {content?.reason && (
        <section className="ds-detail-block">
          <h3>取消原因</h3>
          <p>{content.reason}</p>
        </section>
      )}
    </>
  );
}
