<script setup lang="ts">
/**
 * 血缘 DAG 可视化组件
 *
 * 使用 ECharts Graph 展示表级/列级血缘关系图谱。
 * 支持按数据源过滤、按血缘类型着色、点击展开列级血缘。
 */
import { ref, onMounted, watch, nextTick } from 'vue'
import { ElMessage } from 'element-plus'
import * as echarts from 'echarts'
import { listMyDatasources, type UserDatasourceItem } from '../../../api/datasource'
import {
  getEntitiesByDatasource,
  getEntityLineage,
  type MetadataEntityItem,
  type MetadataRelationshipItem,
} from '../../../api/admin/catalog'

const datasourceId = ref<number | null>(null)
const datasources = ref<UserDatasourceItem[]>([])
const entities = ref<MetadataEntityItem[]>([])
const relationships = ref<MetadataRelationshipItem[]>([])
const loading = ref(false)
const chartRef = ref<HTMLElement | null>(null)
let chart: echarts.ECharts | null =null

/** 血缘类型颜色映射 */
const LINEAGE_COLORS: Record<string, string> = {
  QUERY: '#4d8fdc',
  ETL: '#52c41a',
  MANUAL: '#999999',
}

async function loadDatasources() {
  try {
    const res = await listMyDatasources()
    datasources.value = res.data ?? []
  } catch {
    ElMessage.error('数据源列表加载失败')
  }
}

async function loadGraph() {
  if (!datasourceId.value) return
  loading.value = true
  try {
    const entRes = await getEntitiesByDatasource(datasourceId.value)
    entities.value = entRes.data ?? []

    // 加载所有实体的血缘关系
    const lineagePromises = entities.value.map((e) => getEntityLineage(e.id))
    const lineageResults = await Promise.allSettled(lineagePromises)
    const allRels: MetadataRelationshipItem[] = []
    for (const r of lineageResults) {
      if (r.status === 'fulfilled' && r.value.data) {
        allRels.push(...r.value.data)
      }
    }
    // 去重
    const seen = new Set<string>()
    relationships.value = allRels.filter((r) => {
      const key = `${r.sourceId}-${r.targetId}-${r.relationType}`
      if (seen.has(key)) return false
      seen.add(key)
      return true
    })

    await nextTick()
    renderGraph()
  } catch {
    ElMessage.error('血缘图谱加载失败')
  } finally {
    loading.value = false
  }
}

