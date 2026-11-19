import { Alert, Button, Drawer, Input, Radio, Space, Tabs, Tag } from 'antd';
import { ArrowLeftOutlined, SearchOutlined } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { useLocation, useNavigate, useParams, useSearchParams } from 'react-router';
import { api, queryString } from '../../shared/api';
import { displayTime, ReadState } from '../../shared/Records';
import { useReturnFocus } from '../../shared/useReturnFocus';
import { allowed, useSession } from '../../shared/session';
import type { Page } from '../iam/types';
import { GovernanceEditor } from './GovernanceEditor';
import type { ColumnRow, GovernanceObject, History, Item, Target } from './types';
import './governance.css';

/**
 * 按稳定来源和字段进入同一表的治理详情。
 */
export function GovernanceDetailPage() {
  const { id } = useParams();
  const [params, setParams] = useSearchParams();
  const { state } = useLocation();
  const navigate = useNavigate();
  const session = useSession();
  const source = params.get('source');
  const base = '/governance/sources/' + source + '/objects/' + id;
  const object = useQuery({
    queryKey: ['governance', source, 'object', id],
    queryFn: () => api<GovernanceObject>(base),
    enabled: Boolean(source && id),
  });
  const initial = object.data?.nextPendingTarget;
  const choice = params.get('entry') ?? (initial?.kind === 'COLUMN' ? initial.targetId : 'table');
  const tab = params.get('section') ?? (choice === 'table' ? 'table' : 'columns');
  const key = choice === 'table' ? 'TABLE/' + id : 'COLUMN/' + choice;
  const itemPath = base + '/items/' + key;
  const item = useQuery({
    queryKey: ['governance', source, 'item', id, key],
    queryFn: () => api<Item>(itemPath),
    enabled: Boolean(object.data),
  });
  const [fieldTerm, setFieldTerm] = useState('');
  const [fieldState, setFieldState] = useState('ALL');
  const [fieldPage, setFieldPage] = useState(1);
  const columns = useQuery({
    queryKey: ['governance', source, 'columns', id, fieldTerm, fieldState, fieldPage],
    enabled: Boolean(object.data) && tab === 'columns',
    queryFn: () =>
      api<Page<ColumnRow>>(
        base +
          '/columns?' +
          queryString({
            keyword: fieldTerm,
            status: fieldState,
            pageNum: fieldPage,
            pageSize: 20,
          }),
      ),
  });
  const historyAllowed = ['governance.edit', 'governance.confirm', 'governance.supplement'].some(
    (right) => allowed(session, right),
  );

  /**
   * 返回位置由工作台的原筛选和实际对象定位。
   */
  function back() {
    const previous = (state as { listSearch?: string } | null)?.listSearch;
    const fallback = new URLSearchParams(params);
    fallback.delete('entry');
    fallback.delete('section');
    navigate('/governance' + (previous ?? '?' + fallback.toString()), {
      state: { returnObject: id },
    });
  }

  /**
   * 用 URL 保留字段和页签。编辑器的拦截器在切换时保护未保存输入。
   */
  function choose(target: Target | string, keepTab = false) {
    const next = new URLSearchParams(params);
    const entry =
      typeof target === 'string' ? target : target.kind === 'TABLE' ? 'table' : target.targetId;
    next.set('entry', entry);
    if (!keepTab) {
      // 定向目标自动打开对应的表说明或字段区域。
      next.delete('section');
    }
    setParams(next, { state });
  }

  /**
   * 在当前对象中回到实际可办理的下一项。
   */
  function next() {
    const target = object.data?.nextPendingTarget;
    if (target) {
      choose(target);
    }
  }

  /**
   * 切换字段区先定位实际待办字段，无待办则读取第一个真实字段。
   */
  async function switchTab(next: string) {
    if (next === 'columns' && choice === 'table') {
      const preferred = object.data?.nextPendingTarget;
      if (preferred?.kind === 'COLUMN') {
        choose(preferred);
        return;
      }
      const page = await api<Page<ColumnRow>>(base + '/columns?pageSize=1');
      if (page.items[0]) {
        choose(page.items[0].target);
        return;
      }
    }
    const replacement = new URLSearchParams(params);
    replacement.set('section', next);
    replacement.set('entry', next === 'table' ? 'table' : choice);
    setParams(replacement, { state });
  }

  if (!id || !source) {
    return (
      <Alert
        type="warning"
        title="请先选择治理数据源"
        action={<Button onClick={back}>返回工作台</Button>}
      />
    );
  }

  return (
    <section className="record-page gov-detail" aria-label="表治理详情">
      {/* 保留正式后台侧栏、返回位置和当前业务源。 */}
      <ReadState
        loading={object.isPending}
        error={object.error}
        retry={() => void object.refetch()}
      >
        {object.data && (
          <section className="panel gov-detail-panel">
            <header className="gov-detail-heading">
              <Button type="text" icon={<ArrowLeftOutlined />} onClick={back}>
                返回列表
              </Button>
              <div>
                <h1>{object.data.qualifiedName}</h1>
                <small>{object.data.sourceName}</small>
              </div>
              {object.data.pendingFactCount > 0 && (
                <Tag color="orange">来源事实待核实 {object.data.pendingFactCount} 项</Tag>
              )}
            </header>
            {object.data.pendingFactCount > 0 && allowed(session, 'collections.view') && (
              <Alert
                className="gov-source-alert"
                type="info"
                title="在结构变化中查看或核实来源"
                action={
                  <Button
                    type="link"
                    onClick={() =>
                      navigate(
                        '/catalog?' +
                          queryString({
                            source,
                            tab: 'changes',
                            objectId: id,
                          }),
                      )
                    }
                  >
                    查看结构变化
                  </Button>
                }
              />
            )}
            <Tabs
              activeKey={tab}
              onChange={(value) => {
                void switchTab(value);
              }}
              items={[
                { key: 'table', label: '表说明' },
                { key: 'columns', label: '字段' },
                ...(historyAllowed ? [{ key: 'basis', label: '状态与依据' }] : []),
              ]}
            />
            {/* 字段导航并排布置，待复核的移除字段按实际来源保留独立入口。 */}
            <div className={tab === 'columns' ? 'gov-split' : 'gov-single'}>
              {tab === 'columns' && (
                <nav className="gov-column-nav" aria-label="字段导航">
                  <Input
                    aria-label="搜索字段名称或原注释"
                    placeholder="搜索字段或注释"
                    allowClear
                    prefix={<SearchOutlined />}
                    value={fieldTerm}
                    onChange={(event) => {
                      // 新搜索从第一页开始，保留当前打开条目。
                      setFieldTerm(event.target.value);
                      setFieldPage(1);
                    }}
                  />
                  <Radio.Group
                    size="small"
                    optionType="button"
                    value={fieldState}
                    onChange={(event) => {
                      // 状态筛选与关键词取交集。
                      setFieldState(event.target.value);
                      setFieldPage(1);
                    }}
                    options={[
                      { label: '全部', value: 'ALL' },
                      { label: '待处理', value: 'PENDING' },
                      { label: '已确认', value: 'CONFIRMED' },
                    ]}
                  />
                  <ReadState
                    loading={columns.isPending}
                    error={columns.error}
                    retry={() => void columns.refetch()}
                  >
                    <div className="gov-column-list">
                      {columns.data?.items.map((column) => (
                        <button
                          key={column.target.targetId}
                          type="button"
                          aria-current={choice === column.target.targetId ? 'true' : undefined}
                          onClick={() => choose(column.target)}
                          className="gov-column-button"
                        >
                          <span>{column.name}</span>
                          <small>{column.sourceComment || column.nativeType}</small>
                          {column.pendingReview && <Tag color="orange">待复核</Tag>}
                        </button>
                      ))}
                      {object.data.pendingImpacts
                        .filter(
                          (impact) => impact.kind === 'REMOVAL' && impact.target.kind === 'COLUMN',
                        )
                        .filter(
                          (impact, index, all) =>
                            all.findIndex(
                              (candidate) => candidate.target.targetId === impact.target.targetId,
                            ) === index,
                        )
                        .map((impact) => (
                          <button
                            type="button"
                            key={impact.impactId}
                            className="gov-column-button"
                            aria-current={choice === impact.target.targetId ? 'true' : undefined}
                            onClick={() => choose(impact.target)}
                          >
                            <span>{impact.targetName}</span>
                            <Tag color="red">已移除</Tag>
                          </button>
                        ))}
                    </div>
                    {columns.data?.items.length === 0 && (
                      <p className="gov-empty">没有匹配的字段</p>
                    )}
                    {columns.data && columns.data.total > 20 && (
                      <Space size="small" className="gov-column-pages">
                        <Button
                          size="small"
                          aria-label="字段上一页"
                          disabled={columns.data.pageNum <= 1}
                          onClick={() => setFieldPage(columns.data!.pageNum - 1)}
                        >
                          上一页
                        </Button>
                        <Button
                          size="small"
                          aria-label="字段下一页"
                          disabled={columns.data.pageNum * 20 >= columns.data.total}
                          onClick={() => setFieldPage(columns.data!.pageNum + 1)}
                        >
                          下一页
                        </Button>
                      </Space>
                    )}
                  </ReadState>
                </nav>
              )}
              <div className="gov-content">
                <ReadState
                  error={item.error}
                  loading={item.isPending}
                  retry={() => void item.refetch()}
                >
                  {item.data &&
                    (tab === 'basis' ? (
                      <GovernanceHistory
                        source={source}
                        path={itemPath}
                        name={item.data.displayName}
                        item={item.data}
                      />
                    ) : (
                      <GovernanceEditor
                        key={itemPath}
                        item={item.data}
                        source={source}
                        path={itemPath}
                        name={item.data.displayName}
                        nativeType={item.data.nativeType ?? undefined}
                        sourceComment={item.data.sourceComment}
                        next={
                          initial && initial.targetId !== item.data.target.targetId
                            ? next
                            : undefined
                        }
                      />
                    ))}
                </ReadState>
              </div>
            </div>
          </section>
        )}
      </ReadState>
    </section>
  );
}

