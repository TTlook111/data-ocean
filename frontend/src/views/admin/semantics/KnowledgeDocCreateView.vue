<script setup lang="ts">
/**
 * 手工新建知识文档
 *
 * 手工创建的文档没有来源快照和覆盖表，需要作者自行维护内容；
 * 基于快照生成请回到知识列表使用「生成快照目录」。
 */
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Save } from 'lucide-vue-next'
import { createKnowledgeDoc } from '../../../api/admin/knowledge'
import { useAdminContextStore, type AdminDatasourceOption } from '../../../stores/adminContext'
import { useAuthStore } from '../../../stores/auth'
import { pendingKnowledgeWrites,trackKnowledgeWrite } from '../../../composables/knowledgeWriteTracker'
import { useIamS1Store } from '../../../stores/iamS1'
import TaskPageHeader from '../../../components/admin/TaskPageHeader.vue'
import BusinessStatusBadge from '../../../components/admin/BusinessStatusBadge.vue'
import LoadingState from '../../../components/common/LoadingState.vue'
import ErrorState from '../../../components/common/ErrorState.vue'
import EmptyState from '../../../components/common/EmptyState.vue'

const router = useRouter()
const context = useAdminContextStore()
const iamS1 = useIamS1Store(),auth=useAuthStore()

/**
 * 手工新建文档走 `knowledge:manage`，后端按请求里的 datasourceId 经 DATASOURCE 解析器复核真实归属。
 * 这里按「功能 + 目标数据源同一绑定」过滤可选数据源，避免下拉里出现后端一定拒绝的源。
 */
const selectableDatasources = computed(() => {
  const allowed = new Set(iamS1.datasourcesWithFunction('knowledge:manage'))
  if (iamS1.systemAdmin) return datasources.value
  return datasources.value.filter((item) => allowed.has(item.id))
})
const canManage = computed(
  () => iamS1.systemAdmin || iamS1.datasourcesWithFunction('knowledge:manage').length > 0,
)
const canEnterDatasource = computed(() => iamS1.loaded && !iamS1.errorMessage && (iamS1.hasGlobal('datasource:view') || iamS1.datasourcesWithFunction('datasource:view').length > 0))
const MANAGE_HINT = '没有“维护知识文档”能力：需要角色拥有 knowledge:manage 并负责目标数据源。'

const datasources = ref<AdminDatasourceOption[]>([])
const loadingDatasources = ref(true)
/** 数据源加载失败的原因；此前 catch 静默吞掉，页面表现为「空下拉且无任何提示」 */
const datasourcesError = ref('')
const saving = ref(false)
const cancelling = ref(false)
const form = reactive({ datasourceId: undefined as number | undefined, title: '', content: '' })
let epoch=0,disposed=false,initialized=false,requestId=0,createdStamp=''
const actionError=ref(''),createdId=ref<number>(),createdSource=ref<number>()
const unknownWrite=computed(()=>pendingKnowledgeWrites.value.find(x=>x.identity===auth.token && x.source===form.datasourceId && x.kind==='create'))
const cancelClass=`knowledge-create-cancel-${Math.random().toString(36).slice(2)}`

/** 取消只离开当前表单，不撤销已发出的创建请求；写入期间保持入口禁用。 */
async function cancelCreate() {
  if(saving.value || cancelling.value)return
  const generation=epoch,identity=auth.token,source=form.datasourceId
  cancelling.value=true
  try {
    if(JSON.stringify(form)!==createdStamp && (form.title.trim() || form.content.trim())) {
      await ElMessageBox.confirm('离开后，当前未保存的标题与正文将丢失。确认取消创建？','取消创建',{
        type:'warning',confirmButtonText:'放弃并返回',cancelButtonText:'继续编辑',
        closeOnClickModal:false,cancelButtonClass:cancelClass,
      })
    }
    if(disposed || generation!==epoch || identity!==auth.token)return
    await router.push({name:'admin-semantic-knowledge',query:source ? {datasourceId:source} : {}})
  } catch {
    // 关闭确认框即继续编辑，保留全部输入。
  } finally {
    if(!disposed && generation===epoch)cancelling.value=false
  }
}


