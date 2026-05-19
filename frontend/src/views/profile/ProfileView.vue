<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { me, updateProfile } from '../../api/auth'

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
    await fetchProfile()
  } finally {
    saving.value = false
  }
}

onMounted(fetchProfile)
</script>

<template>
  <main class="profile-page post-login-page">
    <section class="profile-panel" v-loading="loading">
      <header class="page-header">
        <div>
          <p>个人资料</p>
          <h1>账号资料维护</h1>
          <span class="header-subtitle">维护个人基础信息，密码修改会重新登录。</span>
        </div>
        <RouterLink to="/admin">返回工作台</RouterLink>
      </header>

      <el-form ref="formRef" :model="form" :rules="rules" label-width="96px">
        <el-form-item label="登录账号">
          <el-input v-model="form.username" disabled />
        </el-form-item>
        <el-form-item label="真实姓名" prop="realName">
          <el-input v-model="form.realName" />
        </el-form-item>
        <el-form-item label="邮箱" prop="email">
          <el-input v-model="form.email" />
        </el-form-item>
        <el-form-item label="手机号">
          <el-input v-model="form.phone" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="saving" @click="saveProfile">保存资料</el-button>
          <RouterLink class="text-link" to="/change-password">修改密码</RouterLink>
        </el-form-item>
      </el-form>
    </section>
  </main>
</template>

<style scoped>
.profile-page {
  display: grid;
}

.profile-panel {
  width: min(720px, 100%);
  padding: 24px;
  border: 1px solid var(--do-line);
  border-radius: 8px;
  background: #fff;
  box-shadow: var(--do-shadow);
}

.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 22px;
}

.page-header p {
  margin: 0 0 6px;
  color: var(--do-primary);
  font-weight: 800;
}

.page-header h1 {
  margin: 0;
  font-size: 26px;
  color: var(--do-ink);
}

.page-header a,
.text-link {
  color: var(--do-primary);
  font-weight: 800;
}

.text-link {
  margin-left: 12px;
}
</style>