function renderGraph() {
  if (!chartRef.value) return
  if (!chart) {
    chart = echarts.init(chartRef.value)
  }

  // 只取 TABLE 和 COLUMN 类型的实体
  const tableEntities = entities.value.filter((e) => e.entityType === 'TABLE' || e.entityType === 'COLUMN')

  // 构建节点
  const nodeMap = new Map<string, { id: string; name: string; entity: MetadataEntityItem }>()
  for (const e of tableEntities) {
    nodeMap.set(String(e.id), { id: String(e.id), name: e.name, entity: e })
  }

  // 构建边（只取 LINEAGE 和 FOREIGN_KEY 关系）
  const edges: Array<{ source: string; target: string; lineStyle: { color: string; width: number } }> = []
  for (const rel of relationships.value) {
    if (!nodeMap.has(String(rel.sourceId)) || !nodeMap.has(String(rel.targetId))) continue
    if (rel.relationType !== 'LINEAGE' && rel.relationType !== 'FOREIGN_KEY') continue

    // 解析血缘类型
    let lineageType = 'MANUAL'
    if (rel.relationMetadata) {
      try {
        const meta = JSON.parse(rel.relationMetadata)
        lineageType = meta.lineage_type || 'MANUAL'
      } catch { /* ignore */ }
    }

    edges.push({
      source: String(rel.sourceId),
      target: String(rel.targetId),
      lineStyle: {
        color: LINEAGE_COLORS[lineageType] || LINEAGE_COLORS.MANUAL,
        width: rel.relationType === 'FOREIGN_KEY' ? 2 : 1.5,
      },
    })
  }

  // 如果没有血缘关系，显示 CONTAINS 关系（数据源→表→列）
  if (edges.length === 0) {
    for (const rel of relationships.value) {
      if (!nodeMap.has(String(rel.sourceId)) || !nodeMap.has(String(rel.targetId))) continue
      if (rel.relationType !== 'CONTAINS' && rel.relationType !== 'HAS_PART') continue
      edges.push({
        source: String(rel.sourceId),
        target: String(rel.targetId),
        lineStyle: { color: '#cccccc', width: 1 },
      })
    }
  }

  const nodes = Array.from(nodeMap.values()).map((n) => ({
    id: n.id,
    name: n.name,
    symbolSize: n.entity.entityType === 'TABLE' ? 30 : 15,
    category: n.entity.entityType === 'TABLE' ? 0 : 1,
    itemStyle: {
      color: n.entity.entityType === 'TABLE' ? '#4d8fdc' : '#91d5ff',
    },
    label: {
      show: n.entity.entityType === 'TABLE',
      fontSize: 11,
    },
  }))

  const option = {
    tooltip: {
      formatter(params: { data: { id: string; name: string } }) {
        const entity = nodeMap.get(params.data.id)
        if (!entity) return params.data.name
        return `<strong>${entity.entity.displayName || entity.entity.name}</strong><br/>
                类型: ${entity.entity.entityType}<br/>
                FQN: ${entity.entity.fqn}<br/>
                ${entity.entity.description || ''}`
      },
    },
    legend: {
      data: ['TABLE', 'COLUMN'],
      bottom: 0,
    },
    series: [
      {
        type: 'graph',
        layout: 'force',
        roam: true,
        draggable: true,
        force: {
          repulsion: 200,
          edgeLength: [80, 160],
          gravity: 0.1,
        },
        categories: [
          { name: 'TABLE' },
          { name: 'COLUMN' },
        ],
        data: nodes,
        links: edges,
        lineStyle: {
          curveness: 0.2,
        },
        emphasis: {
          focus: 'adjacency',
          lineStyle: { width: 3 },
        },
      },
    ],
  }

  chart.setOption(option, true)
}

watch(datasourceId, () => {
  if (datasourceId.value) loadGraph()
})

onMounted(() => {
  loadDatasources()
  window.addEventListener('resize', () => chart?.resize())
})
</script>

<template>
  <div class="lineage-graph-page" v-loading="loading">

    <section class="toolbar">
      <el-select
        v-model="datasourceId"
        placeholder="选择数据源"
        clearable
        filterable
        style="width: 280px"
      >
        <el-option
          v-for="item in datasources"
          :key="item.id"
          :label="`${item.name}${item.databaseName ? ` / ${item.databaseName}` : ''}`"
          :value="item.id"
        />
      </el-select>

      <div class="legend-hint">
        <span class="legend-item"><span class="dot" style="background: #4d8fdc" /> 查询血缘 (QUERY)</span>
        <span class="legend-item"><span class="dot" style="background: #52c41a" /> ETL 血缘 (ETL)</span>
        <span class="legend-item"><span class="dot" style="background: #999" /> 手动标注 (MANUAL)</span>
      </div>
    </section>

    <section class="graph-container">
      <div v-if="!datasourceId" class="empty-hint">
        请先选择数据源查看血缘图谱
      </div>
      <div v-else-if="!loading && entities.length === 0" class="empty-hint">
        该数据源暂无实体数据，请先发布快照
      </div>
      <div v-else ref="chartRef" class="chart-area" />
    </section>
  </div>
</template>

<style scoped>
.lineage-graph-page {
  display: grid;
  gap: 16px;
  padding: 24px;
}





.toolbar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 16px;
}

.legend-hint {
  display: flex;
  gap: 16px;
  font-size: 12px;
  color: var(--do-muted);
}

.legend-item {
  display: flex;
  align-items: center;
  gap: 4px;
}

.dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  display: inline-block;
}

.graph-container {
  border: 1px solid var(--do-line);
  border-radius: 8px;
  background: var(--do-surface);
  min-height: 500px;
  overflow: hidden;
}

.chart-area {
  width: 100%;
  height: 600px;
}

.empty-hint {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 400px;
  color: var(--do-muted);
  font-size: 14px;
}
</style>
