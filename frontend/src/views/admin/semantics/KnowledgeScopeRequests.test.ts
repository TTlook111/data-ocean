import { readFileSync } from 'node:fs'
import ts from 'typescript'
import { ref } from 'vue'
import { describe, it, expect, vi } from 'vitest'

// 执行页面实际读取函数，以可控响应顺序验证状态；不把它称为浏览器验收。
const source = readFileSync(new URL('./KnowledgeView.vue', import.meta.url), 'utf8').split('<script setup lang="ts">')[1]!.split('</script>')[0]!
function loader(name: string, env: Record<string, unknown>, page = source) {
  const file = ts.createSourceFile('page.ts', page, ts.ScriptTarget.Latest, true)
  const declaration = file.statements.find((node) => ts.isFunctionDeclaration(node) && node.name?.text === name)!
  const js = ts.transpile(declaration.getText(file), { target: ts.ScriptTarget.ESNext })
  return new Function('env', `with (env) { ${js}; return ${name}; }`)(env) as (...args: unknown[]) => Promise<void>
}
function deferred() {
  let resolve!: (value: any) => void
  let reject!: (error: Error) => void
  const promise = new Promise<any>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}
function environment() {
  return { disposed: false, scopeEpoch: 0, datasourceId: ref(1), statsRequestId: 0, stats: { total: 0 },
    statsError: ref(''), statsLoading: ref(false), publishedRequestId: 0, publishedSnapshot: ref<any>(null),
    publishedSnapshotError: ref(''), publishedSnapshotLoading: ref(false), ragRequestId: 0, ragBuilds: ref<any[]>([]), ragBuildError: ref(''), ragBuildsLoading: ref(false),
    snapshotRequestId: 0, generateDialogVisible: ref(true), generateForm: { datasourceId: 1 }, snapshots: ref<any[]>([]), snapshotsError: ref(''), snapshotsLoading: ref(false),
    apiError: (error: Error) => error.message }
}

