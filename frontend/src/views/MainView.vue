<template>
  <div class="main-layout stellar-canvas">
    <!-- 顶部工具栏 -->
    <AppHeader />

    <!-- 主体区域 -->
    <div class="main-body">
      <el-button
        v-if="isMobile"
        class="personal-menu-toggle"
        :icon="Menu"
        circle
        aria-label="打开数据源导航"
        @click="navOpen = true"
      />
      <div v-if="isMobile && navOpen" class="personal-overlay" @click="navOpen = false"></div>

      <!-- 左侧导航树：窄屏时改为抽屉 -->
      <SideNav class="personal-nav" :class="{ open: navOpen }" />

      <!-- 中间工作区 -->
      <WorkSpace />

      <!-- 右侧属性面板：窄屏自动隐藏 -->
      <SideProps v-if="uiStore.sidePropsVisible && !isMobile" />
    </div>

    <!-- 底部状态栏 -->
    <StatusBar />

    <!-- 全局命令面板（Ctrl+K） -->
    <CommandPalette />
  </div>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { Menu } from '@element-plus/icons-vue'
import AppHeader from '@/components/layout/AppHeader.vue'
import SideNav from '@/components/layout/SideNav.vue'
import WorkSpace from '@/components/layout/WorkSpace.vue'
import StatusBar from '@/components/layout/StatusBar.vue'
import SideProps from '@/components/layout/SideProps.vue'
import CommandPalette from '@/components/layout/CommandPalette.vue'
import { useUiStore } from '@/stores/ui'

const uiStore = useUiStore()
const isMobile = ref(false)
const navOpen = ref(false)
let mediaQuery: MediaQueryList | null = null

function syncViewport(event?: MediaQueryListEvent) {
  isMobile.value = event?.matches ?? mediaQuery?.matches ?? false
  if (!isMobile.value) navOpen.value = false
}

onMounted(() => {
  mediaQuery = window.matchMedia('(max-width: 768px)')
  syncViewport()
  mediaQuery.addEventListener('change', syncViewport)
})

onBeforeUnmount(() => {
  mediaQuery?.removeEventListener('change', syncViewport)
})
</script>

<style scoped>
.main-layout {
  display: flex;
  flex-direction: column;
  width: 100%;
  height: 100vh;
  overflow: hidden;
}

.main-body {
  position: relative;
  display: flex;
  flex: 1;
  min-width: 0;
  overflow: hidden;
}

.personal-menu-toggle,
.personal-overlay {
  display: none;
}

@media (max-width: 768px) {
  .main-layout { height: 100dvh; }

  .personal-menu-toggle {
    display: inline-flex;
    position: absolute;
    top: 8px;
    left: 8px;
    z-index: 902;
    box-shadow: var(--shadow-md);
  }

  .personal-overlay {
    display: block;
    position: fixed;
    inset: 0;
    z-index: 900;
    background: rgba(0, 0, 0, 0.42);
  }

  :deep(.personal-nav) {
    position: fixed;
    top: 0;
    bottom: 0;
    left: 0;
    z-index: 901;
    width: min(86vw, 320px);
    max-width: 320px;
    transform: translateX(-100%);
    transition: transform var(--transition-normal);
    box-shadow: none;
  }

  :deep(.personal-nav.open) {
    transform: translateX(0);
    box-shadow: var(--shadow-overlay);
  }
}
</style>