function apiError(cause: unknown, fallback: string) {
  const message = (cause as { response?: { data?: { message?: string } } })?.response?.data?.message
  return message || (cause instanceof Error ? cause.message : fallback)
}

async function save(){
  const source=form.datasourceId
  if(saving.value || unknownWrite.value || !source || !iamS1.loaded || (!iamS1.systemAdmin && !iamS1.datasourcesWithFunction('knowledge:manage').includes(source))){if(!source)ElMessage.warning('请选择数据源');return}
  if(!form.title.trim()){ElMessage.warning('请填写文档标题');return}
  // 冻结本次来源与内容；等待期间的新输入留在表单，回执不能替它们导航或重发。
  const generation=epoch,identity=auth.token,scope=context.datasourceId,payload={datasourceId:source,title:form.title.trim(),content:form.content},stamp=JSON.stringify(form)
  saving.value=true;actionError.value='';createdId.value=undefined
  try{
    const result=await trackKnowledgeWrite({identity,source,kind:'create',started:Date.now()},()=>createKnowledgeDoc(payload))
    if(disposed || generation!==epoch || identity!==auth.token || scope!==context.datasourceId)return
    const id=result.data?.id
    if(!id)throw new Error('创建已响应但未返回文档ID，请核对文档列表，不要重复创建')
    createdId.value=id;createdSource.value=source;createdStamp=stamp
    if(stamp!==JSON.stringify(form)){actionError.value='原草稿已创建；等待期间新增的输入已保留，请先核对刚创建文档。';return}
    if(!iamS1.systemAdmin && !iamS1.datasourcesWithFunction('knowledge:view').includes(source)){actionError.value='文档已创建，当前账号没有该源的查看权；请交接给有权人员继续审核。';return}
    context.selectDatasource(source);context.selectKnowledgeDoc(id);await router.replace({name:'admin-semantic-knowledge-detail',params:{id},query:{datasourceId:source,tab:'content'}})
  }catch(cause){if(!disposed && generation===epoch && identity===auth.token){
    const status=(cause as {response?:{status?:number}})?.response?.status
    console.warn('[知识文档创建未完成]',{stage:'DOCUMENT_CREATE',datasourceId:source,httpStatus:Number.isInteger(status)?status:null})
    actionError.value=apiError(cause,'创建未完成')+'；输入保留，结果未知先核对列表，不会自动重发。'
  }}
  finally{if(!disposed && generation===epoch)saving.value=false}
}

async function loadDatasources() {
  const seq=++requestId,identity=auth.token
  loadingDatasources.value = true
  datasourcesError.value = ''
  try {
    await context.initialize()
    if(disposed || seq!==requestId || identity!==auth.token)return
    datasources.value = context.datasources
    if(!form.datasourceId)form.datasourceId = selectableDatasources.value.some(x=>x.id===context.datasourceId) ? context.datasourceId : undefined
  } catch (cause) {
    if(disposed || seq!==requestId || identity!==auth.token)return
    datasources.value = []
    // 必须显式呈现失败：静默置空会让用户以为「系统里没有数据源」（§11.3、§18）
    datasourcesError.value = apiError(cause, '数据源加载失败')
  } finally {
    if(disposed || seq!==requestId || identity!==auth.token)return
    loadingDatasources.value = false
  }
}

onMounted(async()=>{await iamS1.load();if(disposed)return;initialized=true;await loadDatasources()})
watch(()=>[auth.token,iamS1.snapshot],()=>{document.querySelector<HTMLButtonElement>(`.${cancelClass}`)?.click();cancelling.value=false;epoch++;requestId++;form.title='';form.content='';form.datasourceId=undefined;saving.value=false;createdId.value=undefined;createdStamp='';actionError.value='';if(initialized)void loadDatasources()},{flush:'sync'})
onBeforeUnmount(()=>{disposed=true;epoch++;requestId++;document.querySelector<HTMLButtonElement>(`.${cancelClass}`)?.click()})
</script>

