import { Button, Form, Input, Select, Space, Tag } from 'antd';
import { SearchOutlined } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { useEffect, useRef, useState } from 'react';
import { useLocation, useNavigate } from 'react-router';
import { api, queryString } from '../../shared/api';
import { ReadState } from '../../shared/Records';
import { RecordTable } from '../../shared/RecordTable';
import { MoreFilters } from '../../shared/MoreFilters';
import { allowed, useSession } from '../../shared/session';
import { SourcePicker, SourceUnavailable, useSourceSelection } from '../datasource/CollectionPage';
import type { Page } from '../iam/types';
import type { GovernanceRow, Target } from './types';
import { GovernanceNoteMapping } from './GovernanceNoteMapping';
import './governance.css';

/**
 * 保留 B 批目录快照及完整工作台分页。
 */
type TablePage = {
  /**
   * 当前源的对象分页。
   */
  page: Page<GovernanceRow>;
  /**
   * 最近一次成功采集存在时才有值。
   */
  currentSnapshotId: string | null;
  /**
   * 当前范围是否需要新的成功采集。
   */
  freshness: { scopePending: boolean; stale: boolean };
};

/**
 * 长表格只显示业务操作所需的三种进度。
 */
const progressNames: Record<string, string> = {
  UNSTARTED: '待治理',
  IN_PROGRESS: '治理中',
  COMPLETE: '已治理',
};

/**
 * 工作台表列表复用现成数据源选项、查询表单、桌面表格和分页。
 */
