import { DetailDialog } from '../../shared/DetailDialog';
import { App, Button, Descriptions, Form, Input, Modal, Select, Space } from 'antd';
import { SearchOutlined } from '@ant-design/icons';
import dayjs from 'dayjs';
import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import type { ReactNode } from 'react';
import { useLocation, useNavigate, useSearchParams } from 'react-router';
import { api, queryString } from '../../shared/api';
import { ReadState, displayTime } from '../../shared/Records';
import { RecordTable } from '../../shared/RecordTable';
import { MoreFilters } from '../../shared/MoreFilters';
import { QueryDateRange } from '../../shared/QueryDateRange';
import { ReviewDialog } from '../../shared/ReviewDialog';
import type { ReviewSubmission } from '../../shared/ReviewDialog';
import { allowed, useSession } from '../../shared/session';
import type { Page, Preview } from '../iam/types';
import { SourcePicker, SourceUnavailable, useSourceSelection } from './CollectionPage';
import { DatasourceStatus, datasourceLabel, datasourceOptions } from './Presentation';
import type { StructureChange } from './types';

/**
 * 采集任务可独立打开该源的变化记录。
 */
export function StandaloneStructureChangePage() {
  const { setParams, sources, selected } = useSourceSelection('COLLECTIONS');
  return (
    <section className="record-page" aria-label="结构变化">
      <div className="panel ds-toolbar">
        <SourcePicker
          withLabel
          sources={sources.data}
          selected={selected}
          choose={(source) => setParams({ source })}
        />
      </div>
      <ReadState
        error={sources.error}
        loading={sources.isPending}
        retry={() => void sources.refetch()}
      >
        {selected ? (
          <StructureChanges key={selected.id} source={selected.id} />
        ) : (
          <SourceUnavailable />
        )}
      </ReadState>
    </section>
  );
}

/**
 * 从原任务或目录查看真实结构变化，可直接核实待办。
 */