<template>
  <div class="admin-page knowledge-create-page">
    <!-- 创建页以任务标题定位，保留手工内容与来源覆盖说明，避免重复上级分类。 -->
    <TaskPageHeader
      title="新建知识文档"
      description="手工创建的文档需要自己维护内容与覆盖范围。基于已发布快照生成草稿会更省事，且能带上来源信息。"
    >
      <template #actions>
        <el-button :disabled="saving || cancelling" @click="cancelCreate">取消</el-button>
        <el-button type="primary" :icon="Save" :loading="saving" :disabled="!canManage || !!unknownWrite" :title="canManage ? '' : MANAGE_HINT" @click="save">创建文档</el-button>
      </template>
    </TaskPageHeader>

    <section v-if="actionError || unknownWrite" role="alert" class="knowledge-create-page__hint">{{ actionError || '此数据源创建请求执行中或结果待确认，请先核对文档列表。' }}<RouterLink v-if="createdId && createdSource && (iamS1.systemAdmin || iamS1.datasourcesWithFunction('knowledge:view').includes(createdSource))" :to="{name:'admin-semantic-knowledge-detail',params:{id:createdId},query:{datasourceId:createdSource}}">查看刚创建的文档 #{{ createdId }}</RouterLink></section>
    <ErrorState v-if="datasourcesError" :message="datasourcesError" @retry="loadDatasources" />
    <LoadingState v-else-if="loadingDatasources" variant="skeleton" :rows="4" />
    <EmptyState
      v-else-if="!selectableDatasources.length"
      :message="canManage
        ? '暂无可维护的数据源，请先接入数据源或核对负责范围。'
        : MANAGE_HINT"
      :action-text="canEnterDatasource ? '去数据源接入' : undefined"
      @action="router.push('/admin/data-sources')"
    />

    <section v-else class="knowledge-create-page__card">
      <div class="knowledge-create-page__mode"><BusinessStatusBadge status="DRAFT" /><span>手工文档 · 未绑定来源快照</span></div>
      <el-form label-width="96px">
        <el-form-item label="数据源" required>
          <el-select v-model="form.datasourceId" aria-label="知识文档数据源" placeholder="选择数据源" style="width: 100%; max-width:480px">
            <el-option v-for="item in selectableDatasources" :key="item.id" :label="item.name" :value="item.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="文档标题" required>
          <el-input v-model="form.title" aria-label="文档标题" aria-required="true" placeholder="例如：订单域业务语义" style="width: 100%; max-width:720px" />
        </el-form-item>
        <el-form-item label="初始内容">
          <el-input
            v-model="form.content"
            type="textarea"
            :rows="18"
            aria-label="知识文档初始内容"
            placeholder="可留空，支持 Markdown"
          />
        </el-form-item>
      </el-form>
      <p class="knowledge-create-page__hint">
        手工文档发布后不会自动构建索引；构建仍需满足来源与覆盖校验。
      </p>
    </section>
  </div>
</template>

<style scoped>
.knowledge-create-page { display: grid; gap: 16px; }
.knowledge-create-page__card {
  padding: 18px;
  border: 1px solid var(--do-line);
  border-radius: var(--do-radius-lg);
  background: var(--do-surface);
  box-shadow: var(--do-shadow);
}
.knowledge-create-page__hint { margin: 0; color: var(--do-muted); font-size: 12px; line-height: 1.7; }
.knowledge-create-page__mode { display:flex; align-items:center; flex-wrap:wrap; gap:8px; margin-bottom:18px; color:var(--do-muted); font-size:12px; }
</style>
