<template>
  <el-config-provider :locale="elementLocale">
    <!-- 加载中（探测发行版） -->
    <div v-if="!auth.ready" class="app-boot">
      <el-icon class="is-loading"><Loading /></el-icon>
      <span>{{ t('common.loading') }}</span>
    </div>

    <!-- 探测失败必须显式报错，不能自动降级为免认证个人版 -->
    <div v-else-if="auth.initError" class="app-boot app-boot-error">
      <span>{{ auth.initError }}</span>
      <el-button type="primary" @click="boot(true)">重试</el-button>
    </div>

    <!-- 个人版：沿用本地 DBA 工具 UI -->
    <MainView v-else-if="!auth.isEnterprise" />

    <!-- 企业版：路由 -->
    <router-view v-else />
  </el-config-provider>
</template>

<script setup lang="ts">
import { computed, defineAsyncComponent, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { Loading } from '@element-plus/icons-vue'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import en from 'element-plus/es/locale/lang/en'
import { useAuthStore } from './stores/auth'
import { useThemeStore } from './stores/theme'
import { useConnectionStore } from './stores/connection'

const { t, locale } = useI18n()
const MainView = defineAsyncComponent(() => import('./views/MainView.vue'))

/** Element Plus 组件库语言跟随应用语言 */
const elementLocale = computed(() => (locale.value === 'en-US' ? en : zhCn))

const auth = useAuthStore()
const themeStore = useThemeStore()
const connectionStore = useConnectionStore()

let personalInitialized = false

async function boot(retry = false) {
  if (retry) {
    await auth.retryInit()
  } else {
    await auth.init()
  }
  if (auth.initError || auth.isEnterprise || personalInitialized) return

  // 仅经过一致性校验的 personal 模式才初始化直连能力。
  personalInitialized = true
  themeStore.initTheme()
  await Promise.all([
    connectionStore.loadDrivers(),
    connectionStore.loadConnections(),
  ])
}

onMounted(() => boot())
</script>

<style>
.app-boot {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  height: 100vh;
  color: var(--color-text-muted);
  font-size: 14px;
}
.app-boot-error {
  flex-direction: column;
  padding: 24px;
  text-align: center;
  color: var(--color-destructive);
}
/* 全局样式已在 global.css 中定义 */
</style>
