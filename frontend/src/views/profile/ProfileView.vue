<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { ArrowLeft, Settings } from 'lucide-vue-next'
import { me, updateProfile } from '../../api/auth'
import { useAuthStore } from '../../stores/auth'
import { useIamS1Store } from '../../stores/iamS1'
import AccountPage from '../../components/common/AccountPage.vue'
import ErrorState from '../../components/common/ErrorState.vue'
import LoadingState from '../../components/common/LoadingState.vue'
import GovernanceWriteNotice from '../../components/admin/GovernanceWriteNotice.vue'
import { trackGovernanceWrite } from '../../composables/governanceWriteTracker'
const auth = useAuthStore(),
  iamS1 = useIamS1Store()
const loading = ref(false),
  saving = ref(false),
  loaded = ref(false),
  error = ref(''),
  saveError = ref(''),
  notice = ref(''),
  baseline = ref('')
const formRef = ref<FormInstance>()
const form = reactive({ username: '', realName: '', email: '', phone: '' })
const draft = () => JSON.stringify([form.realName, form.email, form.phone])
const dirty = computed(() => loaded.value && baseline.value !== draft())
const cancelClass=`profile-confirm-${Math.random().toString(36).slice(2)}`
const cancelOwn=()=>document.querySelector<HTMLButtonElement>(`.${cancelClass}`)?.click()
let alive = true,
  generation = 0,
  read = 0,
  edit = 0
watch(
  () => [form.realName, form.email, form.phone],
  () => {
    edit++
  },
  { flush: 'sync' },
)
const rules: FormRules = {
  realName: [
    { required: true, message: '请输入真实姓名', trigger: 'blur' },
    { min: 2, max: 50, message: '真实姓名需为2-50位', trigger: 'blur' },
  ],
  email: [{ type: 'email', message: '邮箱格式不正确', trigger: 'blur' }],
}

