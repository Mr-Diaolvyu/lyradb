<template>
  <el-dialog
    v-model="visibleRef"
    width="92%"
    top="4vh"
    class="er-dialog"
    :close-on-click-modal="false"
    destroy-on-close
    @open="load"
  >
    <template #header>
      <div class="er-dialog-header">
        <DatabaseIcon :db-type="connectionType" :size="34" :connected="true" />
        <div>
          <div class="section-kicker">Constellation map</div>
          <div class="er-dialog-title">{{ connectionName }} · ER 关系图</div>
        </div>
      </div>
    </template>

    <div class="er-toolbar glass-surface">
      <div class="er-stats">
        <span class="stat-number">{{ er?.tables.length || 0 }}</span>
        <span class="stat-label">数据表</span>
        <span class="stat-divider"></span>
        <span class="stat-number">{{ er?.edges.length || 0 }}</span>
        <span class="stat-label">关系</span>
      </div>
      <el-input
        v-model="schemaInput"
        placeholder="输入 schema / 数据库"
        size="small"
        class="schema-input"
        clearable
        @keyup.enter="load"
      />
      <div class="toolbar-spacer"></div>
      <el-button size="small" :icon="Grid" :disabled="!er?.tables.length" @click="autoLayout">适应画布</el-button>
      <el-button size="small" :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      <el-dropdown :disabled="!er?.tables.length" @command="handleExport">
        <el-button size="small" type="primary" :icon="Download" :disabled="!er?.tables.length">
          导出
          <el-icon class="el-icon--right"><ArrowDown /></el-icon>
        </el-button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="png">导出 PNG</el-dropdown-item>
            <el-dropdown-item command="svg">导出 SVG</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>

    <div class="er-canvas stellar-canvas">
      <VueFlow
        v-if="er?.tables.length"
        :nodes="nodes"
        :edges="edges"
        :default-viewport="{ zoom: 0.82 }"
        :min-zoom="0.15"
        :max-zoom="2"
        fit-view-on-init
        class="vue-flow-theme"
        @init="onPaneReady"
      >
        <template #node-table="props">
          <div class="er-table-node">
            <Handle type="target" :position="Position.Left" class="er-handle" />
            <div class="er-table-title">
              <svg viewBox="0 0 20 20" class="table-glyph" aria-hidden="true">
                <rect x="2.5" y="3" width="15" height="14" rx="3" />
                <path d="M2.5 7.5h15M7.3 7.5V17" />
                <circle cx="5" cy="5.2" r=".7" class="glyph-dot" />
              </svg>
              <div class="table-title-copy">
                <span class="table-name">{{ props.data.label }}</span>
                <span v-if="props.data.schema" class="table-schema">{{ props.data.schema }}</span>
              </div>
              <span class="column-count">{{ (props.data.columns || []).length }}</span>
            </div>
            <ul class="er-table-cols">
              <li v-for="(column, index) in (props.data.columns || []).slice(0, 30)" :key="column">
                <span class="column-index">{{ String(index + 1).padStart(2, '0') }}</span>
                <span class="column-name">{{ column }}</span>
              </li>
              <li v-if="(props.data.columns || []).length > 30" class="more">
                另有 {{ (props.data.columns || []).length - 30 }} 个字段
              </li>
            </ul>
            <Handle type="source" :position="Position.Right" class="er-handle" />
          </div>
        </template>
        <Background :gap="24" :size="1" />
        <Controls />
      </VueFlow>
      <el-empty
        v-else-if="!loading"
        description="当前范围没有可绘制的数据表；NoSQL 或空库不生成 ER 关系图"
        :image-size="80"
      />
      <div v-else class="er-loading">
        <span class="loading-orbit"><span></span></span>
        <div>
          <div class="loading-title">正在构建数据星图</div>
          <div class="loading-copy">读取表结构与外键关系…</div>
        </div>
      </div>
    </div>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, computed, watch, nextTick } from 'vue'
