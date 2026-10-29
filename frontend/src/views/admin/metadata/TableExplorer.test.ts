// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, reactive } from 'vue'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import TableExplorer from './TableExplorer.vue'
const state=vi.hoisted(()=>({auth:null as any,iam:null as any,context:null as any,route:null as any}))
const api=vi.hoisted(()=>({listSnapshots:vi.fn(),getSnapshotDetail:vi.fn(),replace:vi.fn()}))
vi.mock('../../../api/admin/metadata',()=>api)
vi.mock('../../../stores/auth',()=>({useAuthStore:()=>state.auth}))
vi.mock('../../../stores/iamS1',()=>({useIamS1Store:()=>state.iam}))
vi.mock('../../../stores/adminContext',()=>({useAdminContextStore:()=>state.context}))
vi.mock('vue-router',()=>({useRoute:()=>state.route,useRouter:()=>({replace:api.replace})}))
const Message=defineComponent({props:['message'],emits:['retry'],template:'<p>{{message}}<button @click="$emit(\'retry\')">retry</button></p>'})
const wrappers:VueWrapper[]=[]
const snapshot={id:11,datasourceId:1,snapshotVersion:3,status:'PUBLISHED',tableCount:1,columnCount:1}
function detail(name='CURRENT'){return {data:{snapshot,tables:[{id:1,tableName:name,tableType:'TABLE',governanceStatus:'NORMAL'}],columns:[]}}}
async function render(){const w=mount(TableExplorer,{global:{plugins:[ElementPlus],stubs:{LoadingState:true,ErrorState:Message,EmptyState:Message}}});wrappers.push(w);await flushPromises();return w}
beforeEach(()=>{
 vi.clearAllMocks();state.auth=reactive({token:'identity-A'});state.route=reactive({query:{}})
 state.iam=reactive({loaded:true,errorMessage:null,snapshot:{revision:1},allowed:true,canOnDatasource:()=>state.iam.allowed,load:vi.fn(async()=>{})})
 state.context=reactive({datasourceId:1,snapshotId:undefined,initialize:vi.fn(async()=>{}),ensureSnapshot:vi.fn(async(id:number)=>({...snapshot,id})),selectSnapshot:vi.fn((id:number)=>{state.context.snapshotId=id})})
 api.listSnapshots.mockResolvedValue({data:{records:[snapshot],total:1}});api.getSnapshotDetail.mockResolvedValue(detail())
})
afterEach(()=>{wrappers.splice(0).forEach(w=>w.unmount())})
describe('快照结构精确来源与恢复',()=>{
 it('列表失败重试恢复完整详情',async()=>{api.listSnapshots.mockRejectedValueOnce(new Error('LIST FAILURE'));const w=await render();expect(w.text()).toContain('LIST FAILURE');expect(api.getSnapshotDetail).not.toHaveBeenCalled();await w.findAll('button').find(b=>b.text()==='retry')!.trigger('click');await flushPromises();expect(api.getSnapshotDetail).toHaveBeenCalledWith(11);expect(w.text()).toContain('CURRENT')})
 it('首批外历史快照精确读取，不选最新',async()=>{state.route.query.snapshotId='99';api.getSnapshotDetail.mockResolvedValue({data:{...detail('HISTORY').data,snapshot:{...snapshot,id:99}}});const w=await render();expect(state.context.ensureSnapshot).toHaveBeenCalledWith(99);expect(api.getSnapshotDetail).toHaveBeenCalledWith(99);expect(w.text()).toContain('HISTORY')})
 it('历史快照无权失败不回退',async()=>{state.route.query.snapshotId='99';state.context.ensureSnapshot.mockRejectedValue(new Error('HISTORY DENIED'));const w=await render();expect(w.text()).toContain('HISTORY DENIED');expect(api.getSnapshotDetail).not.toHaveBeenCalled()})
 it('相同源错误快照ID也拒绝展示',async()=>{api.getSnapshotDetail.mockResolvedValue({data:{...detail('WRONG').data,snapshot:{...snapshot,id:12}}});const w=await render();expect(w.text()).toContain('指定的快照不存在或不属于当前数据源');expect(w.text()).not.toContain('WRONG')})
 it('切换账号阻止旧快照回填',async()=>{let resolve!:(v:any)=>void;api.getSnapshotDetail.mockReturnValueOnce(new Promise(r=>resolve=r)).mockResolvedValueOnce(detail('NEW IDENTITY'));const w=await render();state.auth.token='identity-B';await flushPromises();resolve(detail('OLD IDENTITY'));await flushPromises();expect(w.text()).toContain('NEW IDENTITY');expect(w.text()).not.toContain('OLD IDENTITY')})
 it('撤权停止读取并清空字段',async()=>{const w=await render();const count=api.listSnapshots.mock.calls.length;state.iam.allowed=false;state.iam.snapshot={revision:2};await flushPromises();expect(w.text()).toContain('没有当前数据源的元数据查看权限');expect(w.text()).not.toContain('CURRENT');expect(api.listSnapshots).toHaveBeenCalledTimes(count)})
 it('列表来源混入别的源直接报错',async()=>{api.listSnapshots.mockResolvedValue({data:{records:[{...snapshot,datasourceId:2}]}});const w=await render();expect(w.text()).toContain('快照列表来源与当前数据源不一致');expect(api.getSnapshotDetail).not.toHaveBeenCalled()})
})

it('初次初始化默认快照不能重写显式历史快照深链',async()=>{
 state.route.query.snapshotId='99'
 state.context.initialize.mockImplementation(async()=>{state.context.snapshotId=12})
 api.getSnapshotDetail.mockResolvedValue({data:{...detail('HISTORY').data,snapshot:{...snapshot,id:99}}})
 const w=await render();expect(w.text()).toContain('HISTORY');expect(state.context.ensureSnapshot).toHaveBeenCalledWith(99);expect(api.replace).not.toHaveBeenCalled()
})
it('账号重新加载IAM与偏好时仍保留显式历史快照',async()=>{
 state.route.query.snapshotId='99';api.getSnapshotDetail.mockResolvedValue({data:{...detail('HISTORY').data,snapshot:{...snapshot,id:99}}})
 const w=await render();api.replace.mockClear();state.iam.loaded=false;state.iam.snapshot=null;state.auth.token='identity-B';state.context.snapshotId=12;await flushPromises()
 state.iam.snapshot={revision:2};state.iam.loaded=true;await flushPromises()
 expect(w.text()).toContain('HISTORY');expect(state.route.query.snapshotId).toBe('99');expect(api.replace).not.toHaveBeenCalled()
})
