<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { ArrowLeft, KeyRound } from 'lucide-vue-next'
import { changePassword } from '../../api/auth'
import { useAuthStore } from '../../stores/auth'

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()
const formRef = ref<FormInstance>()
const loading = ref(false)

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

async function handleSubmit() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  loading.value = true
  try {
    await changePassword({
      oldPassword: form.oldPassword,
      newPassword: form.newPassword,
    })
    ElMessage.success('密码修改成功，请重新登录')
    await authStore.logout()
    router.push('/login')
  } catch (err: unknown) {
    const msg =
      typeof err === 'object' && err !== null && 'response' in err
        ? (err as { response?: { data?: { message?: string } } }).response?.data?.message
        : undefined
    ElMessage.error(msg || '密码修改失败')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <main class="change-pwd-page post-login-page">
    <RouterLink v-if="!isForced" class="top-back-link" to="/query">
      <ArrowLeft :size="16" />
      返回问答
    </RouterLink>

    <section class="pwd-panel">
      <header class="panel-header">
        <span class="panel-icon">
          <KeyRound :size="22" />
        </span>
        <div>
          <h2>修改密码</h2>
          <p v-if="isForced" class="forced-tip">首次登录请修改初始密码后继续使用系统</p>
          <p v-else class="subtitle">定期修改密码有助于保障账号安全。修改成功后需重新登录。</p>
        </div>
      </header>

      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-width="96px"
        class="pwd-form"
        @submit.prevent="handleSubmit"
      >
        <el-form-item label="当前密码" prop="oldPassword">
          <el-input v-model="form.oldPassword" type="password" show-password placeholder="请输入当前密码" />
        </el-form-item>
        <el-form-item label="新密码" prop="newPassword">
          <el-input v-model="form.newPassword" type="password" show-password placeholder="8-32位，至少包含字母和数字" />
        </el-form-item>
        <el-form-item label="确认密码" prop="confirmPassword">
          <el-input v-model="form.confirmPassword" type="password" show-password placeholder="请再次输入新密码" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" @click="handleSubmit">确认修改</el-button>
          <RouterLink v-if="!isForced" class="back-link" to="/profile">返回个人资料</RouterLink>
        </el-form-item>
      </el-form>
    </section>
  </main>
</template>

<style scoped>
.change-pwd-page {
  min-height: 100vh;
  display: grid;
  align-content: start;
  justify-items: center;
  gap: 18px;
  padding: 32px 24px;
  background:
    linear-gradient(180deg, rgba(189, 232, 248, 0.44) 0, rgba(245, 251, 239, 0.76) 300px, var(--do-bg) 100%),
    var(--do-bg);
}

.top-back-link {
  width: min(520px, 100%);
  height: 36px;
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 0 12px;
  border: 1px solid var(--do-line);
  border-radius: 8px;
  color: var(--do-primary-strong);
  background: var(--do-surface);
  font-size: 13px;
  font-weight: 900;
}

.pwd-panel {
  width: min(520px, 100%);
  padding: 28px;
  border: 1px solid var(--do-line);
  border-radius: var(--do-radius-lg);
  background: var(--do-surface);
  box-shadow: var(--do-shadow);
}

.panel-header {
  display: grid;
  grid-template-columns: 48px 1fr;
  gap: 14px;
  align-items: center;
  margin-bottom: 24px;
  padding-bottom: 18px;
  border-bottom: 1px solid var(--do-line);
}

.panel-icon {
  width: 48px;
  height: 48px;
  display: grid;
  place-items: center;
  border-radius: 10px;
  color: var(--do-primary);
  background: var(--do-primary-soft);
}

.panel-header h2 {
  margin: 0;
  font-size: 20px;
  color: var(--do-ink);
}

.subtitle {
  margin: 4px 0 0;
  color: var(--do-muted);
  font-size: 13px;
}

.forced-tip {
  margin: 4px 0 0;
  color: var(--do-warning, #c08a24);
  font-size: 13px;
  font-weight: 700;
}

.pwd-form {
  max-width: 420px;
}

.back-link {
  margin-left: 12px;
  color: var(--do-primary);
  font-size: 13px;
  font-weight: 700;
}
</style>
