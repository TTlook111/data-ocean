import { Alert, Button, Drawer, Input, Segmented, Space, Tabs, Tag } from 'antd';
import { SearchOutlined } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { useEffect, useRef, useState } from 'react';
import { useLocation, useNavigate, useParams, useSearchParams } from 'react-router';
import { api, queryString } from '../../shared/api';
import { displayTime, ReadState } from '../../shared/Records';
import { useReturnFocus } from '../../shared/useReturnFocus';
import { allowed, useSession } from '../../shared/session';
import type { Page } from '../iam/types';
import { GovernanceBatch, type BatchRun } from './GovernanceBatch';
import { GovernanceEditor } from './GovernanceEditor';
import { GovernanceDiscoveryPanel, type DiscoveryKind } from './GovernanceDiscoveryPanel';
import { GovernanceStatus } from './GovernanceStatus';
import type { ColumnRow, GenerationTarget, GovernanceObject, History, Item, Target } from './types';
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
  // 明确选择表说明时不能被当前待办字段抢占，否则会在表说明页编辑字段。
  const choice =
    params.get('section') === 'table'
      ? 'table'
      : (params.get('entry') ?? (initial?.kind === 'COLUMN' ? initial.targetId : 'table'));
  const tab = params.get('section') ?? (choice === 'table' ? 'table' : 'columns');
  const discoveryKind = tab === 'relations' ? 'RELATION' : tab === 'metrics' ? 'METRIC' : undefined;
  const key = choice === 'table' ? 'TABLE/' + id : 'COLUMN/' + choice;
  const itemPath = base + '/items/' + key;
  const item = useQuery({
    queryKey: ['governance', source, 'item', id, key],
    queryFn: () => api<Item>(itemPath),
    enabled: Boolean(object.data) && !discoveryKind,
  });
  const [fieldTerm, setFieldTerm] = useState('');
  const [fieldState, setFieldState] = useState('ALL');
  const [fieldPage, setFieldPage] = useState(1);
  const fieldList = useRef<HTMLDivElement>(null);
  const [batchOpen, setBatchOpen] = useState(false);
  const [batch, setBatch] = useState<{ base: string; run: BatchRun }>();
  const [batchSuggestion, setBatchSuggestion] = useState<GenerationTarget>();
  const currentBatch = batch?.base === base ? batch.run : undefined;
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

  useEffect(() => {
    // 定向进入和切换字段时，仅滚动字段列表，独立编辑区与键盘焦点保持原位。
    const list = fieldList.current;
    const current = list?.querySelector<HTMLButtonElement>('[aria-current="true"]');
    if (!list || !current) return;

    const viewport = list.getBoundingClientRect();
    const row = current.getBoundingClientRect();
    if (row.top < viewport.top) {
      // 当前项在上方时只补齐上边缘。
      list.scrollTop += row.top - viewport.top;
    } else if (row.bottom > viewport.bottom) {
      // 当前项在下方时只补齐下边缘。
      list.scrollTop += row.bottom - viewport.bottom;
    }
  }, [tab, choice, columns.data, object.data?.pendingImpacts]);

  /**
   * 返回位置由工作台的原筛选和实际对象定位。
   */
  function back() {
    const catalog = state as { catalogSearch?: string; catalogRow?: string } | null;
    if (catalog?.catalogSearch && allowed(session, 'catalog.view')) {
      // 从目录治理回到原来源的同一筛选和对象位置。
      navigate('/catalog' + catalog.catalogSearch, {
        state: { catalogReturnTo: catalog.catalogRow },
      });
      return;
    }
    const previous = (state as { listSearch?: string } | null)?.listSearch;
    const fallback = new URLSearchParams(params);
    fallback.delete('entry');
    fallback.delete('section');
    fallback.delete('field');
    fallback.delete('candidate');
    fallback.delete('candidatePage');
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
    if (entry !== 'table') {
      // 表说明使用自己的入口，字段页记住最后选中的字段。
      next.set('field', entry);
    }
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
      const previous = params.get('field');
      if (previous) {
        choose(previous);
        return;
      }
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
    if (choice !== 'table') replacement.set('field', choice);
    replacement.delete('candidate');
    replacement.delete('candidatePage');
    setParams(replacement, { state });
  }

  /**
   * 从全表状态定位到对应候选及其分页位置。
   */
  function openCandidate(kind: DiscoveryKind, candidate: string, page: number) {
    const next = new URLSearchParams(params);
    next.set('section', kind === 'RELATION' ? 'relations' : 'metrics');
    next.set('candidate', candidate);
    next.set('candidatePage', String(page));
    setParams(next, { state });
  }

  /**
   * 从批次结果进入对应治理条目，继续使用当前编辑区的离开保护。
   */
  function viewBatchResult(result: GenerationTarget) {
    setBatchOpen(false);
    if (result.discoveryKind) {
      // 发现结果持久化为候选，交回同一对象的人工审核页签。
      void switchTab(result.discoveryKind === 'RELATION' ? 'relations' : 'metrics');
    } else {
      // 只有切到匹配条目后才显示建议差异。
      setBatchSuggestion(result);
      if (
        result.target.targetId !== item.data?.target.targetId ||
        tab === 'basis' ||
        discoveryKind
      ) {
        choose(result.target);
      }
    }
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

  const suggestReason = object.data?.tableEntry.actions.suggestUnavailableReason;
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
              <div className="gov-object-name">
                <h1>{object.data.qualifiedName}</h1>
                <small>{object.data.sourceName}</small>
              </div>
              {object.data.pendingFactCount > 0 && (
                <Tag color="orange">来源事实待核实 {object.data.pendingFactCount} 项</Tag>
              )}
              <Space wrap>
                {(currentBatch || object.data.tableEntry.actions.canSuggest) && (
                  <>
                    {suggestReason && <small>{suggestReason}</small>}
                    <Button
                      disabled={Boolean(suggestReason && !currentBatch)}
                      onClick={() => setBatchOpen(true)}
                    >
                      {currentBatch ? '批量生成结果' : '批量生成 AI 建议'}
                    </Button>
                  </>
                )}
                {source && allowed(session, 'knowledge.view') && (
                  <Button onClick={() => navigate('/knowledge?' + queryString({ source }))}>
                    知识文档
                  </Button>
                )}
              </Space>
            </header>
            {/* 关闭弹窗后可在当前详情重新打开已接收批次。 */}
            {batchOpen && (
              <GovernanceBatch
                key={base}
                base={base}
                source={source}
                sourceName={object.data.sourceName}
                table={object.data.tableEntry}
                run={currentBatch}
                accepted={(run) => setBatch(run ? { base, run } : undefined)}
                onView={viewBatchResult}
                close={() => setBatchOpen(false)}
              />
            )}
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
                { key: 'relations', label: '关联' },
                { key: 'metrics', label: '指标' },
                ...(historyAllowed ? [{ key: 'basis', label: '状态与依据' }] : []),
              ]}
            />
            {/* 字段导航并排布置，待复核的移除字段按实际来源保留独立入口。 */}
            <div className="gov-detail-area">
              {discoveryKind ? (
                <GovernanceDiscoveryPanel
                  key={base + discoveryKind + (params.get('candidate') ?? '')}
                  kind={discoveryKind}
                  source={source}
                  path={base + '/candidates'}
                  canCreate={object.data.tableEntry.actions.canEdit}
                  suggestReason={suggestReason}
                  initialCandidate={params.get('candidate') ?? undefined}
                  initialPage={Number(params.get('candidatePage')) || 1}
                />
              ) : (
                <div className={tab === 'columns' ? 'gov-split' : 'gov-single'}>
                  {tab === 'basis' && (
                    <GovernanceStatus
                      base={base}
                      object={object.data}
                      choose={choose}
                      openCandidate={openCandidate}
                    />
                  )}
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
                      {/* 分段控件等宽排列，选中样式与键盘焦点分别表达。 */}
                      <Segmented
                        className="gov-state-filter"
                        aria-label="字段状态"
                        block
                        size="small"
                        value={fieldState}
                        onChange={(value) => {
                          // 状态筛选与关键词取交集。
                          setFieldState(value);
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
                        <div className="gov-column-list" ref={fieldList}>
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
                              (impact) =>
                                impact.kind === 'REMOVAL' && impact.target.kind === 'COLUMN',
                            )
                            .filter(
                              (impact, index, all) =>
                                all.findIndex(
                                  (candidate) =>
                                    candidate.target.targetId === impact.target.targetId,
                                ) === index,
                            )
                            .map((impact) => (
                              <button
                                type="button"
                                key={impact.impactId}
                                className="gov-column-button"
                                aria-current={
                                  choice === impact.target.targetId ? 'true' : undefined
                                }
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
                  <div
                    className={
                      tab === 'basis'
                        ? 'gov-content gov-history-content'
                        : 'gov-content gov-edit-content'
                    }
                  >
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
                            suggestionRequest={
                              batchSuggestion?.target.targetId === item.data.target.targetId &&
                              batchSuggestion.target.kind === item.data.target.kind
                                ? batchSuggestion.requestId
                                : undefined
                            }
                            onSuggestionClose={() => setBatchSuggestion(undefined)}
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
              )}
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
  const resolved = item.gaps.filter((gap) => gap.state === 'RESOLVED');
  if (history.isSuccess && history.data.total === 0 && resolved.length === 0) return null;
  return (
    <section className="gov-history" aria-label="当前条目记录">
      <h2>{name} · 办理记录</h2>
      {/* 核清记录保留实际问题和人工结论。 */}
      {resolved.map((gap) => (
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