export function StructureChanges({
  source,
  navigation,
}: {
  source: string;
  /** 嵌入目录时将原型页签放入结果面板。 */
  navigation?: ReactNode;
}) {
  const session = useSession();
  const navigate = useNavigate();
  const { state } = useLocation();
  const [params, setParams] = useSearchParams();
  const [form] = Form.useForm();
  const [moreOpen, setMoreOpen] = useState(false);
  const [id, setId] = useState<string | null>(params.get('change'));
  const [checking, setChecking] = useState<StructureChange | null>(null);
  const [review, setReview] = useState<ReviewSubmission | null>(null);
  const path = '/datasources/' + source + '/changes';
  const list = useQuery({
    queryKey: ['source-changes', source, params.toString()],
    queryFn: () => api<Page<StructureChange>>(path + '?' + queryString(Object.fromEntries(params))),
  });
  const detail = useQuery({
    queryKey: ['source-change', source, id],
    queryFn: () => api<StructureChange>(path + '/' + id),
    enabled: Boolean(id),
  });
  const field = detail.data && changedField(detail.data);
  const size = Number(params.get('pageSize') ?? 10);
  const taskId = params.get('taskId');
  const objectId = params.get('objectId');
  const from = params.get('fromDate');
  const to = params.get('toDate');
  const hasFilters = ['keyword', 'changeType', 'fromDate', 'toDate', 'stage'].some((key) =>
    params.has(key),
  );

  /**
   * 关闭由对账打开的变化时保留来源与普通列表条件。
   */
  function closeChange() {
    setId(null);
    if (params.has('change')) {
      // 只清除详情入口，来源、筛选和导航状态继续可用。
      const next = new URLSearchParams(params);
      next.delete('change');
      setParams(next, { replace: true, state });
    }
  }

  /**
   * 重置手选条件，保留入口指定的采集任务或对象。
   */
  function reset() {
    setMoreOpen(false);
    form.setFieldsValue({
      keyword: undefined,
      changeType: undefined,
      dates: undefined,
      stage: undefined,
    });
    setParams(queryString({ source, pageSize: size, taskId, objectId, tab: params.get('tab') }), {
      state,
    });
    form.focusField('keyword');
  }
  return (
    <>
      {/* 过滤顺序按变化对象、类型、发现时间与处理阶段。 */}
      <div className="panel query-panel ds-query-panel compact-query">
        <Form
          name="structure-change-query"
          form={form}
          layout="inline"
          initialValues={{
            ...Object.fromEntries(params),
            dates: from || to ? [from ? dayjs(from) : null, to ? dayjs(to) : null] : undefined,
          }}
          onFinish={() => {
            // 保留未展开的日期条件，只有查询或重置才应用。
            const values = form.getFieldsValue(true);
            setMoreOpen(false);
            const next = {
              ...Object.fromEntries(params),
              pageNum: 1,
              pageSize: size,
              keyword: values.keyword,
              changeType: values.changeType,
              stage: values.stage,
              fromDate: values.dates?.[0]?.format('YYYY-MM-DD'),
              toDate: values.dates?.[1]?.format('YYYY-MM-DD'),
            };
            setParams(queryString(next), { state });
            form.focusField('keyword');
          }}
        >
          <Form.Item name="keyword">
            <Input
              aria-label="变化对象"
              placeholder="对象名或变化项"
              prefix={<SearchOutlined />}
              allowClear
            />
          </Form.Item>
          <Form.Item name="changeType">
            <Select
              aria-label="变化类型"
              placeholder="变化类型"
              allowClear
              options={datasourceOptions([
                'OBJECT_ADDED',
                'OBJECT_REMOVED',
                'OBJECT_RENAMED',
                'OBJECT_REBUILT',
                'ACCESS_LOST',
                'IDENTITY_UNCERTAIN',
                'COLUMN_ADDED',
                'COLUMN_REMOVED',
                'ATTRIBUTE_CHANGED',
              ])}
            />
          </Form.Item>
          {/* 常用阶段在工具栏中，发现日期收入更多筛选。 */}
          <Form.Item name="stage" className="status-query">
            <Select
              aria-label="处理阶段"
              placeholder="处理阶段"
              allowClear
              options={datasourceOptions(['PENDING_REVIEW', 'PENDING_GOVERNANCE', 'COMPLETED'])}
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
                  <QueryDateRange label="发现时间范围" />
                </Form.Item>
              </MoreFilters>
              <Button htmlType="submit">查询</Button>
              <Button onClick={reset}>重置</Button>
            </Space>
          </div>
        </Form>
      </div>
      {/* 结构事实结果与分页使用后台同款结果面板。 */}
      <section className="panel records-panel" aria-label="结构变化列表">
        {navigation}
        {(taskId || objectId) && (
          <div className="ds-change-scope">
            <span>{taskId ? '本次采集的结构变化' : '当前对象的结构变化'}</span>
            <Button
              type="link"
              onClick={() => {
                // 明确切换到同源全部变化，避免误以为范围内结果来自所有任务。
                setParams(queryString({ source, pageSize: size, tab: params.get('tab') }));
              }}
            >
              查看全部变化
            </Button>
          </div>
        )}
        <RecordTable<StructureChange>
          rowKey="changeId"
          dataSource={list.data?.items}
          loading={list.isPending}
          error={list.error}
          retry={() => void list.refetch()}
          filtered={hasFilters}
          onReset={reset}
          emptyDescription="暂无结构变化"
          pagination={{
            current: list.data?.pageNum ?? 1,
            pageSize: size,
            total: list.data?.total ?? 0,
            onChange: (pageNum, pageSize) =>
              setParams(
                queryString({
                  ...Object.fromEntries(params),
                  source,
                  pageNum,
                  pageSize,
                }),
                { state },
              ),
          }}
          columns={[
            {
              title: '变化对象',
              width: 195,
              render: (_, item) => {
                // 先辨认表，再显示受影响字段，技术名称保留完整提示。
                const name = changedField(item) ?? datasourceLabel(item.attributeKind);
                return (
                  <>
                    <Button
                      type="link"
                      className="cell-title"
                      title={item.target}
                      onClick={() => setId(item.changeId)}
                    >
                      {item.target}
                    </Button>
                    <small className="ds-secondary cell-text" title={name}>
                      {name}
                    </small>
                  </>
                );
              },
            },
            {
              title: '变化类型',
              width: 150,
              render: (_, item) => datasourceLabel(item.changeType),
            },
            { title: '发现时间', width: 145, render: (_, item) => displayTime(item.discoveredAt) },
            {
              title: '处理阶段',
              width: 135,
              render: (_, item) => <DatasourceStatus value={item.stage} />,
            },
            {
              title: '操作',
              width: 105,
              fixed: 'right',
              className: 'record-actions-cell',
              render: (_, item) => (
                <Button type="link" onClick={() => setId(item.changeId)}>
                  查看详情
                </Button>
              ),
            },
          ]}
        />
      </section>
      {id && (
        <DetailDialog
          title={
            <div className="ds-change-title">
              {/* 字段名和所属表一起构成比较对象。 */}
              <span>{field ?? detail.data?.target ?? '结构变化'}</span>
              {field && <small>{detail.data?.target}</small>}
            </div>
          }
          width={880}
          close={closeChange}
          actions={
            detail.data && (
              <>
                <Button
                  onClick={() =>
                    navigate(
                      '/collections?' +
                        queryString({
                          source,
                          task: detail.data?.sourceTaskId,
                        }),
                    )
                  }
                >
                  查看来源采集
                </Button>
                {detail.data.factState === 'PENDING' && allowed(session, 'collections.review') && (
                  <Button type="primary" onClick={() => setChecking(detail.data ?? null)}>
                    核实变化
                  </Button>
                )}
              </>
            )
          }
        >
          <ReadState
            error={detail.error}
            loading={detail.isPending}
            retry={() => void detail.refetch()}
          >
            {detail.data && (
              <>
                <Descriptions
                  size="small"
                  column={2}
                  items={[
                    {
                      key: 'type',
                      label: '变化类型',
                      children: datasourceLabel(detail.data.changeType),
                    },
                    {
                      key: 'attribute',
                      label: '变化项',
                      children: datasourceLabel(detail.data.attributeKind),
                    },
                    {
                      key: 'fact',
                      label: '事实状态',
                      children: <DatasourceStatus value={detail.data.factState} />,
                    },
                    {
                      key: 'stage',
                      label: '处理阶段',
                      children: <DatasourceStatus value={detail.data.stage} />,
                    },
                    {
                      key: 'date',
                      label: '发现时间',
                      children: displayTime(detail.data.discoveredAt),
                    },
                    ...(detail.data.latestReview
                      ? [
                          {
                            key: 'evidence',
                            label: '核实依据',
                            children: detail.data.latestReview.evidenceText,
                            span: 2,
                          },
                        ]
                      : []),
                  ]}
                />
                {/* 左右使用相同的属性顺序，核对时可直接找到类型、空值与注释。 */}
                <div className="ds-compare">
                  <section>
                    <h3>变化前</h3>
                    <ChangeSnapshot
                      value={detail.data.before}
                      absent={detail.data.changeType === 'COLUMN_ADDED' ? '字段尚不存在' : '—'}
                    />
                  </section>
                  <section>
                    <h3>变化后</h3>
                    <ChangeSnapshot
                      value={detail.data.after}
                      absent={detail.data.changeType === 'COLUMN_REMOVED' ? '字段已移除' : '—'}
                    />
                  </section>
                </div>
              </>
            )}
          </ReadState>
        </DetailDialog>
      )}
      {checking && (
        <ChangeDecision
          change={checking}
          path={path}
          close={() => setChecking(null)}
          ready={(next) => {
            setReview(next);
            setChecking(null);
          }}
          done={() => {
            setReview(null);
            void list.refetch();
            void detail.refetch();
          }}
        />
      )}
      {review && <ReviewDialog submission={review} close={() => setReview(null)} />}
    </>
  );
}

