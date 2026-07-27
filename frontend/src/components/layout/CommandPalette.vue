<template>
  <!-- 全局命令面板（迭代二 D1，Ctrl+K 唤起） -->
  <el-dialog
    v-model="visible"
    width="640"
    :show-close="false"
    :close-on-click-modal="true"
    align-center
    class="command-palette-dialog"
    @closed="reset"
  >
    <div class="palette">
      <el-input
        ref="inputRef"
        v-model="keyword"
        :placeholder="t('palette.placeholder')"
        size="large"
        clearable
        @keydown="onKeydown"
      >
        <template #prefix>
          <el-icon><Search /></el-icon>
        </template>
      </el-input>

      <div class="palette-hint">{{ t('palette.hint') }}</div>

      <div ref="listRef" class="palette-list">
        <template v-for="group in groups" :key="group.key">
          <div v-if="group.items.length" class="palette-group">{{ t(`palette.group.${group.key}`) }}</div>
          <div
            v-for="item in group.items"
            :key="item.id"
            class="palette-item"
            :class="{ active: item.flatIndex === selectedIndex }"
            :data-index="item.flatIndex"
            @click="run(item)"
            @mouseenter="selectedIndex = item.flatIndex"
          >
            <el-icon class="palette-item-icon"><component :is="item.icon" /></el-icon>
            <span class="palette-item-label">{{ item.label }}</span>
            <span v-if="item.desc" class="palette-item-desc">{{ item.desc }}</span>
          </div>
        </template>
        <div v-if="totalCount === 0" class="palette-empty">
          {{ searching ? t('palette.searching') : t('palette.empty') }}
        </div>
      </div>
    </div>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, computed, watch, nextTick, onMounted, onBeforeUnmount } from 'vue'
import { useI18n } from 'vue-i18n'
import { Search, DocumentAdd, Connection, Clock, Grid, Sunny, List } from '@element-plus/icons-vue'
import { metadataApi } from '@/api/metadata'
import { historyApi } from '@/api/history'
import { useConnectionStore } from '@/stores/connection'
import { useEditorStore } from '@/stores/editor'
import { useUiStore } from '@/stores/ui'
import { useThemeStore } from '@/stores/theme'
import { useTaskStore } from '@/stores/tasks'

interface PaletteItem {
  id: string
  label: string
  desc?: string
  icon: any
  flatIndex: number
  /** 动作返回值不关心（connect 返回 boolean、建 Tab 返回 id） */
  action: () => unknown
}

const { t } = useI18n()
const connectionStore = useConnectionStore()
const editorStore = useEditorStore()
const uiStore = useUiStore()
const themeStore = useThemeStore()
const taskStore = useTaskStore()

const visible = ref(false)
const keyword = ref('')
const selectedIndex = ref(0)
const searching = ref(false)
const inputRef = ref()
const listRef = ref<HTMLElement>()

const tableResults = ref<PaletteItem[]>([])
const historyResults = ref<PaletteItem[]>([])

/** 前缀解析：> 命令 / # 表 / @ 连接，无前缀 = 全部 */
const prefix = computed(() => {
  const c = keyword.value.charAt(0)
  return c === '>' || c === '#' || c === '@' ? c : ''
})
const term = computed(() => (prefix.value ? keyword.value.slice(1) : keyword.value).trim().toLowerCase())

function match(text: string) {
  return !term.value || text.toLowerCase().includes(term.value)
}

/** 内置命令 */
const commandDefs = [
  { id: 'cmd-new-query', icon: DocumentAdd, action: newQuery },
  { id: 'cmd-new-connection', icon: Connection, action: () => uiStore.openConnectionDialog() },
  { id: 'cmd-history', icon: Clock, action: () => uiStore.openHistoryTab() },
  { id: 'cmd-theme', icon: Sunny, action: () => themeStore.toggleTheme() },
  { id: 'cmd-tasks', icon: List, action: () => taskStore.openPanel() },
]

function newQuery() {
  if (connectionStore.activeConnectionId) {
    editorStore.createTab(connectionStore.activeConnectionId)
  }
}

const groups = computed(() => {
  let flat = 0
  const assign = (items: Omit<PaletteItem, 'flatIndex'>[]): PaletteItem[] =>
    items.map(i => ({ ...i, flatIndex: flat++ }))

  const result: { key: string; items: PaletteItem[] }[] = []
  if (!prefix.value || prefix.value === '>') {
    result.push({
      key: 'commands',
      items: assign(
        commandDefs
          .map(c => ({ ...c, label: t(`palette.cmd.${c.id}`) }))
          .filter(c => match(c.label))
      ),
    })
  }
  if (!prefix.value || prefix.value === '@') {
    result.push({
      key: 'connections',
      items: assign(
        connectionStore.connections
          .filter(c => match(c.name))
          .slice(0, 8)
          .map(c => ({
            id: `conn-${c.id}`,
            label: c.name,
            desc: c.status === 'CONNECTED' ? t('palette.connected') : '',
            icon: Connection,
            action: () => connectionStore.connect(c.id),
          }))
      ),
    })
  }
  if (!prefix.value || prefix.value === '#') {
    result.push({ key: 'tables', items: assign(tableResults.value) })
  }
  if (!prefix.value) {
    result.push({ key: 'history', items: assign(historyResults.value) })
  }
  return result
})

