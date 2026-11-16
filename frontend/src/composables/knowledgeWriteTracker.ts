import { ref } from 'vue'
/** Unknown operations survive workspace navigation in memory, never persisted with credentials. */
export const pendingKnowledgeWrites = ref<{identity:string;source:number;docId?:number;snapshotId?:number;version?:number;kind:string;started:number}[]>([])
export async function trackKnowledgeWrite<T>(record: typeof pendingKnowledgeWrites.value[number], write:()=>Promise<T>):Promise<T> {
  if(pendingKnowledgeWrites.value.some(x=>x.identity===record.identity && x.source===record.source && x.kind===record.kind && (record.kind==='generate-group' || record.kind==='rag-build' ? x.snapshotId===record.snapshotId : x.docId===record.docId))) throw new Error('此目标仍有执行中或结果未知的同类操作，请先核对，不能重复发起')
  pendingKnowledgeWrites.value.push(record)
  const remove=()=>{pendingKnowledgeWrites.value=pendingKnowledgeWrites.value.filter(x=>!(x.identity===record.identity && x.source===record.source && x.started===record.started && x.kind===record.kind && x.docId===record.docId && x.snapshotId===record.snapshotId))}
  try {const result=await write();remove();return result}
  catch(cause) {const status=(cause as {response?:{status?:number}})?.response?.status;if(status && status<500 && status!==408) remove();throw cause}
}
