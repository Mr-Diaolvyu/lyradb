<template>
  <div class="login-page">
    <div class="login-card">
      <div class="login-title">LyraDB · 企业版</div>
      <div class="login-sub">统一数据访问治理平台</div>
      <el-form @submit.prevent="handleLogin" label-position="top">
        <el-form-item label="用户名">
          <el-input v-model="username" placeholder="admin" :prefix-icon="User" autocomplete="username" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="password" type="password" show-password placeholder="密码"
                    :prefix-icon="Lock" autocomplete="current-password" @keyup.enter="handleLogin" />
        </el-form-item>
        <el-button type="primary" :loading="loading" style="width: 100%" @click="handleLogin">登录</el-button>
      </el-form>
      <div v-if="error" class="login-error">{{ error }}</div>
      <div class="login-hint">默认账号 admin / admin（首次登录后请改密）</div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { User, Lock } from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const router = useRouter()

const username = ref('admin')
const password = ref('')
const loading = ref(false)
const error = ref('')

async function handleLogin() {
  if (!username.value || !password.value) {
    error.value = '请输入用户名和密码'
    return
  }
  loading.value = true
  error.value = ''
  try {
    await auth.login(username.value, password.value)
    router.push({ name: 'my-sources' })
  } catch (e: any) {
    error.value = e.message || '登录失败'
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100vh;
  background: var(--color-background, #f8fafc);
}
.login-card {
  width: 360px;
  padding: 32px;
  background: var(--color-panel, #fff);
  border: 1px solid var(--color-border, #e4e7eb);
  border-radius: 10px;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.08);
}
.login-title {
  font-size: 20px;
  font-weight: 700;
  color: var(--color-primary, #1e3a5f);
  margin-bottom: 4px;
}
.login-sub {
  font-size: 12px;
  color: var(--color-text-muted, #999);
  margin-bottom: 24px;
}
.login-error {
  margin-top: 12px;
  font-size: 12px;
  color: var(--color-destructive, #dc2626);
}
.login-hint {
  margin-top: 16px;
  font-size: 11px;
  color: var(--color-text-muted, #999);
  text-align: center;
}
</style>
