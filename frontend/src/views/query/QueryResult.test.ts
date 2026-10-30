// @vitest-environment happy-dom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { config, mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import type { IamS1QueryTaskResult } from '../../api/iamS1'
import { resolveQueryDisplayState } from '../../utils/queryDisplayState'
import { buildQueryChartOption } from '../../utils/queryChart'
import ChartContainer from '../../components/chart/ChartContainer.vue'
import QueryResult from './QueryResult.vue'
import QueryResultTable from './QueryResultTable.vue'

vi.mock('../../components/chart/ChartContainer.vue', () => ({
  default: {
    name: 'ChartContainer',
    props: ['option'],
    emits: ['error'],
    template: '<div class="chart-container" data-testid="chart-stub"></div>',
  },
}))

config.global.stubs = { 'el-table': true, 'el-table-column': true, 'el-pagination': true }

describe('QueryResult protected result views', () => {
  afterEach(() => vi.restoreAllMocks())
  it('allows feedback on completed empty results without exposing export',async()=>{
    const result:IamS1QueryTaskResult={taskId:'empty-completed',status:'COMPLETED',data:[],rowCount:0}
    const wrapper=mount(QueryResult,{props:{latestResult:result,viewState:resolveQueryDisplayState({result}),resultTab:'table',chartType:'bar',chartOption:null,
      pagedTableData:[],tablePage:1,tablePageSize:50,agentProgress:[],trustSummary:[],datasourceFacts:[],canViewSql:false,canExport:false,sqlLoading:false,sqlErrorMessage:'',originalQuestion:'没有符合条件的数据吗？'}})
    expect(wrapper.find('.feedback-buttons').exists()).toBe(true)
    await wrapper.get('button[aria-label="评价：结果有误"]').trigger('click')
    expect(wrapper.emitted('feedback')).toEqual([['DISLIKE']]);expect(wrapper.find('.export-button').exists()).toBe(false);wrapper.unmount()
  })

  it('shows provider failure as failure and retries the same question without asking for clarification', async () => {
    const question = '已完成订单一共有多少笔？'
    const result: IamS1QueryTaskResult = {
      taskId: 'task-provider-failure', status: 'FAILED', question,
      errorMessage: 'AI 服务因账户欠费或额度不足暂不可用，请联系管理员处理后重试',
    }
    const wrapper = mount(QueryResult, { props: {
      latestResult: result, viewState: resolveQueryDisplayState({ result }),
      resultTab: 'chart', chartType: 'bar', chartOption: null,
      pagedTableData: [], tablePage: 1, tablePageSize: 50,
      agentProgress: [], trustSummary: [], datasourceFacts: [], canViewSql: false, canExport: false,
      sqlLoading: false, sqlErrorMessage: '', originalQuestion: question,
    } })
    expect(wrapper.text()).toContain('查询未能完成')
    expect(wrapper.text()).toContain('账户欠费或额度不足')
    expect(wrapper.text()).not.toContain('需要补充查询条件')
    expect(wrapper.text()).not.toContain('补充查询条件')
    await wrapper.get('.status-actions button').trigger('click')
    expect(wrapper.emitted('retry')).toEqual([[question]])
    expect(wrapper.find('.result-actions').exists()).toBe(false)
    wrapper.unmount()
  })

  it('shows the concise metric once and keeps the complete question in details', async () => {
    const question = '按商品类别统计全部已完成订单的销售额，其中销售额为 quantity 与 unit_price 的乘积。'
    const result: IamS1QueryTaskResult = {
      taskId: 'task-category', status: 'COMPLETED', question,
      data: [{ category: 'Furniture', revenue: 180 }],
      columns: [{ name: 'category', type: 'VARCHAR' }, { name: 'revenue', type: 'DECIMAL' }],
      chartConfig: { title: { text: question }, xAxis: { type: 'category', data: ['Furniture'] }, yAxis: { type: 'value' }, series: [{ type: 'bar', name: '商品类别销售额', data: [180] }] },
    }
    const wrapper = mount(QueryResult, {
      props: {
        latestResult: result, viewState: resolveQueryDisplayState({ result }),
        resultTab: 'chart', chartType: 'pie', chartOption: buildQueryChartOption(result.chartConfig, 'pie'),
        pagedTableData: result.data!, tablePage: 1, tablePageSize: 50,
        agentProgress: [], trustSummary: [], datasourceFacts: [], canViewSql: false, canExport: false,
        sqlLoading: false, sqlErrorMessage: '', originalQuestion: question,
      },
    })
    expect(wrapper.find('.result-title').text()).toBe('查询结果')
    expect(wrapper.find('.chart-heading h3').text()).toBe('商品类别销售额')
    expect(wrapper.text().split('商品类别销售额')).toHaveLength(2)
    expect(wrapper.text()).not.toContain(question)
    expect(wrapper.findComponent(ChartContainer).props('option')!.title).toMatchObject([{ show: false }])
    ;(wrapper.vm as unknown as { openDetails: () => void }).openDetails()
    await nextTick()
    expect(wrapper.text()).toContain('原始问题')
    expect(wrapper.text()).toContain(question)
  })

  it('makes a pie unavailable for multiple metrics instead of dropping the other metric', () => {
    const result: IamS1QueryTaskResult = {
      taskId: 'task-metrics', status: 'COMPLETED', data: [{ revenue: 7, cost: 3 }],
      chartConfig: { xAxis: { type: 'category', data: ['A'] }, series: [{ type: 'bar', name: '收入', data: [7] }, { type: 'bar', name: '成本', data: [3] }] },
    }
    const wrapper = mount(QueryResult, {
      props: {
        latestResult: result, viewState: resolveQueryDisplayState({ result }),
        resultTab: 'chart', chartType: 'bar', chartOption: buildQueryChartOption(result.chartConfig, 'bar'),
        pagedTableData: result.data!, tablePage: 1, tablePageSize: 50,
        agentProgress: [], trustSummary: [], datasourceFacts: [], canViewSql: false, canExport: false,
        sqlLoading: false, sqlErrorMessage: '', originalQuestion: '',
      },
    })
    const pieButton = wrapper.findAll('.chart-type-switcher button').find((button) => button.text() === '饼图')!
    expect(pieButton.attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('多个指标')
    expect(wrapper.findComponent(ChartContainer).props('option')!.series).toHaveLength(2)
  })

  it('falls back to the same paged protected rows and columns when chart rendering fails', async () => {
    const result: IamS1QueryTaskResult = {
      taskId: 'task-chart',
      status: 'COMPLETED',
      data: [{ region: 'North', amount: 50 }, { region: 'South', amount: 120 }],
      columns: [
        { name: 'region', type: 'VARCHAR', comment: '地区' },
        { name: 'amount', type: 'DECIMAL', comment: '地区销售额' },
      ],
      rowCount: 2,
      finalProtectionStatus: 'FINAL_PROTECTED',
      chartConfig: { title: { text: '地区销售额' }, series: [{ type: 'bar' }] },
    }
    const rows = [{ region: 'South', amount: 120 }]
    const wrapper = mount(QueryResult, {
      props: {
        latestResult: result,
        viewState: resolveQueryDisplayState({ result }),
        resultTab: 'chart',
        chartType: 'bar',
        chartOption: result.chartConfig ?? null,
        pagedTableData: rows,
        tablePage: 2,
        tablePageSize: 1,
        agentProgress: [],
        trustSummary: [],
        datasourceFacts: [],
        canViewSql: true,
        canExport: true,
        sqlLoading: false,
        sqlErrorMessage: '',
        originalQuestion: '按地区统计销售额',
      },
    })

    await wrapper.findComponent(ChartContainer).vm.$emit('error', 'chart failed')
    await nextTick()

    expect(wrapper.text()).toContain('图表未能显示，已回退到同一查询的受保护数据')
    expect(wrapper.text()).toContain('已按当前权限校验')
    expect(wrapper.findComponent(QueryResultTable).props('result').columns).toEqual(result.columns)
    expect(wrapper.findComponent(QueryResultTable).props('rows')).toEqual(rows)
    expect(wrapper.findComponent(QueryResultTable).props('page')).toBe(2)
  })

  it('uses the protected data table when a completed result has no chart configuration', () => {
    const result: IamS1QueryTaskResult = {
      taskId: 'task-no-chart',
      status: 'COMPLETED',
      data: [{ region: 'East', amount: 40 }],
      columns: [{ name: 'region', type: 'VARCHAR', comment: '地区' }],
    }
    const rows = [{ region: 'East', amount: 40 }]
    const wrapper = mount(QueryResult, {
      props: {
        latestResult: result,
        viewState: resolveQueryDisplayState({ result }),
        resultTab: 'chart',
        chartType: 'bar',
        chartOption: null,
        pagedTableData: rows,
        tablePage: 1,
        tablePageSize: 50,
        agentProgress: [],
        trustSummary: [],
        datasourceFacts: [],
        canViewSql: false,
        canExport: false,
        sqlLoading: false,
        sqlErrorMessage: '',
        originalQuestion: '原问题',
      },
    })

    expect(wrapper.text()).toContain('当前结果没有图表配置，以下显示同一查询的受保护数据')
    expect(wrapper.findComponent(QueryResultTable).props('result').columns).toEqual(result.columns)
    expect(wrapper.findComponent(QueryResultTable).props('rows')).toEqual(rows)
  })

  it.each(['CLARIFICATION_REQUIRED', 'FAILED', 'CANCELLED', 'TIMEOUT'])('hides stale table rows and result actions for %s', (status) => {
    const result: IamS1QueryTaskResult = {
      taskId: `task-${status}`,
      status,
      data: [{ region: 'North', amount: 50 }],
      columns: [{ name: 'region', type: 'VARCHAR' }],
      canExport: true,
    }
    const wrapper = mount(QueryResult, {
      props: {
        latestResult: result,
        viewState: resolveQueryDisplayState({ result }),
        resultTab: 'chart',
        chartType: 'bar',
        chartOption: null,
        pagedTableData: result.data ?? [],
        tablePage: 1,
        tablePageSize: 50,
        agentProgress: [],
        trustSummary: [],
        datasourceFacts: [],
        canViewSql: true,
        canExport: true,
        sqlLoading: false,
        sqlErrorMessage: '',
        originalQuestion: '原问题',
      },
    })

    expect(wrapper.findComponent(QueryResultTable).exists()).toBe(false)
    expect(wrapper.find('.result-actions').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('North')
    expect(wrapper.text()).not.toContain('导出 CSV')
  })

  it('shows one successful empty state with feedback but without table or export', () => {
    const result: IamS1QueryTaskResult = { taskId: 'task-empty', status: 'COMPLETED', data: [] }
    const wrapper = mount(QueryResult, {
      props: {
        latestResult: result,
        viewState: resolveQueryDisplayState({ result }),
        resultTab: 'chart',
        chartType: 'bar',
        chartOption: null,
        pagedTableData: [],
        tablePage: 1,
        tablePageSize: 50,
        agentProgress: [],
        trustSummary: [],
        datasourceFacts: [],
        canViewSql: false,
        canExport: false,
        sqlLoading: false,
        sqlErrorMessage: '',
        originalQuestion: '原问题',
      },
    })

    expect(wrapper.text()).toContain('查询成功，没有匹配记录')
    expect(wrapper.findComponent(QueryResultTable).exists()).toBe(false)
    expect(wrapper.find('.feedback-buttons').exists()).toBe(true)
    expect(wrapper.find('.export-button').exists()).toBe(false)
  })

  it('does not expose SQL when the task capability denies it', async () => {
    const result: IamS1QueryTaskResult = { taskId: 'task-no-sql', status: 'COMPLETED', data: [{ amount: 1 }] }
    const wrapper = mount(QueryResult, {
      props: {
        latestResult: result,
        viewState: resolveQueryDisplayState({ result }),
        resultTab: 'chart',
        chartType: 'bar',
        chartOption: null,
        pagedTableData: result.data ?? [],
        tablePage: 1,
        tablePageSize: 50,
        agentProgress: [],
        trustSummary: [],
        datasourceFacts: [{ label: '当前发布快照', value: 'v64' }],
        canViewSql: false,
        canExport: false,
        sqlLoading: false,
        sqlErrorMessage: '',
        originalQuestion: '原问题',
      },
    })

    ;(wrapper.vm as unknown as { openDetails: () => void }).openDetails()
    await nextTick()

    expect(wrapper.text()).toContain('可信依据')
    expect(wrapper.text()).toContain('当前发布快照')
    expect(wrapper.text()).toContain('v64')
    expect(wrapper.findAll('[role="tab"]').some((tab) => tab.text() === 'SQL')).toBe(false)
    expect(wrapper.text()).not.toContain('SELECT')
    expect(wrapper.find('.permission-hint').text()).toContain('不可导出')
  })

  it('shows a failed SQL read beside the selected task and offers an explicit retry', async () => {
    const result: IamS1QueryTaskResult = { taskId: 'task-sql-failed', status: 'COMPLETED', data: [{ amount: 1 }] }
    const wrapper = mount(QueryResult, {
      props: {
        latestResult: result,
        viewState: resolveQueryDisplayState({ result }),
        resultTab: 'chart',
        chartType: 'bar',
        chartOption: null,
        pagedTableData: result.data ?? [],
        tablePage: 1,
        tablePageSize: 50,
        agentProgress: [],
        trustSummary: [],
        datasourceFacts: [],
        canViewSql: true,
        canExport: false,
        sqlLoading: false,
        sqlErrorMessage: '当前权限不允许查看 SQL，或结果已失效。',
        originalQuestion: '原问题',
      },
    })

    ;(wrapper.vm as unknown as { openDetails: () => void }).openDetails()
    await nextTick()
    await wrapper.get('.details-tabs button:nth-child(2)').trigger('click')
    await nextTick()
    expect(wrapper.find('.sql-read-error').text()).toContain('当前权限不允许查看 SQL')

    await wrapper.find('.sql-read-error button').trigger('click')
    expect(wrapper.emitted('retry-sql')).toHaveLength(1)
  })
})

it('business evidence comes first and raw trace identifiers are in an explicit disclosure',async()=>{
 const result={taskId:'a',status:'COMPLETED',data:[{s1_c1:2}],activeMetadataSnapshotId:1,permissionRevision:31,finalProtectionStatus:'FINAL_PROTECTED'}
 const w=mount(QueryResult,{props:{latestResult:result,viewState:resolveQueryDisplayState({result}),resultTab:'trust',chartType:'bar',chartOption:null,pagedTableData:result.data,tablePage:1,tablePageSize:50,agentProgress:[],trustSummary:[{label:'本次使用的表',value:'products',muted:false},{label:'受保护执行标识',value:'EXACT_ATTEMPT',muted:false},{label:'查询来源记录',value:'尚未确认登记完成',muted:true},{label:'追溯投影',value:'尚未确认投影完成',muted:true}],datasourceFacts:[],canViewSql:false,canExport:false,sqlLoading:false,sqlErrorMessage:'',originalQuestion:'计数'} })
 expect(w.get('.trust-business-summary').text()).toContain('products');expect(w.get('.trust-business-summary').text()).toContain('尚未确认登记完成')
 const technical=w.get('details.trust-technical');expect(technical.attributes('open')).toBeUndefined();expect(technical.text()).toContain('EXACT_ATTEMPT');expect(technical.text()).toContain('FINAL_PROTECTED')
 expect(w.get('.trust-business-summary').text()).not.toContain('EXACT_ATTEMPT');w.unmount()
})