import { VueFlow, Handle, Position, MarkerType, type Node, type Edge } from '@vue-flow/core'
import { Background } from '@vue-flow/background'
import { Controls } from '@vue-flow/controls'
import { Refresh, Download, Grid, ArrowDown } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { metadataApi } from '@/api/metadata'
import { useUiStore } from '@/stores/ui'
import { useConnectionStore } from '@/stores/connection'
import type { ErDiagram } from '@/types/metadata'
import { saveBlob } from '@/utils/download'
import DatabaseIcon from '@/components/common/DatabaseIcon.vue'
import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'
import '@vue-flow/controls/dist/style.css'

const props = defineProps<{ visible: boolean; connectionId: string | null }>()
const emit = defineEmits<{ 'update:visible': [boolean] }>()
const uiStore = useUiStore()
const connectionStore = useConnectionStore()

const visibleRef = ref(props.visible)
watch(() => props.visible, value => { visibleRef.value = value })
watch(visibleRef, value => emit('update:visible', value))

const currentConnection = computed(() => connectionStore.connections.find(item => item.id === props.connectionId))
const connectionName = computed(() => currentConnection.value?.name || '数据库')
const connectionType = computed(() => currentConnection.value?.dbType || 'DATABASE')
const er = ref<ErDiagram | null>(null)
const loading = ref(false)
const schemaInput = ref('')
const flowInstance = ref<any>(null)

const NODE_W = 232
const NODE_H = 250
const GAP_X = 92
const GAP_Y = 100

const orderedTables = computed(() => {
  const tables = er.value?.tables || []
  const degree = new Map<string, number>()
  for (const edge of er.value?.edges || []) {
    degree.set(edge.source, (degree.get(edge.source) || 0) + 1)
    degree.set(edge.target, (degree.get(edge.target) || 0) + 1)
  }
  return [...tables].sort((a, b) =>
    (degree.get(b.name) || 0) - (degree.get(a.name) || 0) || a.name.localeCompare(b.name),
  )
})

const nodes = computed<Node[]>(() => {
  const tables = orderedTables.value
  const columns = Math.max(1, Math.ceil(Math.sqrt(tables.length * 1.45)))
  return tables.map((table, index) => {
    const row = Math.floor(index / columns)
    const col = index % columns
    const offset = row % 2 === 0 ? 0 : (NODE_W + GAP_X) / 2
    return {
      id: table.name,
      type: 'table',
      position: { x: col * (NODE_W + GAP_X) + offset, y: row * (NODE_H + GAP_Y) },
      data: { label: table.name, schema: table.schema, columns: table.columns },
      style: { width: `${NODE_W}px` },
    }
  })
})

const edges = computed<Edge[]>(() => (er.value?.edges || []).map((edge, index) => ({
  id: `e${index}-${edge.source}-${edge.target}`,
  source: edge.source,
  target: edge.target,
  label: `${edge.sourceColumn} → ${edge.targetColumn}`,
  type: 'smoothstep',
  animated: false,
  markerEnd: { type: MarkerType.ArrowClosed, color: 'var(--color-brand)', width: 16, height: 16 },
  style: { stroke: 'var(--color-border-strong)', strokeWidth: 1.35 },
  labelStyle: { fill: 'var(--color-text-muted)', fontSize: 10 },
  labelBgStyle: { fill: 'var(--color-panel-translucent)', fillOpacity: 0.92 },
  labelBgPadding: [5, 3],
  labelBgBorderRadius: 5,
})))

async function load() {
  if (!props.connectionId) return
  loading.value = true
  try {
    const schema = schemaInput.value.trim() || uiStore.currentDatabase || undefined
    er.value = await metadataApi.getErDiagram(props.connectionId, schema)
    await nextTick()
    requestAnimationFrame(() => flowInstance.value?.fitView?.({ padding: 0.14, duration: 360 }))
  } catch (error: any) {
    ElMessage.error('获取 ER 图失败：' + (error.message || '未知错误'))
    er.value = null
  } finally {
    loading.value = false
  }
}

function onPaneReady(instance: any) {
  flowInstance.value = instance
}

function autoLayout() {
  flowInstance.value?.fitView?.({ padding: 0.14, duration: 420 })
}

