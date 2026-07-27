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
      <span v-else class="status-item muted">{{ t('statusBar.notConnected') }}</span>
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
          {{ t('statusBar.rows', { count: editorStore.activeSqlTab.result.totalRows }) }}
        </span>
        <span v-if="editorStore.activeSqlTab.result.truncated" class="status-item warning">
          {{ t('statusBar.truncated') }}
        </span>
      </template>
    </div>

    <!-- 右侧：其他信息 -->
    <div class="status-right">
      <span class="status-item" v-if="driverStatus">
        <el-icon :class="driverStatusClass"><Download /></el-icon>
        {{ driverStatusText }}
      </span>
      <span class="status-item muted">{{ t('statusBar.tabs', { count: tabCount }) }}</span>
      <!-- V5 行密度快捷切换 -->
      <button
        class="density-toggle"
        :title="t('appearance.density') + ': ' + t('appearance.' + themeStore.density)"
        @click="toggleDensity"
      >
        <svg viewBox="0 0 14 14" fill="none" aria-hidden="true">
          <template v-if="themeStore.density === 'compact'">
            <path d="M2 3h10M2 5.5h10M2 8h10M2 10.5h10" stroke="currentColor" stroke-width="1.2" stroke-linecap="round" />
          </template>
          <template v-else>
            <path d="M2 3.5h10M2 7h10M2 10.5h10" stroke="currentColor" stroke-width="1.2" stroke-linecap="round" />
          </template>
        </svg>
        <span>{{ t('appearance.' + themeStore.density) }}</span>
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { Timer, Document, Download } from '@element-plus/icons-vue'
import { useConnectionStore } from '@/stores/connection'
import { useEditorStore } from '@/stores/editor'
import { useThemeStore } from '@/stores/theme'

const { t } = useI18n()

const connectionStore = useConnectionStore()
const editorStore = useEditorStore()
const themeStore = useThemeStore()

function toggleDensity() {
  themeStore.setDensity(themeStore.density === 'compact' ? 'comfortable' : 'compact')
}

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
  if (!driverStatus.value) return t('statusBar.driverNotLoaded')
  return t('statusBar.driverReady', { name: driverStatus.value.displayName })
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

/* V5 密度切换按钮 */
.density-toggle {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 6px;
  border: none;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--color-text-muted);
  font-size: var(--text-caption);
  cursor: pointer;
  transition: background var(--transition-fast), color var(--transition-fast);
}

.density-toggle:hover {
  background: var(--color-hover);
  color: var(--color-foreground);
}

.density-toggle svg {
  width: 14px;
  height: 14px;
}
</style>
