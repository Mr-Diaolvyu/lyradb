<template>
  <div class="sql-history">
    <!-- 工具栏 -->
    <div class="history-toolbar">
      <el-input
        v-model="searchInput"
        placeholder="搜索 SQL / 标题（Ctrl+P）"
        size="small"
        clearable
        :prefix-icon="Search"
        class="search-input"
        @input="onSearchInput"
        @keyup.enter="onSearchInput"
        @clear="onClear"
      />
      <el-tooltip content="仅看收藏" placement="top">
        <el-button
          size="small"
          text
          :type="historyStore.favoriteOnly ? 'primary' : ''"
          @click="historyStore.toggleFavoriteOnly()"
        >
          <el-icon><Star /></el-icon>
        </el-button>
      </el-tooltip>
      <el-tooltip content="刷新" placement="top">
        <el-button size="small" text :icon="Refresh" @click="historyStore.load()" />
      </el-tooltip>
      <el-tooltip content="清空当前作用域历史" placement="top">
        <el-button size="small" text :icon="Delete" @click="confirmClear" />
      </el-tooltip>
      <el-tag v-if="scopeLabel" size="small" type="info" effect="plain" class="scope-tag">
        {{ scopeLabel }}
      </el-tag>
    </div>

    <!-- 列表 -->
    <div class="history-list">
      <template v-if="historyStore.loading">
        <div class="history-empty"><el-icon class="is-loading"><Loading /></el-icon> 加载中...</div>
      </template>
      <template v-else-if="historyStore.items.length === 0">
        <el-empty description="暂无查询历史" :image-size="56" />
      </template>
      <template v-else>
        <div
          v-for="item in historyStore.items"
          :key="item.id"
          class="history-item"
          :class="{ failed: item.success === false }"
        >
          <div class="item-header">
            <el-icon class="status-dot" :color="item.success === false ? 'var(--color-destructive)' : 'var(--color-success)'">
              <CircleCheckFilled v-if="item.success !== false" />
              <WarningFilled v-else />
            </el-icon>
            <span class="item-db">{{ item.dbType || '?' }}</span>
            <span class="item-time">{{ formatTime(item.executedAt) }}</span>
            <span v-if="item.durationMs !== undefined && item.durationMs !== null" class="item-meta">{{ item.durationMs }}ms</span>
            <span v-if="item.rowCount !== undefined && item.rowCount !== null" class="item-meta">{{ item.rowCount }} 行</span>
            <div class="item-actions">
              <el-tooltip content="收藏" placement="top">
                <el-button
                  size="small"
                  text
                  @click="historyStore.toggleFavorite(item.id)"
                >
                  <el-icon :color="item.favorite ? 'var(--color-warning)' : ''">
                    <StarFilled v-if="item.favorite" />
                    <Star v-else />
                  </el-icon>
                </el-button>
              </el-tooltip>
              <el-tooltip content="插入到当前 SQL 标签" placement="top">
                <el-button size="small" text :icon="EditPen" @click="insert(item.sql)" />
              </el-tooltip>
              <el-tooltip content="删除" placement="top">
                <el-button size="small" text :icon="Delete" @click="historyStore.remove(item.id)" />
              </el-tooltip>
            </div>
          </div>
          <pre class="item-sql" @dblclick="insert(item.sql)">{{ item.sql }}</pre>
          <div v-if="item.errorMessage" class="item-error">{{ item.errorMessage }}</div>
        </div>
      </template>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, watch, onUnmounted } from 'vue'
import {
  Search, Star, StarFilled, Refresh, Delete, EditPen,
  Loading, CircleCheckFilled, WarningFilled,
} from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useHistoryStore } from '@/stores/history'
import { useConnectionStore } from '@/stores/connection'
import type { QueryHistory } from '@/types/history'

const historyStore = useHistoryStore()
const connectionStore = useConnectionStore()

const searchInput = ref('')
let debounceTimer: ReturnType<typeof setTimeout> | null = null