describe('知识页异步范围', () => {
  it('主图加载期间展开实体后两请求结束会解除 loading 并允许刷新', async () => {
    const page = readFileSync(new URL('../audit/DataLineage.vue', import.meta.url), 'utf8').split('<script setup lang="ts">')[1]!.split('</script>')[0]!
    const pending = deferred()
    const env = { datasourceId: ref(1), canViewLineage: ref(true), graphRequestId: 0,
      loading: ref(false), loadingDatasourceId: null, impactDisposed: false,
      depth: ref(3), lineageTypeFilter: ref([]), entities: ref<any[]>([]), relationships: ref<any[]>([]),
      getEntitiesByDatasource: vi.fn().mockReturnValue(pending.promise),
      getEnrichedLineage: vi.fn().mockResolvedValue({ data: { nodes: [], edges: [] } }),
      nextTick: async () => {}, renderGraph: vi.fn(), ElMessage: { error: vi.fn() },
      currentDatasourceEntityIds: new Set(), routeEntityId: ref(null), focusEntityId: ref(''),
      routeEntityHint: ref(''), graphError: ref(''), getHttpErrorMessage: (error: Error) => error.message }
    const load = loader('loadGraph', env, page)
    const expand = loader('loadEntityLineage', env, page)
    const request = load()
    await expand(2)
    pending.resolve({ data: [] }); await request
    expect(env.loading.value).toBe(false)
    env.getEntitiesByDatasource.mockResolvedValue({ data: [] })
    await load()
    expect(env.getEntitiesByDatasource).toHaveBeenCalledTimes(2)
  })
  it.each(['success', 'failure'] as const)('旧主图增强读取 %s 晚到不会覆盖新展开或解除其加载状态', async (outcome) => {
    const page = readFileSync(new URL('../audit/DataLineage.vue', import.meta.url), 'utf8').split('<script setup lang="ts">')[1]!.split('</script>')[0]!
    const old = deferred(), latest = deferred()
    const root = { id: 1, entityType: 'TABLE', fqn: 'ds.db.orders' }
    const env = { datasourceId: ref(1), canViewLineage: ref(true), graphRequestId: 0,
      loading: ref(false), loadingDatasourceId: null, impactDisposed: false,
      depth: ref(3), lineageTypeFilter: ref([]), entities: ref<any[]>([]), relationships: ref<any[]>([]),
      getEntitiesByDatasource: vi.fn().mockResolvedValue({ data: [root] }),
      getEnrichedLineage: vi.fn().mockReturnValueOnce(old.promise).mockReturnValueOnce(latest.promise),
      getEntityLineage: vi.fn(), nextTick: async () => {}, renderGraph: vi.fn(), ElMessage: { error: vi.fn() },
      currentDatasourceEntityIds: new Set(), routeEntityId: ref(null), focusEntityId: ref(''),
      routeEntityHint: ref(''), graphError: ref(''), getHttpErrorMessage: (error: Error) => error.message }
    const load = loader('loadGraph', env, page), expand = loader('loadEntityLineage', env, page)
    const request = load(); await Promise.resolve()
    const expanded = expand(2)
    if (outcome === 'success') old.resolve({ data: { nodes: [], edges: [{ id: 'old' }] } })
    else old.reject(new Error('旧请求失败'))
    await request
    expect(env.loading.value).toBe(true)
    expect(env.getEntityLineage).not.toHaveBeenCalled()
    latest.resolve({ data: { nodes: [root], edges: [{ id: 'new' }] } }); await expanded
    expect(env.relationships.value).toEqual([{ id: 'new' }])
    expect(env.loading.value).toBe(false)
    expect(env.graphError.value).toBe('')
  })
  it('血缘影响面板换源后旧失败不能覆盖新结果或停止新请求加载', async () => {
    const page = readFileSync(new URL('../audit/DataLineage.vue', import.meta.url), 'utf8').split('<script setup lang="ts">')[1]!.split('</script>')[0]!
    const first = deferred(), second = deferred()
    const env = { datasourceId: ref(1), impactRequestId: 0, impactDisposed: false, impactVisible: ref(false),
      selectedEntity: ref(null), impactTarget: ref(null), impactLoading: ref(false), impactError: ref(''), impact: ref(null), impactQueries: ref([]),
      closeContextMenu: vi.fn(), ElMessage: { warning: vi.fn() },
      analyzeImpact: vi.fn().mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise),
      queryTableLineage: vi.fn().mockResolvedValue({ data: [] }), queryColumnLineage: vi.fn() }
    const analyze = loader('handleAnalyzeImpact', env, page)
    const entity = { id: 1, entityType: 'TABLE', name: 'orders', fqn: 'ds.db.orders' }
    const a = analyze(entity); env.datasourceId.value = 2; const b = analyze(entity)
    first.reject(new Error('旧源错误')); await a
    expect(env.impactError.value).toBe(''); expect(env.impactLoading.value).toBe(true)
    second.resolve({ data: { dependentQueryCount: 105 } }); await b
    expect(env.impact.value).toEqual({ dependentQueryCount: 105 })
  })
  it('A→B→A 后最早的 A 统计不能覆盖最新 A', async () => {
    const first = deferred(), middle = deferred(), latest = deferred()
    const env = { ...environment(), listKnowledgeDocs: vi.fn() }
    env.listKnowledgeDocs.mockReturnValue(first.promise)
    const load = loader('loadStats', env); const a = load()
    env.datasourceId.value = 2; env.scopeEpoch++; env.listKnowledgeDocs.mockReturnValue(middle.promise); const b = load()
    env.datasourceId.value = 1; env.scopeEpoch++; env.listKnowledgeDocs.mockReturnValue(latest.promise); const c = load()
    latest.resolve({ data: { total: 30 } }); await c
    first.resolve({ data: { total: 10 } }); middle.resolve({ data: { total: 20 } }); await Promise.all([a, b])
    expect(env.stats.total).toBe(30)
  })

  it('旧失败及 finally 不覆盖新构建范围的加载状态', async () => {
    const first = deferred(), second = deferred()
    const env = { ...environment(), listRagBuilds: vi.fn().mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise) }
    const load = loader('loadRagBuilds', env); const a = load()
    env.datasourceId.value = 2; env.scopeEpoch++; const b = load()
    first.reject(new Error('旧范围失败')); await a
    expect(env.ragBuildError.value).toBe(''); expect(env.ragBuildsLoading.value).toBe(true)
    second.resolve({ data: [{ buildId: 'B' }] }); await b
    expect(env.ragBuilds.value).toEqual([{ buildId: 'B' }]); expect(env.ragBuildsLoading.value).toBe(false)
  })

  it('卸载后发布快照不再更新', async () => {
    const pending = deferred()
    const env = { ...environment(), getPublishedSnapshot: vi.fn().mockReturnValue(pending.promise) }
    const request = loader('loadPublishedSnapshot', env)(); env.disposed = true
    pending.resolve({ data: { snapshotId: 88 } }); await request
    expect(env.publishedSnapshot.value).toBeNull()
  })

  it('生成弹窗换源或关闭后旧快照列表不可回填', async () => {
    const first = deferred(), second = deferred()
    const env = { ...environment(), listSnapshots: vi.fn().mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise) }
    const load = loader('loadSnapshots', env); const a = load(1)
    env.generateForm.datasourceId = 2; const b = load(2)
    second.resolve({ data: { records: [{ id: 2 }] } }); await b
    first.resolve({ data: { records: [{ id: 1 }] } }); await a
    expect(env.snapshots.value).toEqual([{ id: 2 }])
    env.listSnapshots.mockResolvedValue({ data: { records: [{ id: 3 }] } })
    const c = load(2); env.generateDialogVisible.value = false; await c
    expect(env.snapshots.value).toEqual([{ id: 2 }])
  })
})
