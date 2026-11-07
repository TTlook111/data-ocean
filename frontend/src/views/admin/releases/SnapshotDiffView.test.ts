// @vitest-environment happy-dom
import { beforeEach,it,expect,vi } from 'vitest'
import { mount,flushPromises } from '@vue/test-utils'
import { reactive } from 'vue'
const h=vi.hoisted(()=>({route:{params:{snapshotId:'1',compareId:'2'},query:{} as Record<string,string>},auth:{token:'synthetic'},iam:{loaded:true,errorMessage:'',snapshot:{},systemAdmin:false,canOnDatasource:vi.fn(),datasourcesWithFunction:vi.fn()},api:{getSnapshotDetail:vi.fn(),compareSnapshots:vi.fn(),getPublishedSnapshot:vi.fn()}}))
vi.mock('vue-router',()=>({useRoute:()=>route}))
vi.mock('../../../stores/auth',()=>({useAuthStore:()=>auth}))
vi.mock('../../../stores/iamS1',()=>({useIamS1Store:()=>iam}))
vi.mock('../../../api/admin/metadata',()=>({getSnapshotDetail:h.api.getSnapshotDetail}))
vi.mock('../../../api/admin/versioning',()=>({compareSnapshots:h.api.compareSnapshots,getPublishedSnapshot:h.api.getPublishedSnapshot}))
import Page from './SnapshotDiffView.vue'
const route=reactive(h.route),auth=reactive(h.auth),iam=reactive(h.iam)
const output=(overrides:Record<string,unknown>={})=>({datasourceId:1,oldSnapshotId:1,newSnapshotId:2,oldSnapshotVersion:1,newSnapshotVersion:2,addedTables:['new_orders'],removedTables:[],addedColumns:[],removedColumns:[],modifiedColumns:[{tableName:'orders',columnName:'amount',oldType:'DECIMAL',newType:'DECIMAL',oldComment:'原含义',newComment:'新含义'}],...overrides})
const render=()=>mount(Page,{global:{stubs:{TaskPageHeader:true,RouterLink:true,ErrorState:{props:['message'],template:'<p>{{message}}</p>'},LoadingState:true,EmptyState:true,'el-table':{props:['data'],template:'<div>{{JSON.stringify(data)}}<slot/></div>'},'el-table-column':true}}})
beforeEach(()=>{Object.values(h.api).forEach(x=>x.mockReset());route.params={snapshotId:'1',compareId:'2'};route.query={};auth.token='synthetic';iam.loaded=true;iam.canOnDatasource.mockReturnValue(true);iam.datasourcesWithFunction.mockReturnValue([1]);h.api.compareSnapshots.mockResolvedValue({data:output()});h.api.getPublishedSnapshot.mockResolvedValue({data:{snapshotId:2,datasourceId:1}})})
it('保留指定方向、表名及纯注释差异',async()=>{const w=render();await flushPromises();expect(h.api.compareSnapshots).toHaveBeenCalledWith(1,2);expect(w.text()).toContain('new_orders');expect(w.text()).toContain('原含义');expect(w.text()).toContain('新含义');w.unmount()})
it('显式源非法不请求快照',async()=>{route.query.datasourceId='bad';const w=render();await flushPromises();expect(h.api.getSnapshotDetail).not.toHaveBeenCalled();expect(w.text()).toContain('指定数据源标识无效');w.unmount()})
it('返回方向错误不展示结构差异',async()=>{h.api.compareSnapshots.mockResolvedValue({data:output({oldSnapshotId:99})});const w=render();await flushPromises();expect(w.text()).toContain('指定版本不一致');expect(w.text()).not.toContain('new_orders');w.unmount()})
it('返回来源与显式源不符时拒绝差异',async()=>{route.query.datasourceId='1';h.api.compareSnapshots.mockResolvedValue({data:output({datasourceId:2})});const w=render();await flushPromises();expect(w.text()).toContain('不属于当前授权来源');expect(w.text()).not.toContain('new_orders');w.unmount()})
it('版本只读账号比较不额外请求整份元数据目录',async()=>{iam.canOnDatasource.mockImplementation((code,id)=>code==='metadata:release:view'&&id===1);const w=render();await flushPromises();expect(h.api.getSnapshotDetail).not.toHaveBeenCalled();expect(w.text()).toContain('new_orders');w.unmount()})
it('不借用其他源的结构权限',async()=>{route.query.datasourceId='2';iam.canOnDatasource.mockImplementation((_code,id)=>id===1);const w=render();await flushPromises();expect(h.api.getSnapshotDetail).not.toHaveBeenCalled();w.unmount()})
it('账号切换使晚到差异失效',async()=>{let resolve!:(x:any)=>void;h.api.compareSnapshots.mockReturnValueOnce(new Promise(r=>{resolve=r}));const w=render();await flushPromises();auth.token='new';await flushPromises();resolve({data:{addedTables:['OLD_SECRET']}});await flushPromises();expect(w.text()).not.toContain('OLD_SECRET');w.unmount()})
