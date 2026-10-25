// @vitest-environment happy-dom
import { mount,flushPromises } from '@vue/test-utils'
import { reactive,nextTick } from 'vue'
import { beforeEach,describe,expect,it,vi } from 'vitest'
import GovernanceWorklist from './GovernanceWorklist.vue'
import { discoverGovernance,listGovernanceTasks } from '../../api/admin/governanceWorkflow'
const state=vi.hoisted(()=>({context:{datasourceId:5,snapshotId:8},query:{} as Record<string,unknown>,permissions:['governance:field:view'],push:vi.fn()}))
vi.mock('../../stores/adminContext',()=>({useAdminContextStore:()=>state.context}))
vi.mock('../../stores/iamS1',()=>({useIamS1Store:()=>({canOnDatasource:(code:string)=>state.permissions.includes(code)})}))
vi.mock('vue-router',()=>({useRoute:()=>({query:state.query}),useRouter:()=>({push:state.push})}))
vi.mock('../../api/admin/governanceWorkflow',()=>({pauseGovernance:vi.fn(),governanceError:vi.fn(),discoverGovernance:vi.fn(),listGovernanceTasks:vi.fn(),retryGovernance:vi.fn(),getGovernanceRules:vi.fn()}))
vi.mock('../../api/admin/fieldSemantic',()=>({runSemanticCheck:vi.fn()}))
vi.mock('element-plus',()=>({ElMessage:{success:vi.fn(),error:vi.fn()}}))
const button={props:['disabled','loading'],emits:['click'],template:'<button :disabled="disabled" @click="$emit(\'click\')"><slot/></button>'}
function mountPage(){return mount(GovernanceWorklist,{global:{stubs:{ElButton:button,ElTable:{props:['data'],template:'<div>{{data.map(row=>row.tableName).join("|")}}</div>'},ElTableColumn:true,ElPagination:true,ElCollapse:{template:'<div><slot/></div>'},ElCollapseItem:{template:'<div><slot/></div>'}},directives:{loading:()=>{}}}})}
function response(name:string,total=1){return {data:{records:[{id:1,tableName:name,columnName:'amount',stage:'NEEDS_GOVERNANCE',aiStatus:'HUMAN_READY',origin:'DISCOVERY',reason:'缺少解释',nextAction:'EDIT'}],total,aiSummary:{queued:0,running:0,failed:0}}} as never}
beforeEach(()=>{vi.clearAllMocks();state.context=reactive({datasourceId:5,snapshotId:8});state.query={};state.permissions=['governance:field:view']})
describe('治理任务上下文与能力',()=>{
  it('查看能力不授予执行 AI 的能力，数量来自服务端',async()=>{
    vi.mocked(listGovernanceTasks).mockResolvedValue(response('orders',99));const wrapper=mountPage();await flushPromises()
    const start=wrapper.findAll('button').find(b=>b.text()==='开始治理')!
    expect(start.attributes('disabled')).toBeDefined();await start.trigger('click')
    expect(discoverGovernance).not.toHaveBeenCalled();expect(wrapper.text()).toContain('共 99 项任务');wrapper.unmount()
  })
  it('切换快照后旧请求不能覆盖新范围',async()=>{
    let resolve!:(value:never)=>void
    vi.mocked(listGovernanceTasks).mockReturnValueOnce(new Promise(r=>{resolve=r}) as never).mockResolvedValueOnce(response('new_orders'))
    const wrapper=mountPage();await nextTick();state.context.snapshotId=9;await flushPromises()
    expect(wrapper.text()).toContain('new_orders');resolve(response('old_orders'));await flushPromises()
    expect(wrapper.text()).not.toContain('old_orders');expect(listGovernanceTasks).toHaveBeenLastCalledWith(9,1);wrapper.unmount()
  })
  it('显式快照尚未同步时不回退到旧范围',async()=>{
    state.query={snapshotId:'9'};vi.mocked(listGovernanceTasks).mockResolvedValue(response('requested'))
    const wrapper=mountPage();await flushPromises();expect(listGovernanceTasks).not.toHaveBeenCalled()
    state.context.snapshotId=9;await flushPromises();expect(listGovernanceTasks).toHaveBeenCalledWith(9,1);wrapper.unmount()
  })
  it('显式数据源尚未同步时不读取或启动旧数据源的任务',async()=>{
    state.query={datasourceId:'6'};vi.mocked(listGovernanceTasks).mockResolvedValue(response('requested_source'))
    const wrapper=mountPage();await flushPromises();expect(listGovernanceTasks).not.toHaveBeenCalled()
    state.context.datasourceId=6;state.context.snapshotId=9;await flushPromises();expect(listGovernanceTasks).toHaveBeenCalledWith(9,1);wrapper.unmount()
  })
  it('没有字段查看能力不请求数据也不显示伪造的零任务',async()=>{
    state.permissions=[];const wrapper=mountPage();await flushPromises()
    expect(listGovernanceTasks).not.toHaveBeenCalled();expect(wrapper.text()).toContain('没有当前范围');expect(wrapper.text()).not.toContain('共 0 项任务');wrapper.unmount()
  })
  it('旧范围的启动响应不能锁住新范围或清除新范围的忙碌状态',async()=>{
    state.permissions=['governance:field:view','governance:check','governance:field:manage','knowledge:manage','knowledge:view']
    vi.mocked(listGovernanceTasks).mockResolvedValue(response('orders'))
    let resolveA!:(value:unknown)=>void,resolveB!:(value:unknown)=>void
    vi.mocked(discoverGovernance).mockReturnValueOnce(new Promise(r=>{resolveA=r}) as never).mockReturnValueOnce(new Promise(r=>{resolveB=r}) as never)
    const wrapper=mountPage();await flushPromises()
    await wrapper.findAll('button').find(b=>b.text()==='开始治理')!.trigger('click')
    state.context.datasourceId=6;state.context.snapshotId=9;await flushPromises()
    let start=wrapper.findAll('button').find(b=>b.text()==='开始治理')!
    expect(start.attributes('disabled')).toBeUndefined();await start.trigger('click')
    resolveA({data:{scanned:1,created:1}});await flushPromises();start=wrapper.findAll('button').find(b=>b.text()==='开始治理')!
    expect(start.attributes('disabled')).toBeDefined();resolveB({data:{scanned:2,created:2}});await flushPromises()
    expect(wrapper.findAll('button').find(b=>b.text()==='开始治理')!.attributes('disabled')).toBeUndefined();wrapper.unmount()
  })
})
