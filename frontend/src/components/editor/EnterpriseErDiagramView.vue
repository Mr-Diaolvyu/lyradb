<template>
  <el-dialog
    v-model="visibleRef"
    width="95%"
    top="3vh"
    destroy-on-close
    append-to-body
    class="enterprise-er-dialog"
  >
    <template #header>
      <div class="dialog-heading">
        <span class="heading-icon"><el-icon><Share /></el-icon></span>
        <div>
          <div class="heading-kicker">{{ headingKicker }}</div>
          <div class="heading-title">{{ headingTitle }}</div>
        </div>
        <el-tag v-if="currentGrant" size="small" effect="plain">
          {{ currentGrant.dbType || catalog?.dbType || 'DATABASE' }}
        </el-tag>
      </div>
    </template>

    <div class="er-toolbar">
      <label class="toolbar-field source-field">
        <span>逻辑数据源</span>
        <el-select
          v-model="source"
          placeholder="选择要绘制的数据源"
          @change="onSourceChange"
        >
          <el-option
            v-for="grant in grants"
            :key="grant.id"
            :label="grant.grantedSourceName"
            :value="grant.grantedSourceName"
          />
        </el-select>
      </label>
      <label class="toolbar-field schema-field">
        <span>{{ scopeLabel }}</span>
        <el-select
          v-model="schema"
          filterable
          :placeholder="`选择 ${scopeLabel}`"
          :disabled="!catalog"
          @change="onSchemaChange"
        >
          <el-option
            v-for="item in catalog?.schemas || []"
            :key="item"
            :label="item"
            :value="item"
          />
        </el-select>
      </label>
      <label class="toolbar-field table-field">
        <span>关系图表（最多 24 张）</span>
        <el-select
          v-model="selectedTableNames"
          multiple
          filterable
          collapse-tags
          collapse-tags-tooltip
          :max-collapse-tags="2"
          :multiple-limit="24"
          :disabled="!schema"
          placeholder="搜索并选择要绘制的表"
          @change="onTableSelectionChange"
        >
          <el-option
            v-for="table in availableTables"
            :key="table.qualifiedName"
            :label="table.remarks ? `${table.name} · ${table.remarks}` : table.name"
            :value="table.name"
          />
        </el-select>
      </label>
      <label class="toolbar-field search-field">
        <span>画布过滤</span>
        <el-input
          v-model="filterText"
          :prefix-icon="Search"
          clearable
          placeholder="输入表名、字段名或注释"
        />
      </label>
      <label class="toolbar-field mode-field">
        <span>字段标题</span>
        <el-select v-model="fieldMode">
          <el-option label="字段名" value="physical" />
          <el-option label="注释名" value="comment" />
          <el-option label="字段名 + 注释" value="both" />
        </el-select>
      </label>
      <div class="toolbar-actions">
        <el-button
          type="primary"
          :icon="Share"
          :loading="diagramLoading"
          :disabled="!selectedTableNames.length"
          @click="loadDiagram"
        >
          加载关系图
        </el-button>
        <el-button :icon="Aim" :disabled="!nodes.length" @click="fitView">
          适应画布
        </el-button>
        <el-button
          :icon="Download"
          :disabled="!visibleTables.length"
          @click="exportSvg"
        >
          导出 SVG
        </el-button>
        <el-button
          :icon="Refresh"
          :loading="catalogLoading || diagramLoading"
          :disabled="!source"
          @click="refreshAll"
        >
          刷新
        </el-button>
      </div>
    </div>

    <el-alert
      v-if="error"
      type="error"
      :closable="false"
      :title="error"
      class="er-alert"
    />
    <el-alert
      v-if="catalog?.truncated"
      type="warning"
      :closable="false"
      title="授权目录已达 2500 个对象安全上限，候选表可能不完整"
      class="er-alert"
    />
    <el-alert
      v-if="isMaxCompute && source"
      type="info"
      :closable="false"
      title="MaxCompute 结构地图只展示已选表的元数据；当前未接入 DataWorks 作业血缘，因此不推测上下游关系"
      class="er-alert"
    />

    <div class="scope-strip">
      <span><b>{{ source || '未选择' }}</b> 逻辑数据源</span>
      <span><b>{{ schema || '未选择' }}</b> {{ scopeLabel }}</span>
      <span><b>{{ selectedTableNames.length }}</b> 张已选表</span>
      <span><b>{{ visibleTables.length }}</b> 张可见表</span>
      <span><b>{{ visibleEdges.length }}</b> 条真实关系</span>
      <span class="scope-hint">滚轮缩放 · 拖动画布 · 拖动表节点</span>
    </div>

    <div class="er-canvas">
      <VueFlow
        v-if="nodes.length"
        :nodes="nodes"
        :edges="edges"
        :min-zoom="0.2"
        :max-zoom="2.4"
        fit-view-on-init
        class="enterprise-flow"
        @pane-ready="onPaneReady"
      >
        <template #node-table="{ data }">
          <article class="er-table-node">
            <Handle type="target" :position="Position.Left" class="er-handle" />
            <header>
              <el-icon><Grid /></el-icon>
              <div>
                <strong :title="data.name">{{ data.name }}</strong>
                <small>{{ data.schema }}</small>
              </div>
              <span>{{ data.columns.length }}</span>
            </header>
            <ul>
              <li
                v-for="(column, index) in data.columns.slice(0, 30)"
                :key="column.name"
                :title="column.remarks || column.name"
              >
                <i>{{ String(index + 1).padStart(2, '0') }}</i>
                <b v-if="column.primaryKey">PK</b>
                <code>{{ columnTitle(column) }}</code>
                <em>{{ column.typeName || '' }}</em>
              </li>
              <li v-if="data.columns.length > 30" class="more">
                另有 {{ data.columns.length - 30 }} 个字段
              </li>
            </ul>
            <Handle type="source" :position="Position.Right" class="er-handle" />
          </article>
        </template>
        <Background :gap="24" :size="1" />
        <Controls />
      </VueFlow>
      <el-empty
        v-else-if="!catalogLoading && !diagramLoading"
        :description="emptyDescription"
        :image-size="82"
      />
      <div v-else class="er-loading">
        <span class="loading-ring"></span>
        <div>
          <strong>{{ catalogLoading ? '正在读取授权目录' : '正在构建 ER 图' }}</strong>
          <small>仅处理当前逻辑数据源的授权对象</small>
        </div>
      </div>
    </div>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, nextTick, ref, shallowRef, watch } from 'vue'
