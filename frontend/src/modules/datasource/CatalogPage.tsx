import { App, Button, Descriptions, Form, Input, Select, Space, Table, Tabs } from 'antd';
import { EyeOutlined, PlayCircleOutlined, SearchOutlined, TableOutlined } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { useEffect, useRef, useState } from 'react';
import { useLocation, useNavigate, useParams, useSearchParams } from 'react-router';
import { api, queryString } from '../../shared/api';
import { RecordTable } from '../../shared/RecordTable';
import { ReadState, displayTime } from '../../shared/Records';
import { ReviewDialog } from '../../shared/ReviewDialog';
import type { ReviewSubmission } from '../../shared/ReviewDialog';
import { allowed, useSession } from '../../shared/session';
import {
  prepareCollection,
  SourcePicker,
  SourceUnavailable,
  useSourceSelection,
} from './CollectionPage';
import { DatasourceStatus, datasourceLabel, datasourceOptions } from './Presentation';
import { StructureChanges } from './StructureChangePage';
import type { Page } from '../iam/types';
import type {
  CatalogDetail,
  CatalogField,
  CatalogKey,
  CatalogPage as CatalogData,
  CatalogRecord,
} from './types';

/**
 * 元数据目录与结构变化沿用同一来源和当前页面位置。
 */
