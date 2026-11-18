import { App, Button, Descriptions, Form, Input, Select, Space, Table, Tabs } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router';
import { api, queryString } from '../../shared/api';
import { RecordTable } from '../../shared/RecordTable';
import { ReadState, displayTime } from '../../shared/Records';
import { ReviewDialog } from '../../shared/ReviewDialog';
import type { ReviewSubmission } from '../../shared/ReviewDialog';
import { allowed, useSession } from '../../shared/session';
import { prepareCollection, SourcePicker, useSourceSelection } from './CollectionPage';
import { DatasourceStatus, datasourceLabel, datasourceOptions } from './Presentation';
import { StructureChanges } from './StructureChangePage';
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
  const { message } = App.useApp();
  const { params, setParams, sources, selected } = useSourceSelection('CATALOG');
  const [form] = Form.useForm();
  const [review, setReview] = useState<ReviewSubmission | null>(null);
  const page = Number(params.get('pageNum') ?? 1);
  const size = Number(params.get('pageSize') ?? 10);
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

  /**
   * 首次采集和日常手动采集共享当前源核对。
   */
  async function start() {
    if (!selected) return;
    try {
      setReview(
        await prepareCollection(selected, () => {
          setReview(null);
          navigate('/collections?source=' + selected.id);
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
    navigate('/catalog/' + (item.objectId ?? item.observationId) + '?' + detailParams.toString());
  }

  return (
    <section className="record-page" aria-label="元数据目录">
      {/* 来源选择、首次采集和目录页签保持紧凑。 */}
      <div className="ds-toolbar">
        <SourcePicker
          sources={sources.data}
          selected={selected}
          choose={(source) => setParams({ source, ...(tab === 'changes' ? { tab } : {}) })}
        />
        {selected?.lifecycle === 'ENABLED' && allowed(session, 'collections.collect') && (
          <Button type="primary" onClick={() => void start()}>
            发起采集
          </Button>
        )}
      </div>
      {allowed(session, 'collections.view') && (
        <Tabs
          activeKey={tab}
          onChange={(next) =>
            setParams({ source: selected?.id ?? '', ...(next === 'changes' ? { tab: next } : {}) })
          }
          items={[
            { key: 'objects', label: '元数据目录' },
            { key: 'changes', label: '结构变化' },
          ]}
        />
      )}
      <ReadState
        error={sources.error}
        loading={sources.isPending}
        empty={!selected && !sources.isPending}
        retry={() => void sources.refetch()}
      >
        {selected && tab === 'changes' && <StructureChanges source={selected.id} />}
        {selected && tab === 'objects' && (
          <>
            <Form
              form={form}
              layout="inline"
              className="record-filters"
              initialValues={Object.fromEntries(params)}
              onFinish={(values) =>
                setParams(
                  new URLSearchParams(
                    queryString({ ...values, source: selected.id, pageSize: size }),
                  ),
                )
              }
            >
              <Form.Item name="objectKeyword">
                <Input aria-label="对象名或解释" placeholder="对象名或解释" allowClear />
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
              <Form.Item name="catalogState">
                <Select
                  aria-label="目录状态"
                  placeholder="目录状态"
                  allowClear
                  options={datasourceOptions(['COLLECTED', 'IDENTITY_UNCERTAIN'])}
                />
              </Form.Item>
              <Space>
                <Button htmlType="submit">查询</Button>
                <Button
                  onClick={() => {
                    form.setFieldsValue({
                      objectKeyword: undefined,
                      objectType: undefined,
                      catalogState: undefined,
                    });
                    setParams({ source: selected.id });
                  }}
                >
                  重置
                </Button>
              </Space>
            </Form>
            <RecordTable<CatalogRecord>
              dataSource={catalog.data?.page.items}
              loading={catalog.isPending}
              error={catalog.error}
              rowKey="observationId"
              retry={() => void catalog.refetch()}
              locale={{
                emptyText:
                  catalog.data && !catalog.data.currentSnapshotId ? '尚无结构目录' : undefined,
              }}
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
                  width: 200,
                  render: (_, item) => (
                    <>
                      <Button type="link" onClick={() => openObject(item)}>
                        {item.qualifiedName}
                      </Button>
                      <small className="ds-secondary">{item.sourceComment}</small>
                    </>
                  ),
                },
                {
                  title: '对象类型',
                  width: 125,
                  render: (_, item) => datasourceLabel(item.objectType),
                },
                {
                  title: '目录状态',
                  width: 125,
                  render: (_, item) =>
                    item.catalogStates.map((value) => (
                      <DatasourceStatus key={value} value={value} />
                    )),
                },
                { title: '采集快照', dataIndex: 'snapshotId', width: 110 },
                {
                  title: '采集时间',
                  width: 145,
                  render: (_, item) => displayTime(item.collectedAt),
                },
                {
                  title: '操作',
                  width: 115,
                  fixed: 'right',
                  render: (_, item) => (
                    <Button type="link" onClick={() => openObject(item)}>
                      查看结构
                    </Button>
                  ),
                },
              ]}
            />
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

  /**
   * 返回当前来源的原始查询和有效分页。
   */
  function back() {
    const previous = new URLSearchParams(params);
    previous.delete('snapshotId');
    previous.delete('observation');
    navigate('/catalog?' + previous.toString());
  }

  return (
    <section className="ds-object-page" aria-label="对象结构">
      <ReadState
        error={detail.error}
        loading={detail.isPending && Boolean(source)}
        empty={!source}
        retry={() => void detail.refetch()}
      >
        {detail.data && (
          <>
            {/* 对象摘要和字段表按来源真实定义展示。 */}
            <header className="ds-object-header">
              <div>
                <h2>{detail.data.object.qualifiedName}</h2>
                <span className="muted">{datasourceLabel(detail.data.object.objectType)}</span>
              </div>
              <Space>
                {detail.data.object.catalogStates.map((value) => (
                  <DatasourceStatus key={value} value={value} />
                ))}
              </Space>
            </header>
            <Descriptions
              size="small"
              column={2}
              items={[
                {
                  key: 'comment',
                  label: detail.data.object.objectType === 'VIEW' ? '视图注释' : '表注释',
                  children: detail.data.object.sourceComment || '无注释',
                  span: 2,
                },
                { key: 'snapshot', label: '采集快照', children: detail.data.object.snapshotId },
                {
                  key: 'time',
                  label: '采集时间',
                  children: displayTime(detail.data.object.collectedAt),
                },
                ...(detail.data.parent
                  ? [{ key: 'parent', label: '父表', children: detail.data.parent }]
                  : []),
              ]}
            />
            <h3>字段</h3>
            <Table<CatalogField>
              size="middle"
              rowKey="ordinal"
              dataSource={detail.data.columns}
              pagination={false}
              scroll={{ x: 860 }}
              columns={[
                { title: '字段', dataIndex: 'name', width: 160 },
                { title: '类型', dataIndex: 'type', width: 145 },
                { title: '可空', width: 78, render: (_, field) => (field.nullable ? '是' : '否') },
                ...(detail.data.object.objectType === 'VIEW'
                  ? []
                  : [
                      {
                        title: '默认值 / 生成',
                        width: 180,
                        render: (_: unknown, field: CatalogField) =>
                          field.generated ? '由数据库生成' : (field.defaultValue ?? '无默认值'),
                      },
                    ]),
                {
                  title: '字段注释',
                  dataIndex: 'comment',
                  width: 210,
                  render: (value) => value || '无注释',
                },
              ]}
            />
            {detail.data.object.objectType !== 'VIEW' && (
              <section className="ds-detail-block">
                <h3>键、关系与索引</h3>
                <Descriptions
                  column={2}
                  size="small"
                  items={[
                    { key: 'pk', label: '主键', children: formatKeys(detail.data.primaryKeys) },
                    { key: 'fk', label: '外键', children: formatKeys(detail.data.foreignKeys) },
                    {
                      key: 'indexes',
                      label: '索引',
                      children: formatKeys(detail.data.indexes),
                      span: 2,
                    },
                  ]}
                />
              </section>
            )}
            <div className="detail-footer">
              <Button onClick={back}>返回元数据目录</Button>
              {allowed(session, 'collections.view') && detail.data.object.objectId && (
                <Button
                  onClick={() =>
                    navigate(
                      '/catalog?source=' +
                        source +
                        '&tab=changes&objectId=' +
                        detail.data?.object.objectId,
                    )
                  }
                >
                  查看变化
                </Button>
              )}
            </div>
          </>
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