import {
  Handle,
  MarkerType,
  Position,
  VueFlow,
  type Edge,
  type Node,
} from '@vue-flow/core'
import { Background } from '@vue-flow/background'
import { Controls } from '@vue-flow/controls'
import {
  Aim,
  Download,
  Grid,
  Refresh,
  Search,
  Share,
} from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import {
  entApi,
  type EnterpriseMetadataCatalog,
  type LogicalGrant,
} from '@/api/ent'
import type { ErColumn, ErDiagram, ErTable } from '@/types/metadata'
import { saveBlob } from '@/utils/download'
import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'
import '@vue-flow/controls/dist/style.css'

type FieldDisplayMode = 'physical' | 'comment' | 'both'

const props = defineProps<{
  visible: boolean
  grants: LogicalGrant[]
  initialSource?: string
  initialSchema?: string
}>()
const emit = defineEmits<{ 'update:visible': [value: boolean] }>()

const visibleRef = ref(props.visible)
const source = ref('')
const schema = ref('')
const selectedTableNames = ref<string[]>([])
const filterText = ref('')
const fieldMode = ref<FieldDisplayMode>('physical')
const catalog = shallowRef<EnterpriseMetadataCatalog | null>(null)
const diagram = shallowRef<ErDiagram | null>(null)
const catalogLoading = ref(false)
const diagramLoading = ref(false)
const error = ref('')
const flowInstance = ref<any>(null)
let catalogVersion = 0
let diagramVersion = 0

const currentGrant = computed(() =>
  props.grants.find(grant => grant.grantedSourceName === source.value),
)