const scopeLabel = computed(() => {
  if (historyStore.keyword) return `搜索: "${historyStore.keyword}"`
  const conn = connectionStore.connections.find(c => c.id === historyStore.scopeConnectionId)
  if (conn) return conn.name
  return historyStore.favoriteOnly ? '收藏' : '全部'
})

function onSearchInput() {
  if (debounceTimer) clearTimeout(debounceTimer)
  debounceTimer = setTimeout(() => {
    historyStore.setKeyword(searchInput.value)
  }, 300)
}

function onClear() {
  searchInput.value = ''
  historyStore.setKeyword('')
}

async function insert(sql: string) {
  const ok = historyStore.insertToActiveTab(sql)
  if (ok) {
    ElMessage.success('已插入到当前 SQL 标签')
  } else {
    ElMessage.warning('请先选择一个数据库连接并打开 SQL 标签')
  }
}

async function confirmClear() {
  try {
    await ElMessageBox.confirm('确定清空当前作用域的查询历史吗？此操作不可撤销。', '确认', { type: 'warning' })
    await historyStore.clear()
    ElMessage.success('已清空')
  } catch { /* cancelled */ }
}

function formatTime(iso?: string): string {
  if (!iso) return ''
  try {
    const d = new Date(iso)
    const pad = (n: number) => n.toString().padStart(2, '0')
    return `${pad(d.getMonth() + 1)}/${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
  } catch {
    return ''
  }
}

/** 连接变化时切换作用域 */
watch(() => connectionStore.activeConnectionId, (newId) => {
  historyStore.setScope(newId)
})

function handleCtrlP(e: KeyboardEvent) {
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'p') {
    e.preventDefault()
    const input = document.querySelector('.sql-history .search-input input') as HTMLInputElement | null
    if (input) input.focus()
  }
}

onMounted(async () => {
  historyStore.setScope(connectionStore.activeConnectionId)
  await historyStore.load()
  window.addEventListener('keydown', handleCtrlP)
})

onUnmounted(() => {
  window.removeEventListener('keydown', handleCtrlP)
  if (debounceTimer) clearTimeout(debounceTimer)
})
</script>

<style scoped>
.sql-history {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: var(--color-panel);
}

.history-toolbar {
  display: flex;
  align-items: center;
  gap: var(--space-1);
  padding: var(--space-2);
  border-bottom: 1px solid var(--color-border);
}

.search-input {
  flex: 1;
}

.scope-tag {
  margin-left: auto;
}

.history-list {
  flex: 1;
  overflow-y: auto;
  padding: var(--space-2);
}

.history-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-2);
  height: 100%;
  color: var(--color-text-muted);
  font-size: var(--text-body);
}

.history-item {
  padding: var(--space-2) var(--space-3);
  margin-bottom: var(--space-1);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm, 4px);
  background: var(--color-background, #fff);
  transition: border-color var(--transition-fast);
}

.history-item:hover {
  border-color: var(--color-secondary);
}

.history-item.failed {
  border-left: 3px solid var(--color-destructive);
}

.item-header {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  font-size: var(--text-caption, 12px);
  color: var(--color-text-muted);
}

.status-dot {
  font-size: 14px;
  flex-shrink: 0;
}

.item-db {
  font-weight: 600;
  color: var(--color-secondary);
}

.item-time {
  font-variant-numeric: tabular-nums;
}

.item-meta {
  font-variant-numeric: tabular-nums;
}

.item-actions {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 0;
}

.item-sql {
  margin: var(--space-1) 0 0;
  padding: var(--space-2);
  font-family: var(--font-mono, 'JetBrains Mono', monospace);
  font-size: var(--text-code, 13px);
  color: var(--color-foreground);
  background: var(--color-muted, #f8f9fa);
  border-radius: var(--radius-sm, 4px);
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 120px;
  overflow-y: auto;
  cursor: pointer;
}

.item-error {
  margin-top: var(--space-1);
  font-size: var(--text-caption, 12px);
  color: var(--color-destructive);
  font-family: var(--font-mono, monospace);
}
</style>
