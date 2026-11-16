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
const detail=(id:number,source=1)=>({snapshot:{id,datasourceId:source,snapshotVersion:id}})
const render=()=>mount(Page,{global:{stubs:{TaskPageHeader:true,ObjectContextSummary:true,ErrorState:{props:['message'],template:'<p>{{message}}</p>'},LoadingState:true,EmptyState:true,'el-table':{props:['data'],template:'<div>{{JSON.stringify(data)}}<slot/></div>'},'el-table-column':true}}})
beforeEach(()=>{Object.values(h.api).forEach(x=>x.mockReset());route.params={snapshotId:'1',compareId:'2'};route.query={};auth.token='synthetic';iam.loaded=true;iam.canOnDatasource.mockReturnValue(true);iam.datasourcesWithFunction.mockReturnValue([1]);h.api.getSnapshotDetail.mockImplementation(id=>Promise.resolve({data:detail(id)}));h.api.compareSnapshots.mockResolvedValue({data:{addedTables:['new_orders'],removedTables:[],modifiedColumns:[{tableName:'orders',columnName:'amount',oldType:'DECIMAL',newType:'DECIMAL',oldComment:'原含义',newComment:'新含义'}]}});h.api.getPublishedSnapshot.mockResolvedValue({data:{snapshotId:2}})})
it('保留指定方向、表名及纯注释差异',async()=>{const w=render();await flushPromises();expect(h.api.compareSnapshots).toHaveBeenCalledWith(1,2);expect(w.text()).toContain('new_orders');expect(w.text()).toContain('原含义');expect(w.text()).toContain('新含义');w.unmount()})
it('显式源非法不请求快照',async()=>{route.query.datasourceId='bad';const w=render();await flushPromises();expect(h.api.getSnapshotDetail).not.toHaveBeenCalled();expect(w.text()).toContain('指定数据源标识无效');w.unmount()})
it('返回快照标识错误不比较',async()=>{h.api.getSnapshotDetail.mockResolvedValue({data:detail(99)});const w=render();await flushPromises();expect(h.api.compareSnapshots).not.toHaveBeenCalled();expect(w.text()).toContain('指定版本不一致');w.unmount()})
it('两份结构跨源时不调用比较接口',async()=>{h.api.getSnapshotDetail.mockImplementation(id=>Promise.resolve({data:detail(id,id)}));const w=render();await flushPromises();expect(h.api.compareSnapshots).not.toHaveBeenCalled();expect(w.text()).toContain('同一个数据源');w.unmount()})
it('不借用其他源的结构权限',async()=>{route.query.datasourceId='2';iam.canOnDatasource.mockImplementation((_code,id)=>id===1);const w=render();await flushPromises();expect(h.api.getSnapshotDetail).not.toHaveBeenCalled();w.unmount()})
it('账号切换使晚到差异失效',async()=>{let resolve!:(x:any)=>void;h.api.compareSnapshots.mockReturnValueOnce(new Promise(r=>{resolve=r}));const w=render();await flushPromises();auth.token='new';await flushPromises();resolve({data:{addedTables:['OLD_SECRET']}});await flushPromises();expect(w.text()).not.toContain('OLD_SECRET');w.unmount()})