const isMaxCompute = computed(() =>
  (currentGrant.value?.dbType || catalog.value?.dbType || '')
    .toLocaleUpperCase() === 'MAXCOMPUTE',
)
const headingKicker = computed(() =>
  isMaxCompute.value ? 'MAXCOMPUTE METADATA MAP' : 'AUTHORIZED ER WORKSPACE',
)
const headingTitle = computed(() =>
  isMaxCompute.value ? 'MaxCompute 结构地图' : '企业 ER 图',
)
const scopeLabel = computed(() =>
  isMaxCompute.value ? 'Project' : 'Schema',
)
const availableTables = computed(() =>
  (catalog.value?.tables || []).filter(table =>
    table.schema.toLocaleLowerCase()
      === schema.value.toLocaleLowerCase(),
  ),
)
const emptyDescription = computed(() => {
  if (!source.value) return '请先选择逻辑数据源'
  if (!schema.value) return `请选择 ${scopeLabel.value}`
  if (!selectedTableNames.value.length) {
    return `请搜索并选择 1–24 张表，再加载${isMaxCompute.value ? '结构地图' : '关系图'}`
  }
  if (!diagram.value) return '点击“加载关系图”读取所选表元数据'
  if (!filterText.value.trim()) return '所选表未返回可展示的元数据'
  return '没有符合当前画布过滤条件的表'
})

const normalizedTables = computed(() =>
  (diagram.value?.tables || []).map(table => ({
    ...table,
    columnDetails: tableColumns(table),
  })),
)

const visibleTables = computed(() => {
  const keyword = filterText.value.trim().toLocaleLowerCase()
  if (!keyword) return normalizedTables.value
  return normalizedTables.value.filter(table =>
    table.name.toLocaleLowerCase().includes(keyword)
    || (table.remarks || '').toLocaleLowerCase().includes(keyword)
    || table.columnDetails.some(column =>
      column.name.toLocaleLowerCase().includes(keyword)
      || (column.remarks || '')
        .toLocaleLowerCase().includes(keyword),
    )
  )
})

const visibleNames = computed(() =>
  new Set(visibleTables.value.map(table => table.name)),
)
const visibleEdges = computed(() =>
  (diagram.value?.edges || []).filter(edge =>
    visibleNames.value.has(edge.source)
    && visibleNames.value.has(edge.target),
  ),
)

const NODE_W = 250
const NODE_H = 280
const GAP_X = 100
const GAP_Y = 104

const nodes = computed<Node[]>(() => {
  const tables = visibleTables.value
  const columns = Math.max(1, Math.ceil(Math.sqrt(tables.length * 1.4)))
  return tables.map((table, index) => {
    const row = Math.floor(index / columns)
    const column = index % columns
    const offset = row % 2 ? (NODE_W + GAP_X) / 2 : 0
    return {
      id: table.name,
      type: 'table',
      position: {
        x: column * (NODE_W + GAP_X) + offset,
        y: row * (NODE_H + GAP_Y),
      },
      data: {
        name: table.name,
        schema: table.schema || schema.value,
        columns: table.columnDetails,
      },
      style: { width: `${NODE_W}px` },
    }
  })
})

const edges = computed<Edge[]>(() =>
  visibleEdges.value.map((edge, index) => ({
    id: `er-${index}-${edge.source}-${edge.target}`,
    source: edge.source,
    target: edge.target,
    label: `${edge.sourceColumn} → ${edge.targetColumn}`,
    type: 'smoothstep',
    markerEnd: {
      type: MarkerType.ArrowClosed,
      color: 'var(--color-brand)',
      width: 16,
      height: 16,
    },
    style: {
      stroke: 'var(--color-border-strong)',
      strokeWidth: 1.35,
    },
    labelStyle: {
      fill: 'var(--color-text-muted)',
      fontSize: 10,
    },
  })),
)

function tableColumns(table: ErTable): ErColumn[] {
  if (table.columnDetails?.length) return table.columnDetails
  return (table.columns || []).map(name => ({ name }))
}

function columnTitle(column: ErColumn): string {
  const remark = column.remarks?.trim()
  if (fieldMode.value === 'comment' && remark) return remark
  if (fieldMode.value === 'both' && remark) {
    return `${column.name} · ${remark}`
  }
  return column.name
}

