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
  return { scopeReady:ref(true),canOn:()=>true, disposed: false, scopeEpoch: 0, datasourceId: ref(1), statsRequestId: 0, stats: { total: 0 },
    statsError: ref(''), statsLoading: ref(false), publishedRequestId: 0, publishedSnapshot: ref<any>(null),
    publishedSnapshotError: ref(''), publishedSnapshotLoading: ref(false), ragRequestId: 0, ragBuilds: ref<any[]>([]), ragBuildError: ref(''), ragBuildsLoading: ref(false),
    snapshotRequestId: 0, generateDialogVisible: ref(true), generateForm: { datasourceId: 1 }, snapshots: ref<any[]>([]), snapshotsError: ref(''), snapshotsLoading: ref(false),
    iamS1: { systemAdmin: true }, canViewSnapshots: () => true,
    apiError: (error: Error) => error.message }
}

describe('知识页异步范围', () => {
  // 血缘读取的组件级异步边界测试迁入 DataLineage.test.ts 与 QueryLineageEvidence.test.ts。
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