/**
 * 核实只显示该类型允许的事实结论。
 */
function ChangeDecision({
  change,
  path,
  close,
  ready,
  done,
}: {
  change: StructureChange;
  path: string;
  close: () => void;
  ready: (review: ReviewSubmission) => void;
  done: () => void;
}) {
  const { message } = App.useApp();
  const [form] = Form.useForm<{ decision: string; evidence: string }>();
  const options =
    change.changeType === 'ACCESS_LOST'
      ? [
          { value: 'REMOVED', label: '对象已删除' },
          { value: 'STILL_EXISTS', label: '对象仍存在' },
        ]
      : change.changeType === 'IDENTITY_UNCERTAIN'
        ? [
            { value: 'SAME_OBJECT', label: '仍为原对象' },
            { value: 'REBUILT', label: '确认同名重建' },
          ]
        : [
            { value: 'CONFIRM', label: '确认变化' },
            { value: 'EXCLUDE', label: '排除变化' },
          ];

  /**
   * 把证据和结论交给服务端核对后，再允许确认。
   */
  async function preview() {
    try {
      const proposal = await form.validateFields();
      const expectedVersion = change.version;
      const target = path + '/' + change.changeId;
      const result = await api<Preview>(target + '/impact-preview', {
        method: 'POST',
        body: { expectedVersion, proposal },
      });
      ready({
        title: '核对结构变化',
        preview: result,
        path: target + '/reviews',
        method: 'POST',
        operation: 'collections.review',
        body: { expectedVersion, proposal },
        confirmText: '确认核实',
        done,
      });
    } catch (error) {
      if (error instanceof Error) message.error(error.message);
    }
  }

  return (
    <Modal
      centered
      open
      title={'核实变化 · ' + change.target}
      okText="核对"
      onCancel={close}
      onOk={() => void preview()}
    >
      <Form form={form} layout="vertical">
        <Form.Item name="decision" label="核实结论" rules={[{ required: true }]}>
          <Select options={options} />
        </Form.Item>
        <Form.Item name="evidence" label="依据" rules={[{ required: true, whitespace: true }]}>
          <Input.TextArea rows={4} maxLength={1000} />
        </Form.Item>
      </Form>
    </Modal>
  );
}

