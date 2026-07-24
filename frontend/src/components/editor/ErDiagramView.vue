<template>
  <el-dialog
    v-model="visibleRef"
    :title="`ER 图 - ${connectionName}`"
    width="90%"
    top="5vh"
    :close-on-click-modal="false"
    @open="load"
  >
    <div class="er-toolbar">
      <el-tag v-if="er?.tables.length" size="small" type="info" effect="plain">
        {{ er.tables.length }} 表 · {{ er.edges.length }} 关系
      </el-tag>
      <el-input
        v-model="schemaInput"
        placeholder="schema/库（留空取当前库）"
        size="small"
        style="width: 260px"
        @keyup.enter="load"
      />
      <el-button size="small" :icon="Refresh" @click="load" :loading="loading">刷新</el-button>
      <el-button size="small" :icon="Download" @click="exportPng" :disabled="!er?.tables.length">导出 PNG</el-button>
      <el-tooltip content="自动布局" placement="top">
        <el-button size="small" :icon="Grid" @click="autoLayout" :disabled="!er?.tables.length" circle />
      </el-tooltip>
    </div>

    <div class="er-canvas">
      <VueFlow
        v-if="er && er.tables.length"
        :nodes="nodes"
        :edges="edges"
        :default-viewport="{ zoom: 0.8 }"
        fit-view-on-init
        class="vue-flow-theme"
      >
        <template #node-table="props">
          <div class="er-table-node">
            <div class="er-table-title">{{ props.data.label }}</div>
            <ul class="er-table-cols">
              <li v-for="c in (props.data.columns || []).slice(0, 30)" :key="c">{{ c }}</li>
              <li v-if="(props.data.columns || []).length > 30" class="more">
                … +{{ (props.data.columns || []).length - 30 }}
              </li>
            </ul>
          </div>
        </template>
        <Background />
        <Controls />
      </VueFlow>
      <el-empty v-else-if="!loading" description="无表/外键（NoSQL 或空库不可用 ER 图）" :image-size="80" />
      <div v-else class="er-loading"><el-icon class="is-loading"><Loading /></el-icon> 加载中...</div>
    </div>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { VueFlow, type Node, type Edge } from '@vue-flow/core'
import { Background } from '@vue-flow/background'
import { Controls } from '@vue-flow/controls'
import { Refresh, Download, Grid, Loading } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { metadataApi } from '@/api/metadata'
import { useUiStore } from '@/stores/ui'
import { useConnectionStore } from '@/stores/connection'
import type { ErDiagram } from '@/types/metadata'
import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'
import '@vue-flow/controls/dist/style.css'

const props = defineProps<{ visible: boolean; connectionId: string | null }>()
const emit = defineEmits<{ 'update:visible': [boolean] }>()

const uiStore = useUiStore()
const connectionStore = useConnectionStore()

const visibleRef = ref(props.visible)
watch(() => props.visible, (v) => { visibleRef.value = v })
watch(visibleRef, (v) => emit('update:visible', v))

const connectionName = computed(() =>
  connectionStore.connections.find(c => c.id === props.connectionId)?.name || ''
)

const er = ref<ErDiagram | null>(null)
const loading = ref(false)
const schemaInput = ref('')

const COLS = 4
const NODE_W = 200
const NODE_H = 220
const GAP_X = 60
const GAP_Y = 80

const nodes = computed<Node[]>(() => {
  if (!er.value) return []
  return er.value.tables.map((t, i) => {
    const row = Math.floor(i / COLS)
    const col = i % COLS
    return {
      id: t.name,
      type: 'table',
      position: { x: col * (NODE_W + GAP_X), y: row * (NODE_H + GAP_Y) },
      data: { label: t.name, columns: t.columns },
      style: { width: `${NODE_W}px` },
    }
  })
})

const edges = computed<Edge[]>(() => {
  if (!er.value) return []
  return er.value.edges.map((e, i) => ({
    id: `e${i}-${e.source}-${e.target}`,
    source: e.source,
    target: e.target,
    label: `${e.sourceColumn} → ${e.targetColumn}`,
    animated: false,
    type: 'smoothstep',
  }))
})

async function load() {
  if (!props.connectionId) return
  loading.value = true
  try {
    const schema = schemaInput.value.trim() || uiStore.currentDatabase || undefined
    er.value = await metadataApi.getErDiagram(props.connectionId, schema)
  } catch (e: any) {
    ElMessage.error('获取 ER 图失败: ' + (e.message || ''))
    er.value = null
  } finally {
    loading.value = false
  }
}

function autoLayout() {
  // 重置位置（触发 nodes computed 重新计算位置）
  // 由于 computed 依赖 er，这里简单刷新一次
  load()
}

function exportPng() {
  // Vue Flow 不内置 PNG 导出；提示使用浏览器截图（避免引入 html-to-image 依赖）
  ElMessage.info('请使用浏览器截图（Ctrl+Shift+S 或开发者工具截图）保存 ER 图')
}

watch(() => props.visible, (v) => { if (v) load() })
</script>

<style scoped>
.er-toolbar {
  display: flex;
  align-items: center;
  gap: var(--space-2, 8px);
  margin-bottom: 12px;
}

.er-canvas {
  width: 100%;
  height: 65vh;
  border: 1px solid var(--color-border, #e0e0e0);
  border-radius: 6px;
  overflow: hidden;
  background: var(--color-background, #f8fafc);
}

.er-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  gap: 8px;
  color: var(--color-text-muted, #999);
}

.vue-flow-theme :deep(.vue-flow__node) {
  font-size: 12px;
}

.er-table-node {
  border: 1px solid var(--color-border, #ccc);
  border-radius: 4px;
  background: var(--color-panel, #fff);
  overflow: hidden;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.08);
}

.er-table-title {
  background: var(--color-secondary, #059669);
  color: #fff;
  padding: 4px 8px;
  font-weight: 600;
  font-size: 12px;
}

.er-table-cols {
  list-style: none;
  margin: 0;
  padding: 4px 8px;
  max-height: 180px;
  overflow-y: auto;
  font-family: var(--font-mono, monospace);
  font-size: 11px;
  color: var(--color-foreground, #333);
}

.er-table-cols li.more {
  color: var(--color-text-muted, #999);
  font-style: italic;
}
</style>
