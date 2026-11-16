import { issueStatusLabel } from './enumLabels'
import type { QualityIssueItem, QualityIssueSummary, QualityCheckBatch, listQualityIssues, getSnapshotQualityIssueSummary } from '../api/admin/governance'
export const overviewStatuses=['OPEN','CONFIRMED','REOPENED'] as const
export type OverviewStatus=typeof overviewStatuses[number]
export const overviewStatusNames:Record<OverviewStatus,string>={OPEN:issueStatusLabel('OPEN'),CONFIRMED:issueStatusLabel('CONFIRMED'),REOPENED:issueStatusLabel('REOPENED')}
export const overviewDomains=['SOURCE','SEMANTIC'] as const
const count=(v:unknown):v is number=>typeof v==='number'&&Number.isSafeInteger(v)&&v>=0
export function overviewSummary(s:QualityIssueSummary|null|undefined){
 const total=count(s?.totalUnresolved)?s!.totalUnresolved:null
 const statuses=total!=null&&overviewStatuses.every(k=>count(s?.statusCounts?.[k]))&&overviewStatuses.reduce((n,k)=>n+s!.statusCounts[k],0)===total?s!.statusCounts:null
 const domains=total!=null&&overviewDomains.every(k=>count(s?.domainCounts?.[k]))&&overviewDomains.reduce((n,k)=>n+s!.domainCounts![k],0)===total?s!.domainCounts!:null
 return {total,statuses,domains}
}
export interface OverviewRead {complete:boolean;rows:QualityIssueItem[];message:string;forbidden?:boolean}
/** Bounded complete pagination with summary reconciliation. This is not a transactional read watermark. */
export async function loadOverviewIssues(source:number,snapshot:number,summary:QualityIssueSummary,page:typeof listQualityIssues,verify:typeof getSnapshotQualityIssueSummary,current:()=>boolean):Promise<OverviewRead>{
 const partial=(message:string,forbidden=false):OverviewRead=>({complete:false,rows:[],message,forbidden})
 const model=overviewSummary(summary)
 if(!current())return partial('范围或身份已变化，已停止旧聚合。')
 if(model.total===0)return {complete:true,rows:[],message:'权威摘要确认当前未解决问题为0。'}
 if(model.total==null||model.total>2000)return partial('问题规模超出本页聚合预算或总数未知；保留权威摘要，不推算表排行。')
 if(!model.statuses||!model.domains)return partial('摘要的状态或分域计数尚未核实；不推算表排行。')
 const rows:QualityIssueItem[]=[],ids=new Set<number>();let requests=0
 try{
  for(const status of overviewStatuses){
   const expected=model.statuses[status];let loaded=0,index=1
   while(loaded<expected){
    if(!current())return partial('范围或身份已变化，已停止旧聚合。')
    if(++requests>24)return partial('分页读取超过本页预算；保留权威摘要，不展示部分表排行。')
    const response=await page(snapshot,{status,page:index,size:100})
    if(!current())return partial('范围或身份已变化，已停止旧聚合。')
    const data=response.data
    if(!data||data.total!==expected||!Array.isArray(data.records)||!data.records.length||data.records.length>100||loaded+data.records.length>expected)return partial('分页不完整或统计已变化；保留摘要，不展示总体表排行。')
    for(const r of data.records){
     if(!r||!Number.isSafeInteger(r.id)||r.id<=0||ids.has(r.id)||r.snapshotId!==snapshot||(r.datasourceId!=null&&r.datasourceId!==source)||r.status!==status||!overviewDomains.includes(r.issueDomain as typeof overviewDomains[number])||(r.tableName!=null&&typeof r.tableName!=='string'))return partial('问题记录重复、错属或状态不一致，未展示聚合。')
     ids.add(r.id);rows.push(r)
    }
    loaded+=data.records.length;index++
   }
  }
  if(!current())return partial('范围或身份已变化，已停止旧聚合。')
  const checked=overviewSummary((await verify(snapshot)).data)
  if(!current())return partial('范围或身份已变化，已停止旧聚合。')
  if(checked.total!==model.total||!checked.statuses||!checked.domains||overviewStatuses.some(k=>checked.statuses![k]!==model.statuses![k])||overviewDomains.some(k=>checked.domains![k]!==model.domains![k]))return partial('读取期间摘要已变化；请刷新，暂不展示总体表排行。')
  if(rows.length!==model.total||overviewDomains.some(k=>rows.filter(r=>r.issueDomain===k).length!==model.domains![k]))return partial('完整明细与分域摘要未能对账，暂不展示表排行。')
  return {complete:true,rows,message:'按本轮完整分页结果核对，非事务快照；读取期间可能变化。'}
 }catch(error){const forbidden=(error as {response?:{status?:number}})?.response?.status===403;return partial(forbidden?'当前范围的问题读取被拒绝。':'问题明细或二次核验读取失败；保留摘要，不推算表排行。',forbidden)}
}
export function groupOverviewTables(rows:QualityIssueItem[]){
 const groups=new Map<string|null,{name:string|null;count:number;statuses:Record<OverviewStatus,number>}>()
 for(const row of rows){const name=typeof row.tableName==='string'&&row.tableName.trim()?row.tableName:null;let group=groups.get(name);if(!group){group={name,count:0,statuses:{OPEN:0,CONFIRMED:0,REOPENED:0}};groups.set(name,group)}group.count++;group.statuses[row.status as OverviewStatus]++}
 return [...groups.values()].sort((a,b)=>b.count-a.count||(a.name||'').localeCompare(b.name||''))
}
export function overviewCoverage(batch:QualityCheckBatch|null|undefined){
 if(!batch||batch.legacyLimited||batch.status==='LEGACY'||!Array.isArray(batch.rules)||!batch.rules.length)return null
 const keys=['SUCCESS','FAILURE','NOT_APPLICABLE','NOT_EXECUTED'] as const,counts={SUCCESS:0,FAILURE:0,NOT_APPLICABLE:0,NOT_EXECUTED:0}
 for(const rule of batch.rules){if(!rule||!keys.includes(rule.status))return null;counts[rule.status]++}
 if(!count(batch.ruleCount)||batch.ruleCount!==batch.rules.length)return null
 return {total:batch.rules.length,counts}
}
