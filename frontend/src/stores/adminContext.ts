import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { listSimpleDatasources, type DatasourceSimpleItem } from '../api/admin/datasource'
import { listKnowledgeDocs, type KnowledgeDocItem } from '../api/admin/knowledge'
import { listSnapshots, type SnapshotItem } from '../api/admin/metadata'

const STORAGE_KEY = 'dataocean_admin_context'

interface PersistedContext {
  datasourceId?: number
  snapshotId?: number
  knowledgeDocId?: number
}

function readContext(): PersistedContext {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    return raw ? JSON.parse(raw) : {}
  } catch {
    localStorage.removeItem(STORAGE_KEY)
    return {}
  }
}

function writeContext(context: PersistedContext) {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(context))
}

function latestSnapshotFirst(a: SnapshotItem, b: SnapshotItem) {
  return b.snapshotVersion - a.snapshotVersion
}

export const useAdminContextStore = defineStore('admin-context', () => {
  const persisted = readContext()

  const datasources = ref<DatasourceSimpleItem[]>([])
  const snapshots = ref<SnapshotItem[]>([])
  const knowledgeDocs = ref<KnowledgeDocItem[]>([])
  const datasourceId = ref<number | undefined>(persisted.datasourceId)
  const snapshotId = ref<number | undefined>(persisted.snapshotId)
  const knowledgeDocId = ref<number | undefined>(persisted.knowledgeDocId)
  const loading = ref(false)
  const initialized = ref(false)
  let initPromise: Promise<void> | null = null
  let selectionRequest = 0

  const currentDatasource = computed(() => datasources.value.find((item) => item.id === datasourceId.value))
  const currentSnapshot = computed(() => snapshots.value.find((item) => item.id === snapshotId.value))
  const currentKnowledgeDoc = computed(() => knowledgeDocs.value.find((item) => item.id === knowledgeDocId.value))

  function persist() {
    writeContext({
      datasourceId: datasourceId.value,
      snapshotId: snapshotId.value,
      knowledgeDocId: knowledgeDocId.value,
    })
  }

  function reconcileDatasource() {
    if (datasourceId.value && datasources.value.some((item) => item.id === datasourceId.value)) return
    datasourceId.value = datasources.value[0]?.id
  }

  function reconcileSnapshot() {
    snapshots.value = [...snapshots.value].sort(latestSnapshotFirst)
    if (snapshotId.value && snapshots.value.some((item) => item.id === snapshotId.value)) return
    snapshotId.value = snapshots.value[0]?.id
  }

  function reconcileKnowledgeDoc() {
    if (knowledgeDocId.value && knowledgeDocs.value.some((item) => item.id === knowledgeDocId.value)) return
    knowledgeDocId.value = knowledgeDocs.value.find((item) => item.status === 'PUBLISHED')?.id ?? knowledgeDocs.value[0]?.id
  }

  async function loadDatasources() {
    const result = await listSimpleDatasources()
    datasources.value = result.data
    reconcileDatasource()
  }

  async function loadSnapshots(forDatasourceId = datasourceId.value) {
    if (!forDatasourceId) {
      return
    }
    return listSnapshots({ datasourceId: forDatasourceId, page: 1, size: 50 })
  }

  async function loadKnowledgeDocs(forDatasourceId = datasourceId.value) {
    if (!forDatasourceId) {
      return
    }
    return listKnowledgeDocs({ datasourceId: forDatasourceId, page: 1, pageSize: 50 })
  }

  async function initialize(force = false) {
    if (!force && initialized.value) return
    if (initPromise) return initPromise
    loading.value = true
    initPromise = (async () => {
      await loadDatasources()
      const [snapshotResult, knowledgeResult] = await Promise.all([
        loadSnapshots(),
        loadKnowledgeDocs(),
      ])
      snapshots.value = snapshotResult?.data.records || []
      knowledgeDocs.value = knowledgeResult?.data.records || []
      reconcileSnapshot()
      reconcileKnowledgeDoc()
      persist()
      initialized.value = true
    })()
    try {
      await initPromise
    } finally {
      loading.value = false
      initPromise = null
    }
  }

  async function refresh() {
    await initialize(true)
  }

  async function selectDatasource(id?: number) {
    const requestId = ++selectionRequest
    datasourceId.value = id
    snapshotId.value = undefined
    knowledgeDocId.value = undefined
    snapshots.value = []
    knowledgeDocs.value = []
    persist()
    loading.value = true
    try {
      const [snapshotResult, knowledgeResult] = await Promise.all([
        loadSnapshots(id),
        loadKnowledgeDocs(id),
      ])
      if (requestId !== selectionRequest) return
      snapshots.value = snapshotResult?.data.records || []
      knowledgeDocs.value = knowledgeResult?.data.records || []
      reconcileSnapshot()
      reconcileKnowledgeDoc()
      persist()
    } finally {
      if (requestId === selectionRequest) loading.value = false
    }
  }

  function selectSnapshot(id?: number) {
    snapshotId.value = id
    persist()
  }

  function selectKnowledgeDoc(id?: number) {
    knowledgeDocId.value = id
    persist()
  }

  return {
    datasources,
    snapshots,
    knowledgeDocs,
    datasourceId,
    snapshotId,
    knowledgeDocId,
    loading,
    initialized,
    currentDatasource,
    currentSnapshot,
    currentKnowledgeDoc,
    initialize,
    selectDatasource,
    selectSnapshot,
    selectKnowledgeDoc,
    refresh,
  }
})