const roleText = computed(() =>
  !iamS1.loaded || iamS1.errorMessage
    ? '账号能力尚未确认'
    : iamS1.systemAdmin
      ? '系统管理员 · 业务数据另行授权'
      : '功能、负责源与业务数据分别授权',
)
const canEnterAdmin = computed(() => iamS1.loaded && !iamS1.errorMessage && iamS1.hasAnyAdminCapability)
const canQuery = computed(() => iamS1.loaded && !iamS1.errorMessage && iamS1.queryUse)
async function fetchProfile() {
  if (!auth.token || loading.value || saving.value) return
  const token = auth.token,
    epoch = generation,
    request = ++read,
    edits = edit,
    expectedUserId = auth.user?.token === token ? auth.user.userId : undefined
  const current = () => alive && epoch === generation && auth.token === token && request === read
  loading.value = true
  error.value = ''
  notice.value = ''
  try {
    const result = await me()
    if (!current()) return
    if (expectedUserId && result.data?.id !== expectedUserId) throw new Error('本人资料错属其他账号，未应用')
    if (!result.data?.id || !result.data.username) throw new Error('本人资料回执不完整，未应用')
    if (edit !== edits || dirty.value) {
      notice.value = '当前人工编辑已保留，重读未覆盖输入。'
      return
    }
    Object.assign(form, {
      username: result.data.username,
      realName: result.data.realName || '',
      email: result.data.email || '',
      phone: result.data.phone || '',
    })
    baseline.value = draft()
    loaded.value = true
  } catch (cause) {
    if (current())
      error.value =
        (cause as { response?: { data?: { message?: string } } })?.response?.data?.message ||
        (cause instanceof Error ? cause.message : '个人资料读取失败')
  } finally {
    if (current()) loading.value = false
  }
}
async function discardAndReload() {
  if(loading.value || saving.value)return
  const token=auth.token,epoch=generation
  try {
    await ElMessageBox.confirm('放弃当前账号尚未保存的资料输入，并重新读取服务端本人资料？','放弃草稿并重读',{closeOnClickModal:false,cancelButtonClass:cancelClass})
    if(!alive || auth.token!==token || epoch!==generation)return
    loaded.value=false;Object.assign(form,{username:'',realName:'',email:'',phone:''});baseline.value='';notice.value=''
    await fetchProfile()
  }catch{}
}
async function saveProfile() {
  if (!loaded.value || loading.value || saving.value || !auth.token) return
  const token = auth.token,
    epoch = generation
  const current = () => alive && epoch === generation && auth.token === token
  saving.value = true
  saveError.value = ''
  notice.value = ''
  const payload = { realName: form.realName, email: form.email, phone: form.phone },
    submitted = draft()
  try {
    const valid = await formRef.value?.validate().catch(() => false)
    if (!valid || !current()) return
    if (draft() !== submitted) {
      saveError.value = '验证期间有新编辑，未提交；请核对后重试。'
      return
    }
    await trackGovernanceWrite(token, ['account:profile'], '更新本人资料', async () => {
      const result = await updateProfile(payload)
      if (result?.code !== 200) throw new Error('保存回执不完整，结果未知；请先重读核对')
      return result
    })
    if (!current()) return
    baseline.value = submitted
    notice.value = draft() === submitted ? '个人资料已更新。' : '前一稿已保存，当前新输入尚未保存。'
    await auth.fetchUserInfo()
  } catch (cause) {
    if (current())
      saveError.value =
        (cause as { response?: { data?: { message?: string } } })?.response?.data?.message ||
        (cause instanceof Error ? cause.message : '保存失败，输入已保留；结果未知请先核对')
  } finally {
    if (current()) saving.value = false
  }
}
watch(
  () => auth.token,
  () => {
    cancelOwn()
    generation++
    read++
    loaded.value = false
    loading.value = false
    saving.value = false
    error.value = ''
    saveError.value = ''
    notice.value = ''
    Object.assign(form, { username: '', realName: '', email: '', phone: '' })
    baseline.value = ''
    if (auth.token) void fetchProfile()
  },
  { immediate: true, flush: 'sync' },
)
onMounted(() => {
  void iamS1.load()
})
onBeforeUnmount(() => {
  cancelOwn()
  alive = false
  generation++
  read++
  Object.assign(form, { username: '', realName: '', email: '', phone: '' })
})
</script>
<template>
  <AccountPage
    title="个人资料"
    description="维护你自己的联系信息。角色、负责数据源和业务数据授权由各自的管理工作区维护。"
  >
    <template #navigation>
      <RouterLink v-if="canQuery" to="/query">
        <ArrowLeft :size="16" />
        返回问答
      </RouterLink>
      <RouterLink v-if="canEnterAdmin" to="/admin">
        <Settings :size="16" />
        进入有权后台
      </RouterLink>
      <RouterLink to="/change-password">修改密码</RouterLink>
    </template>
    <p class="profile-role">{{ roleText }}</p>
    <GovernanceWriteNotice />
    <ErrorState v-if="error" :message="error" @retry="fetchProfile" />
    <LoadingState v-if="loading && !loaded" text="正在读取本人资料…" />
    <div :hidden="!loaded" class="profile-loaded">
      <p v-if="saveError" class="account-message account-error" role="alert">{{ saveError }}</p>
      <p v-if="notice" class="account-message" role="status">{{ notice }}</p>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px" @submit.prevent="saveProfile">
        <el-form-item label="登录账号"><el-input :model-value="form.username" disabled /></el-form-item>
        <el-form-item label="真实姓名" prop="realName">
          <el-input v-model="form.realName" autocomplete="name" :disabled="saving || !loaded" />
        </el-form-item>
        <el-form-item label="邮箱" prop="email">
          <el-input
            v-model="form.email"
            autocomplete="email"
            placeholder="选填"
            :disabled="saving || !loaded"
          />
        </el-form-item>
        <el-form-item label="手机号">
          <el-input
            v-model="form.phone"
            autocomplete="tel"
            placeholder="选填"
            :disabled="saving || !loaded"
          />
        </el-form-item>
        <el-form-item>
          <div class="form-actions">
            <el-button type="primary" :loading="saving" @click="saveProfile">保存修改</el-button>
            <el-button :loading="loading" :disabled="saving || !loaded" @click="fetchProfile">
              重读资料
            </el-button>
            <el-button v-if="dirty || saveError" :disabled="saving || loading" @click="discardAndReload">放弃草稿并重读</el-button>
                <span v-if="dirty" class="profile-dirty">有尚未保存的修改</span>
          </div>
        </el-form-item>
      </el-form>
    </div>
  </AccountPage>
</template>
<style scoped>
.profile-role {
  margin: 0 0 24px;
  color: var(--do-muted);
  font-size: 12px;
}
.profile-dirty {
  font-size: 12px;
  color: var(--do-primary);
}
</style>
