// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { reactive } from 'vue'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'
import FieldSemanticWorkspace from './FieldSemanticWorkspace.vue'
const h = vi.hoisted(() => ({ context:null as any,iam:null as any,auth:null as any,route:null as any,router:null as any,server:null as any }))
const api = vi.hoisted(() => Object.fromEntries(['adoptFieldSemanticCandidate','confirmFieldSemanticBindings','confirmFieldSemanticBinding','generateFieldSemanticCandidate','getFieldSemanticWorkspace','getFieldSemanticWorkspaceDetails','getFieldSemanticUsageStatus','getFieldSemanticGlossaryOptions','getPendingFieldSemanticBindings','getLatestSemanticCheck','reviewFieldSemantic','revokeFieldSemantic','runSemanticCheck','saveFieldSemanticDraft','submitFieldSemanticDraft','withdrawFieldSemanticDraft','listSnapshotTableColumns','listSnapshotTables'].map(name=>[name,vi.fn()])) as Record<string,ReturnType<typeof vi.fn>>)
vi.mock('../../../stores/adminContext',()=>({useAdminContextStore:()=>h.context}))
vi.mock('../../../stores/iamS1',()=>({useIamS1Store:()=>h.iam}))
vi.mock('../../../stores/auth',()=>({useAuthStore:()=>h.auth}))
vi.mock('vue-router',()=>({useRoute:()=>h.route,useRouter:()=>h.router}))
vi.mock('../../../api/admin/fieldSemantic',()=>api)
vi.mock('../../../api/admin/governance',()=>api)
const wrappers:VueWrapper[]=[]
function clone<T>(value:T):T{return JSON.parse(JSON.stringify(value))}
function revision(status='DRAFT'){return {id:301,definitionId:1,bindingId:1,revisionNo:2,status,businessName:'人工名称',meaning:'人工草稿含义',evidence:'业务手册第3节',createdAt:'2026-10-29 09:00:00',createdBy:1,unit:'元',unresolvedItemsJson:'[]'}}
function record(column=204,snapshot=11,source=1){return {datasourceId:source,snapshotId:snapshot,tableMetaId:1,columnMetaId:column,tableName:'orders',columnName:column===204?'amount':'status',rowVersion:3,governanceStatus:'NORMAL',rawColumnComment:'数据库采集注释',bindingStatus:'VALID',effectiveStatus:'APPROVED',currentApprovedRevisionId:300,currentApproved:{...revision('APPROVED'),id:300,revisionNo:1},workingRevisionId:301,workingStatus:'DRAFT',draft:revision(),revisions:[revision()],reviews:[],candidates:[{id:401,status:'READY',businessName:'AI候选名称',meaning:'AI建议内容',createdAt:'2026-10-29'}]}}
function basic(value:any){const {draft,revisions,reviews,candidates,bindings,...rest}=value;return rest}
function serverFor(column:number,snapshot:number){return {...clone(h.server),columnMetaId:column,snapshotId:snapshot,datasourceId:h.context.datasourceId,columnName:column===204?'amount':'status'}}
function deferred(){let resolve!:(value:any)=>void;let reject!:(value:any)=>void;const promise=new Promise((r,j)=>{resolve=r;reject=j});return {promise,resolve,reject}}
async function render(){const w=mount(FieldSemanticWorkspace,{global:{plugins:[ElementPlus],stubs:{FieldGovernanceProgress:true}}});wrappers.push(w);await flushPromises();return w}
function button(w:VueWrapper,name:string){return w.findAll('button').find(b=>b.text()===name)!}
function input(w:VueWrapper,label:string){return w.find(`input[aria-label="${label}"],textarea[aria-label="${label}"]`)}
async function chooseColumn(w:VueWrapper,id:number){const select=w.findAllComponents({name:'ElSelect'}).find(s=>s.attributes('aria-label')==='选择解释字段') || w.findAllComponents({name:'ElSelect'})[1]!;select.vm.$emit('update:modelValue',id);select.vm.$emit('change',id);await flushPromises()}
async function showReview(w:VueWrapper){w.findComponent({name:'ElTabs'}).vm.$emit('update:modelValue','review');await flushPromises()}
beforeEach(()=>{
 vi.clearAllMocks();h.server=record();h.context=reactive({datasourceId:1,snapshotId:11,loading:false,initialize:vi.fn(async()=>{})});h.auth=reactive({token:'synthetic-A'});h.route=reactive({query:{datasourceId:'1',snapshotId:'11',tableName:'orders',columnMetaId:'204'}});h.router={replace:vi.fn((target:any)=>{h.route.query={...target.query};return Promise.resolve()})}
 h.iam=reactive({loaded:true,errorMessage:null,snapshot:{permissionRevision:1},codes:['metadata:view','governance:field:view','governance:field:manage','knowledge:manage','knowledge:view','knowledge:approve','glossary:view','governance:check'],canOnDatasource:(code:string)=>h.iam.codes.includes(code),load:vi.fn(async()=>{})})
 api.listSnapshotTables.mockResolvedValue({data:[{id:1,tableName:'orders',tableType:'TABLE',governanceStatus:'NORMAL'}]})
 api.listSnapshotTableColumns.mockResolvedValue({data:[{id:204,tableMetaId:1,tableName:'orders',columnName:'amount',dataType:'DECIMAL'},{id:205,tableMetaId:1,tableName:'orders',columnName:'status',dataType:'INT'}]})
 api.getFieldSemanticWorkspace.mockImplementation(async(column,snapshot)=>({data:basic(serverFor(column,snapshot))}))
 api.getFieldSemanticWorkspaceDetails.mockImplementation(async(column,snapshot)=>({data:serverFor(column,snapshot)}))
 api.getFieldSemanticUsageStatus.mockResolvedValue({data:{status:'NOT_BUILT',message:'活动索引尚未收录'}})
 api.getFieldSemanticGlossaryOptions.mockResolvedValue({data:[{termId:1,reviewId:51,name:'有效术语',description:'业务定义',synonyms:'',dependencies:[]}]})
 api.getLatestSemanticCheck.mockResolvedValue({data:null});api.runSemanticCheck.mockResolvedValue({data:{id:7,datasourceId:1,snapshotId:11,status:'SUCCESS',applicableCount:5,validCount:3,missingCount:1,staleCount:1,notApplicableCount:0}})
 api.saveFieldSemanticDraft.mockImplementation(async(_column,payload)=>{h.server={...h.server,rowVersion:4,draft:{...revision(),...payload},workingStatus:'DRAFT'};return {data:basic(h.server)}})
 api.generateFieldSemanticCandidate.mockImplementation(async()=>({data:basic(h.server)}));api.adoptFieldSemanticCandidate.mockResolvedValue({data:basic(record())})
 api.submitFieldSemanticDraft.mockImplementation(async()=>{h.server={...h.server,rowVersion:4,workingStatus:'PENDING_REVIEW',draft:revision('PENDING_REVIEW')};return {data:basic(h.server)}})
 api.reviewFieldSemantic.mockImplementation(async()=>{h.server={...h.server,rowVersion:4,draft:undefined,workingRevisionId:undefined,workingStatus:undefined};return {data:basic(h.server)}})
 api.revokeFieldSemantic.mockResolvedValue({data:basic(record())})
 api.getPendingFieldSemanticBindings.mockResolvedValue({data:[{columnMetaId:204,expectedVersion:3,tableName:'orders',columnName:'amount',eligible:true},{columnMetaId:205,expectedVersion:3,tableName:'orders',columnName:'status',eligible:true}]})
 api.confirmFieldSemanticBindings.mockResolvedValue({data:{snapshotId:11,totalCount:2,successCount:1,failedCount:1,items:[{columnMetaId:204,success:true,message:'已确认'},{columnMetaId:205,success:false,message:'版本冲突，请核对'}]}})
 vi.spyOn(ElMessageBox,'confirm').mockResolvedValue('confirm' as never)
})
afterEach(()=>{wrappers.splice(0).forEach(w=>w.unmount());vi.restoreAllMocks()})
describe('字段解释读取与最小能力',()=>{
 it('精确字段读取并区分原始注释、草稿、有效解释与索引',async()=>{const w=await render();expect(api.getFieldSemanticWorkspace).toHaveBeenCalledWith(204,11);expect(w.text()).toContain('数据库采集注释');expect(input(w,'字段含义').element).toHaveProperty('value','人工草稿含义');expect(w.text()).toContain('活动索引尚未收录')})
 it('没有metadata:view不尝试表字段目录，不补权',async()=>{h.iam.codes=['governance:field:view'];const w=await render();expect(api.listSnapshotTables).not.toHaveBeenCalled();expect(api.getFieldSemanticWorkspace).not.toHaveBeenCalled();expect(w.text()).toContain('当前账号没有 metadata:view')})
 it('显式快照不匹配时不读取偏好范围',async()=>{h.route.query.snapshotId='99';const w=await render();expect(api.listSnapshotTables).not.toHaveBeenCalled();expect(w.text()).toContain('未改用偏好范围')})
 it('基本字段查看不读取知识明细、收录或术语',async()=>{h.iam.codes=['metadata:view','governance:field:view'];const w=await render();expect(api.getFieldSemanticWorkspaceDetails).not.toHaveBeenCalled();expect(api.getFieldSemanticUsageStatus).not.toHaveBeenCalled();expect(api.getFieldSemanticGlossaryOptions).not.toHaveBeenCalled();expect(w.text()).not.toContain('AI建议内容')})
 it('术语选项不额外要求knowledge:view',async()=>{h.iam.codes=['metadata:view','governance:field:view','governance:field:manage','knowledge:manage','glossary:view'];await render();expect(api.getFieldSemanticGlossaryOptions).toHaveBeenCalledWith(204,11);expect(api.getFieldSemanticWorkspaceDetails).not.toHaveBeenCalled()})
 it('明细失败明确显示，不能盲写空表单覆盖原草稿',async()=>{api.getFieldSemanticWorkspaceDetails.mockRejectedValue(new Error('DETAIL FAILED'));const w=await render();expect(w.text()).toContain('DETAIL FAILED');expect(button(w,'保存草稿').attributes('disabled')).toBeDefined()})
 it('响应字段ID不匹配时拒绝接纳',async()=>{api.getFieldSemanticWorkspace.mockResolvedValue({data:record(205)});const w=await render();expect(w.text()).toContain('字段解释来源与当前字段');expect(w.find('.field-semantic-workspace__content').exists()).toBe(false)})
 it('术语请求期间切字段，旧工作面与表单不回填',async()=>{const w=await render();const d=deferred();api.getFieldSemanticGlossaryOptions.mockReturnValueOnce(d.promise);await button(w,'重读已保存草稿').trigger('click');await flushPromises();await chooseColumn(w,205);await flushPromises();d.resolve({data:[]});await flushPromises();expect(w.find('.field-semantic-card--source').text()).toContain('orders.status');expect(w.find('.field-semantic-card--source').text()).not.toContain('orders.amount')})
 it('撤销明细权限后立即清草稿候选，恢复时重新读取',async()=>{const w=await render();h.iam.codes=h.iam.codes.filter((code:string)=>code!=='knowledge:view');h.iam.snapshot={permissionRevision:2};await flushPromises();expect(w.text()).not.toContain('AI建议内容');expect(w.text()).not.toContain('AI候选名称');const count=api.getFieldSemanticWorkspaceDetails.mock.calls.length;h.iam.codes.push('knowledge:view');h.iam.snapshot={permissionRevision:3};await flushPromises();expect(api.getFieldSemanticWorkspaceDetails.mock.calls.length).toBeGreaterThan(count)})
})
describe('AI、人工草稿和并发版本',()=>{
 it('AI生成期间允许人工输入，迟到候选不覆盖输入',async()=>{const w=await render();const d=deferred();api.generateFieldSemanticCandidate.mockReturnValue(d.promise);await button(w,'生成 AI 候选').trigger('click');await input(w,'字段含义').setValue('生成期间的人工作业');d.resolve({data:basic(h.server)});await flushPromises();expect(input(w,'字段含义').element).toHaveProperty('value','生成期间的人工作业');expect(w.text()).toContain('有未保存输入')})
 it('新版本回读保留输入和原expectedVersion，不静默换版保存',async()=>{const w=await render();const d=deferred();api.generateFieldSemanticCandidate.mockReturnValue(d.promise);await button(w,'生成 AI 候选').trigger('click');await input(w,'字段含义').setValue('未保存输入');h.server.rowVersion=4;d.resolve({data:basic(h.server)});await flushPromises();expect(input(w,'字段含义').element).toHaveProperty('value','未保存输入');expect(w.text()).toContain('记录版本发生变化');expect(button(w,'保存草稿').attributes('disabled')).toBeDefined()})
 it('AI重复点击不重发',async()=>{const w=await render();const d=deferred();api.generateFieldSemanticCandidate.mockReturnValue(d.promise);await button(w,'生成 AI 候选').trigger('click');await button(w,'生成 AI 候选').trigger('click');expect(api.generateFieldSemanticCandidate).toHaveBeenCalledTimes(1);d.resolve({data:basic(h.server)});await flushPromises()})
 it('未保存的人工草稿不能被直接提交为已保存修订',async()=>{const w=await render();await input(w,'字段含义').setValue('新的未保存含义');await button(w,'提交审核').trigger('click');expect(api.submitFieldSemanticDraft).not.toHaveBeenCalled();expect(button(w,'提交审核').attributes('disabled')).toBeDefined()})
 it('保存固定源和并发版本，重复点击只写一次',async()=>{const w=await render();const d=deferred();api.saveFieldSemanticDraft.mockReturnValue(d.promise);await input(w,'字段含义').setValue('新的人工含义');await button(w,'保存草稿').trigger('click');await button(w,'保存草稿').trigger('click');expect(api.saveFieldSemanticDraft).toHaveBeenCalledTimes(1);expect(api.saveFieldSemanticDraft).toHaveBeenCalledWith(204,expect.objectContaining({snapshotId:11,expectedVersion:3,meaning:'新的人工含义'}));d.resolve({data:basic(h.server)});await flushPromises()})
 it('不具知识查看的合法维护者保存后保留自己的输入',async()=>{h.iam.codes=['metadata:view','governance:field:view','governance:field:manage','knowledge:manage'];const w=await render();await input(w,'业务名称').setValue('自己填写的名称');await input(w,'字段含义').setValue('自己填写的含义');await input(w,'业务依据').setValue('自己的资料');await button(w,'保存草稿').trigger('click');await flushPromises();expect(input(w,'字段含义').element).toHaveProperty('value','自己填写的含义');expect(api.getFieldSemanticWorkspaceDetails).not.toHaveBeenCalled()})
 it('换账号丢弃旧AI响应，不回填候选或错误',async()=>{const w=await render();const d=deferred();api.generateFieldSemanticCandidate.mockReturnValue(d.promise);await button(w,'生成 AI 候选').trigger('click');h.auth.token='synthetic-B';await flushPromises();const count=api.getFieldSemanticWorkspace.mock.calls.length;d.resolve({data:basic(record())});await flushPromises();expect(api.getFieldSemanticWorkspace).toHaveBeenCalledTimes(count);expect(button(w,'生成 AI 候选').attributes('disabled')).toBeUndefined()})
})
describe('人工审核、撤销及批量恢复',()=>{
 it('审核者没有维护权仍能核对与审核，不能编辑草稿',async()=>{h.server={...record(),draft:revision('PENDING_REVIEW'),workingStatus:'PENDING_REVIEW'};h.iam.codes=['metadata:view','governance:field:view','knowledge:view','knowledge:approve'];const w=await render();expect(w.find('.field-review-preview').text()).toContain('人工草稿含义');expect(input(w,'字段含义').attributes('disabled')).toBeDefined();expect(button(w,'审核通过')).toBeDefined()})
 it('409保留审核依据及引用，不自动重发',async()=>{h.server={...record(),draft:revision('PENDING_REVIEW'),workingStatus:'PENDING_REVIEW'};api.reviewFieldSemantic.mockRejectedValue({response:{status:409,data:{message:'修订已变化'}}});const w=await render();await showReview(w);await input(w,'字段解释原始资料引用').setValue('资料v3第2节');await input(w,'字段解释审核依据').setValue('核对原始资料后的意见');w.findComponent({name:'ElCheckbox'}).vm.$emit('update:modelValue',true);await flushPromises();await button(w,'审核通过').trigger('click');await flushPromises();expect(input(w,'字段解释审核依据').element).toHaveProperty('value','核对原始资料后的意见');expect(input(w,'字段解释原始资料引用').element).toHaveProperty('value','资料v3第2节');expect(w.text()).toContain('不会自动重试');expect(api.reviewFieldSemantic).toHaveBeenCalledTimes(1)})
 it('审核重复点击只提交精确修订一次',async()=>{h.server={...record(),draft:revision('PENDING_REVIEW'),workingStatus:'PENDING_REVIEW'};const d=deferred();api.reviewFieldSemantic.mockReturnValue(d.promise);const w=await render();await input(w,'字段解释原始资料引用').setValue('原始资料');await input(w,'字段解释审核依据').setValue('审核意见');w.findComponent({name:'ElCheckbox'}).vm.$emit('update:modelValue',true);await flushPromises();await button(w,'审核通过').trigger('click');await button(w,'审核通过').trigger('click');expect(api.reviewFieldSemantic).toHaveBeenCalledTimes(1);expect(api.reviewFieldSemantic).toHaveBeenCalledWith(204,expect.objectContaining({revisionId:301,expectedVersion:3,evidenceConfirmed:true}));d.resolve({data:basic(h.server)});await flushPromises()})
 it('撤销确认期间切字段不撤销新字段',async()=>{const w=await render();const d=deferred();vi.mocked(ElMessageBox.confirm).mockReturnValueOnce(d.promise as never);await input(w,'撤销原因').setValue('撤销原字段原因');await button(w,'撤销当前解释').trigger('click');await chooseColumn(w,205);d.resolve('confirm');await flushPromises();expect(api.revokeFieldSemantic).not.toHaveBeenCalled()})
 it('批量来源部分成功保留失败原因，重复提交不重发',async()=>{const w=await render();const d=deferred();api.confirmFieldSemanticBindings.mockReturnValueOnce(d.promise);await button(w,'批量确认来源').trigger('click');await flushPromises();w.findComponent({name:'ElCheckboxGroup'}).vm.$emit('update:modelValue',[204,205]);const area=w.findAll('textarea').find(i=>i.attributes('maxlength')==='1000')!;await area.setValue('逐项核验依据');await button(w,'确认所选来源').trigger('click');await button(w,'确认所选来源').trigger('click');expect(api.confirmFieldSemanticBindings).toHaveBeenCalledTimes(1);d.resolve({data:{snapshotId:11,totalCount:2,successCount:1,failedCount:1,items:[{columnMetaId:204,success:true,message:'已确认'},{columnMetaId:205,success:false,message:'版本冲突，请核对'}]}});await flushPromises();expect(w.text()).toContain('已完成 1 项，未完成 1 项');expect(w.text()).toContain('版本冲突，请核对')})
 it('批量弹窗关闭后旧列表不回填新弹窗',async()=>{const w=await render();const d=deferred();api.getPendingFieldSemanticBindings.mockReturnValueOnce(d.promise).mockResolvedValueOnce({data:[{columnMetaId:205,expectedVersion:3,tableName:'orders',columnName:'status',eligible:true}]});await button(w,'批量确认来源').trigger('click');w.findComponent({name:'ElDialog'}).vm.$emit('update:modelValue',false);await flushPromises();await button(w,'批量确认来源').trigger('click');await flushPromises();d.resolve({data:[{columnMetaId:204,expectedVersion:3,tableName:'OLD TABLE',columnName:'OLD COLUMN',eligible:true}]});await flushPromises();expect(w.text()).not.toContain('OLD TABLE');expect(w.text()).toContain('orders.status')})
 it('PARTIAL复查与成功分开，未自动发布或构建',async()=>{api.runSemanticCheck.mockResolvedValue({data:{snapshotId:11,datasourceId:1,status:'PARTIAL',errorSummary:'本轮部分复查失败'}});const w=await render();await button(w,'复查当前快照').trigger('click');await flushPromises();expect(w.text()).toContain('本轮部分复查失败');expect(w.text()).not.toContain('语义复查完成：有效')})
})