/**
 * 从实际前后快照识别变化字段，普通对象保留已有表名。
 */
function changedField(change: StructureChange) {
  const snapshot = change.after ?? change.before;
  // 字段存在性保存完整结构，单属性变化保存字段名与该属性的值。
  if (
    snapshot &&
    typeof snapshot === 'object' &&
    ('nullable' in snapshot || 'value' in snapshot) &&
    'name' in snapshot &&
    typeof snapshot.name === 'string'
  ) {
    return snapshot.name;
  }
}

/** 结构事实的显示顺序与标签，用于阅读实际采集值。 */
const snapshotLabels: Record<string, string> = {
  name: '名称',
  type: '类型',
  nullable: '允许为空',
  defaultValue: '默认值',
  generated: '生成列',
  ordinal: '列序号',
  comment: '源库注释',
  catalog: '数据库',
  schema: 'Schema',
  parent: '父对象',
  identity: '源端身份',
};

/**
 * 普通结构事实按属性展示，其他类型保留原始证据。
 */
function ChangeSnapshot({ value: snapshot, absent }: { value: unknown; absent: string }) {
  // 单项变化只呈现对应属性值，字段名由抽屉页头说明。
  const value =
    snapshot && typeof snapshot === 'object' && 'name' in snapshot && 'value' in snapshot
      ? snapshot.value
      : snapshot;
  if (value && typeof value === 'object' && 'name' in value) {
    const properties = Object.entries(value);
    if (properties.every(([key]) => Object.hasOwn(snapshotLabels, key))) {
      // 只整理已知的事实形状，新增协议字段仍可原样回看。
      const entries = Object.keys(snapshotLabels).filter((key) => Object.hasOwn(value, key));
      const fields = Object.fromEntries(properties);
      return (
        <dl className="ds-change-properties">
          {entries.map((key) => (
            // 按名称、类型和其他属性给出可对照的顺序。
            <div key={key}>
              <dt>{snapshotLabels[key]}</dt>
              <dd>{displayValue(fields[key], key === 'defaultValue' ? '未设置' : '—')}</dd>
            </div>
          ))}
        </dl>
      );
    }
  }
  return (
    <pre className={value == null ? 'ds-change-empty' : undefined}>
      {displayValue(value, absent)}
    </pre>
  );
}

/** 布尔值使用可读用语，长内容允许换行并保留实际值。 */
function displayValue(value: unknown, absent: string) {
  if (value == null || value === '') return absent;
  if (typeof value === 'boolean') return value ? '是' : '否';
  return typeof value === 'string' ? value : JSON.stringify(value, null, 2);
}
