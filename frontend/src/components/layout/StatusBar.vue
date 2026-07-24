<template>
  <div class="status-bar">
    <!-- 左侧：连接状态 -->
    <div class="status-left">
      <span v-if="activeConnection" class="status-item">
        <span
          class="status-dot"
          :class="statusDotClass"
        ></span>
        <span class="status-text">{{ activeConnection.name }}</span>
        <span class="status-type">{{ activeConnection.displayName }}</span>
      </span>
      <span v-else class="status-item muted">未连接</span>
    </div>

    <!-- 中间：查询信息 -->
    <div class="status-center">
      <template v-if="editorStore.activeSqlTab?.result">
        <span class="status-item">
          <el-icon><Timer /></el-icon>
          {{ formatElapsed(editorStore.activeSqlTab.result.elapsedMs) }}
        </span>
        <span class="status-item">
          <el-icon><Document /></el-icon>
          {{ editorStore.activeSqlTab.result.totalRows }} 行
        </span>
        <span v-if="editorStore.activeSqlTab.result.truncated" class="status-item warning">
          结果已截断
        </span>
      </template>
    </div>

    <!-- 右侧：其他信息 -->
    <div class="status-right">
      <span class="status-item" v-if="driverStatus">
        <el-icon :class="driverStatusClass"><Download /></el-icon>
        {{ driverStatusText }}
      </span>
      <span class="status-item muted">{{ tabCount }} 个标签页</span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { Timer, Document, Download } from '@element-plus/icons-vue'
import { useConnectionStore } from '@/stores/connection'
import { useEditorStore } from '@/stores/editor'

const connectionStore = useConnectionStore()
const editorStore = useEditorStore()

const activeConnection = computed(() => connectionStore.activeConnection)

const statusDotClass = computed(() => {
  if (!activeConnection.value) return 'disconnected'
  switch (activeConnection.value.status) {
    case 'CONNECTED': return 'connected'
    case 'ERROR': return 'error'
    default: return 'disconnected'
  }
})

const tabCount = computed(() => editorStore.tabs.length)

const driverStatus = computed(() => {
  if (!activeConnection.value) return null
  return connectionStore.getDriverByType(activeConnection.value.dbType)
})

const driverStatusClass = computed(() => {
  return driverStatus.value ? 'ready' : 'pending'
})

const driverStatusText = computed(() => {
  if (!driverStatus.value) return '驱动未加载'
  return `${driverStatus.value.displayName} 驱动就绪`
})

function formatElapsed(ms: number): string {
  if (ms < 1000) return `${ms}ms`
  return `${(ms / 1000).toFixed(2)}s`
}
</script>

<style scoped>
.status-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: var(--statusbar-height);
  padding: 0 var(--space-3);
  background: var(--color-panel-header);
  border-top: 1px solid var(--color-border);
  font-size: var(--text-caption);
  color: var(--color-foreground);
  flex-shrink: 0;
  user-select: none;
}

.status-left,
.status-center,
.status-right {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}

.status-center {
  flex: 1;
  justify-content: center;
}

.status-item {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
}

.status-item.muted {
  color: var(--color-disconnected);
}

.status-item.warning {
  color: var(--color-warning);
}

.status-text {
  font-weight: 500;
}

.status-type {
  color: var(--color-text-muted);
  margin-left: var(--space-1);
}

.status-dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
}

.status-dot.connected {
  background: var(--color-connected);
  box-shadow: 0 0 6px var(--color-connected);
}

.status-dot.disconnected {
  background: var(--color-disconnected);
}

.status-dot.error {
  background: var(--color-error);
  box-shadow: 0 0 6px var(--color-error);
}

:deep(.el-icon.ready) {
  color: var(--color-success);
}

:deep(.el-icon.pending) {
  color: var(--color-warning);
}
</style>
