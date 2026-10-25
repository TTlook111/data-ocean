<script setup lang="ts">
import { computed,onBeforeUnmount,ref,watch } from 'vue'
import { useRoute } from 'vue-router'
import { useAdminContextStore } from '../../stores/adminContext'
import { useIamS1Store } from '../../stores/iamS1'
import { listSnapshotTables,listSnapshotTableColumns,type TableMetaItem } from '../../api/admin/governance'
import { currentUsageProfile,usageEvidence,type UsageProfile,type UsageEvidence } from '../../api/admin/governanceWorkflow'
const context=useAdminContextStore(),iam=useIamS1Store(),route=useRoute()
const tables=ref<TableMetaItem[]>([]),selectedTable=ref(''),rows=ref<UsageProfile[]>([]),loading=ref(false),error=ref('')
const evidenceTarget=ref<UsageProfile>(),evidenceRows=ref<UsageEvidence[]>([]),evidenceTotal=ref(0),evidencePage=ref(1),evidenceLoading=ref(false),evidenceError=ref('')
const ready=computed(()=>!!context.snapshotId&&!!context.datasourceId&&(!route.query.datasourceId||Number(route.query.datasourceId)===context.datasourceId)&&(!route.query.snapshotId||Number(route.query.snapshotId)===context.snapshotId))
const canView=computed(()=>ready.value&&iam.canOnDatasource('governance:field:view',context.datasourceId))
let epoch=0,disposed=false,evidenceRequest=0
async function loadTables(){const ticket=++epoch,snapshot=context.snapshotId;tables.value=[];rows.value=[];selectedTable.value='';error.value='';evidenceTarget.value=undefined;evidenceRequest++;if(!canView.value||!snapshot)return;loading.value=true
  try{const response=await listSnapshotTables(snapshot);if(!disposed&&ticket===epoch)tables.value=response.data}
  catch{if(!disposed&&ticket===epoch)error.value='表目录读取失败，请重试。'}finally{if(!disposed&&ticket===epoch)loading.value=false}
}
async function load(){const ticket=++epoch,snapshot=context.snapshotId,name=selectedTable.value;rows.value=[];error.value='';evidenceTarget.value=undefined;evidenceRequest++;if(!canView.value||!snapshot||!name)return;loading.value=true
  try{const columns=await listSnapshotTableColumns(snapshot,name);if(disposed||ticket!==epoch)return
    // 有界并发读取当前档案，部分失败不伪装为零分。
    const profiles:UsageProfile[]=[];let failures=0
    for(let start=0;start<columns.data.length;start+=8){
      const results=await Promise.allSettled(columns.data.slice(start,start+8).map(column=>currentUsageProfile(snapshot,column.id)))
      if(disposed||ticket!==epoch||snapshot!==context.snapshotId)return
      for(const result of results)if(result.status==='fulfilled')profiles.push(result.value.data);else failures++
    }
    rows.value=profiles;if(failures)error.value=`有 ${failures} 个字段档案读取失败，已读取 ${profiles.length} 项；请重试。`
  }catch{if(!disposed&&ticket===epoch)error.value='字段档案读取失败，请重试。'}finally{if(!disposed&&ticket===epoch)loading.value=false}
}
function openEvidence(row:UsageProfile){evidenceTarget.value=row;evidencePage.value=1;loadEvidence()}
function closeEvidence(visible:boolean){if(!visible){evidenceTarget.value=undefined;evidenceRequest++}}
async function loadEvidence(){const target=evidenceTarget.value,snapshot=context.snapshotId,ticket=++evidenceRequest;evidenceRows.value=[];evidenceTotal.value=0;evidenceError.value='';if(!target||!snapshot||!canView.value)return;evidenceLoading.value=true
  try{const result=await usageEvidence(snapshot,target.columnMetaId,evidencePage.value);if(disposed||ticket!==evidenceRequest||snapshot!==context.snapshotId)return;evidenceRows.value=result.data.records;evidenceTotal.value=result.data.total}
  catch{if(!disposed&&ticket===evidenceRequest)evidenceError.value='使用证据读取失败，请重试。'}finally{if(!disposed&&ticket===evidenceRequest)evidenceLoading.value=false}
}
watch(()=>[context.snapshotId,context.datasourceId,canView.value],loadTables,{immediate:true})
onBeforeUnmount(()=>{disposed=true;epoch++})
</script>
<template><section class="usage-profiles">
  <div class="usage-heading"><div><h3>字段使用置信度</h3><p>业务标签独立维护；参考分仅供试用，来自当前版本已审核的 SQL 样例；相同 SQL 合并计算，不代表业务正确概率。</p></div><el-button :disabled="!canView||loading" @click="selectedTable?load():loadTables()">刷新</el-button></div>
  <p v-if="!ready">请先选择数据源与快照。</p><p v-else-if="!canView">没有当前范围的字段查看权限。</p>
  <template v-else><el-select v-model="selectedTable" placeholder="选择要查看的表" filterable :disabled="loading" @change="load"><el-option v-for="table in tables" :key="table.id" :label="table.tableComment?`${table.tableName} · ${table.tableComment}`:table.tableName" :value="table.tableName" /></el-select>
    <p v-if="error" role="alert">{{error}}</p>
    <el-table :data="rows" v-loading="loading" :empty-text="selectedTable?'当前表暂无可读取的字段档案。':'选择表后查看字段标签、审核情况和使用证据。'">
      <el-table-column prop="fieldName" label="字段" min-width="220" />
      <el-table-column label="业务标签" min-width="140"><template #default="{row}">{{row.tags.join('、')||'尚未分类'}}</template></el-table-column>
      <el-table-column label="问数知识资格" width="160"><template #default="{row}">{{row.reviewStatus==='APPROVED'?'当前修订可用':'来源或修订待确认'}}</template></el-table-column>
      <el-table-column label="使用参考分" min-width="150"><template #default="{row}"><strong>{{row.reviewStatus!=='APPROVED'?'资格待确认':row.score==null?'证据不足':row.score}}</strong></template></el-table-column>
      <el-table-column label="有效使用证据" width="160"><template #default="{row}">{{row.evidenceCount}} 条<span v-if="row.evidenceCount"> · 正向 {{row.positiveCount}} / 负向 {{row.negativeCount}}</span></template></el-table-column>
      <el-table-column label="操作" width="120"><template #default="{row}"><el-button link type="primary" :disabled="!row.revisionId" @click="openEvidence(row)">证据明细</el-button></template></el-table-column>
    </el-table>
  </template>
  <el-dialog :model-value="!!evidenceTarget" :title="evidenceTarget?.fieldName||'使用证据'" width="min(820px,94vw)" @update:model-value="closeEvidence">
    <p>历史包含已撤回或替换的意见，当前分数只计入有效且去重的贡献。</p>
    <p v-if="evidenceError" role="alert">{{evidenceError}}<el-button @click="loadEvidence">重试</el-button></p>
    <el-table v-else :data="evidenceRows" v-loading="evidenceLoading" empty-text="当前修订尚无已确认使用证据。">
      <el-table-column label="时间" min-width="170"><template #default="{row}">{{row.createdAt?.replace('T',' ')}}</template></el-table-column>
      <el-table-column label="评价" width="90"><template #default="{row}">{{row.direction==='LIKE'?'认可':'有问题'}}</template></el-table-column>
      <el-table-column label="当前贡献" min-width="170"><template #default="{row}">{{row.contributionStatus==='LEGACY_POLICY'?'旧政策，仅供追溯':row.contributionStatus==='NOT_QUALIFIED'?'当前知识资格待确认':row.contributes?'参与当前分数':row.active?'同源较新意见已替代':'已撤回或替换'}}</template></el-table-column>
      <el-table-column prop="note" label="确认依据" min-width="230" />
    </el-table>
    <el-pagination v-if="evidenceTotal>20" v-model:current-page="evidencePage" :total="evidenceTotal" :page-size="20" layout="prev,pager,next,total" @current-change="loadEvidence" />
    <template #footer><el-button @click="closeEvidence(false)">关闭</el-button></template>
  </el-dialog>
</section></template>
<style scoped>.usage-profiles{display:grid;gap:16px;min-width:0}.usage-heading{display:flex;flex-wrap:wrap;align-items:center;justify-content:space-between;gap:12px}.usage-heading h3{margin:0}.usage-heading p{font-size:13px;color:var(--do-ink-muted)}.usage-profiles>.el-select{max-width:420px}</style>