async function loadCatalog(refresh = false) {
  const sourceSnapshot = source.value
  if (!sourceSnapshot) return
  const schemaSnapshot = schema.value
  const selectionSnapshot = [...selectedTableNames.value]
  const version = ++catalogVersion
  ++diagramVersion
  catalogLoading.value = true
  error.value = ''
  diagram.value = null
  try {
    const next = await entApi.metadataCatalog(sourceSnapshot, refresh)
    if (version !== catalogVersion || sourceSnapshot !== source.value) return
    catalog.value = next
    const current = schemaSnapshot
      && next.schemas.includes(schemaSnapshot)
      ? schemaSnapshot : ''
    const preferred = props.initialSchema
      && next.schemas.includes(props.initialSchema)
      ? props.initialSchema : ''
    schema.value = current || preferred || next.schemas[0] || ''
    const candidateNames = new Set(next.tables
      .filter(table => table.schema === schema.value)
      .map(table => table.name))
    selectedTableNames.value = selectionSnapshot
      .filter(tableName => candidateNames.has(tableName))
  } catch (exception: any) {
    if (version !== catalogVersion) return
    catalog.value = null
    schema.value = ''
    selectedTableNames.value = []
    error.value = exception.message || '授权元数据目录加载失败'
  } finally {
    if (version === catalogVersion) catalogLoading.value = false
  }
}

async function loadDiagram() {
  const sourceSnapshot = source.value
  const schemaSnapshot = schema.value
  const tablesSnapshot = [...selectedTableNames.value]
  if (!sourceSnapshot || !schemaSnapshot || !tablesSnapshot.length) {
    diagram.value = null
    return
  }
  const version = ++diagramVersion
  diagramLoading.value = true
  error.value = ''
  try {
    const next = await entApi.erDiagram(
      sourceSnapshot, schemaSnapshot, tablesSnapshot,
    )
    if (version !== diagramVersion
      || sourceSnapshot !== source.value
      || schemaSnapshot !== schema.value
      || tablesSnapshot.join('\u0000')
        !== selectedTableNames.value.join('\u0000')) return
    diagram.value = next
    await nextTick()
    requestAnimationFrame(fitView)
  } catch (exception: any) {
    if (version !== diagramVersion) return
    diagram.value = null
    error.value = exception.message || '关系图加载失败'
  } finally {
    if (version === diagramVersion) diagramLoading.value = false
  }
}

function onSourceChange() {
  ++catalogVersion
  ++diagramVersion
  catalog.value = null
  diagram.value = null
  schema.value = ''
  selectedTableNames.value = []
  filterText.value = ''
  void loadCatalog(false)
}

function onSchemaChange() {
  ++diagramVersion
  diagram.value = null
  selectedTableNames.value = []
  filterText.value = ''
  error.value = ''
}

function onTableSelectionChange() {
  ++diagramVersion
  diagram.value = null
  error.value = ''
}

async function refreshAll() {
  await loadCatalog(true)
  if (selectedTableNames.value.length) await loadDiagram()
}

function onPaneReady(instance: any) {
  flowInstance.value = instance
}

function fitView() {
  flowInstance.value?.fitView?.({
    padding: 0.16,
    duration: 360,
  })
}