const SVG_MAX_COLS = 15
const SVG_ROW_H = 18
const SVG_TITLE_H = 38

function escapeXml(value: string): string {
  return value.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')
}

function generateSvg(): string {
  const tables = orderedTables.value
  const relations = er.value?.edges || []
  const columns = Math.max(1, Math.ceil(Math.sqrt(tables.length * 1.45)))
  const positions = new Map<string, { x: number; y: number; h: number }>()

  tables.forEach((table, index) => {
    const row = Math.floor(index / columns)
    const col = index % columns
    const offset = row % 2 === 0 ? 0 : (NODE_W + GAP_X) / 2
    const visibleColumns = Math.min(table.columns.length, SVG_MAX_COLS)
    const extra = table.columns.length > SVG_MAX_COLS ? 1 : 0
    positions.set(table.name, {
      x: 40 + col * (NODE_W + GAP_X) + offset,
      y: 40 + row * (NODE_H + GAP_Y),
      h: SVG_TITLE_H + (visibleColumns + extra) * SVG_ROW_H + 10,
    })
  })

  const rowCount = Math.ceil(tables.length / columns)
  const width = Math.max(760, columns * (NODE_W + GAP_X) + NODE_W)
  const height = Math.max(460, rowCount * (NODE_H + GAP_Y) + 100)
  const parts: string[] = []
  parts.push(`<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" viewBox="0 0 ${width} ${height}" font-family="Segoe UI, sans-serif">`)
  parts.push('<defs>')
  parts.push('<pattern id="stars" width="90" height="90" patternUnits="userSpaceOnUse"><circle cx="12" cy="18" r="0.8" fill="#9a8bea" opacity=".34"/><circle cx="72" cy="56" r="0.65" fill="#9a8bea" opacity=".26"/></pattern>')
  parts.push('<marker id="arrow" markerWidth="7" markerHeight="7" refX="6" refY="3.5" orient="auto"><path d="M0,0 L7,3.5 L0,7 Z" fill="#7866d8"/></marker>')
  parts.push('</defs>')
  parts.push('<rect width="100%" height="100%" fill="#f7f8fc"/>')
  parts.push('<rect width="100%" height="100%" fill="url(#stars)"/>')

  for (const relation of relations) {
    const source = positions.get(relation.source)
    const target = positions.get(relation.target)
    if (!source || !target) continue
    const x1 = source.x + NODE_W
    const y1 = source.y + source.h / 2
    const x2 = target.x
    const y2 = target.y + target.h / 2
    const midX = (x1 + x2) / 2
    parts.push(`<path d="M ${x1} ${y1} H ${midX} V ${y2} H ${x2}" fill="none" stroke="#aab2c2" stroke-width="1.4" marker-end="url(#arrow)"/>`)
    parts.push(`<rect x="${midX - 54}" y="${(y1 + y2) / 2 - 10}" width="108" height="18" rx="5" fill="#ffffff" stroke="#e0e3ec"/>`)
    parts.push(`<text x="${midX}" y="${(y1 + y2) / 2 + 3}" font-size="9" fill="#687087" text-anchor="middle">${escapeXml(`${relation.sourceColumn} → ${relation.targetColumn}`)}</text>`)
  }

  for (const table of tables) {
    const position = positions.get(table.name)!
    parts.push(`<rect x="${position.x}" y="${position.y}" width="${NODE_W}" height="${position.h}" rx="12" fill="#ffffff" stroke="#d8dce7"/>`)
    parts.push(`<path d="M ${position.x + 1} ${position.y + 38} H ${position.x + NODE_W - 1}" stroke="#e4e6ee"/>`)
    parts.push(`<rect x="${position.x + 12}" y="${position.y + 10}" width="18" height="18" rx="5" fill="#eeeafe"/>`)
    parts.push(`<path d="M ${position.x + 16} ${position.y + 15}h10v8H16z M16 ${position.y + 18}h10" fill="none" stroke="#7866d8" stroke-width="1.2"/>`)
    parts.push(`<text x="${position.x + 38}" y="${position.y + 24}" font-size="12" font-weight="600" fill="#222530">${escapeXml(table.name)}</text>`)
    table.columns.slice(0, SVG_MAX_COLS).forEach((column, index) => {
      const y = position.y + SVG_TITLE_H + (index + 1) * SVG_ROW_H - 4
      parts.push(`<text x="${position.x + 13}" y="${y}" font-size="9" fill="#939aad">${String(index + 1).padStart(2, '0')}</text>`)
      parts.push(`<text x="${position.x + 36}" y="${y}" font-size="10" fill="#3f4555" font-family="Consolas, monospace">${escapeXml(column)}</text>`)
    })
    if (table.columns.length > SVG_MAX_COLS) {
      const y = position.y + SVG_TITLE_H + (SVG_MAX_COLS + 1) * SVG_ROW_H - 4
      parts.push(`<text x="${position.x + 13}" y="${y}" font-size="10" fill="#7866d8">另有 ${table.columns.length - SVG_MAX_COLS} 个字段</text>`)
    }
  }

  parts.push('</svg>')
  return parts.join('')
}

