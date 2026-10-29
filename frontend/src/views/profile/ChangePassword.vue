<script setup lang="ts">
import { computed, reactive, ref, watch, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { ArrowLeft } from 'lucide-vue-next'
import { changePassword } from '../../api/auth'
import AccountPage from '../../components/common/AccountPage.vue'
import GovernanceWriteNotice from '../../components/admin/GovernanceWriteNotice.vue'
import { trackGovernanceWrite } from '../../composables/governanceWriteTracker'
import { useAuthStore } from '../../stores/auth'

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()
const formRef = ref<FormInstance>()
const loading = ref(false)
const error = ref('')
const exiting = ref(false)
let alive = true,
  generation = 0
function clearSecrets() {
  form.oldPassword = ''
  form.newPassword = ''
  form.confirmPassword = ''
}
watch(
  () => authStore.token,
  () => {
    generation++
    loading.value = false
    exiting.value = false
    error.value = ''
    clearSecrets()
  },
  { flush: 'sync' },
)
onBeforeUnmount(() => {
  alive = false
  generation++
  clearSecrets()
})

const isForced = computed(() => route.query.forced === '1')

const form = reactive({
  oldPassword: '',
  newPassword: '',
  confirmPassword: '',
})

const rules: FormRules = {
  oldPassword: [{ required: true, message: '请输入旧密码', trigger: 'blur' }],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    {
      pattern: /^(?=.*[a-zA-Z])(?=.*\d).{8,32}$/,
      message: '密码需为8-32位且至少包含字母和数字',
      trigger: 'blur',
    },
  ],
  confirmPassword: [
    { required: true, message: '请再次输入新密码', trigger: 'blur' },
    {
      validator: (_rule, value, callback) => {
        if (value !== form.newPassword) {
          callback(new Error('两次输入的密码不一致'))
        } else {
          callback()
        }
      },
      trigger: 'blur',
    },
  ],
}

async function exitAndLogin() {
  if(exiting.value)return
  const token=authStore.token
  exiting.value=true;generation++;clearSecrets()
  try { await authStore.logout(); if(alive && !authStore.token) await router.replace('/login') }
  finally { if(alive && authStore.token===token)exiting.value=false }
}
async function handleSubmit() {
  if (loading.value || exiting.value || !authStore.token) return
  const token = authStore.token,
    epoch = generation
  const current = () => alive && generation === epoch && authStore.token === token
  loading.value = true
  error.value = ''
  try {
    const valid = await formRef.value?.validate().catch(() => false)
    if (!valid || !current()) return
    if (!form.oldPassword) {
      error.value = '请输入旧密码'
      return
    }
    if (!/^(?=.*[a-zA-Z])(?=.*\d).{8,32}$/.test(form.newPassword)) {
      error.value = '密码需为8-32位且至少包含字母和数字'
      return
    }
    if (form.newPassword !== form.confirmPassword) {
      error.value = '两次输入的密码不一致'
      return
    }
    const payload = { oldPassword: form.oldPassword, newPassword: form.newPassword }
    await trackGovernanceWrite(token, ['account:password'], '修改当前账号密码', async () => {
      const result = await changePassword(payload)
      if (result?.code !== 200) throw new Error('修改回执不完整，结果未知；请重新登录核对，不要重复提交')
      return result
    })
    if (!current()) return
    clearSecrets()
    ElMessage.success('密码修改成功，请重新登录')
    await authStore.logout()
    if (alive && !authStore.token) await router.replace('/login')
  } catch (cause) {
    if (!current()) return
    error.value =
      (cause as { response?: { data?: { message?: string } } })?.response?.data?.message ||
      (cause instanceof Error ? cause.message : '修改失败；若结果未知，请先重新登录核对')
    clearSecrets()
  } finally {
    if (current()) loading.value = false
  }
}
</script>

<template>
  <AccountPage
    title="修改密码"
    :description="
      isForced
        ? '首次登录需要修改初始密码，完成后重新登录即可继续。'
        : '更新当前账号的登录凭据。修改成功后需要重新登录。'
    "
    :eyebrow="isForced ? '首次登录 · 完成账号设置' : '账号安全'"
  >
    <template #navigation>
      <button type="button" class="exit-account" :disabled="exiting" @click="exitAndLogin">退出并重新登录</button>
      <RouterLink v-if="!isForced" to="/profile">
        <ArrowLeft :size="16" />
        返回个人资料
      </RouterLink>
    </template>
    <GovernanceWriteNotice />
    <p v-if="error" role="alert" class="account-message account-error">{{ error }}</p>
    <el-form :key="authStore.token" ref="formRef" :model="form" :rules="rules" label-width="100px" @submit.prevent="handleSubmit">
      <el-form-item label="当前密码" prop="oldPassword">
        <el-input
          v-model="form.oldPassword"
          type="password"
          show-password
          autocomplete="current-password"
          placeholder="请输入当前密码"
          :disabled="loading || exiting"
        />
      </el-form-item>
      <el-form-item label="新密码" prop="newPassword">
        <el-input
          v-model="form.newPassword"
          type="password"
          show-password
          autocomplete="new-password"
          placeholder="8–32位，至少包含字母和数字"
          :disabled="loading || exiting"
        />
      </el-form-item>
      <el-form-item label="确认密码" prop="confirmPassword">
        <el-input
          v-model="form.confirmPassword"
          type="password"
          show-password
          autocomplete="new-password"
          placeholder="请再次输入新密码"
          :disabled="loading || exiting"
        />
      </el-form-item>
      <el-form-item>
        <div class="form-actions">
          <el-button type="primary" :loading="loading" :disabled="exiting" @click="handleSubmit">确认修改并重新登录</el-button>
          <RouterLink v-if="!isForced" to="/profile">暂不修改</RouterLink>
        </div>
      </el-form-item>
    </el-form>
    <p class="account-message">
      密码仅用于本次修改。提交失败后会清空密码输入；结果未知时，请先重新登录核对。
    </p>
  </AccountPage>
</template>

<style scoped>.exit-account{margin-left:auto;border:0;background:transparent;color:var(--do-muted);font-size:13px;cursor:pointer}.exit-account:focus-visible{outline:2px solid var(--do-primary);outline-offset:4px}</style>
