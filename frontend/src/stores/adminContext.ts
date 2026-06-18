import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { listMyDatasources, type UserDatasourceItem } from '../api/datasource'
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

  const datasources = ref<UserDatasourceItem[]>([])
  const snapshots = ref<SnapshotItem[]>([])
  const knowledgeDocs = ref<KnowledgeDocItem[]>([])
  const datasourceId = ref<number | undefined>(persisted.datasourceId)
  const snapshotId = ref<number | undefined>(persisted.snapshotId)
  const knowledgeDocId = ref<number | undefined>(persisted.knowledgeDocId)
  const loading = ref(false)
  const initialized = ref(false)
  let initPromise: Promise<void> | null = null

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
    const result = await listMyDatasources()
    datasources.value = result.data
    reconcileDatasource()
  }

  async function loadSnapshots() {
    if (!datasourceId.value) {
      snapshots.value = []
      snapshotId.value = undefined
      return
    }
    const result = await listSnapshots({ datasourceId: datasourceId.value, page: 1, size: 50 })
    snapshots.value = result.data.records
    reconcileSnapshot()
  }

  async function loadKnowledgeDocs() {
    if (!datasourceId.value) {
      knowledgeDocs.value = []
      knowledgeDocId.value = undefined
      return
    }
    const result = await listKnowledgeDocs({ datasourceId: datasourceId.value, page: 1, pageSize: 50 })
    knowledgeDocs.value = result.data.records
    reconcileKnowledgeDoc()
  }

  async function initialize(force = false) {
    if (!force && initialized.value) return
    if (initPromise) return initPromise
    loading.value = true
    initPromise = (async () => {
      await loadDatasources()
      await Promise.all([loadSnapshots(), loadKnowledgeDocs()])
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
    datasourceId.value = id
    snapshotId.value = undefined
    knowledgeDocId.value = undefined
    loading.value = true
    try {
      await Promise.all([loadSnapshots(), loadKnowledgeDocs()])
      persist()
    } finally {
      loading.value = false
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