async function handleExport(format: string) {
  if (!er.value?.tables.length) return
  const svg = generateSvg()
  const base = `er_${connectionName.value || 'diagram'}_${Date.now()}`
  if (format === 'svg') {
    await saveBlob(new Blob([svg], { type: 'image/svg+xml;charset=utf-8' }), `${base}.svg`)
    ElMessage.success('SVG 导出成功')
    return
  }

  const image = new Image()
  image.onload = () => {
    const canvas = document.createElement('canvas')
    canvas.width = image.width * 2
    canvas.height = image.height * 2
    const context = canvas.getContext('2d')
    if (!context) {
      ElMessage.error('当前浏览器不支持 Canvas 导出')
      return
    }
    context.scale(2, 2)
    context.drawImage(image, 0, 0)
    canvas.toBlob(async blob => {
      if (!blob) {
        ElMessage.error('PNG 生成失败')
        return
      }
      await saveBlob(blob, `${base}.png`)
      ElMessage.success('PNG 导出成功')
    }, 'image/png')
  }
  image.onerror = () => ElMessage.error('PNG 导出失败：SVG 渲染错误')
  image.src = 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(svg)
}

watch(() => props.visible, value => { if (value) load() })
</script>

<style scoped>
.er-dialog-header {
  display: flex;
  align-items: center;
  gap: 10px;
}

.er-dialog-title {
  margin-top: 2px;
  font-size: 15px;
  font-weight: 680;
}

.er-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 52px;
  margin-bottom: 10px;
  padding: 8px 10px;
  border-radius: 12px;
}

.er-stats {
  display: flex;
  align-items: baseline;
  gap: 5px;
  padding-right: 12px;
}

.stat-number { color: var(--color-foreground); font-size: 16px; font-weight: 720; font-variant-numeric: tabular-nums; }
.stat-label { color: var(--color-text-muted); font-size: 10px; }
.stat-divider { width: 1px; height: 18px; margin: 0 4px; background: var(--color-panel-border); }
.schema-input { width: 250px; }
.toolbar-spacer { flex: 1; }

.er-canvas {
  width: 100%;
  height: 69vh;
  overflow: hidden;
  border: 1px solid var(--color-panel-border);
  border-radius: 14px;
}

.er-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 16px;
  height: 100%;
}

.loading-orbit {
  position: relative;
  width: 42px;
  height: 42px;
  border: 1px solid var(--color-border-strong);
  border-radius: 50%;
  animation: orbit 1.4s linear infinite;
}

.loading-orbit::before,
.loading-orbit span {
  position: absolute;
  border-radius: 50%;
  content: '';
}

.loading-orbit::before { top: 4px; left: 17px; width: 7px; height: 7px; background: var(--color-brand); }
.loading-orbit span { inset: 15px; background: var(--color-accent); }
.loading-title { font-size: 13px; font-weight: 650; }
.loading-copy { margin-top: 3px; color: var(--color-text-muted); font-size: 10px; }
@keyframes orbit { to { transform: rotate(360deg); } }

.vue-flow-theme :deep(.vue-flow__node) {
  border: 0;
  background: transparent;
  box-shadow: none;
  font-size: 12px;
}

