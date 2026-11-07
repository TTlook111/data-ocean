// @vitest-environment happy-dom
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, shallowMount, type VueWrapper } from '@vue/test-utils'
import { reactive } from 'vue'
import type { IssueTrendPeriod, QualityIssueTrend } from '../../../api/admin/governance'

const data = vi.hoisted(() => ({
  api: vi.fn(),
  auth: { token:'reader' },
  iam: { loaded:true, loading:false, errorMessage:'', snapshot: {}, issueView:true, canOnDatasource:vi.fn() },
  context: { datasourceId:5, snapshotId:8, initialized:true, currentSnapshot:{ id:8 }, loading:false, routeSyncing:false, routeSyncError:'' },
  route: { query:{} as Record<string,string> },
}))
vi.mock('vue-router', () => ({ useRoute: () => reactive(data.route) }))
vi.mock('../../../stores/auth', () => ({ useAuthStore: () => reactive(data.auth) }))
vi.mock('../../../stores/iamS1', () => ({ useIamS1Store: () => reactive(data.iam) }))
vi.mock('../../../stores/adminContext', () => ({ useAdminContextStore: () => reactive(data.context) }))
vi.mock('../../../api/admin/governance', () => ({ getQualityIssueTrend:data.api }))
import QualityDashboard from './QualityDashboard.vue'

function trend(snapshotId: number, days: IssueTrendPeriod): QualityIssueTrend {
  // 前一天只有基线之后的进出流量；只有当前日真实剩余为零。
  const firstRecorded='2026-11-06'
  return { datasourceId:5, snapshotId, asOf:'2026-11-07T12:00:00', coverageStart:firstRecorded+'T18:30:00',
    days:Array.from({length:days},(_,i)=>{
      const date=new Date(Date.UTC(2026,10,8-days+i)).toISOString().slice(0,10)
      if(date<firstRecorded) return {date,coverage:'NONE',remaining:null,added:null,reopened:null,resolved:null,otherClosed:null}
      return date===firstRecorded
        ? {date,coverage:'PARTIAL',remaining:2,added:1,reopened:1,resolved:0,otherClosed:0}
        : {date,coverage:'FULL',remaining:0,added:0,reopened:0,resolved:1,otherClosed:1}
    }),
  }
}
let wrapper: VueWrapper
beforeEach(()=>{
  data.context.snapshotId=8
  data.context.currentSnapshot={ id:8 }
  data.context.initialized=true
  data.iam.snapshot={}
  data.iam.issueView=true
  data.api.mockReset().mockImplementation(async(snapshot:number,days:IssueTrendPeriod)=>({data:trend(snapshot,days)}))
  data.iam.canOnDatasource.mockReset().mockImplementation((code:string)=>code!=='governance:issue:view' || reactive(data.iam).issueView)
})
afterEach(()=>wrapper?.unmount())
function mountPage(){
  wrapper=shallowMount(QualityDashboard,{global:{stubs:{
    TaskPageHeader:{template:'<header><slot name="actions"/></header>'},RouterLink:true,ErrorState:{props:['message'],template:'<p>{{message}}</p>'},
  }}})
  return wrapper
}

it('历史空档、部分覆盖及今日真实零值分别呈现，日期选择只查看当日进出',async()=>{
  const w=mountPage()
  await flushPromises()
  expect(data.api).toHaveBeenCalledWith(8,7)
  expect(w.get('.balance strong').text()).toBe('0')
  expect(w.text()).not.toContain('较期初')
  expect(w.get('.trend-detail p').text()).toContain('截至目前剩余 0 条')
  await w.get('select').setValue('2026-11-06')
  expect(w.get('.trend-detail p').text()).toContain('重开 1 条')
  await w.get('select').setValue('2026-11-01')
  expect(w.get('.trend-detail p').text()).toBe('此日期尚无历史记录。')
  expect(w.find('.quality-check-workbench').exists()).toBe(false)
})

it('切到30天时不会接纳晚到的7天结果，错属结果显示读取错误',async()=>{
  let finish!: (value:unknown)=>void
  data.api.mockReturnValueOnce(new Promise(resolve=>{finish=resolve}))
  const w=mountPage()
  await flushPromises()
  expect(data.api).toHaveBeenCalledWith(8,7)
  await w.get('button:last-child').trigger('click')
  await flushPromises()
  expect(w.findAll('option')).toHaveLength(30)
  finish({data:trend(8,7)})
  await flushPromises()
  expect(w.findAll('option')).toHaveLength(30)
  data.api.mockResolvedValueOnce({data:trend(8,30)})
  reactive(data.context).snapshotId=9
  reactive(data.context).currentSnapshot={ id:9 }
  await flushPromises()
  expect(w.find('.balance').exists()).toBe(false)
  expect(w.text()).toContain('返回的日期或数据范围与当前选择不一致')
})

it('只有本地偏好编号时不发请求，等待本账号目录完成核验', async () => {
  data.context.initialized=false
  const w=mountPage()
  await flushPromises()
  expect(data.api).not.toHaveBeenCalled()
  expect(w.find('.balance').exists()).toBe(false)
  reactive(data.context).initialized=true
  await flushPromises()
  expect(data.api).toHaveBeenCalledWith(8,7)
  expect(w.get('.balance strong').text()).toBe('0')
})

it('有治理查看权但缺问题查看权，可定位检查页，不能读或保留同源问题趋势',async()=>{
  const w=mountPage()
  await flushPromises()
  reactive(data.iam).issueView=false
  await flushPromises()
  expect(w.find('.balance').exists()).toBe(false)
  expect(w.text()).toContain('没有此来源的问题趋势查看权限')
  expect(data.api).toHaveBeenCalledTimes(1)
})
