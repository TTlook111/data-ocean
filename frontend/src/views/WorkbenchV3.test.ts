// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ElementPlus from 'element-plus'
import { nextTick, reactive } from 'vue'
const api=vi.hoisted(()=>({getDashboardStats:vi.fn(),listSimpleDatasources:vi.fn(),getBatchDatasourceReadiness:vi.fn(),listIamS1AccessRequestQueue:vi.fn(),push:vi.fn(),replace:vi.fn()}))
vi.mock('../api/admin/dashboard',()=>({getDashboardStats:api.getDashboardStats}))
vi.mock('../api/admin/datasource',()=>({listSimpleDatasources:api.listSimpleDatasources,getBatchDatasourceReadiness:api.getBatchDatasourceReadiness}))
vi.mock('../api/iamS1',()=>({listIamS1AccessRequestQueue:api.listIamS1AccessRequestQueue}))
vi.mock('../api/http',()=>({http:{}}))
const routing=vi.hoisted(()=>({value:{query:{} as Record<string,string>}}))
vi.mock('vue-router',()=>({useRouter:()=>({push:api.push,replace:api.replace,currentRoute:reactive(routing)})}))
import Page from './AdminHomeView.vue'
import {useAuthStore} from '../stores/auth'
import {useIamS1Store} from '../stores/iamS1'
const codes=['admin:workbench:view','datasource:view','metadata:view','metadata:release:view','governance:field:view','knowledge:view','governance:issue:view']
const sources=[{id:2,name:'真实回执名称A'},{id:9,name:'真实回执名称B'},{id:5,name:'真实回执名称C'},{id:12,name:'真实回执名称D'}]
function result(id=2,overrides={}){return {datasourceId:id,datasourceName:'来源'+id,askable:false,stage:'SNAPSHOT_PENDING',stageLabel:'快照待发布',latestCollectedSnapshotId:id===2?31:id*10+1,latestCollectedSnapshotVersion:4,connectionReady:true,metadataReady:false,governanceReady:true,knowledgeReady:false,permissionReady:false,blockReasons:[{code:'SNAPSHOT_NOT_PUBLISHED',message:'尚未发布元数据快照',ownerRole:'治理负责人',actionText:'发布快照',actionPath:'/untrusted'}],...overrides}}
function rights(functions=codes){const iam=useIamS1Store();iam.loaded=true;iam.snapshot={protocolVersion:'IAM-SIMPLE-1',userId:1,systemAdmin:false,globalFunctions:functions.filter(x=>x==='admin:workbench:view'||x==='security:approval:view'),datasourceCapabilities:sources.map(x=>({datasourceId:x.id,datasourceName:x.name,functionCodes:functions.filter(x=>!x.startsWith('admin:')),functionNames:[]})),queryUse:false,viewSql:false,export:false,permissionRevision:1}}
function page(){return mount(Page,{global:{plugins:[ElementPlus],stubs:{TaskPageHeader:{props:['title'],template:'<header>{{title}}<slot name="actions"/></header>'},TitleHelp:true,RouterLink:{props:['to'],template:'<a><slot/></a>'}}}})}
async function settle(){await flushPromises();await nextTick();await flushPromises()}
function expectFocused(w:ReturnType<typeof page>,id?:number){
 expect(w.findAll('[data-source-choice][aria-pressed="true"]').map(row=>row.attributes('data-source-choice'))).toEqual(id===undefined?[]:[String(id)])
 expect(w.find('[data-source-detail]').exists()).toBe(id!==undefined)
 if(id!==undefined)expect(w.find('[data-source-detail] .detail-kicker span:last-child').text()).toBe('#'+id)
}
beforeEach(()=>{setActivePinia(createPinia());useAuthStore().token='A';Object.values(api).forEach(x=>x.mockReset());reactive(routing).value.query={};api.replace.mockImplementation(target=>{reactive(routing).value.query=target.query;return Promise.resolve()});rights();api.getDashboardStats.mockResolvedValue({data:{openIssues:15,recentActivities:[]}});api.listSimpleDatasources.mockResolvedValue({data:sources});api.getBatchDatasourceReadiness.mockResolvedValue({data:sources.map(x=>result(x.id)),failedDatasourceIds:[]});api.listIamS1AccessRequestQueue.mockResolvedValue({data:{total:0,records:[]}})})
describe('V3 工作台真实读取合同',()=>{
 it.each([9,12])('刷新来源%s时列表选中态与精确详情使用同一对象',async(id)=>{
  reactive(routing).value.query={focusSourceId:String(id)}
  const w=page();try{await settle();expectFocused(w,id);expect(routing.value.query.focusSourceId).toBe(String(id));expect(w.findAll('[data-source-choice]')).toHaveLength(3)}finally{w.unmount()}
 })
 it('默认与路由前后切换保持高亮、详情一致，非法定位不高亮其他来源',async()=>{
  const w=page();try{
   await settle();expectFocused(w,2)
   for(const id of ['9','2','5']){reactive(routing).value.query={focusSourceId:id};await settle();expectFocused(w,Number(id))}
   reactive(routing).value.query={};await settle();expectFocused(w,2)
   for(const id of ['0','777']){reactive(routing).value.query={focusSourceId:id};await settle();expectFocused(w);expect(api.push).not.toHaveBeenCalled()}
   await w.find('[data-source-choice="9"]').trigger('click');await settle();expectFocused(w,9);expect(routing.value.query.focusSourceId).toBe('9')
   await w.find('[data-source-filter="attention"]').trigger('click');await settle();expectFocused(w,9);expect(routing.value.query.focusSourceId).toBeUndefined()
  }finally{w.unmount()}
 })
 it('目录准备晚回时以当前URL定位，不把早先默认项当选中项',async()=>{
  let release!:(v:any)=>void
  api.getBatchDatasourceReadiness.mockReturnValueOnce(new Promise(resolve=>{release=resolve}))
  const w=page();try{
   await settle();reactive(routing).value.query={focusSourceId:'9'};await settle()
   release({data:sources.map(s=>result(s.id)),failedDatasourceIds:[]});await settle();expectFocused(w,9)
   await w.find('[data-source-primary]').trigger('click');expect(api.push).toHaveBeenLastCalledWith({path:'/admin/releases',query:{datasourceId:'9'}})
  }finally{w.unmount()}
 })
 it('定位来源撤权后没有残留高亮或详情，不自动改写为其他来源',async()=>{
  reactive(routing).value.query={focusSourceId:'9'}
  api.getBatchDatasourceReadiness.mockImplementation(ids=>Promise.resolve({data:ids.map((id:number)=>result(id)),failedDatasourceIds:[]}))
  const w=page();try{
   await settle();expectFocused(w,9)
   const iam=useIamS1Store();iam.snapshot!.datasourceCapabilities=iam.snapshot!.datasourceCapabilities.filter(s=>s.datasourceId!==9);iam.snapshot!.permissionRevision=(iam.snapshot!.permissionRevision??0)+1
   await settle();expectFocused(w);expect(w.find('[data-source-choice="9"]').exists()).toBe(false);expect(routing.value.query.focusSourceId).toBe('9');expect(w.text()).toContain('指定来源不在当前有权列表中');expect(api.push).not.toHaveBeenCalled()
  }finally{w.unmount()}
 })
 it('只预览三个来源，选中真实来源给出精确且有权的版本入口，不信actionPath',async()=>{const w=page();try{await settle();expect(w.findAll('[data-source-choice]')).toHaveLength(3);expect(w.text()).not.toContain('演示交易库');expect(w.text()).not.toContain('最近工作');await w.find('[data-source-primary]').trigger('click');expect(api.push).toHaveBeenLastCalledWith({path:'/admin/releases',query:{datasourceId:'2'}});expect(api.push).not.toHaveBeenCalledWith(expect.objectContaining({path:'/untrusted'}))}finally{w.unmount()}})
 it('同源无版本查看权时禁用且解释，不用其他能力补权',async()=>{rights(codes.filter(x=>x!=='metadata:release:view'));const w=page();try{await settle();expect(w.find('[data-source-primary]').attributes('disabled')).toBeDefined();expect(w.text()).toContain('元数据版本查看');await w.find('[data-source-primary]').trigger('click');expect(api.push).not.toHaveBeenCalled()}finally{w.unmount()}})
 it('基础条件满足与活动索引独立，不宣称本人已获问数许可',async()=>{api.getBatchDatasourceReadiness.mockResolvedValue({data:[result(2,{askable:true,metadataReady:true,publishedSnapshotId:30,snapshotVersion:3,permissionReady:true,blockReasons:[],stage:'ASKABLE',stageLabel:'可询问'})],failedDatasourceIds:[5,9,12]});const w=page();try{await settle();expect(w.text()).toContain('无活动索引');expect(w.text()).toContain('不代表你已获查询权');await w.find('[data-source-filter="ready"]').trigger('click');expect(w.findAll('[data-source-choice]')).toHaveLength(1);expect(w.text()).not.toContain('你可以问数')}finally{w.unmount()}})
 it('缺失准备回执保留未知，切来源不保留旧快照或旧动作',async()=>{api.getBatchDatasourceReadiness.mockResolvedValue({data:[result(2)],failedDatasourceIds:[9,5,12]});const w=page();try{await settle();await w.find('[data-source-choice="9"]').trigger('click');expect(w.find('[data-source-detail]').text()).toContain('待确认');expect(w.find('[data-source-detail]').text()).not.toContain('#31');await w.find('[data-source-primary]').trigger('click');expect(api.push).toHaveBeenLastCalledWith({path:'/admin/data-sources/9'})}finally{w.unmount()}})
 it('审批最小角色只读两条预览，不请求来源或元数据',async()=>{rights(['admin:workbench:view','security:approval:view']);api.listIamS1AccessRequestQueue.mockResolvedValue({data:{total:1,records:[{id:101,requesterId:2,datasourceId:2,datasourceName:'申请回执来源',metadataSnapshotId:30,tableName:'orders',requestedColumns:['id'],rowScope:'ALL',purpose:'核对申请依据',status:'PENDING'}]}});const w=page();try{await settle();expect(api.listIamS1AccessRequestQueue).toHaveBeenCalledWith('PENDING',1,2);expect(api.listSimpleDatasources).not.toHaveBeenCalled();expect(w.findAll('[data-approval-preview]')).toHaveLength(1);expect(w.text()).toContain('核对申请依据');expect(w.find('select').exists()).toBe(false)}finally{w.unmount()}})
 it('工作台403后不继续旧来源请求，清理所有已返回摘要',async()=>{let release!:(v:any)=>void;api.listSimpleDatasources.mockReturnValue(new Promise(r=>{release=r}));api.getDashboardStats.mockRejectedValue(Object.assign(new Error('工作台拒绝'),{response:{status:403}}));const w=page();try{await settle();release({data:sources});await settle();expect(api.getBatchDatasourceReadiness).not.toHaveBeenCalled();expect(w.find('[data-source-detail]').exists()).toBe(false)}finally{w.unmount()}})
 it('403恢复不等待旧目录请求，旧load完成不能结束新读取',async()=>{let release!:(v:any)=>void;api.listSimpleDatasources.mockReturnValueOnce(new Promise(r=>{release=r})).mockResolvedValue({data:sources});api.getDashboardStats.mockRejectedValueOnce(Object.assign(new Error('工作台拒绝'),{response:{status:403}})).mockResolvedValue({data:{openIssues:3,recentActivities:[]}});const w=page();try{await settle();await w.find('.noaccess button').trigger('click');await settle();expect(api.getDashboardStats).toHaveBeenCalledTimes(2);expect(w.find('[data-source-detail]').exists()).toBe(true);release({data:[{id:2,name:'旧目录不得覆盖'}]});await settle();expect(w.text()).not.toContain('旧目录不得覆盖')}finally{w.unmount()}})

 it('刷新深链只在当前有权目录内恢复，错误定位不自动换源',async()=>{reactive(routing).value.query={focusSourceId:'777'};const w=page();try{await settle();expect(w.find('[data-source-detail]').exists()).toBe(false);expect(w.text()).toContain('指定来源不在当前有权列表');await w.find('[data-source-choice="9"]').trigger('click');await settle();expect(routing.value.query.focusSourceId).toBe('9');expect(w.find('[data-source-detail]').text()).toContain('真实回执名称B')}finally{w.unmount()}})
 it('已授权的第4个来源精确恢复，仍只给三个预览',async()=>{reactive(routing).value.query={focusSourceId:'12'};const w=page();try{await settle();expect(w.findAll('[data-source-choice]')).toHaveLength(3);expect(w.find('[data-source-detail]').text()).toContain('真实回执名称D')}finally{w.unmount()}})
 it('缺失布尔字段不能显示已准备，安全保留未知',async()=>{api.getBatchDatasourceReadiness.mockResolvedValue({data:[{...result(2),permissionReady:undefined}],failedDatasourceIds:[]});const w=page();try{await settle();expect(w.findAll('.condition.unknown')).toHaveLength(15);expect(w.find('[data-source-detail]').text()).toContain('待确认')}finally{w.unmount()}})
 it('大范围读取有上限，未读来源不被算作0或就绪',async()=>{const list=Array.from({length:105},(_,i)=>({id:i+1,name:'来源'+(i+1)}));useIamS1Store().snapshot!.datasourceCapabilities=list.map(s=>({datasourceId:s.id,datasourceName:s.name,functionCodes:['datasource:view'],functionNames:[]}));api.listSimpleDatasources.mockResolvedValue({data:list});api.getBatchDatasourceReadiness.mockImplementation(ids=>Promise.resolve({data:ids.map((id:number)=>result(id)),failedDatasourceIds:[]}));const w=page();try{await settle();expect(api.getBatchDatasourceReadiness.mock.calls[0][0].length).toBeLessThanOrEqual(100);expect(w.text()).toContain('5 个未知');expect(w.find('[data-source-filter="ready"] b').text()).toBe('—')}finally{w.unmount()}})

})
