<template>
  <el-config-provider :locale="elementLocale">
    <!-- 加载中（探测发行版） -->
    <div v-if="!auth.ready" class="app-boot">
      <el-icon class="is-loading"><Loading /></el-icon>
      <span>{{ t('common.loading') }}</span>
    </div>

    <!-- 个人版：沿用本地 DBA 工具 UI -->
    <MainView v-else-if="!auth.isEnterprise" />

    <!-- 企业版：路由 -->
    <router-view v-else />
  </el-config-provider>
</template>

<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { Loading } from '@element-plus/icons-vue'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import en from 'element-plus/es/locale/lang/en'
import MainView from './views/MainView.vue'
import { useAuthStore } from './stores/auth'
import { useThemeStore } from './stores/theme'
import { useConnectionStore } from './stores/connection'

const { t, locale } = useI18n()

/** Element Plus 组件库语言跟随应用语言 */
const elementLocale = computed(() => (locale.value === 'en-US' ? en : zhCn))

const auth = useAuthStore()
const themeStore = useThemeStore()
const connectionStore = useConnectionStore()

onMounted(async () => {
  await auth.init()
  if (!auth.isEnterprise) {
    // 个人版：沿用本地 DBA 工具的启动初始化
    themeStore.initTheme()
    connectionStore.loadDrivers()
    connectionStore.loadConnections()
  }
})
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
/* 全局样式已在 global.css 中定义 */
</style>
