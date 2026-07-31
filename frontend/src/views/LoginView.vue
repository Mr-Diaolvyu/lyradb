<template>
  <div class="login-page stellar-canvas">
    <section class="login-shell">
      <div class="login-story">
        <svg class="login-mark" viewBox="0 0 46 46" aria-hidden="true">
          <rect x="1" y="1" width="44" height="44" rx="13" class="mark-tile" />
          <ellipse cx="20" cy="15" rx="9.5" ry="4" class="mark-db" />
          <path d="M10.5 15v12c0 2.2 4.2 4 9.5 4s9.5-1.8 9.5-4V15M10.5 21c0 2.2 4.2 4 9.5 4 3 0 5.8-.7 7.5-1.8" class="mark-db" />
          <circle cx="34.5" cy="32" r="2.8" class="mark-star" />
          <path d="m31 28.9 1.5 1.4m5-1.5-1.4 1.5" class="mark-orbit" />
        </svg>
        <div class="section-kicker">LyraDB Enterprise</div>
        <h1>让企业数据连接<br />清晰、可靠、可治理</h1>
        <p>统一管理多引擎数据源、查询权限、审批与审计，在同一工作空间内安全协作。</p>
        <div class="story-points">
          <span><i></i> 本地与企业端一致的工作体验</span>
          <span><i></i> 连接凭据全程安全托管</span>
          <span><i></i> 数据访问策略可审计、可追溯</span>
        </div>
      </div>

      <div class="login-card glass-surface">
        <div class="login-title">登录企业空间</div>
        <div class="login-sub">使用组织分配的 LyraDB 账户继续</div>
        <el-form label-position="top" @submit.prevent="handleLogin">
          <el-form-item label="用户名">
            <el-input v-model="username" placeholder="输入用户名" :prefix-icon="User" autocomplete="username" />
          </el-form-item>
          <el-form-item label="密码">
            <el-input
              v-model="password"
              type="password"
              show-password
              placeholder="输入密码"
              :prefix-icon="Lock"
              autocomplete="current-password"
              @keyup.enter="handleLogin"
            />
          </el-form-item>
          <el-button type="primary" :loading="loading" class="login-button" @click="handleLogin">进入工作空间</el-button>
        </el-form>
        <div v-if="error" class="login-error">{{ error }}</div>
        <div class="login-hint">首次部署可使用管理员初始账户，登录后请立即修改密码。</div>
      </div>
    </section>
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
  min-height: 100vh;
  padding: 30px;
}

.login-shell {
  width: min(920px, 100%);
  display: grid;
  grid-template-columns: minmax(0, 1.1fr) 390px;
  gap: 70px;
  align-items: center;
}

.login-story { padding: 24px 0; }
.login-mark { width: 48px; height: 48px; margin-bottom: 22px; }
.mark-tile { fill: var(--color-active); stroke: var(--color-border-strong); }
.mark-db { fill: none; stroke: var(--color-brand); stroke-width: 1.8; stroke-linecap: round; stroke-linejoin: round; }
.mark-star { fill: var(--color-accent); }
.mark-orbit { fill: none; stroke: var(--color-accent); stroke-width: 1.4; stroke-linecap: round; }

.login-story h1 {
  margin: 9px 0 13px;
  color: var(--color-foreground);
  font-size: 34px;
  font-weight: 740;
  letter-spacing: -0.045em;
  line-height: 1.22;
}

.login-story > p {
  max-width: 500px;
  margin: 0;
  color: var(--color-text-muted);
  font-size: 13px;
  line-height: 1.75;
}

.story-points {
  display: grid;
  gap: 9px;
  margin-top: 28px;
  color: var(--color-text-muted);
  font-size: 11px;
}

.story-points span { display: flex; align-items: center; gap: 8px; }
.story-points i { width: 7px; height: 7px; border-radius: 50%; background: var(--color-connected); box-shadow: 0 0 0 3px color-mix(in srgb, var(--color-connected) 13%, transparent); }

.login-card {
  width: 100%;
  padding: 28px;
  border-radius: 16px;
}

.login-title {
  margin-bottom: 5px;
  color: var(--color-foreground);
  font-size: 19px;
  font-weight: 710;
}

.login-sub { margin-bottom: 23px; color: var(--color-text-muted); font-size: 11px; }
.login-button { width: 100%; margin-top: 4px; }
.login-error { margin-top: 12px; color: var(--color-destructive); font-size: 11px; }
.login-hint { margin-top: 16px; color: var(--color-text-muted); font-size: 10px; line-height: 1.6; text-align: center; }

@media (max-width: 768px) {
  .login-page { padding: 18px; }
  .login-shell { display: block; max-width: 390px; }
  .login-story { padding: 0 4px 22px; }
  .login-mark { width: 40px; height: 40px; margin-bottom: 12px; }
  .login-story h1 { font-size: 25px; }
  .login-story > p,
  .story-points { display: none; }
}
</style>