/**
 * 状态与依据在当前条目展示真实来源处理与历史。
 */
function GovernanceHistory({
  source,
  path,
  name,
  item,
}: {
  source: string;
  path: string;
  name: string;
  item: Item;
}) {
  const [page, setPage] = useState(1);
  const [revision, setRevision] = useState<number>();
  const history = useQuery({
    queryKey: ['governance', source, 'history', path, page],
    queryFn: () => api<Page<History>>(path + '/history?' + queryString({ pageNum: page })),
  });
  return (
    <section className="gov-history" aria-label="状态与依据">
      <h2>{name}</h2>
      {/* 核清记录保留实际问题和人工结论。 */}
      {item.gaps
        .filter((gap) => gap.state === 'RESOLVED')
        .map((gap) => (
          <div className="gov-history-row" key={gap.gapId}>
            <span>{gap.question}</span>
            <span>{gap.resolution}</span>
          </div>
        ))}
      <ReadState
        loading={history.isPending}
        error={history.error}
        retry={() => void history.refetch()}
      >
        {history.data?.items.map((row) => (
          <div className="gov-history-row" key={row.kind + row.id}>
            <strong>
              {row.kind === 'REVISION'
                ? '保存草稿'
                : row.action === 'ACK_REMOVAL'
                  ? '确认移除处理'
                  : '确认治理'}
            </strong>
            <small>{displayTime(row.occurredAt)}</small>
            {row.revision && (
              <Button type="link" onClick={() => setRevision(row.revision!)}>
                第 {row.revision} 版
              </Button>
            )}
          </div>
        ))}
        {history.data?.items.length === 0 && <p className="gov-empty">暂无人工修订或确认</p>}
        {history.data && history.data.total > history.data.pageSize && (
          <Space>
            <Button
              disabled={history.data.pageNum === 1}
              onClick={() => setPage(history.data!.pageNum - 1)}
            >
              上一页
            </Button>
            <Button
              disabled={history.data.pageNum * history.data.pageSize >= history.data.total}
              onClick={() => setPage(history.data!.pageNum + 1)}
            >
              下一页
            </Button>
          </Space>
        )}
      </ReadState>
      {revision && (
        <HistoryDrawer path={path} revision={revision} close={() => setRevision(undefined)} />
      )}
    </section>
  );
}

/**
 * 历史只读抽屉使用固定修订回读当时真实内容。
 */
function HistoryDrawer({
  path,
  revision,
  close,
}: {
  path: string;
  revision: number;
  close: () => void;
}) {
  useReturnFocus();
  const result = useQuery({
    queryKey: ['governance-history-detail', path, revision],
    queryFn: () => api<Page<History>>(path + '/history?' + queryString({ revision })),
  });
  const detail = result.data?.items.find((record) => record.detail)?.detail;
  return (
    <Drawer
      title={'治理修订 · 第 ' + revision + ' 版'}
      open
      width={600}
      onClose={close}
      rootClassName="record-drawer"
    >
      {/* 历史内容按可披露的原记录回读。 */}
      <ReadState
        loading={result.isPending}
        error={result.error}
        retry={() => void result.refetch()}
      >
        {detail && (
          <div className="gov-source-body">
            {Object.entries(detail.content).map(([field, value]) => (
              <p key={field} className="gov-pre-line">
                {typeof value === 'string'
                  ? value
                  : value.map(({ code, meaning }) => code + ' · ' + meaning).join('\n')}
              </p>
            ))}
          </div>
        )}
      </ReadState>
    </Drawer>
  );
}
