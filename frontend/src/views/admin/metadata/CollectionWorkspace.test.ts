// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, reactive } from 'vue'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import SyncTask from './SyncTask.vue'
import SyncSchedule from './SyncSchedule.vue'
const h=vi.hoisted(()=>({context:null as any,auth:null as any,iam:null as any,router:null as any,route:null as any}))
const api=vi.hoisted(()=>({listSyncTasks:vi.fn(),triggerSync:vi.fn(),getSyncSchedule:vi.fn(),updateSyncSchedule:vi.fn(),listSimpleDatasources:vi.fn()}))
vi.mock('vue-router',()=>({useRoute:()=>h.route,useRouter:()=>h.router}))
vi.mock('../../../api/admin/metadata',()=>api)
vi.mock('../../../api/admin/system',()=>api)
vi.mock('../../../api/admin/datasource',()=>api)
vi.mock('../../../stores/adminContext',()=>({useAdminContextStore:()=>h.context}))
vi.mock('../../../stores/auth',()=>({useAuthStore:()=>h.auth}))
vi.mock('../../../stores/iamS1',()=>({useIamS1Store:()=>h.iam}))
const wrappers:VueWrapper[]=[]
const Message=defineComponent({props:['message'],template:'<p>{{message}}</p>'})
function deferred(){let resolve!:(value:any)=>void;const promise=new Promise(r=>resolve=r);return {promise,resolve}}
async function render(component:any){const w=mount(component,{global:{plugins:[ElementPlus],stubs:{ScopeBar:true,RouterLink:{props:['to'],template:'<a><slot /></a>'},LoadingState:true,ErrorState:Message,EmptyState:Message}}});wrappers.push(w);await flushPromises();return w}
function button(w:VueWrapper,name:string){return w.findAll('button').find(b=>b.text()===name)!}
beforeEach(()=>{
 vi.clearAllMocks();h.route=reactive({query:{}});h.router={replace:vi.fn(async()=>{})};h.auth=reactive({token:'identity-A'});h.context=reactive({datasourceId:1,datasources:[{id:1,name:'source-one'},{id:2,name:'source-two'}],initialize:vi.fn(async()=>{})})
 h.iam=reactive({loaded:true,errorMessage:null,systemAdmin:false,snapshot:{revision:1},datasourceCapabilities:[{datasourceId:1,functionCodes:['metadata:collect:view','metadata:collect:run']}],hasGlobal:vi.fn(()=>false),canOnDatasource:(code:string,id:number)=>h.iam.systemAdmin||h.iam.datasourceCapabilities.some((s:any)=>s.datasourceId===id&&s.functionCodes.includes(code)),load:vi.fn(async()=>{})})
 api.listSyncTasks.mockResolvedValue({data:{records:[],total:0}});api.triggerSync.mockResolvedValue({data:{taskId:7}})
 api.getSyncSchedule.mockResolvedValue({code:200,data:{cron:'0 0 2 * * ?',enabled:false,running:true}})
 api.updateSyncSchedule.mockImplementation(async p=>({code:200,data:{...p,running:true}}))
})
afterEach(()=>{wrappers.splice(0).forEach(w=>w.unmount());document.body.innerHTML=''})
describe('采集范围与生命周期',()=>{
 it('无需数据源查看权，执行源同一绑定且防止重复提交',async()=>{const d=deferred();api.triggerSync.mockReturnValue(d.promise);const w=await render(SyncTask);(w.vm as any).openSyncDialog();await flushPromises();expect(api.listSimpleDatasources).not.toHaveBeenCalled();await button(w,'开始采集').trigger('click');await button(w,'开始采集').trigger('click');expect(api.triggerSync).toHaveBeenCalledTimes(1);expect(api.triggerSync).toHaveBeenCalledWith({datasourceId:1,includeStatistics:false});d.resolve({data:{taskId:7}});await flushPromises()})
 it('跨源提交导航到有查看权的精确源，并保留统计开关',async()=>{h.iam.datasourceCapabilities.push({datasourceId:2,functionCodes:['metadata:collect:view','metadata:collect:run']});const w=await render(SyncTask);(w.vm as any).openSyncDialog();await flushPromises();w.findComponent({name:'ElSelect'}).vm.$emit('update:modelValue',2);w.findComponent({name:'ElSwitch'}).vm.$emit('update:modelValue',true);await flushPromises();await button(w,'开始采集').trigger('click');await flushPromises();expect(api.triggerSync).toHaveBeenCalledWith({datasourceId:2,includeStatistics:true});expect(h.router.replace).toHaveBeenCalledWith({query:{datasourceId:'2'}})})
 it('分页继续使用后端page与total',async()=>{api.listSyncTasks.mockResolvedValue({data:{records:[],total:45}});const w=await render(SyncTask);api.listSyncTasks.mockResolvedValue({data:{records:[{id:9,datasourceId:1,status:'SUCCESS'}],total:45}});await button(w,'刷新任务').trigger('click');await flushPromises();w.findComponent({name:'ElPagination'}).vm.$emit('update:current-page',2);w.findComponent({name:'ElPagination'}).vm.$emit('current-change',2);await flushPromises();expect(api.listSyncTasks).toHaveBeenLastCalledWith({datasourceId:1,page:2,size:20})})
 it('仅查看任务不显示重试或越权跳转',async()=>{h.iam.datasourceCapabilities[0].functionCodes=['metadata:collect:view'];api.listSyncTasks.mockResolvedValue({data:{records:[{id:7,datasourceId:1,status:'FAILED'},{id:8,datasourceId:1,status:'SUCCESS',snapshotId:11}],total:2}});const w=await render(SyncTask);expect(button(w,'重试')).toBeUndefined();expect(w.text()).not.toContain('查看生成快照');expect(w.findAll('a').some(a=>a.text()==='检查连接')).toBe(false);(w.vm as any).openSyncDialog();await flushPromises();expect(w.find('.el-dialog').exists()).toBe(false)})
 it('切换无权源后不发新请求，旧列表不回填',async()=>{const d=deferred();api.listSyncTasks.mockReturnValueOnce(d.promise);const w=await render(SyncTask);h.context.datasourceId=2;await flushPromises();d.resolve({data:{records:[{id:1,datasourceId:1,datasourceName:'OLD SOURCE'}],total:1}});await flushPromises();expect(w.text()).toContain('没有当前范围');expect(w.text()).not.toContain('OLD SOURCE');expect(api.listSyncTasks).toHaveBeenCalledTimes(1)})
 it('换账号后忽略旧提交成功和刷新',async()=>{const d=deferred();api.triggerSync.mockReturnValue(d.promise);const w=await render(SyncTask);(w.vm as any).openSyncDialog();await flushPromises();await button(w,'开始采集').trigger('click');h.auth.token='identity-B';await flushPromises();const reads=api.listSyncTasks.mock.calls.length;d.resolve({data:{taskId:7}});await flushPromises();expect(api.listSyncTasks).toHaveBeenCalledTimes(reads)})
 it('来源身份不一致显示错误',async()=>{api.listSyncTasks.mockResolvedValue({data:{records:[{id:7,datasourceId:2,datasourceName:'OTHER'}],total:1}});const w=await render(SyncTask);expect(w.text()).toContain('任务来源与当前数据源不一致');expect(w.text()).not.toContain('OTHER')})
})
describe('全局计划合同',()=>{
 it('负责源采集员只读计划，调度注册不表示正在采集',async()=>{const w=await render(SyncSchedule);expect(w.text()).toContain('只读计划');expect(w.text()).toContain('调度已注册');expect(w.text()).not.toContain('自动同步执行中');expect(button(w,'保存配置')).toBeUndefined()})
 for(const cron of ['30 0 2 * 1 ?','0 */15 * * * ?','0 0 2 * * ? 2026'])it(`仅开关不改写 ${cron}`,async()=>{h.iam.systemAdmin=true;api.getSyncSchedule.mockResolvedValue({code:200,data:{cron,enabled:false,running:false}});const w=await render(SyncSchedule);expect((w.find('input[aria-label="Cron 表达式"]').element as HTMLInputElement).value).toBe(cron);await w.find('.el-switch').trigger('click');await button(w,'保存配置').trigger('click');await flushPromises();expect(api.updateSyncSchedule).toHaveBeenCalledWith({cron,enabled:true})})
 it('保存防重复，撤权不回填迟到结果',async()=>{h.iam.systemAdmin=true;const d=deferred();api.updateSyncSchedule.mockReturnValue(d.promise);const w=await render(SyncSchedule);await w.find('.el-switch').trigger('click');await button(w,'保存配置').trigger('click');await button(w,'保存配置').trigger('click');expect(api.updateSyncSchedule).toHaveBeenCalledTimes(1);h.iam.systemAdmin=false;h.iam.snapshot={revision:2};await flushPromises();d.resolve({code:200,data:{cron:'0 0 9 * * ?',enabled:true,running:true}});await flushPromises();expect(w.text()).toContain('只读计划');expect(w.text()).not.toContain('每天 09:00')})
 for(const weekly of [false,true])it(`预设时间清空时安全提示 ${weekly}`,async()=>{h.iam.systemAdmin=true;const w=await render(SyncSchedule);if(weekly){w.findComponent({name:'ElRadioGroup'}).vm.$emit('update:modelValue','weekly');await flushPromises()}w.findComponent({name:'ElTimeSelect'}).vm.$emit('update:modelValue',undefined);await flushPromises();expect(w.text()).toContain('请选择有效的执行时间');expect(button(w,'保存配置').attributes('disabled')).toBeDefined();await button(w,'保存配置').trigger('click');expect(api.updateSyncSchedule).not.toHaveBeenCalled()})
 it('读取失败不展示默认值为已保存事实',async()=>{api.getSyncSchedule.mockRejectedValue(new Error('PLAN READ FAILED'));const w=await render(SyncSchedule);expect(w.text()).toContain('PLAN READ FAILED');expect(w.text()).not.toContain('已保存配置')})
})

it('任务显式源与偏好源不一致时不读取偏好源',async()=>{h.route.query.datasourceId='2';const w=await render(SyncTask);expect(api.listSyncTasks).not.toHaveBeenCalled();expect(w.text()).toContain('没有当前范围的采集任务查看权限')})