function escapeXml(value: string): string {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

async function exportSvg() {
  const tables = visibleTables.value
  if (!tables.length) return
  const width = 920
  const cardWidth = 270
  const columns = 3
  const cardGap = 22
  const cardHeights = tables.map(table =>
    58 + Math.min(table.columnDetails?.length || 0, 24) * 18)
  const rows = Math.ceil(tables.length / columns)
  const rowHeights = Array.from({ length: rows }, (_, row) =>
    Math.max(...cardHeights.slice(row * columns, row * columns + columns), 120))
  const rowOffsets: number[] = []
  rowHeights.reduce((offset, height, index) => {
    rowOffsets[index] = offset
    return offset + height + cardGap
  }, 56)
  const height = rowHeights.reduce((sum, value) => sum + value + cardGap, 90)
  const parts = [
    `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" viewBox="0 0 ${width} ${height}" font-family="Segoe UI, sans-serif">`,
    '<rect width="100%" height="100%" fill="#0c101b"/>',
    `<text x="28" y="30" fill="#f2f4ff" font-size="15" font-weight="700">${escapeXml(source.value)} · ${escapeXml(schema.value)}</text>`,
  ]
  tables.forEach((table, index) => {
    const row = Math.floor(index / columns)
    const column = index % columns
    const x = 28 + column * (cardWidth + cardGap)
    const y = rowOffsets[row]
    const cardHeight = cardHeights[index]
    parts.push(`<rect x="${x}" y="${y}" width="${cardWidth}" height="${cardHeight}" rx="10" fill="#151b2a" stroke="#39415a"/>`)
    parts.push(`<text x="${x + 14}" y="${y + 24}" fill="#f2f4ff" font-size="12" font-weight="700">${escapeXml(table.name)}</text>`)
    table.columnDetails?.slice(0, 24).forEach((columnValue, columnIndex) => {
      const label = columnTitle(columnValue)
      parts.push(`<text x="${x + 14}" y="${y + 49 + columnIndex * 18}" fill="#b8c0d9" font-size="9" font-family="Consolas, monospace">${escapeXml(label)}</text>`)
    })
  })
  parts.push('</svg>')
  const name = `er_${source.value}_${schema.value}_${Date.now()}.svg`
  await saveBlob(
    new Blob([parts.join('')], {
      type: 'image/svg+xml;charset=utf-8',
    }),
    name,
  )
  ElMessage.success('ER 图 SVG 已导出')
}

watch(() => props.visible, value => {
  visibleRef.value = value
  if (!value) {
    ++catalogVersion
    ++diagramVersion
    catalogLoading.value = false
    diagramLoading.value = false
    return
  }
  const preferred = props.initialSource
    && props.grants.some(grant =>
      grant.grantedSourceName === props.initialSource)
    ? props.initialSource : ''
  const nextSource = preferred
    || source.value
    || props.grants[0]?.grantedSourceName
    || ''
  if (source.value !== nextSource) {
    source.value = nextSource
    schema.value = ''
    catalog.value = null
    diagram.value = null
    selectedTableNames.value = []
    filterText.value = ''
    ++catalogVersion
    ++diagramVersion
  }
  if (source.value) void loadCatalog(false)
})
watch(visibleRef, value => emit('update:visible', value))
watch(filterText, () => {
  nextTick(() => requestAnimationFrame(fitView))
})
</script>

<style scoped>
.dialog-heading {
  display: flex;
  align-items: center;
  gap: 10px;
}
.heading-icon {
  display: grid;
  width: 36px;
  height: 36px;
  place-items: center;
  border: 1px solid color-mix(in srgb, var(--color-brand) 42%, var(--color-border));
  border-radius: 10px;
  background: var(--color-active);
  color: var(--color-brand);
  font-size: 18px;
}
.heading-kicker { color: var(--color-brand); font-size: 9px; font-weight: 760; letter-spacing: .12em; }
.heading-title { margin-top: 2px; font-size: 15px; font-weight: 700; }
.dialog-heading .el-tag { margin-left: auto; margin-right: 38px; }

.er-toolbar {
  display: flex;
  align-items: flex-end;
  gap: 8px;
  padding: 9px;
  border: 1px solid var(--color-border);
  border-radius: 12px;
  background: var(--color-panel-header);
}
.toolbar-field { display: flex; flex-direction: column; gap: 4px; }
.toolbar-field > span { color: var(--color-text-muted); font-size: 9px; }
.source-field { width: 230px; }
.schema-field { width: 210px; }
.table-field { width: 330px; }
.search-field { width: 190px; }
.mode-field { width: 170px; }
.toolbar-actions { display: flex; flex-wrap: wrap; gap: 6px; margin-left: auto; }
.er-alert { margin-top: 8px; }
.scope-strip {
  display: flex;
  gap: 16px;
  min-height: 34px;
  align-items: center;
  padding: 0 10px;
  color: var(--color-text-muted);
  font-size: 10px;
}
.scope-strip b { color: var(--color-foreground); font-weight: 700; }
.scope-hint { margin-left: auto; }
.er-canvas {
  position: relative;
  height: 67vh;
  min-height: 480px;
  overflow: hidden;
  border: 1px solid var(--color-border);
  border-radius: 14px;
  background: var(--color-background);
}
.enterprise-flow { width: 100%; height: 100%; }
.er-loading {
  display: flex;
  height: 100%;
  align-items: center;
  justify-content: center;
  gap: 14px;
}
.er-loading div { display: flex; flex-direction: column; gap: 4px; }
.er-loading small { color: var(--color-text-muted); }
.loading-ring {
  width: 34px;
  height: 34px;
  border: 2px solid var(--color-border);
  border-top-color: var(--color-brand);
  border-radius: 50%;
  animation: spin 1s linear infinite;
}
@keyframes spin { to { transform: rotate(360deg); } }

.er-table-node {
  overflow: visible;
  border: 1px solid var(--color-panel-border);
  border-radius: 11px;
  background: var(--color-panel);
  box-shadow: var(--shadow-panel);
}
.er-table-node header {
  display: grid;
  min-height: 44px;
  grid-template-columns: 24px 1fr auto;
  align-items: center;
  gap: 7px;
  padding: 5px 9px;
  border-bottom: 1px solid var(--color-border);
  background: var(--color-panel-header);
  border-radius: 10px 10px 0 0;
}
.er-table-node header > .el-icon { color: var(--color-brand); font-size: 17px; }
.er-table-node header div { min-width: 0; display: flex; flex-direction: column; }
.er-table-node header strong,
.er-table-node header small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.er-table-node header strong { font: 650 11px var(--font-mono); }
.er-table-node header small,
.er-table-node header span { color: var(--color-text-muted); font-size: 8px; }
.er-table-node header span { padding: 2px 5px; border-radius: 4px; background: var(--color-active); }
.er-table-node ul {
  max-height: 226px;
  margin: 0;
  padding: 5px 0;
  overflow: auto;
  list-style: none;
}
.er-table-node li {
  display: grid;
  min-height: 22px;
  grid-template-columns: 20px auto minmax(70px, 1fr) auto;
  align-items: center;
  gap: 5px;
  padding: 0 8px;
}
.er-table-node li:hover { background: var(--color-hover); }
.er-table-node li i,
.er-table-node li em { color: var(--color-text-muted); font-size: 8px; font-style: normal; }
.er-table-node li b { color: #f4b942; font-size: 8px; }
.er-table-node li code { overflow: hidden; font: 9px var(--font-mono); text-overflow: ellipsis; white-space: nowrap; }
.er-table-node li.more { display: block; padding-top: 4px; color: var(--color-brand); font-size: 9px; }
.er-handle {
  width: 8px;
  height: 8px;
  border: 2px solid var(--color-panel);
  background: var(--color-brand);
}

:global(.enterprise-er-dialog.el-dialog) {
  overflow: hidden;
  border: 1px solid var(--color-panel-border);
  border-radius: 16px;
  background: var(--color-panel);
}
:global(.enterprise-er-dialog .el-dialog__header) {
  margin: 0;
  padding: 12px 16px;
  border-bottom: 1px solid var(--color-border);
}
:global(.enterprise-er-dialog .el-dialog__body) { padding: 10px 12px 12px; }
.enterprise-flow :deep(.vue-flow__node) { border: 0; background: transparent; box-shadow: none; }
.enterprise-flow :deep(.vue-flow__controls) {
  overflow: hidden;
  border: 1px solid var(--color-border);
  border-radius: 8px;
  background: var(--color-panel);
}
.enterprise-flow :deep(.vue-flow__controls-button) {
  border-bottom-color: var(--color-border);
  background: transparent;
  fill: var(--color-foreground);
}

@media (max-width: 1050px) {
  .er-toolbar { flex-wrap: wrap; }
  .toolbar-actions { width: 100%; margin-left: 0; }
  .source-field,
  .schema-field,
  .table-field,
  .search-field,
  .mode-field { width: calc(50% - 4px); }
}
@media (max-width: 680px) {
  .source-field,
  .schema-field,
  .table-field,
  .search-field,
  .mode-field { width: 100%; }
  .scope-strip { flex-wrap: wrap; padding: 6px 8px; }
  .scope-hint { width: 100%; margin-left: 0; }
}
</style>