describe('跨字段保存和无效对象导航',()=>{
 it('旧保存完成后的基础重读不能把新字段输入标成已保存',async()=>{
  h.iam.codes=h.iam.codes.filter((code:string)=>code!=='knowledge:view')
  const w=await render();await input(w,'业务名称').setValue('A');await input(w,'字段含义').setValue('A含义');await input(w,'业务依据').setValue('A资料')
  const d=deferred();api.getFieldSemanticWorkspace.mockReturnValueOnce(d.promise)
  await button(w,'保存草稿').trigger('click');await flushPromises();await chooseColumn(w,205)
  await input(w,'业务名称').setValue('B');await input(w,'字段含义').setValue('B未保存含义');await input(w,'业务依据').setValue('B资料')
  d.resolve({data:basic(record())});await flushPromises()
  expect(input(w,'字段含义').element).toHaveProperty('value','B未保存含义');expect(w.text()).toContain('有未保存输入');expect(button(w,'提交审核').attributes('disabled')).toBeDefined()
 })
 for(const query of [{columnMetaId:'999',tableName:'orders'},{columnMetaId:'invalid',tableName:'orders'},{columnMetaId:'204',tableName:'missing'}])it(`无效深链不保留旧对象操作 ${query.columnMetaId}/${query.tableName}`,async()=>{
  const w=await render();h.route.query={...h.route.query,...query};await flushPromises();expect(w.text()).toContain('未改用其他');expect(w.find('.field-semantic-workspace__content').exists()).toBe(false);expect(button(w,'保存草稿')).toBeUndefined();expect(api.saveFieldSemanticDraft).not.toHaveBeenCalled()
 })
})

it('AI保存后读取失败，错误重试不能覆盖未保存人工输入',async()=>{
 const w=await render();await input(w,'字段含义').setValue('必须保留的人工输入')
 api.getFieldSemanticWorkspace.mockRejectedValueOnce(new Error('AI AFTER READ FAILED'))
 await button(w,'生成 AI 候选').trigger('click');await flushPromises();expect(w.text()).toContain('AI AFTER READ FAILED')
 await button(w,'重试').trigger('click');await flushPromises()
 expect(input(w,'字段含义').element).toHaveProperty('value','必须保留的人工输入');expect(w.text()).toContain('有未保存输入')
})

it('an approved field without a draft points to publication rather than asking to resubmit', async()=>{
 h.server={...record(),draft:null,workingStatus:null,workingRevisionId:null}
 const w=await render(); await showReview(w)
 expect(w.text()).not.toContain('请先保存并提交修订')
 expect(w.text()).toContain('当前解释已审核通过')
 expect(w.text()).toContain('文档发布与 RAG 构建仍需分别确认')
})
