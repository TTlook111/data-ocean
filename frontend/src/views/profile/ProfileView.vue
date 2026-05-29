<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { ArrowLeft, Settings, UserRound } from 'lucide-vue-next'
import { me, updateProfile } from '../../api/auth'
import { useAuthStore } from '../../stores/auth'
import { roleCodesLabel } from '../../utils/enumLabels'

const auth = useAuthStore()
const loading = ref(false)
const saving = ref(false)
const formRef = ref<FormInstance>()
const form = reactive({
  username: '',
  realName: '',
  email: '',
  phone: '',
})

const rules: FormRules = {
  realName: [
    { required: true, message: '请输入真实姓名', trigger: 'blur' },
    { min: 2, max: 50, message: '真实姓名需为2-50位', trigger: 'blur' },
  ],
  email: [{ type: 'email', message: '邮箱格式不正确', trigger: 'blur' }],
}

const displayName = () => auth.currentUser?.realName || auth.user?.realName || form.realName || '用户'
const roleText = () => roleCodesLabel(auth.currentUser?.roles || auth.user?.roles, '—')
const canEnterAdmin = computed(() =>
  auth.hasAnyPermission([
    'admin:view',
    'datasource:manage',
    'metadata:manage',
    'skills:manage',
    'prompt:manage',
    'field:manage',
    'field-tag:manage',
    'feedback:review',
    'audit:view',
    'user:manage',
    'role:manage',
    'role:view',
    'department:manage',
  ]),
)

async function fetchProfile() {
  loading.value = true
  try {
    const result = await me()
    Object.assign(form, {
      username: result.data.username,
      realName: result.data.realName || '',
      email: result.data.email || '',
      phone: result.data.phone || '',
    })
  } catch {
    ElMessage.error('个人资料加载失败')
  } finally {
    loading.value = false
  }
}

async function saveProfile() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  saving.value = true
  try {
    await updateProfile({
      realName: form.realName,
      email: form.email,
      phone: form.phone,
    })
    ElMessage.success('个人资料已更新')
    await auth.fetchUserInfo()
  } catch {
    ElMessage.error('保存失败，请稍后重试')
  } finally {
    saving.value = false
  }
}

onMounted(fetchProfile)
</script>

<template>
  <main class="profile-page post-login-page">
    <nav class="profile-nav">
      <RouterLink to="/query">
        <ArrowLeft :size="16" />
        返回问答
      </RouterLink>
      <RouterLink v-if="canEnterAdmin" to="/admin">
        <Settings :size="16" />
        后台管理
      </RouterLink>
    </nav>

    <section class="profile-panel" v-loading="loading">
      <header class="profile-header">
        <div class="profile-avatar">
          <span>{{ displayName().slice(0, 1) }}</span>
        </div>
        <div class="profile-meta">
          <h2>{{ displayName() }}</h2>
          <p>{{ roleText() }}</p>
        </div>
        <RouterLink class="pwd-link" to="/change-password">修改密码</RouterLink>
      </header>

      <div class="profile-form-area">
        <div class="form-section-title">
          <UserRound :size="16" />
          <span>基本信息</span>
        </div>

        <el-form ref="formRef" :model="form" :rules="rules" label-width="96px">
          <el-form-item label="登录账号">
            <el-input v-model="form.username" disabled />
          </el-form-item>
          <el-form-item label="真实姓名" prop="realName">
            <el-input v-model="form.realName" />
          </el-form-item>
          <el-form-item label="邮箱" prop="email">
            <el-input v-model="form.email" placeholder="选填" />
          </el-form-item>
          <el-form-item label="手机号">
            <el-input v-model="form.phone" placeholder="选填" />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="saving" @click="saveProfile">保存修改</el-button>
          </el-form-item>
        </el-form>
      </div>
    </section>
  </main>
</template>

<style scoped>
.profile-page {
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

.profile-nav {
  width: min(680px, 100%);
  display: flex;
  justify-content: space-between;
  gap: 12px;
}

.profile-nav a {
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

.profile-panel {
  width: min(680px, 100%);
  border: 1px solid var(--do-line);
  border-radius: var(--do-radius-lg);
  background: var(--do-surface);
  box-shadow: var(--do-shadow);
  overflow: hidden;
}

.profile-header {
  display: grid;
  grid-template-columns: 56px 1fr auto;
  align-items: center;
  gap: 16px;
  padding: 24px 28px;
  background: linear-gradient(135deg, rgba(189,232,248,0.5) 0, rgba(246,251,239,0.7) 100%);
  border-bottom: 1px solid var(--do-line);
}

.profile-avatar span {
  width: 56px;
  height: 56px;
  display: grid;
  place-items: center;
  border-radius: 14px;
  color: #fff;
  background: linear-gradient(135deg, var(--do-primary), var(--do-accent));
  font-size: 22px;
  font-weight: 900;
}

.profile-meta h2 {
  margin: 0;
  font-size: 20px;
  color: var(--do-ink);
}

.profile-meta p {
  margin: 4px 0 0;
  color: var(--do-muted);
  font-size: 13px;
}

.pwd-link {
  padding: 6px 14px;
  border: 1px solid var(--do-line);
  border-radius: 8px;
  color: var(--do-primary);
  font-size: 13px;
  font-weight: 700;
  background: var(--do-surface);
  transition: border-color 160ms;
}

.pwd-link:hover {
  border-color: var(--do-primary);
}

.profile-form-area {
  padding: 24px 28px;
}

.form-section-title {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 18px;
  color: var(--do-primary);
  font-size: 13px;
  font-weight: 900;
}
</style>
