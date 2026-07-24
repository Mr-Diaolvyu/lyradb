<template>
  <!-- 加载中（探测发行版） -->
  <div v-if="!auth.ready" class="app-boot">
    <el-icon class="is-loading"><Loading /></el-icon>
    <span>加载中...</span>
  </div>

  <!-- 个人版：沿用本地 DBA 工具 UI -->
  <MainView v-else-if="!auth.isEnterprise" />

  <!-- 企业版：路由 -->
  <router-view v-else />
</template>

<script setup lang="ts">
import { onMounted } from 'vue'
import { Loading } from '@element-plus/icons-vue'
import MainView from './views/MainView.vue'
import { useAuthStore } from './stores/auth'
import { useThemeStore } from './stores/theme'
import { useConnectionStore } from './stores/connection'

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
  color: var(--color-text-muted, #999);
  font-size: 14px;
}
/* 全局样式已在 global.css 中定义 */
</style>
