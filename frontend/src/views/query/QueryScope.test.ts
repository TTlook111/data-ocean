// @vitest-environment happy-dom
import {afterEach,beforeEach,it,expect,vi} from 'vitest'
import {mount,flushPromises,type VueWrapper} from '@vue/test-utils'
import {reactive} from 'vue'
import Scope from './QueryScope.vue'
const h=vi.hoisted(()=>({auth:{token:'A'},iam:{loaded:true,queryUse:true,snapshot:{}},api:{iamS1GetDatasourceReadiness:vi.fn(),listIamS1QueryResourceTables:vi.fn(),listIamS1QueryResourceColumns:vi.fn()}}))
const auth=reactive(h.auth),iam=reactive(h.iam)
vi.mock('../../stores/auth',()=>({useAuthStore:()=>auth}))
vi.mock('../../stores/iamS1',()=>({useIamS1Store:()=>iam}))
vi.mock('../../api/iamS1',()=>h.api)
const wrappers:VueWrapper[]=[]
const deferred=()=>{let resolve!:(v:any)=>void;const promise=new Promise<any>(r=>{resolve=r});return {promise,resolve}}
async function render(){const w=mount(Scope,{props:{datasourceId:1,datasourceName:'当前来源'},global:{stubs:{'el-dialog':{props:['modelValue'],template:'<div v-if="modelValue"><slot/></div>'}}}});wrappers.push(w);await w.get('button').trigger('click');await flushPromises();return w}
beforeEach(()=>{vi.resetAllMocks();auth.token='A';iam.loaded=true;iam.queryUse=true;iam.snapshot={};h.api.iamS1GetDatasourceReadiness.mockResolvedValue({data:{datasourceId:1,askable:true,publishedSnapshotId:11,ragBuildId:'BUILD'}});h.api.listIamS1QueryResourceTables.mockResolvedValue({data:[{datasourceId:1,metadataSnapshotId:11,tableName:'products',selectable:true}]});h.api.listIamS1QueryResourceColumns.mockResolvedValue({data:[{columnMetaId:1,columnName:'category',selectable:true,dataType:'VARCHAR'}]})})
afterEach(()=>wrappers.splice(0).forEach(w=>w.unmount()))
it('uses only the current user QUERY catalog pinned to server published snapshot',async()=>{
 const w=await render();expect(h.api.listIamS1QueryResourceTables).toHaveBeenCalledWith(1,11,'QUERY');expect(h.api.listIamS1QueryResourceColumns).toHaveBeenCalledWith(1,11,'products','QUERY')
 expect(w.text()).toContain('products');expect(w.text()).toContain('category');expect(w.text()).toContain('行范围、字段用途与脱敏')
 expect(w.text()).not.toContain('sales_orders');expect(w.text()).not.toContain('product_name')
})
it('403 is explained without implying no data or enumerating hidden schema',async()=>{
 h.api.listIamS1QueryResourceTables.mockRejectedValue({response:{status:403}});const w=await render()
 expect(w.text()).toContain('当前账号无权读取可问范围');expect(w.text()).not.toContain('暂无可问字段');expect(h.api.listIamS1QueryResourceColumns).not.toHaveBeenCalled()
})
it('a failed scope can retry; empty authorized columns do not invent business examples',async()=>{
 h.api.listIamS1QueryResourceColumns.mockRejectedValueOnce(new Error('offline')).mockResolvedValueOnce({data:[]});const w=await render()
 expect(w.text()).toContain('范围读取失败');await w.findAll('button').find(b=>b.text()==='重新读取范围')!.trigger('click');await flushPromises()
 expect(w.text()).toContain('暂无可问字段');expect(w.text()).not.toContain('category')
})
it('changing source or identity closes the scope and rejects delayed old columns',async()=>{
 const d=deferred();h.api.listIamS1QueryResourceColumns.mockReturnValueOnce(d.promise);const w=await render()
 await w.setProps({datasourceId:2});auth.token='B';await flushPromises();d.resolve({data:[{columnName:'OLD_SECRET',selectable:true}]});await flushPromises()
 expect(w.text()).not.toContain('OLD_SECRET');expect(w.text()).not.toContain('products')
})
it('unknown or mismatched readiness does not load a guessed snapshot',async()=>{
 h.api.iamS1GetDatasourceReadiness.mockResolvedValue({data:{datasourceId:2,publishedSnapshotId:99}});const w=await render()
 expect(w.text()).toContain('范围读取失败');expect(h.api.listIamS1QueryResourceTables).not.toHaveBeenCalled()
})
it('revocation clears a visible catalog immediately',async()=>{const w=await render();expect(w.text()).toContain('category');iam.queryUse=false;iam.snapshot={revision:2};await flushPromises();expect(w.text()).not.toContain('category');expect(w.text()).not.toContain('products')})