.vue-flow-theme :deep(.vue-flow__edge-path) {
  transition: stroke var(--transition-normal), stroke-width var(--transition-normal);
}

.vue-flow-theme :deep(.vue-flow__edge.selected .vue-flow__edge-path),
.vue-flow-theme :deep(.vue-flow__edge:hover .vue-flow__edge-path) {
  stroke: var(--color-brand);
  stroke-width: 2;
}

.vue-flow-theme :deep(.vue-flow__background pattern circle) {
  fill: var(--color-star);
}

.vue-flow-theme :deep(.vue-flow__controls) {
  overflow: hidden;
  border: 1px solid var(--color-panel-border);
  border-radius: 9px;
  background: var(--color-panel-translucent);
  box-shadow: var(--shadow-panel);
  backdrop-filter: blur(12px);
}

.vue-flow-theme :deep(.vue-flow__controls-button) {
  border-bottom-color: var(--color-panel-border);
  background: transparent;
  fill: var(--color-foreground);
}

.er-table-node {
  overflow: visible;
  border: 1px solid var(--color-panel-border);
  border-radius: 12px;
  background: var(--color-panel-translucent);
  box-shadow: var(--shadow-panel);
  backdrop-filter: blur(14px) saturate(112%);
}

.er-table-title {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 42px;
  padding: 6px 9px;
  border-bottom: 1px solid var(--color-panel-border);
  background: var(--color-panel-header);
  border-radius: 11px 11px 0 0;
}

.table-glyph {
  width: 23px;
  height: 23px;
  flex-shrink: 0;
  fill: var(--color-active);
  stroke: var(--color-brand);
  stroke-width: 1.3;
  stroke-linecap: round;
  stroke-linejoin: round;
}

.glyph-dot { fill: var(--color-accent); stroke: none; }
.table-title-copy { min-width: 0; flex: 1; display: flex; flex-direction: column; }
.table-name { overflow: hidden; color: var(--color-foreground); font-size: 12px; font-weight: 680; white-space: nowrap; text-overflow: ellipsis; }
.table-schema { margin-top: 1px; overflow: hidden; color: var(--color-text-muted); font-size: 8px; white-space: nowrap; text-overflow: ellipsis; }
.column-count { padding: 2px 5px; border-radius: 5px; background: var(--color-muted); color: var(--color-text-muted); font-size: 9px; }

.er-table-cols {
  max-height: 205px;
  margin: 0;
  padding: 5px 0 7px;
  overflow-y: auto;
  list-style: none;
}

.er-table-cols li {
  display: flex;
  align-items: center;
  gap: 7px;
  min-height: 22px;
  padding: 0 9px;
  font-family: var(--font-mono);
  font-size: 10px;
}

.er-table-cols li:hover { background: var(--color-hover); }
.column-index { width: 17px; color: var(--color-text-muted); font-size: 8px; font-variant-numeric: tabular-nums; }
.column-name { overflow: hidden; color: var(--color-foreground); white-space: nowrap; text-overflow: ellipsis; }
.er-table-cols li.more { color: var(--color-brand); font-family: var(--font-ui); font-size: 9px; }

.er-handle {
  width: 8px;
  height: 8px;
  border: 2px solid var(--color-panel);
  background: var(--color-brand);
}

:global(.er-dialog.el-dialog) {
  overflow: hidden;
  border: 1px solid var(--color-panel-border);
  border-radius: 16px;
  background: var(--color-panel-translucent);
  box-shadow: var(--shadow-overlay);
  backdrop-filter: blur(20px) saturate(116%);
}

:global(.er-dialog .el-dialog__header) {
  margin: 0;
  padding: 14px 18px;
  border-bottom: 1px solid var(--color-panel-border);
}

:global(.er-dialog .el-dialog__body) {
  padding: 12px;
}

@media (max-width: 768px) {
  .er-toolbar { flex-wrap: wrap; }
  .schema-input { width: 100%; order: 3; }
  .toolbar-spacer { display: none; }
  .er-canvas { height: 66vh; }
}
</style>
