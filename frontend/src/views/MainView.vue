<template>
  <div class="main-layout">
    <!-- 顶部工具栏 -->
    <AppHeader />

    <!-- 主体区域 -->
    <div class="main-body">
      <!-- 左侧导航树 -->
      <SideNav />

      <!-- 中间工作区 -->
      <WorkSpace />

      <!-- 右侧属性面板（可折叠） -->
      <SideProps v-if="uiStore.sidePropsVisible" />
    </div>

    <!-- 底部状态栏 -->
    <StatusBar />
  </div>
</template>

<script setup lang="ts">
import AppHeader from '@/components/layout/AppHeader.vue'
import SideNav from '@/components/layout/SideNav.vue'
import WorkSpace from '@/components/layout/WorkSpace.vue'
import StatusBar from '@/components/layout/StatusBar.vue'
import SideProps from '@/components/layout/SideProps.vue'
import { useUiStore } from '@/stores/ui'

const uiStore = useUiStore()
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
  display: flex;
  flex: 1;
  overflow: hidden;
}
</style>