const totalCount = computed(() => groups.value.reduce((n, g) => n + g.items.length, 0))
const flatItems = computed(() => groups.value.flatMap(g => g.items))

/** 远程搜索（表 + 历史），200ms 防抖 */
let debounceTimer: ReturnType<typeof setTimeout> | undefined
watch([term, prefix], () => {
  selectedIndex.value = 0
  if (debounceTimer) clearTimeout(debounceTimer)
  debounceTimer = setTimeout(remoteSearch, 200)
})

async function remoteSearch() {
  const kw = term.value
  if (!kw || prefix.value === '>' || prefix.value === '@') {
    tableResults.value = []
    historyResults.value = []
    return
  }
  searching.value = true
  try {
    const jobs: Promise<void>[] = []
    if (connectionStore.activeConnectionId && (!prefix.value || prefix.value === '#')) {
      jobs.push(
        metadataApi.searchNodes(connectionStore.activeConnectionId, kw, 'TABLE').then(nodes => {
          tableResults.value = nodes.slice(0, 8).map(n => ({
            id: `table-${n.path}`,
            label: n.name,
            desc: n.path,
            icon: Grid,
            flatIndex: 0,
            action: () =>
              editorStore.createTableDetailTab(
                connectionStore.activeConnectionId!,
                n.name,
                n.properties?.schema ?? null
              ),
          }))
        })
      )
    } else {
      tableResults.value = []
    }
    if (!prefix.value) {
      jobs.push(
        historyApi.search(kw).then(items => {
          historyResults.value = items.slice(0, 6).map(h => ({
            id: `history-${h.id}`,
            label: h.sql.length > 60 ? h.sql.slice(0, 60) + '…' : h.sql,
            desc: h.executedAt ? new Date(h.executedAt).toLocaleString() : '',
            icon: Clock,
            flatIndex: 0,
            action: () => openHistory(h.connectionId, h.sql),
          }))
        })
      )
    } else {
      historyResults.value = []
    }
    await Promise.all(jobs)
  } catch {
    // 搜索失败静默处理
  } finally {
    searching.value = false
  }
}

function openHistory(connectionId: string, sql: string) {
  const connId = connectionStore.connections.some(c => c.id === connectionId)
    ? connectionId
    : connectionStore.activeConnectionId
  if (!connId) return
  const tabId = editorStore.createTab(connId)
  editorStore.updateSql(tabId, sql)
}

async function run(item: PaletteItem) {
  visible.value = false
  await item.action()
}

function onKeydown(e: KeyboardEvent | Event) {
  const evt = e as KeyboardEvent
  if (evt.key === 'ArrowDown') {
    evt.preventDefault()
    selectedIndex.value = Math.min(selectedIndex.value + 1, flatItems.value.length - 1)
    scrollToSelected()
  } else if (evt.key === 'ArrowUp') {
    evt.preventDefault()
    selectedIndex.value = Math.max(selectedIndex.value - 1, 0)
    scrollToSelected()
  } else if (evt.key === 'Enter') {
    evt.preventDefault()
    const item = flatItems.value[selectedIndex.value]
    if (item) run(item)
  } else if (evt.key === 'Escape') {
    visible.value = false
  }
}

function scrollToSelected() {
  nextTick(() => {
    listRef.value
      ?.querySelector(`[data-index="${selectedIndex.value}"]`)
      ?.scrollIntoView({ block: 'nearest' })
  })
}

function reset() {
  keyword.value = ''
  selectedIndex.value = 0
  tableResults.value = []
  historyResults.value = []
}

function onGlobalKeydown(e: KeyboardEvent) {
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') {
    e.preventDefault()
    visible.value = !visible.value
    if (visible.value) {
      nextTick(() => inputRef.value?.focus())
    }
  }
}

onMounted(() => window.addEventListener('keydown', onGlobalKeydown))
onBeforeUnmount(() => window.removeEventListener('keydown', onGlobalKeydown))
</script>

<style scoped>
.palette-hint {
  margin-top: var(--space-2);
  font-size: 12px;
  color: var(--color-text-secondary);
}

.palette-list {
  margin-top: var(--space-2);
  max-height: 360px;
  overflow-y: auto;
}

.palette-group {
  padding: var(--space-2) var(--space-2) var(--space-1);
  font-size: 11px;
  font-weight: 600;
  text-transform: uppercase;
  color: var(--color-text-secondary);
}

.palette-item {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-2);
  border-radius: 4px;
  cursor: pointer;
}

.palette-item.active {
  background: var(--el-color-primary-light-9);
}

.palette-item-icon {
  flex-shrink: 0;
  color: var(--color-text-secondary);
}

.palette-item-label {
  font-size: 13px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.palette-item-desc {
  margin-left: auto;
  flex-shrink: 0;
  max-width: 40%;
  font-size: 12px;
  color: var(--color-text-secondary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.palette-empty {
  padding: var(--space-4);
  text-align: center;
  font-size: 13px;
  color: var(--color-text-secondary);
}
</style>

<style>
/* 命令面板对话框去掉默认头部留白 */
.command-palette-dialog .el-dialog__header {
  display: none;
}
</style>
