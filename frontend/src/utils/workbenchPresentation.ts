import type { RouteLocationRaw } from 'vue-router'
import type { DatasourceReadiness } from '../api/admin/datasource'
export const WORKBENCH_READINESS_LIMIT = 100
export function positiveId(value: unknown): value is number { return Number.isSafeInteger(value) && Number(value) > 0 }
/** Missing DTO fields stay unknown; never coerce undefined to a successful/false condition. */
export function validWorkbenchReadiness(value: DatasourceReadiness) {
  return positiveId(value?.datasourceId) &&
    ['askable','connectionReady','metadataReady','governanceReady','knowledgeReady','permissionReady'].every(key => typeof value[key as keyof DatasourceReadiness] === 'boolean') &&
    typeof value.stage === 'string' && typeof value.stageLabel === 'string' &&
    Array.isArray(value.blockReasons) && value.blockReasons.every(reason => reason && typeof reason.code === 'string' && typeof reason.message === 'string') &&
    ['publishedSnapshotId','latestCollectedSnapshotId','ragSourceSnapshotId'].every(key => value[key as keyof DatasourceReadiness] == null || positiveId(value[key as keyof DatasourceReadiness]))
}
export interface WorkbenchAction { label: string; description: string; target: RouteLocationRaw; capability?: string; globalAny?: string[]; unavailable?: string }
/** Only known business codes choose destinations; backend actionPath is never executed. */
export function workbenchSourceAction(id: number, readiness?: DatasourceReadiness): WorkbenchAction {
  const fallback:WorkbenchAction={label:'查看来源详情',description:'打开来源详情核对准备条件；不会自动测试连接、发布或修改配置。',target:{path:`/admin/data-sources/${id}`},capability:'datasource:view'}
  if (!readiness) return {...fallback,description:'准备情况待确认，不沿用旧快照或推断已就绪。可到来源详情重新核对。'}
  const reason=readiness.blockReasons[0]
  switch(reason?.code){
    case 'DATASOURCE_DISABLED':case 'CONNECTION_NOT_HEALTHY':return {...fallback,label:'核对连接详情',description:'先在来源详情核对连接和启用条件；不会在工作台执行修改。'}
    case 'SNAPSHOT_NOT_PUBLISHED':return {label:'核对元数据版本',description:'先核对已采集结构与治理依据，再到版本专页决定审核和发布。',target:{path:'/admin/releases',query:{datasourceId:String(id)}},capability:'metadata:release:view'}
    case 'BLOCKING_GOVERNANCE_ISSUES':return {label:'核对治理问题',description:'进入已发布快照的问题工作区核对；目标默认全部状态，不冒充只展示这些阻断问题。',target:{path:'/admin/governance/issues',query:{datasourceId:String(id),...(positiveId(readiness.publishedSnapshotId)?{snapshotId:String(readiness.publishedSnapshotId)}:{})}},capability:'governance:issue:view',unavailable:positiveId(readiness.publishedSnapshotId)?undefined:'缺少精确的已发布快照，暂不能进入该范围的问题。'}
    case 'QUERY_PERMISSION_NOT_CONFIGURED':return {label:'核对授权配置',description:'到授权工作区核对现有配置；查看准备回执不会获得任何业务数据权。',target:{path:'/admin/access/iam',query:{datasourceId:String(id)}},globalAny:['security:permission:view','security:mask:view','security:effective:view']}
  }
  if(!reason && (!readiness.knowledgeReady || readiness.ragStale))return {label:'查看知识准备',description:'后台基础条件与知识索引分别核对，可到知识工作区查看发布文档和显式构建记录。',target:{path:'/admin/semantics/knowledge',query:{datasourceId:String(id),tab:'index'}},capability:'knowledge:view'}
  return fallback
}
export function workbenchCondition(r: DatasourceReadiness | undefined, key: 'connection'|'metadata'|'governance'|'knowledge'|'permission') {
  if(!r)return {text:'待确认',tone:'unknown'}
  switch(key){
    case 'connection':return {text:r.connectionReady?'已连接':'待检查',tone:r.connectionReady?'good':'attention'}
    case 'metadata':return {text:r.metadataReady?'已发布':positiveId(r.latestCollectedSnapshotId)?'待发布':'未采集',tone:r.metadataReady?'good':'attention'}
    case 'governance':return {text:!positiveId(r.publishedSnapshotId)?'无发布版':r.governanceReady?'无高危阻断':'有阻断',tone:!positiveId(r.publishedSnapshotId)?'unknown':r.governanceReady?'good':'attention'}
    case 'knowledge':return {text:r.knowledgeReady?'有活动索引':'无活动索引',tone:r.knowledgeReady?'good':'unknown'}
    case 'permission':return {text:r.permissionReady?'已配置':'未配置',tone:r.permissionReady?'good':'attention'}
  }
}
