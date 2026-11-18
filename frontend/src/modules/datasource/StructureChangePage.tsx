import {
  App,
  Button,
  DatePicker,
  Descriptions,
  Drawer,
  Form,
  Input,
  Modal,
  Select,
  Space,
} from 'antd';
import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router';
import { api, queryString } from '../../shared/api';
import { DetailFooter, ReadState, displayTime } from '../../shared/Records';
import { RecordTable } from '../../shared/RecordTable';
import { ReviewDialog } from '../../shared/ReviewDialog';
import type { ReviewSubmission } from '../../shared/ReviewDialog';
import { allowed, useSession } from '../../shared/session';
import type { Page, Preview } from '../iam/types';
import { SourcePicker, useSourceSelection } from './CollectionPage';
import { DatasourceStatus, datasourceLabel, datasourceOptions } from './Presentation';
import type { StructureChange } from './types';

/**
 * 采集任务可独立打开该源的变化记录。
 */
export function StandaloneStructureChangePage() {
  const { setParams, sources, selected } = useSourceSelection('COLLECTIONS');
  return (
    <section className="record-page" aria-label="结构变化">
      <div className="ds-toolbar">
        <SourcePicker
          sources={sources.data}
          selected={selected}
          choose={(source) => setParams({ source })}
        />
      </div>
      <ReadState
        error={sources.error}
        loading={sources.isPending}
        empty={!selected && !sources.isPending}
        retry={() => void sources.refetch()}
      >
        {selected && <StructureChanges source={selected.id} />}
      </ReadState>
    </section>
  );
}

/**
 * 从原任务或目录查看真实结构变化，可直接核实待办。
 */
export function StructureChanges({ source }: { source: string }) {
  const session = useSession();
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const [form] = Form.useForm();
  const [id, setId] = useState<string | null>(null);
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
  const size = Number(params.get('pageSize') ?? 10);
  return (
    <>
      {/* 过滤顺序按变化对象、类型、发现时间与处理阶段。 */}
      <Form
        form={form}
        layout="inline"
        className="record-filters"
        initialValues={Object.fromEntries(params)}
        onFinish={(values) => {
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
          setParams(new URLSearchParams(queryString(next)));
        }}
      >
        <Form.Item name="keyword">
          <Input aria-label="变化对象" placeholder="对象名或变化项" allowClear />
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
        <Form.Item name="dates">
          <DatePicker.RangePicker placeholder={['发现开始日期', '发现结束日期']} />
        </Form.Item>
        <Form.Item name="stage">
          <Select
            aria-label="处理阶段"
            placeholder="处理阶段"
            allowClear
            options={datasourceOptions(['PENDING_REVIEW', 'PENDING_GOVERNANCE', 'COMPLETED'])}
          />
        </Form.Item>
        <Space>
          <Button htmlType="submit">查询</Button>
          <Button
            onClick={() => {
              form.setFieldsValue({
                keyword: undefined,
                changeType: undefined,
                dates: undefined,
                stage: undefined,
              });
              setParams({ source, ...(params.get('tab') ? { tab: 'changes' } : {}) });
            }}
          >
            重置
          </Button>
        </Space>
      </Form>
      <RecordTable<StructureChange>
        rowKey="changeId"
        dataSource={list.data?.items}
        loading={list.isPending}
        error={list.error}
        retry={() => void list.refetch()}
        pagination={{
          current: list.data?.pageNum ?? 1,
          pageSize: size,
          total: list.data?.total ?? 0,
          onChange: (pageNum, pageSize) =>
            setParams({
              ...Object.fromEntries(params),
              source,
              pageNum: String(pageNum),
              pageSize: String(pageSize),
            }),
        }}
        columns={[
          {
            title: '变化对象',
            width: 195,
            render: (_, item) => (
              <>
                <Button type="link" onClick={() => setId(item.changeId)}>
                  {item.target}
                </Button>
                <small className="ds-secondary">{datasourceLabel(item.attributeKind)}</small>
              </>
            ),
          },
          { title: '变化类型', width: 150, render: (_, item) => datasourceLabel(item.changeType) },
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
            render: (_, item) => (
              <Button type="link" onClick={() => setId(item.changeId)}>
                查看详情
              </Button>
            ),
          },
        ]}
      />
      <Drawer
        title={detail.data?.target ?? '结构变化'}
        width={600}
        open={Boolean(id)}
        onClose={() => setId(null)}
        destroyOnHidden
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
              {/* 只有真实包含前后证据时才展示比较。 */}
              <div className="ds-compare">
                <section>
                  <h3>变化前</h3>
                  <pre>{displayValue(detail.data.before)}</pre>
                </section>
                <section>
                  <h3>变化后</h3>
                  <pre>{displayValue(detail.data.after)}</pre>
                </section>
              </div>
              <DetailFooter close={() => setId(null)}>
                <Button
                  onClick={() =>
                    navigate('/collections?source=' + source + '&task=' + detail.data?.sourceTaskId)
                  }
                >
                  查看来源采集
                </Button>
                {detail.data.factState === 'PENDING' && allowed(session, 'collections.review') && (
                  <Button type="primary" onClick={() => setChecking(detail.data ?? null)}>
                    核实变化
                  </Button>
                )}
              </DetailFooter>
            </>
          )}
        </ReadState>
      </Drawer>
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
 * 保留数据类型的实际值并使用可换行的文本阅读方式。
 */
function displayValue(value: unknown) {
  if (value == null) return '—';
  return typeof value === 'string' ? value : JSON.stringify(value, null, 2);
}
