// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { reactive } from 'vue'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import GovernanceWorklist from './GovernanceWorklist.vue'
import GovernanceFieldsView from '../../views/admin/governance/GovernanceFieldsView.vue'
import ScopeBar from './ScopeBar.vue'
const h = vi.hoisted(() => ({ context: null as any, iam: null as any, auth: null as any, route: null as any, router: null as any }))
const api = vi.hoisted(() => ({ listGovernanceTasks: vi.fn(), discoverGovernance: vi.fn(), retryGovernance: vi.fn(), pauseGovernance: vi.fn(), getGovernanceRules: vi.fn(), runSemanticCheck: vi.fn() }))
vi.mock('../../api/http', () => ({ http: { get:vi.fn(),post:vi.fn(),put:vi.fn(),patch:vi.fn(),delete:vi.fn() } }))
vi.mock('../../stores/adminContext', () => ({ useAdminContextStore: () => h.context }))
vi.mock('../../stores/iamS1', () => ({ useIamS1Store: () => h.iam }))
vi.mock('../../stores/auth', () => ({ useAuthStore: () => h.auth }))
vi.mock('vue-router', () => ({ useRoute: () => h.route, useRouter: () => h.router }))
vi.mock('../../api/admin/governanceWorkflow', () => ({ ...api, governanceError: (e: any, fallback: string) => e?.response?.data?.message || fallback }))
vi.mock('../../api/admin/fieldSemantic', () => ({ runSemanticCheck: api.runSemanticCheck }))
const rows = [{ id: 1, columnMetaId: 204, tableName: 'orders', columnName: 'amount', origin: 'DISCOVERY', aiStatus: 'AI_FAILED', stage: 'NEEDS_GOVERNANCE', reason: '待补充', nextAction: 'EDIT' }]
const wrappers: VueWrapper[] = []
function deferred() { let resolve!: (v: any) => void; const promise = new Promise(r => { resolve = r }); return { promise, resolve } }
async function render() { const w = mount(GovernanceWorklist, { global: { plugins: [ElementPlus] } }); wrappers.push(w); await flushPromises(); return w }
function button(w: VueWrapper, name: string) { return w.findAll('button').find(b => b.text() === name)! }
beforeEach(() => {
 vi.clearAllMocks(); h.context = reactive({ datasourceId: 1, snapshotId: 11, loading: false, initialized:true, datasources:[{id:1,name:'合成源'}], initialize:vi.fn(async()=>{}), selectSnapshot:vi.fn((id:number)=>{h.context.snapshotId=id}), ensureSnapshot:vi.fn() }); h.auth = reactive({ token: 'synthetic-A' }); h.route = reactive({ query: {} }); h.router = { push: vi.fn(), replace:vi.fn() }
 h.iam = reactive({ load:vi.fn(async()=>{}), loaded: true, errorMessage: null, snapshot: { permissionRevision: 1 }, codes: ['metadata:view','governance:field:view','governance:field:manage','knowledge:manage','knowledge:view','governance:check'], canOnDatasource: (code: string) => h.iam.codes.includes(code) })
 api.listGovernanceTasks.mockResolvedValue({ data: { records: rows, total: 49, aiSummary: { queued: 0, running: 0, failed: 1 } } }); api.discoverGovernance.mockResolvedValue({ data: { scanned: 5, created: 2 } }); api.pauseGovernance.mockResolvedValue({ data: null }); api.retryGovernance.mockResolvedValue({ data: null }); api.getGovernanceRules.mockResolvedValue({ data: [] })
 api.runSemanticCheck.mockResolvedValue({ data: { snapshotId: 11, datasourceId: 1, status: 'SUCCESS' } })
})
afterEach(() => wrappers.splice(0).forEach(w => w.unmount()))
describe('治理任务身份与动作合同', () => {
 it('重复触发只提交一次', async () => { const d = deferred(); api.discoverGovernance.mockReturnValue(d.promise); const w = await render(); await button(w,'开始治理').trigger('click'); await button(w,'开始治理').trigger('click'); expect(api.discoverGovernance).toHaveBeenCalledTimes(1); d.resolve({ data: { scanned: 5, created: 2 } }); await flushPromises() })
 it('相同范围换账号后旧读取不回填', async () => { const d = deferred(); api.listGovernanceTasks.mockReturnValueOnce(d.promise).mockResolvedValue({ data: { records: [{ ...rows[0], tableName: 'NEW' }], total: 1 } }); const w = await render(); h.auth.token='synthetic-B'; await flushPromises(); d.resolve({ data: { records: [{ ...rows[0], tableName: 'OLD' }], total: 1 } }); await flushPromises(); expect(w.text()).toContain('NEW'); expect(w.text()).not.toContain('OLD'); expect(w.text()).toContain('AI 汇总未返回') })
 it('撤权时清旧任务，不借其他范围', async () => { const w=await render(); const count=api.listGovernanceTasks.mock.calls.length; h.iam.codes=[]; h.iam.snapshot={permissionRevision:2}; await flushPromises(); expect(w.text()).toContain('没有当前范围'); expect(w.text()).not.toContain('orders.amount'); expect(api.listGovernanceTasks).toHaveBeenCalledTimes(count) })
 it('最小字段角色缺元数据权时显示真实快照合同缺口', async () => { h.context.snapshotId=undefined; h.iam.codes=['governance:field:view']; const w=await render(); expect(api.listGovernanceTasks).not.toHaveBeenCalled(); expect(w.text()).toContain('没有 metadata:view'); expect(w.text()).not.toContain('共 0 项任务') })
 it('显式范围或非法ID不读取偏好快照', async () => { h.route.query={snapshotId:'not-valid'}; const w=await render(); expect(api.listGovernanceTasks).not.toHaveBeenCalled(); expect(w.text()).toContain('未改用其他范围') })
 it('暂停只需字段维护，不误要求知识维护或执行检查', async () => { h.iam.codes=['governance:field:view','governance:field:manage']; api.listGovernanceTasks.mockResolvedValue({data:{records:rows,total:1,aiSummary:{queued:1,running:0,failed:0}}}); const w=await render(); expect(button(w,'暂停 AI').attributes('disabled')).toBeUndefined(); await button(w,'暂停 AI').trigger('click'); await flushPromises(); expect(api.pauseGovernance).toHaveBeenCalledWith(11); expect(button(w,'开始治理').attributes('disabled')).toBeDefined() })
 it('失败字段重试沿独立字段维护合同', async () => { h.iam.codes=['governance:field:view','governance:field:manage']; const w=await render(); await button(w,'重试 AI').trigger('click'); await flushPromises(); expect(api.retryGovernance).toHaveBeenCalledWith(1) })
 it('PARTIAL复查保留原因，不宣称完成', async () => { api.runSemanticCheck.mockResolvedValue({data:{snapshotId:11,datasourceId:1,status:'PARTIAL',errorSummary:'部分规则无法核验'}}); const w=await render(); await button(w,'复查治理结果').trigger('click'); await flushPromises(); expect(w.text()).toContain('部分规则无法核验'); expect(w.text()).not.toContain('语义复查完成，未通过项继续保留') })
 it('冲突原因可见，不自动重发', async () => { api.discoverGovernance.mockRejectedValue({response:{status:409,data:{message:'范围正在由另一人扫描'}}}); const w=await render(); await button(w,'开始治理').trigger('click'); await flushPromises(); expect(w.text()).toContain('范围正在由另一人扫描'); expect(api.discoverGovernance).toHaveBeenCalledTimes(1) })
 it('服务器分页和全量total保留', async () => { const w=await render(); const pagination=w.findComponent({name:'ElPagination'}); expect(pagination.props('total')).toBe(49); pagination.vm.$emit('update:current-page',2); pagination.vm.$emit('current-change',2); await flushPromises(); expect(api.listGovernanceTasks).toHaveBeenLastCalledWith(11,2) })
 it('规则迟到返回不写入新范围', async () => { const d=deferred(); api.getGovernanceRules.mockReturnValue(d.promise); const w=await render(); w.findComponent({name:'ElCollapse'}).vm.$emit('change',['rules']); await flushPromises(); h.context.snapshotId=12; await flushPromises(); d.resolve({data:[{code:'OLD',name:'OLD RULE',target:'字段',requirement:'旧规则',version:1}]}); await flushPromises(); expect(w.text()).not.toContain('OLD RULE') })
 it('读取失败不显示零任务结论', async () => { api.listGovernanceTasks.mockRejectedValue(new Error('网络读取失败')); const w=await render(); expect(w.text()).toContain('治理任务读取失败'); expect(w.text()).not.toContain('共 0 项任务') })
})