export function CatalogPage() {
  const session = useSession();
  const navigate = useNavigate();
  const { state } = useLocation();
  const returnTo = (state as { catalogReturnTo?: string } | null)?.catalogReturnTo;
  const restoredFocus = useRef(false);
  const { message } = App.useApp();
  const { params, setParams, sources, selected } = useSourceSelection('CATALOG');
  const [form] = Form.useForm();
  const [review, setReview] = useState<ReviewSubmission | null>(null);
  const page = Number(params.get('pageNum') ?? 1);
  const size = Number(params.get('pageSize') ?? 10);
  const hasFilters = ['objectKeyword', 'objectType', 'catalogState'].some((key) => params.has(key));
  const tab =
    params.get('tab') === 'changes' && allowed(session, 'collections.view') ? 'changes' : 'objects';
  const catalog = useQuery({
    queryKey: ['catalog', selected?.id, params.toString()],
    queryFn: () =>
      api<CatalogData>(
        '/datasources/' + selected?.id + '/catalog?' + queryString(Object.fromEntries(params)),
      ),
    enabled: Boolean(selected) && tab === 'objects',
  });

  useEffect(() => {
    // 详情返回后将焦点交还所在行，随后的刷新不重复移动焦点。
    if (returnTo && catalog.isSuccess && !restoredFocus.current) {
      restoredFocus.current = true;
      document.getElementById('catalog-object-' + returnTo)?.focus();
    }
  }, [catalog.isSuccess, returnTo]);

  /**
   * 首次采集和日常手动采集共享当前源核对。
   */
  async function start() {
    if (!selected) return;
    try {
      setReview(
        await prepareCollection(selected, (taskId) => {
          setReview(null);
          navigate('/collections?' + queryString({ source: selected.id, task: taskId }));
        }),
      );
    } catch (error) {
      message.error((error as Error).message);
    }
  }

  /**
   * 对象结构作为独立页面，来源和列表查询参数继续保留。
   */
  function openObject(item: CatalogRecord) {
    const detailParams = new URLSearchParams(params);
    detailParams.set('source', selected?.id ?? '');
    detailParams.set('snapshotId', item.snapshotId);
    if (!item.objectId) detailParams.set('observation', '1');
    navigate('/catalog/' + (item.objectId ?? item.observationId) + '?' + detailParams.toString(), {
      state: { catalogRow: item.observationId },
    });
  }

  /**
   * 清空目录条件并保留当前来源和分页尺寸。
   */
  function reset() {
    form.setFieldsValue({
      objectKeyword: undefined,
      objectType: undefined,
      catalogState: undefined,
    });
    setParams({ source: selected?.id ?? '', pageSize: String(size) });
    form.focusField('objectKeyword');
  }

  // 目录与变化的页签承载结果，源级采集与切源保持在独立工具栏。
  const navigation = selected && allowed(session, 'collections.view') && (
    <Tabs
      className="ds-catalog-tabs"
      activeKey={tab}
      onChange={(next) =>
        setParams({ source: selected.id, ...(next === 'changes' ? { tab: next } : {}) })
      }
      items={[
        { key: 'objects', label: '元数据目录' },
        { key: 'changes', label: '结构变化' },
      ]}
    />
  );

  return (
    <section className="record-page" aria-label="元数据目录">
      {/* 来源选择、首次采集和目录页签保持紧凑。 */}
      <div className="panel ds-toolbar ds-catalog-toolbar">
        <SourcePicker
          withLabel
          sources={sources.data}
          selected={selected}
          choose={(source) => {
            // 来源变更后表单与新来源的结果同步。
            if (tab === 'objects') {
              form.setFieldsValue({
                objectKeyword: undefined,
                objectType: undefined,
                catalogState: undefined,
              });
            }
            setParams({ source, ...(tab === 'changes' ? { tab } : {}) });
          }}
        />
        <Space size={12} wrap>
          {selected && <DatasourceStatus value={selected.lifecycle} />}
          {selected?.lifecycle === 'ENABLED' && allowed(session, 'collections.collect') && (
            <Button
              type="primary"
              icon={<PlayCircleOutlined aria-hidden="true" />}
              onClick={() => void start()}
            >
              {catalog.data && !catalog.data.currentSnapshotId ? '发起首次采集' : '发起采集'}
            </Button>
          )}
        </Space>
      </div>
      <ReadState
        error={sources.error}
        loading={sources.isPending}
        retry={() => void sources.refetch()}
      >
        {!selected && <SourceUnavailable />}
        {selected && tab === 'changes' && (
          <StructureChanges key={selected.id} source={selected.id} navigation={navigation} />
        )}
        {selected && tab === 'objects' && (
          <>
            {/* 目录筛选与结果面板分别占据统一的工作区边界。 */}
            <div className="panel query-panel ds-query-panel ds-catalog-query compact-query">
              <Form
                name="catalog-query"
                form={form}
                layout="inline"
                initialValues={Object.fromEntries(params)}
                onFinish={(values) => {
                  // 查询留在当前来源，并把键盘位置交回关键词。
                  setParams(
                    new URLSearchParams(
                      queryString({ ...values, source: selected.id, pageSize: size }),
                    ),
                  );
                  form.focusField('objectKeyword');
                }}
              >
                <Form.Item name="objectKeyword">
                  <Input
                    aria-label="对象名或解释"
                    placeholder="对象名或解释"
                    prefix={<SearchOutlined />}
                    allowClear
                  />
                </Form.Item>
                <Form.Item name="objectType">
                  <Select
                    aria-label="对象类型"
                    placeholder="对象类型"
                    allowClear
                    options={datasourceOptions([
                      'TABLE',
                      'VIEW',
                      'MATERIALIZED_VIEW',
                      'PARTITION',
                      'SYSTEM',
                      'TEMPORARY',
                      'FOREIGN',
                    ])}
                  />
                </Form.Item>
                {/* 日期以外的目录条件常用，直接保持并排。 */}
                <Form.Item name="catalogState" className="status-query">
                  <Select
                    aria-label="目录状态"
                    placeholder="目录状态"
                    allowClear
                    options={datasourceOptions(['COLLECTED', 'IDENTITY_UNCERTAIN'])}
                  />
                </Form.Item>
                <div className="query-tail-actions">
                  <Space>
                    <Button htmlType="submit">查询</Button>
                    <Button onClick={reset}>重置</Button>
                  </Space>
                </div>
              </Form>
            </div>
            <section className="panel records-panel" aria-label="目录对象列表">
              {navigation}
              <RecordTable<CatalogRecord>
                dataSource={catalog.data?.page.items}
                loading={catalog.isPending}
                error={catalog.error}
                rowKey="observationId"
                retry={() => void catalog.refetch()}
                filtered={hasFilters && Boolean(catalog.data?.currentSnapshotId)}
                onReset={reset}
                emptyDescription={
                  catalog.data?.currentSnapshotId ? '目录暂无表或视图' : '等待首次采集成功'
                }
                pagination={{
                  current: catalog.data?.page.pageNum ?? page,
                  pageSize: size,
                  total: catalog.data?.page.total ?? 0,
                  onChange: (pageNum, pageSize) =>
                    setParams({
                      ...Object.fromEntries(params),
                      source: selected.id,
                      pageNum: String(pageNum),
                      pageSize: String(pageSize),
                    }),
                }}
                columns={[
                  {
                    title: '对象 / 解释',
                    width: 224,
                    render: (_, item) => (
                      <div className="ds-row-identity">
                        <span className="ds-identity-icon" aria-hidden="true">
                          {item.objectType.includes('VIEW') ? <EyeOutlined /> : <TableOutlined />}
                        </span>
                        <div className="ds-row-name">
                          <Button
                            type="link"
                            id={'catalog-object-' + item.observationId}
                            className="cell-title"
                            title={item.qualifiedName}
                            onClick={() => openObject(item)}
                          >
                            {item.qualifiedName}
                          </Button>
                          {item.sourceComment && (
                            <small className="table-secondary" title={item.sourceComment}>
                              {item.sourceComment}
                            </small>
                          )}
                        </div>
                      </div>
                    ),
                  },
                  {
                    title: '对象类型',
                    width: 108,
                    render: (_, item) => datasourceLabel(item.objectType),
                  },
                  {
                    title: '目录状态',
                    width: 112,
                    render: (_, item) => (
                      <Space size={4} wrap>
                        {item.catalogStates.map((value) => (
                          <DatasourceStatus key={value} value={value} />
                        ))}
                      </Space>
                    ),
                  },
                  { title: '采集快照', dataIndex: 'snapshotId', width: 90 },
                  {
                    title: '采集时间',
                    width: 145,
                    render: (_, item) => displayTime(item.collectedAt),
                  },
                  {
                    title: '操作',
                    width: allowed(session, 'governance.view') ? 158 : 115,
                    fixed: 'right',
                    className: 'record-actions-cell',
                    render: (_, item) => (
                      <Space size={8}>
                        <Button type="link" onClick={() => openObject(item)}>
                          查看结构
                        </Button>
                        {item.objectId && allowed(session, 'governance.view') && (
                          <Button
                            type="link"
                            onClick={() =>
                              navigate(
                                '/governance/' +
                                  item.objectId +
                                  '?' +
                                  queryString({ source: selected.id }),
                                {
                                  state: {
                                    catalogSearch:
                                      '?' +
                                      queryString({
                                        ...Object.fromEntries(params),
                                        source: selected.id,
                                      }),
                                    catalogRow: item.observationId,
                                  },
                                },
                              )
                            }
                          >
                            治理
                          </Button>
                        )}
                      </Space>
                    ),
                  },
                ]}
              />
            </section>
          </>
        )}
      </ReadState>
      {review && <ReviewDialog submission={review} close={() => setReview(null)} />}
    </section>
  );
}

