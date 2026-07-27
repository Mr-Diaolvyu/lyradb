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
      <el-dropdown @command="handleExport" :disabled="!er?.tables.length">
        <el-button size="small" :icon="Download" :disabled="!er?.tables.length">
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
import { Refresh, Download, Grid, Loading, ArrowDown } from '@element-plus/icons-vue'
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

// === 导出 PNG / SVG（基于网格布局自绘 SVG，零依赖） ===

const SVG_MAX_COLS = 15
const SVG_ROW_H = 16
const SVG_TITLE_H = 24

function escapeXml(s: string): string {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')
}

/** 将当前 ER 图数据渲染为独立 SVG 文档 */
function generateSvg(): string {
  const tables = er.value?.tables || []
  const relEdges = er.value?.edges || []
  const pos = new Map<string, { x: number; y: number; h: number }>()

  tables.forEach((t, i) => {
    const row = Math.floor(i / COLS)
    const col = i % COLS
    const shown = Math.min(t.columns.length, SVG_MAX_COLS)
    const extra = t.columns.length > SVG_MAX_COLS ? 1 : 0
    const h = SVG_TITLE_H + (shown + extra) * SVG_ROW_H + 8
    pos.set(t.name, { x: col * (NODE_W + GAP_X), y: row * (NODE_H + GAP_Y), h })
  })

  const width = COLS * (NODE_W + GAP_X) + 40
  const rows = Math.ceil(tables.length / COLS)
  const height = rows * (NODE_H + GAP_Y) + 40

  const parts: string[] = []
  parts.push(`<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" viewBox="0 0 ${width} ${height}" font-family="Segoe UI, sans-serif">`)
  // SVG 导出为脱离页面的独立文件，CSS 变量不生效，此处固定为亮色主题色值
  parts.push(`<rect width="100%" height="100%" fill="#f8fafc"/>`)

  // 先画关系边（避免遮挡节点）
  for (const e of relEdges) {
    const s = pos.get(e.source)
    const t = pos.get(e.target)
    if (!s || !t) continue
    const x1 = s.x + NODE_W / 2 + 20, y1 = s.y + s.h / 2 + 20
    const x2 = t.x + NODE_W / 2 + 20, y2 = t.y + t.h / 2 + 20
    parts.push(`<line x1="${x1}" y1="${y1}" x2="${x2}" y2="${y2}" stroke="#94a3b8" stroke-width="1.5"/>`)
    const mx = (x1 + x2) / 2, my = (y1 + y2) / 2
    parts.push(`<text x="${mx}" y="${my - 4}" font-size="10" fill="#64748b" text-anchor="middle">${escapeXml(`${e.sourceColumn} → ${e.targetColumn}`)}</text>`)
  }

  // 再画表节点
  for (const t of tables) {
    const p = pos.get(t.name)!
    const x = p.x + 20, y = p.y + 20
    parts.push(`<g>`)
    parts.push(`<rect x="${x}" y="${y}" width="${NODE_W}" height="${p.h}" rx="4" fill="#ffffff" stroke="#cbd5e1"/>`)
    parts.push(`<rect x="${x}" y="${y}" width="${NODE_W}" height="${SVG_TITLE_H}" rx="4" fill="#059669"/>`)
    parts.push(`<text x="${x + 8}" y="${y + 16}" font-size="12" font-weight="600" fill="#ffffff">${escapeXml(t.name)}</text>`)
    const shown = t.columns.slice(0, SVG_MAX_COLS)
    shown.forEach((c, i) => {
      parts.push(`<text x="${x + 8}" y="${y + SVG_TITLE_H + (i + 1) * SVG_ROW_H - 4}" font-size="11" fill="#334155" font-family="Consolas, monospace">${escapeXml(c)}</text>`)
    })
    if (t.columns.length > SVG_MAX_COLS) {
      parts.push(`<text x="${x + 8}" y="${y + SVG_TITLE_H + (shown.length + 1) * SVG_ROW_H - 4}" font-size="11" fill="#94a3b8" font-style="italic">… +${t.columns.length - SVG_MAX_COLS}</text>`)
    }
    parts.push(`</g>`)
  }

  parts.push('</svg>')
  return parts.join('')
}

function downloadBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.click()
  URL.revokeObjectURL(url)
}

function handleExport(format: string) {
  if (!er.value?.tables.length) return
  const svg = generateSvg()
  const base = `er_${connectionName.value || 'diagram'}_${Date.now()}`

  if (format === 'svg') {
    downloadBlob(new Blob([svg], { type: 'image/svg+xml;charset=utf-8' }), `${base}.svg`)
    ElMessage.success('SVG 导出成功')
    return
  }

  // PNG：SVG 经 canvas 栅格化（2x 分辨率）
  const img = new Image()
  const svgUrl = 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(svg)
  img.onload = () => {
    const canvas = document.createElement('canvas')
    canvas.width = img.width * 2
    canvas.height = img.height * 2
    const ctx = canvas.getContext('2d')
    if (!ctx) {
      ElMessage.error('当前浏览器不支持 Canvas 导出')
      return
    }
    ctx.scale(2, 2)
    ctx.drawImage(img, 0, 0)
    canvas.toBlob((blob) => {
      if (blob) {
        downloadBlob(blob, `${base}.png`)
        ElMessage.success('PNG 导出成功')
      } else {
        ElMessage.error('PNG 生成失败')
      }
    }, 'image/png')
  }
  img.onerror = () => ElMessage.error('PNG 导出失败：SVG 渲染错误')
  img.src = svgUrl
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
  border: 1px solid var(--color-border);
  border-radius: 6px;
  overflow: hidden;
  background: var(--color-background);
}

.er-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  gap: 8px;
  color: var(--color-text-muted);
}

.vue-flow-theme :deep(.vue-flow__node) {
  font-size: 12px;
}

.er-table-node {
  border: 1px solid var(--color-border);
  border-radius: 4px;
  background: var(--color-panel);
  overflow: hidden;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.08);
}

.er-table-title {
  background: var(--color-secondary);
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
  color: var(--color-foreground);
}

.er-table-cols li.more {
  color: var(--color-text-muted);
  font-style: italic;
}
</style>