export function GovernancePage() {
  const navigate = useNavigate();
  const session = useSession();
  const location = useLocation();
  const returnId = (location.state as { returnObject?: string } | null)?.returnObject;
  const focusRestored = useRef(false);
  const { params, setParams, sources, selected } = useSourceSelection('GOVERNANCE');
  const [form] = Form.useForm();
  const [moreOpen, setMoreOpen] = useState(false);
  const size = Number(params.get('pageSize') ?? 10);
  const filters = ['keyword', 'pendingKind', 'hasPendingImpact', 'progress'];
  const filtered = filters.some((key) => params.has(key));
  const table = useQuery({
    queryKey: ['governance', selected?.id, 'objects', params.toString()],
    enabled: Boolean(selected),
    queryFn: () =>
      api<TablePage>(
        '/governance/sources/' +
          selected!.id +
          '/objects?' +
          queryString(Object.fromEntries(params)),
      ),
  });

  useEffect(() => {
    // 回到原筛选时将焦点定位到刚办理的行。
    if (returnId && table.isSuccess && !focusRestored.current) {
      focusRestored.current = true;
      document.getElementById('governance-object-' + returnId)?.focus();
    }
  }, [returnId, table.isSuccess]);

  /**
   * 重置查询条件并保留当前源及页尺寸。
   */
  function reset(source = selected?.id) {
    setMoreOpen(false);
    // 显式清空上一个数据源的查询条件。
    form.setFieldsValue({
      keyword: undefined,
      pendingKind: undefined,
      hasPendingImpact: undefined,
      progress: undefined,
    });
    setParams(queryString({ source, pageSize: size }));
    form.focusField('keyword');
  }

  /**
   * 打开已核实的对象或定向的复核条目。
   */
  function open(row: Pick<GovernanceRow, 'sourceId' | 'objectId'>, target?: Target) {
    // 用稳定源和目标标识保持入口及返回条件。
    const next = new URLSearchParams(params);
    next.set('source', row.sourceId);
    if (target) {
      next.set('entry', target.kind === 'TABLE' ? 'table' : target.targetId);
    }
    navigate('/governance/' + row.objectId + '?' + next.toString(), {
      state: {
        listSearch: '?' + queryString({ ...Object.fromEntries(params), source: row.sourceId }),
      },
    });
  }

  return (
    <section className="record-page" aria-label="治理工作台">
      {/* 表治理查询面板的条件依表头顺序排列。 */}
      <div className="panel query-panel gov-query compact-query">
        <Form
          name="governance-list-query"
          layout="inline"
          form={form}
          initialValues={Object.fromEntries(params)}
          onFinish={(values) => {
            // 查找留在当前源，应用条件和分页保存在 URL。
            setMoreOpen(false);
            setParams(
              queryString({
                ...values,
                pendingKind: form.getFieldValue('pendingKind'),
                hasPendingImpact: form.getFieldValue('hasPendingImpact'),
                source: selected?.id,
                pageSize: size,
              }),
            );
            form.focusField('keyword');
          }}
        >
          <Form.Item>
            <div className="gov-source-navigation">
              <SourcePicker sources={sources.data} selected={selected} choose={reset} />
              {selected && (
                <Space size={8} wrap>
                  {allowed(session, 'business-notes.view') && (
                    <Button onClick={() => navigate('/business-notes?source=' + selected.id)}>
                      业务说明
                    </Button>
                  )}
                  {allowed(session, 'knowledge.view') && (
                    <Button onClick={() => navigate('/knowledge?source=' + selected.id)}>
                      知识文档
                    </Button>
                  )}
                </Space>
              )}
            </div>
          </Form.Item>
          <Form.Item name="keyword">
            <Input
              aria-label="表名、业务名称或原注释"
              placeholder="表名或说明"
              prefix={<SearchOutlined />}
              allowClear
            />
          </Form.Item>
          {/* 常用查询保持一行，低频字段仍可按原规则提交。 */}
          <Form.Item name="progress" className="status-query">
            <Select
              allowClear
              aria-label="治理进度"
              placeholder="治理进度"
              options={Object.entries(progressNames).map(([value, label]) => ({ value, label }))}
            />
          </Form.Item>
          <div className="query-tail-actions">
            <Space>
              <MoreFilters
                open={moreOpen}
                setOpen={setMoreOpen}
                count={Number(params.has('pendingKind')) + Number(params.has('hasPendingImpact'))}
              >
                <Form.Item name="pendingKind">
                  <Select
                    allowClear
                    aria-label="待处理内容"
                    placeholder="待处理内容"
                    options={[
                      { value: 'TABLE', label: '表说明' },
                      { value: 'COLUMN', label: '字段' },
                    ]}
                  />
                </Form.Item>
                <Form.Item name="hasPendingImpact">
                  <Select
                    allowClear
                    aria-label="变更后待核对项"
                    placeholder="变更后待核对项"
                    options={[
                      { value: 'true', label: '有待核对项' },
                      { value: 'false', label: '无待核对项' },
                    ]}
                  />
                </Form.Item>
              </MoreFilters>
              <Button htmlType="submit">查询</Button>
              <Button onClick={() => reset()}>重置</Button>
            </Space>
          </div>
        </Form>
      </div>
      {/* 工作台结果始终保留表头、分页和真实采集状态。 */}
      <ReadState
        error={sources.error}
        loading={sources.isPending}
        retry={() => void sources.refetch()}
      >
        {!selected ? (
          <SourceUnavailable />
        ) : (
          <section className="panel records-panel" aria-label="表治理列表">
            <GovernanceNoteMapping
              key={selected.id}
              source={selected.id}
              open={(target) => open({ sourceId: selected.id, objectId: target.objectId }, target)}
            />
            <RecordTable<GovernanceRow>
              rowKey="observationId"
              dataSource={table.data?.page.items}
              loading={table.isPending}
              error={table.error}
              filtered={filtered}
              retry={() => void table.refetch()}
              onReset={() => reset()}
              emptyDescription={
                table.data?.currentSnapshotId ? '当前源没有可治理的表或视图' : '等待首次采集成功'
              }
              pagination={{
                current: table.data?.page.pageNum ?? 1,
                pageSize: size,
                total: table.data?.page.total ?? 0,
                onChange: (pageNum, pageSize) =>
                  setParams(
                    queryString({
                      ...Object.fromEntries(params),
                      source: selected.id,
                      pageNum,
                      pageSize,
                    }),
                  ),
              }}
              columns={[
                {
                  title: '对象 / 业务名称',
                  width: 245,
                  // 源名和业务名称共享表格轴，完整名称可悬停查看。
                  render: (_, row) => (
                    <div className="ds-row-identity">
                      <span title={row.qualifiedName}>{row.qualifiedName}</span>
                      {(row.businessName || row.sourceComment) && (
                        <small title={row.businessName ?? row.sourceComment ?? ''}>
                          {row.businessName ?? row.sourceComment}
                        </small>
                      )}
                    </div>
                  ),
                },
                {
                  title: '业务用途',
                  dataIndex: 'purpose',
                  ellipsis: true,
                  width: 170,
                  // 来源注释不会填成业务用途。
                  render: (value) => value || '—',
                },
                {
                  title: '待处理内容',
                  width: 130,
                  // 表说明和字段分开定位。
                  render: (_, row) =>
                    row.pendingKinds
                      .map((kind) => (kind === 'TABLE' ? '表说明' : '字段'))
                      .join('、') || '—',
                },
                {
                  title: '变更后待核对项',
                  width: 200,
                  // 只显示实际开放的影响目标，并提供直接定位。
                  render: (_, row) => {
                    const targets = [
                      ...new Map(
                        row.pendingImpacts.map((impact) => [
                          impact.target.targetId + impact.target.kind,
                          impact,
                        ]),
                      ).values(),
                    ];
                    return targets.length ? (
                      <div className="gov-impact-links">
                        {targets.map((impact) => (
                          <Button
                            type="link"
                            size="small"
                            key={impact.impactId}
                            onClick={() => open(row, impact.target)}
                          >
                            {impact.targetName}
                          </Button>
                        ))}
                      </div>
                    ) : (
                      '—'
                    );
                  },
                },
                {
                  title: '表说明与字段进度',
                  width: 156,
                  // 数量仅汇总当前表说明和实际字段。
                  render: (_, row) => (
                    <div className="gov-progress">
                      <Tag
                        color={
                          row.progress === 'COMPLETE'
                            ? 'green'
                            : row.progress === 'IN_PROGRESS'
                              ? 'blue'
                              : 'default'
                        }
                      >
                        {progressNames[row.progress]}
                      </Tag>
                      <span>
                        {row.confirmedCount} / {row.totalCount}
                      </span>
                    </div>
                  ),
                },
                {
                  title: '操作',
                  width: 112,
                  fixed: 'right',
                  className: 'record-actions-cell',
                  // 未核实的观察不冒用治理条目标识。
                  render: (_, row) =>
                    row.objectId ? (
                      <Button
                        id={'governance-object-' + row.objectId}
                        type="link"
                        onClick={() => open(row)}
                      >
                        {row.availability === 'AVAILABLE' ? '治理' : '查看'}
                      </Button>
                    ) : (
                      <span className="table-action-note">待核实</span>
                    ),
                },
              ]}
            />
          </section>
        )}
      </ReadState>
    </section>
  );
}