/**
 * 在独立工作区查看成功快照对应的字段、键和来源注释。
 */
export function CatalogObjectPage() {
  const session = useSession();
  const navigate = useNavigate();
  const { id } = useParams();
  const [params] = useSearchParams();
  const source = params.get('source');
  const snapshot = params.get('snapshotId');
  const observation = params.get('observation') === '1';
  const detail = useQuery({
    queryKey: ['catalog-object', source, id, snapshot, observation],
    queryFn: () =>
      api<CatalogDetail>(
        '/datasources/' +
          source +
          (observation ? '/catalog-observations/' : '/objects/') +
          id +
          (snapshot && !observation ? '?snapshotId=' + snapshot : ''),
      ),
    enabled: Boolean(source && id),
  });

  const primaryFields = new Set(detail.data?.primaryKeys.map((key) => key.field));

  const objectId = detail.data?.object.objectId;
  // 用实际变化结果决定是否提供入口，次级查询失败仍允许手动重试。
  const canReadChanges = allowed(session, 'collections.view') && Boolean(source && objectId);
  const changes = useQuery({
    queryKey: ['catalog-object-changes', source, objectId],
    enabled: canReadChanges,
    queryFn: () =>
      api<Page<unknown>>(
        '/datasources/' + source + '/changes?' + queryString({ objectId, pageSize: 1 }),
      ),
  });
  const changeCount = changes.data?.total ?? 0;

  return (
    <section className="ds-object-page" aria-label="对象结构">
      <ReadState
        error={detail.error}
        loading={detail.isPending && Boolean(source)}
        empty={!source}
        retry={() => void detail.refetch()}
      >
        {detail.data && (
          <article className="panel ds-object-content">
            {/* 对象身份、状态和对象级操作共用一行，完整名称可自然换行。 */}
            <header className="ds-object-header">
              <div className="ds-object-identity">
                <span className="ds-identity-icon" aria-hidden="true">
                  {detail.data.object.objectType.includes('VIEW') ? (
                    <EyeOutlined />
                  ) : (
                    <TableOutlined />
                  )}
                </span>
                <div>
                  <h1>{detail.data.object.qualifiedName}</h1>
                  <Space className="ds-object-meta" size={8} wrap>
                    <span>{datasourceLabel(detail.data.object.objectType)}</span>
                    {detail.data.object.catalogStates.map((value) => (
                      <DatasourceStatus key={value} value={value} />
                    ))}
                  </Space>
                </div>
              </div>
              {canReadChanges &&
                (changeCount > 0 ? (
                  <Button
                    onClick={() =>
                      navigate('/catalog?' + queryString({ source, tab: 'changes', objectId }))
                    }
                  >
                    查看结构变化（{changeCount}）
                  </Button>
                ) : changes.isSuccess ? (
                  <span className="muted">暂无结构变化记录</span>
                ) : (
                  changes.isError && (
                    <Button onClick={() => void changes.refetch()}>重新检查变化</Button>
                  )
                ))}
            </header>
            {/* 字段区作为主要内容，保持与工作区同宽。 */}
            <section className="panel ds-fields-panel" aria-labelledby="ds-fields-title">
              <header className="ds-fields-heading">
                <h2 id="ds-fields-title" className="ds-section-heading">
                  字段
                </h2>
                <span className="ds-field-count">{detail.data.columns.length} 个字段</span>
              </header>
              <Table<CatalogField>
                className="full-text-table ds-field-table"
                size="middle"
                rowKey="ordinal"
                dataSource={detail.data.columns}
                pagination={false}
                scroll={{ x: 780 }}
                columns={[
                  {
                    title: '字段',
                    width: 210,
                    render: (_, field) => (
                      <div className="ds-field-name">
                        <code>{field.name}</code>
                        {primaryFields.has(field.name) && (
                          <span className="ds-field-key">主键</span>
                        )}
                      </div>
                    ),
                  },
                  {
                    title: '类型',
                    dataIndex: 'type',
                    width: 160,
                    render: (value) => <code>{value}</code>,
                  },
                  {
                    title: '可空',
                    width: 76,
                    render: (_, field) => (field.nullable ? '是' : '否'),
                  },
                  ...(detail.data.object.objectType === 'VIEW'
                    ? []
                    : [
                        {
                          title: '默认值 / 生成',
                          width: 180,
                          /** 区分数据库生成、显式空字符串和没有默认定义。 */
                          render: (_: unknown, field: CatalogField) => {
                            if (field.generated) return '由数据库生成';
                            if (field.defaultValue != null)
                              return <code>{field.defaultValue || "''"}</code>;
                            return (
                              <span className="muted" title="无默认值">
                                —
                              </span>
                            );
                          },
                        },
                      ]),
                  {
                    title: '字段注释',
                    dataIndex: 'comment',
                    render: (value) =>
                      value || (
                        <span className="muted" title="无注释">
                          —
                        </span>
                      ),
                  },
                ]}
              />
            </section>
            {detail.data.object.objectType !== 'VIEW' && (
              <section className="panel ds-keys-panel">
                <h2 className="ds-section-heading">键、关系与索引</h2>
                <Descriptions
                  column={3}
                  layout="vertical"
                  colon={false}
                  size="small"
                  items={[
                    { key: 'pk', label: '主键', children: formatKeys(detail.data.primaryKeys) },
                    { key: 'fk', label: '外键', children: formatKeys(detail.data.foreignKeys) },
                    {
                      key: 'indexes',
                      label: '索引',
                      children: formatKeys(detail.data.indexes),
                    },
                  ]}
                />
              </section>
            )}
            {/* 短小的来源事实直接显示，视图注释允许正文换行。 */}
            <section className="ds-object-origin">
              <h2 className="ds-section-heading">来源与采集信息</h2>
              <Descriptions
                column={2}
                size="small"
                items={[
                  {
                    key: 'snapshot',
                    label: '采集快照',
                    children: detail.data.object.snapshotId,
                  },
                  {
                    key: 'time',
                    label: '采集时间',
                    children: displayTime(detail.data.object.collectedAt),
                  },
                  ...(detail.data.objectRevision
                    ? [
                        {
                          key: 'revision',
                          label: '结构修订',
                          children: detail.data.objectRevision,
                        },
                      ]
                    : []),
                  ...(detail.data.parent
                    ? [{ key: 'parent', label: '父表', children: detail.data.parent }]
                    : []),
                  ...(detail.data.object.sourceComment
                    ? [
                        {
                          key: 'comment',
                          label: detail.data.object.objectType.includes('VIEW')
                            ? '源视图注释'
                            : '源表注释',
                          children: (
                            <p className="ds-object-comment">{detail.data.object.sourceComment}</p>
                          ),
                          span: 2,
                        },
                      ]
                    : []),
                ]}
              />
            </section>
          </article>
        )}
      </ReadState>
    </section>
  );
}

/**
 * 字段、键和外键目标都由源结构事实决定。
 */
function formatKeys(keys: CatalogKey[]) {
  return keys.length
    ? keys
        .map((key) =>
          [key.name, key.field, key.referencedObject?.concat('.' + (key.referencedField ?? ''))]
            .filter(Boolean)
            .join(' · '),
        )
        .join('、')
    : '未声明';
}
