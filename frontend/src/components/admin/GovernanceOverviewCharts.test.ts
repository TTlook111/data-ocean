// @vitest-environment happy-dom
import {mount} from '@vue/test-utils'
import {describe,it,expect} from 'vitest'
import Charts from './GovernanceOverviewCharts.vue'
const summary={totalUnresolved:1,statusCounts:{OPEN:0,CONFIRMED:0,REOPENED:1},domainCounts:{SOURCE:1,SEMANTIC:0},recentIssues:[]}
const props={summary,rows:[{id:1,snapshotId:31,datasourceId:2,status:'REOPENED',tableName:'orders',issueDomain:'SOURCE',issueDescription:'这段问题处理说明不应该放在总览',dimension:'ACCURACY',severity:'LOW',createdAt:'2026-10-31T10:00:00'}],complete:true,aggregateLoading:false,message:'',issueLoading:false,issueError:'',canViewIssues:true,batch:null,batchLoading:false,batchError:'',batchState:'无批次',batchDescription:'无可核验批次',batchScope:''}
describe('治理总览分析呈现',()=>{
 it('需重新处理保留REOPENED语义，图形只发出精确表/状态意图',async()=>{const w=mount(Charts,{props});try{expect(w.text()).toContain('需重新处理');expect(w.text()).not.toContain('这段问题处理说明');await w.find('[aria-label="orders 需重新处理 1条，前往问题中心"]').trigger('click');expect(w.emitted('plot')?.[0]).toEqual([{tableName:'orders',status:'REOPENED'}]);await w.find('[aria-label="查看处理状态说明"]').trigger('click');expect(w.emitted('help')).toHaveLength(1)}finally{w.unmount()}})
 it('未完整取得明细时只有状态摘要，不显示表排行',()=>{const w=mount(Charts,{props:{...props,complete:false,rows:[],message:'分页不完整'}});try{expect(w.find('.stack-row').exists()).toBe(false);expect(w.findAll('.fallback-row')).toHaveLength(3);expect(w.text()).toContain('分页不完整')}finally{w.unmount()}})
 it('无权不显示0或残留图形，校验失败与无批次独立',()=>{const w=mount(Charts,{props:{...props,summary:null,canViewIssues:false,complete:false,rows:[],batchError:'读取被拒绝'}});try{expect(w.find('.donut').exists()).toBe(false);expect(w.find('.stack-row').exists()).toBe(false);expect(w.text()).toContain('无权');expect(w.text()).toContain('读取被拒绝')}finally{w.unmount()}})
})
