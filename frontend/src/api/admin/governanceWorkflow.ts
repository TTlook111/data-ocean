import { http } from '../http'
import type { ApiResult } from './user'

async function unwrap<T>(request:Promise<{data:ApiResult<T>}>){return (await request).data}
export function governanceError(error:unknown,fallback:string){const value=(error as {response?:{data?:{message?:string}}})?.response?.data?.message;return typeof value==='string'&&value.trim()?value:fallback}
export interface GovernanceTaskRow { id:number; columnMetaId?:number; businessRevisionId?:number; targetType?:string; tableName:string; columnName:string; origin:string; aiStatus:string; stage:string; reason:string; nextAction:string }
export interface UsageProfile { columnMetaId:number; fieldName:string; revisionId?:number; reviewStatus:string; score:number|null; evidenceCount:number; positiveCount?:number; negativeCount?:number; tags:string[] }
export interface UsageEvidence { id:number; feedbackId:number; direction:string; createdAt:string; active:boolean; contributes:boolean; contributionStatus:string; note:string }
export const usageEvidence=(snapshot:number,column:number,page=1)=>unwrap(http.get<ApiResult<{records:UsageEvidence[];total:number;revisionId?:number}>>(`/api/admin/field-profiles/snapshots/${snapshot}/columns/${column}/evidence`,{params:{pageNo:page,pageSize:20}}))
export interface BusinessKnowledge { id:number; kind:string; knowledgeKey:string; title:string; body:Record<string,string>; dependencies:string[]; evidence:string; evidenceConfirmation?:{reference?:string;confirmedBy?:number}; unresolvedItems:string[]; status:string; effectiveStatus?:string; rowVersion:number; origin:string; reviewNote?:string }
export interface BusinessKnowledgeDraft { expectedRevisionId?:number;expectedVersion?:number;confirmedSourceHash?:string;kind:string; knowledgeKey:string; title:string; body:Record<string,string>; dependencies:string[]; evidence:string; evidenceReference?:string;evidenceConfirmed?:boolean; unresolvedItems:string[] }
export interface BusinessSourceItem {dependency:string;tableName:string;columnName?:string;dataType?:string;comment?:string;tableComment?:string;reviewedSemantics?:{qualified:boolean;body?:string;sourceIds?:number[]}}
export interface BusinessSourceStatus {sourceChanged:boolean;currentHash?:string;reason?:string;original:BusinessSourceItem[];current:BusinessSourceItem[]}
export const businessSourceStatus=(id:number,dependencies?:string[])=>dependencies?unwrap(http.post<ApiResult<BusinessSourceStatus>>(`/api/admin/business-knowledge/${id}/source-status`,{dependencies})):unwrap(http.get<ApiResult<BusinessSourceStatus>>(`/api/admin/business-knowledge/${id}/source-status`))
export interface GovernanceFeedbackRow { id:number; taskId:string; feedbackType:string; reasonCode:string; comment?:string; columnMetaId?:number; status:string; rowVersion:number; reviewNote?:string;followupStatus?:string;currentRevisionId?:number;resolution?:{scenario?:string;expectedResult?:string}; targets:Array<{columnMetaId:number;fieldName:string}> }
export interface GovernanceHistoryRow { id:number; action:string; operatorId:number; createdAt:string; payload:{note?:string;comment?:string;origin?:string;reasonCode?:string;feedbackType?:string} }
export const readGovernanceHistory=(kind:'BUSINESS'|'FEEDBACK',id:number)=>unwrap(http.get<ApiResult<GovernanceHistoryRow[]>>(`/api/admin/${kind==='BUSINESS'?'business-knowledge':'governance-feedback'}/${id}/history`))
export const discoverGovernance=(snapshot:number,aiFirst=true)=>unwrap(http.post<ApiResult<{created:number;scanned:number}>>(`/api/admin/governance-tasks/snapshots/${snapshot}/discover`,null,{params:{aiFirst}}))
export const listGovernanceTasks=(snapshot:number,page=1)=>unwrap(http.get<ApiResult<{records:GovernanceTaskRow[];total:number;aiSummary?:{queued:number;running:number;failed:number}}>>(`/api/admin/governance-tasks/snapshots/${snapshot}`,{params:{pageNo:page,pageSize:20}}))
export const getGovernanceRules=()=>unwrap(http.get<ApiResult<Array<{code:string;name:string;target:string;requirement:string;version:number}>>>('/api/admin/governance-tasks/rules'))
export const retryGovernance=(id:number)=>unwrap(http.post<ApiResult<void>>(`/api/admin/governance-tasks/${id}/retry`))
export const currentUsageProfile=(snapshot:number,column:number)=>unwrap(http.get<ApiResult<UsageProfile>>(`/api/admin/field-profiles/snapshots/${snapshot}/columns/${column}`))
export const listBusinessKnowledge=(snapshot:number)=>unwrap(http.get<ApiResult<BusinessKnowledge[]>>(`/api/admin/business-knowledge/snapshots/${snapshot}`))
export const saveBusinessKnowledge=(snapshot:number,body:BusinessKnowledgeDraft,ai=false)=>unwrap(http.post<ApiResult<BusinessKnowledge>>(`/api/admin/business-knowledge/snapshots/${snapshot}/${ai?'candidates':'drafts'}`,body))
export const actBusinessKnowledge=(id:number,expectedVersion:number,action:string,note:string)=>unwrap(http.post<ApiResult<void>>(`/api/admin/business-knowledge/${id}/actions`,{expectedVersion,action,note}))
export const listGovernanceFeedback=(datasource:number,page=1)=>unwrap(http.get<ApiResult<{records:GovernanceFeedbackRow[];total:number}>>(`/api/admin/governance-feedback/datasources/${datasource}`,{params:{pageNo:page,pageSize:20}}))
export const reviewGovernanceFeedback=(id:number,body:{expectedVersion:number;approved:boolean;columnMetaId?:number;reasonCode:string;note:string;scenario?:string;expectedResult?:string})=>unwrap(http.post<ApiResult<void>>(`/api/admin/governance-feedback/${id}/review`,body))

export const verifyGovernanceFeedback=(id:number,body:{expectedVersion:number;targetRevisionId:number;actualResult:string;evidenceReference:string;confirmed:boolean})=>unwrap(http.post<ApiResult<void>>(`/api/admin/governance-feedback/${id}/verification`,body))

export const pauseGovernance=(snapshot:number)=>unwrap(http.post<ApiResult<void>>(`/api/admin/governance-tasks/snapshots/${snapshot}/pause`))
