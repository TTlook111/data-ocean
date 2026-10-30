import { describe, expect, it, vi } from 'vitest'
import { http } from '../http'
import { discoverGovernance, listGovernanceTasks, reviewGovernanceFeedback, readGovernanceHistory } from './governanceWorkflow'
vi.mock('../http', () => ({ http: { get:vi.fn(),post:vi.fn() } }))
describe('治理闭环接口',()=>{
  it('读取审计历史时使用明确对象类型与 ID',async()=>{
    vi.mocked(http.get).mockResolvedValue({data:{data:[]}})
    await readGovernanceHistory('BUSINESS',7)
    expect(http.get).toHaveBeenCalledWith('/api/admin/business-knowledge/7/history')
    await readGovernanceHistory('FEEDBACK',9)
    expect(http.get).toHaveBeenLastCalledWith('/api/admin/governance-feedback/9/history')
  })
  it('发现任务绑定显式快照并允许人工接管，不自动采纳候选',async()=>{
    vi.mocked(http.post).mockResolvedValue({data:{data:{created:2}}})
    await discoverGovernance(81,false)
    expect(http.post).toHaveBeenCalledWith('/api/admin/governance-tasks/snapshots/81/discover',null,{params:{aiFirst:false}})
  })
  it('服务端分页和反馈审核保留版本及归因',async()=>{
    vi.mocked(http.get).mockResolvedValue({data:{data:{records:[],total:0}}})
    await listGovernanceTasks(81,3)
    expect(http.get).toHaveBeenCalledWith('/api/admin/governance-tasks/snapshots/81',{params:{pageNo:3,pageSize:20}})
    await reviewGovernanceFeedback(7,{expectedVersion:2,approved:true,reasonCode:'FIELD_USAGE',columnMetaId:4,note:'业务核对'})
    expect(http.post).toHaveBeenLastCalledWith('/api/admin/governance-feedback/7/review',{expectedVersion:2,approved:true,reasonCode:'FIELD_USAGE',columnMetaId:4,note:'业务核对'})
  })
})