async function renderTabs() {
 const w=mount(GovernanceFieldsView,{global:{plugins:[ElementPlus],stubs:{GovernanceWorklist:{template:'<div>task-workspace</div>'},BusinessKnowledgeWorkbench:true,FieldTagManager:true,GovernanceUsageProfiles:true,GovernanceFeedbackQueue:{template:'<div>source-feedback</div>'},ConfidenceDashboard:{template:'<div>legacy-source-confidence</div>'},FeedbackReview:true}}})
 wrappers.push(w);await flushPromises();return w
}
describe('治理Tab按源与快照分层',()=>{
 it('无metadata:view仍可打开按源反馈，不给其他Tab补快照权限',async()=>{h.context.snapshotId=undefined;h.iam.codes=['governance:field:view'];h.route.query={tab:'feedback'};const w=await renderTabs();expect(w.text()).toContain('source-feedback');expect(w.findAll('[role="tab"]').length).toBe(5)})
 it('无快照仍能按需打开旧来源可信度',async()=>{h.context.snapshotId=undefined;h.iam.codes=['governance:field:view'];h.route.query={tab:'confidence'};const w=await renderTabs();expect(w.text()).toContain('没有 metadata:view');w.findComponent({name:'ElCollapse'}).vm.$emit('update:modelValue',['source']);await flushPromises();expect(w.text()).toContain('legacy-source-confidence')})
 it('范围初始化失败可以重试，不永久阻断Tabs',async()=>{h.context.initialize.mockRejectedValueOnce(new Error('SCOPE FAILED'));const w=await renderTabs();expect(w.text()).toContain('SCOPE FAILED');await button(w,'重试范围').trigger('click');await flushPromises();expect(w.text()).not.toContain('SCOPE FAILED');expect(w.text()).toContain('task-workspace')})
 it('公共范围外部刷新成功也会清除旧错误',async()=>{h.context.initialized=false;h.context.initialize.mockRejectedValueOnce(new Error('SCOPE FAILED'));const w=await renderTabs();expect(w.text()).toContain('SCOPE FAILED');h.context.loading=true;h.context.initialized=true;h.context.loading=false;await flushPromises();expect(w.text()).not.toContain('SCOPE FAILED')})
 it('ScopeBar缺metadata:view不向精确快照接口探测',async()=>{h.context.snapshotId=undefined;h.iam.codes=['governance:field:view'];h.route.query={snapshotId:'11'};const w=mount(ScopeBar,{props:{mode:'datasource-snapshot'},global:{plugins:[ElementPlus]}});wrappers.push(w);await flushPromises();expect(h.context.ensureSnapshot).not.toHaveBeenCalled();expect(w.text()).toContain('当前账号没有 metadata:view')})
})
