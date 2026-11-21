import { Button, Form, Input, Select, Space } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { useEffect, useRef } from 'react';
import { useLocation, useNavigate } from 'react-router';
import { api, queryString } from '../../shared/api';
import { RecordTable } from '../../shared/RecordTable';
import type { Page } from '../iam/types';
import { VersionAvailability, type PublishedVersion } from './ReleasePublicationPanel';
import { versionAvailabilityLabels, type EffectiveVersionListPosition } from './types';

/**
 * 在当前生效页签按同源权威绑定浏览正式版本。
 */
export function KnowledgeEffectiveVersions({
  source,
  params,
  update,
}: {
  /** 列表限定同一来源。 */
  source: string;
  /** 已应用的筛选上下文可在地址中恢复。 */
  params: URLSearchParams;
  /** 更换列表条件时保留当前来源与页签。 */
  update: (values: Record<string, string | number | null>) => void;
}) {
  const [form] = Form.useForm();
  const location = useLocation();
  const navigate = useNavigate();
  const restoredFocus = useRef<string>(undefined);
  const list = useRef<HTMLElement>(null);
  const path = '/knowledge/sources/' + source + '/bindings';
  const scope = params.get('scopeId');
  const keyword = params.get('versionKeyword');
  const object = params.get('versionObject');
  const availability = params.get('versionAvailability');
  const pageNum = Number(params.get('versionPageNum')) || 1;
  const pageSize = Number(params.get('versionPageSize')) || 10;

  useEffect(() => {
    // 浏览器前进和后退同步已应用条件，分页仍保留当前未提交的输入。
    form.setFieldsValue({
      versionKeyword: keyword ?? '',
      versionObject: object ?? '',
      versionAvailability: availability ?? null,
    });
  }, [form, keyword, object, availability]);

  const page = useQuery({
    queryKey: ['release-bindings', source, scope, keyword, object, pageNum, pageSize, availability],
    queryFn: () =>
      api<Page<PublishedVersion>>(
        path +
          '?' +
          queryString({ scopeId: scope, keyword, object, pageNum, pageSize, availability }),
      ),
  });
  const returnState = location.state as {
    effectiveVersionFocus?: string;
    effectiveVersionPosition?: EffectiveVersionListPosition;
  } | null;
  const focus = returnState?.effectiveVersionFocus;
  const position = returnState?.effectiveVersionPosition;
  useEffect(() => {
    // 正式列表加载后恢复表体位置，并把键盘焦点交回原版本入口。
    if (focus && page.isSuccess && !page.isFetching && restoredFocus.current !== location.key) {
      restoredFocus.current = location.key;
      const body = list.current?.querySelector('.ant-table-content');
      const restored = body && position;
      if (restored) body.scrollTo(restored);
      (document.getElementById(focus) ?? list.current)?.focus({ preventScroll: Boolean(restored) });
    }
  }, [focus, location.key, page.isFetching, page.isSuccess, position]);

  /**
   * 重置当前生效的查找条件，原任务列表条件继续独立维护。
   */
  function reset() {
    form.setFieldsValue({ versionKeyword: '', versionObject: '', versionAvailability: null });
    update({
      scopeId: null,
      versionKeyword: null,
      versionObject: null,
      versionAvailability: null,
      versionPageNum: 1,
    });
  }

  /**
   * 来源及已应用条件随独立版本页传递，返回时无需重新选择。
   */
  function openVersion(bindingId: string) {
    const search = new URLSearchParams(params);
    search.set('source', source);
    search.set('tab', 'active');
    const body = list.current?.querySelector('.ant-table-content');
    navigate(
      { pathname: '/releases/versions/' + bindingId, search: search.toString() },
      {
        state: { effectiveVersionPosition: body && { top: body.scrollTop, left: body.scrollLeft } },
      },
    );
  }

  return (
    <>
      {/* 来源与页签下方的筛选均由正式绑定查询执行。 */}
      <div className="panel query-panel compact-query effective-version-filters">
        <Form
          form={form}
          layout="inline"
          initialValues={{
            versionKeyword: keyword,
            versionObject: object,
            versionAvailability: availability ?? undefined,
          }}
          onFinish={(values) =>
            update({
              versionKeyword: values.versionKeyword || null,
              versionObject: values.versionObject || null,
              versionAvailability: values.versionAvailability || null,
              versionPageNum: 1,
            })
          }
        >
          <Form.Item name="versionKeyword">
            <Input aria-label="文档名称" placeholder="文档名称" allowClear />
          </Form.Item>
          <Form.Item name="versionObject">
            <Input aria-label="来源对象" placeholder="来源对象" allowClear />
          </Form.Item>
          {/* 状态与查询操作同组，桌面较窄时也不会只换行两个按钮。 */}
          <div className="query-tail status-tail">
            <Form.Item name="versionAvailability" className="status-query">
              <Select
                aria-label="当前可用性"
                placeholder="全部状态"
                allowClear
                options={Object.entries(versionAvailabilityLabels)
                  .filter(([value]) => value !== 'SUPERSEDED')
                  .map(([value, label]) => ({ value, label }))}
              />
            </Form.Item>
            <Space>
              <Button htmlType="submit">查询</Button>
              <Button onClick={reset}>重置</Button>
            </Space>
          </div>
        </Form>
      </div>
      <section
        ref={list}
        id="effective-version-list"
        tabIndex={-1}
        className="panel records-panel knowledge-records"
        aria-label="当前生效列表"
      >
        <RecordTable<PublishedVersion>
          rowKey="id"
          tableLayout="fixed"
          flexibleColumns={[0, 1, 2, 3]}
          dataSource={page.data?.items}
          loading={page.isPending}
          error={page.error}
          retry={() => void page.refetch()}
          emptyDescription="暂无当前生效版本"
          filtered={Boolean(keyword || object || scope || availability)}
          onReset={reset}
          columns={[
            {
              title: '文档 / 来源对象',
              width: 340,
              render: (_, row) => (
                <div className="knowledge-candidate-identity">
                  <span className="cell-text" title={row.title}>
                    {row.title}
                  </span>
                  <small className="cell-text muted" title={row.objects.join('、')}>
                    {row.objects.join('、') || '—'}
                  </small>
                </div>
              ),
            },
            { title: '生效修订', width: 118, render: (_, row) => 'r' + row.revision },
            { title: '来源发布', width: 180, dataIndex: 'taskId', ellipsis: true },
            {
              title: '当前可用性',
              width: 148,
              render: (_, row) => <VersionAvailability version={row} />,
            },
            {
              title: '操作',
              width: 116,
              className: 'record-actions-cell',
              fixed: 'right',
              render: (_, row) => (
                <Button
                  id={'release-version-' + row.id}
                  type="link"
                  onClick={() => openVersion(row.id)}
                >
                  查看版本
                </Button>
              ),
            },
          ]}
          pagination={{
            current: page.data?.pageNum ?? pageNum,
            pageSize,
            total: page.data?.total,
            onChange: (n, size) => update({ versionPageNum: n, versionPageSize: size }),
          }}
        />
      </section>
    </>
  );
}
