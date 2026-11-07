import { afterEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { useAuthStore } from '../../../stores/auth'
import { useIamS1Store } from '../../../stores/iamS1'
import type { ExperiencePeriod, QueryExperience } from '../../../api/admin/dashboard'
import AdminHomeView from '../../AdminHomeView.vue'
import ExperienceDetailsView from './ExperienceDetailsView.vue'

export const firstStart = '2026-11-07T09:00:00', secondStart = '2026-11-07T09:30:00', cutoff = '2026-11-07T10:00:00'

// 样本的反馈分母独立于审计权限范围；无样本桶用 null，有耗时的两个桶可对比和精确下钻。
export function summary(period: ExperiencePeriod = 'today'): QueryExperience {
  return { period, asOf: cutoff, start: firstStart, end: cutoff, targetSeconds: 10,
    usage: { total: 10, completed: 7, measured: 6, failed: 1, timedOut: 1, processing: 1, cancelled: 0, clarification: 0,
      avgSeconds: 10, p95Seconds: 16, previousAvgSeconds: null, slow: 2, buckets: [
        { start: firstStart, end: secondStart, measured: 4, slow: 0, avgSeconds: 7 },
        { start: secondStart, end: cutoff, measured: 2, slow: 2, avgSeconds: 16 },
      ] },
    feedback: { eligible: 60, likes: 20, dislikes: 5, previousLikes: 1, previousDislikes: 0 } }
}

export function grant(codes: string[], global = true, revision = 1) {
  const iam = useIamS1Store()
  iam.snapshot = { protocolVersion: 'IAM-SIMPLE-1', userId: 1, systemAdmin: false, globalFunctions: global ? ['admin:workbench:view'] : [],
    datasourceCapabilities: [{ datasourceId: 5, datasourceName: '负责源', functionCodes: codes, functionNames: [] }],
    queryUse: false, viewSql: false, export: false, permissionRevision: revision }
  iam.loaded = true
}
export function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(r => { resolve = r })
  return { resolve, promise }
}
const wrappers: VueWrapper[] = []
afterEach(() => wrappers.splice(0).forEach(w => w.unmount()))

// 使用真实 Vue 路由来覆盖前进、返回与同组件换周期；只将 Canvas 图表交互替换为可断言的组件。
export async function openWorkbench(path = '/admin/workbench', codes = ['audit:view','governance:field:view','datasource:view'], global = true) {
  const pinia = createPinia()
  setActivePinia(pinia)
  useAuthStore().token = 'A'
  grant(codes, global)
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/admin/workbench', component: AdminHomeView }, { path: '/admin/workbench/details', name: 'admin-workbench-details', component: ExperienceDetailsView },
    { path: '/admin/data-sources/:id', name: 'admin-data-source-detail', component: { template: '<p>来源详情</p>' } },
  ] })
  await router.push(path)
  const wrapper = mount(RouterView, { global: { plugins: [pinia, router], stubs: { WaitingTimeChart: true } } })
  wrappers.push(wrapper)
  await flushPromises()
  return { wrapper, router }
}
