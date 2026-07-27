<template>
  <div class="data-table-wrapper">
    <!-- 工具栏 -->
    <div class="table-toolbar">
      <span class="row-count">
        共 {{ rows.length }} 行
        <span v-if="rows.length > 0" class="col-count">{{ columns.length }} 列</span>
      </span>
      <div class="toolbar-right">
        <el-tag v-if="editable && editContext" size="small" type="warning" effect="plain" class="edit-tag">
          <el-icon><EditPen /></el-icon>
          编辑模式 · 双击单元格编辑
        </el-tag>
        <el-tooltip v-if="remarksLoader" :content="showRemarks ? '显示原始字段名' : '显示字段注释'" placement="top">
          <el-button
            size="small"
            :type="showRemarks ? 'primary' : ''"
            :loading="remarksLoading"
            text
            bg
            @click="toggleRemarks"
          >
            {{ showRemarks ? '注释' : '字段名' }}
          </el-button>
        </el-tooltip>
        <el-input
          v-model="filterText"
          size="small"
          placeholder="过滤行..."
          :prefix-icon="Search"
          clearable
          style="width: 200px"
        />
      </div>
    </div>

    <!-- Vxe-table 数据表格 -->
    <div class="table-container">
      <vxe-table
        ref="tableRef"
        :data="filteredRows"
        :height="tableHeight"
        :row-config="{ isHover: true, isCurrent: true }"
        :cell-config="{ height: rowHeight }"
        :column-config="{ isCurrent: true, resizable: true }"
        :scroll-y="{ enabled: true, gt: 50 }"
        :scroll-x="{ enabled: true, gt: 20 }"
        show-overflow
        show-header-overflow
        border
        stripe
        empty-text="暂无数据"
      >
        <vxe-column type="seq" width="50" align="center" header-align="center" fixed="left" />
        <vxe-column
          v-for="col in columns"
          :key="col"
          :field="col"
          :title="col"
          min-width="120"
          :align="colTypes[col] === 'number' ? 'right' : 'left'"
          show-overflow
        >
          <template #header>
            <span class="col-type-badge" :title="colTypes[col]">{{ typeBadge(colTypes[col]) }}</span>
            <span :title="headerTitle(col) === col ? undefined : col">{{ headerTitle(col) }}</span>
            <el-icon v-if="isPk(col)" class="pk-icon" title="主键"><Key /></el-icon>
          </template>
          <template #default="{ row }">
            <el-input
              v-if="isEditing(row, col)"
              v-model="editValue"
              size="small"
              class="cell-editor"
              @blur="commitEdit"
              @keyup.enter="commitEdit"
              @keyup.esc="cancelEdit"
            />
            <span
              v-else-if="previewable(row[col])"
              class="cell-blob"
              @click="openPreview(row[col], col)"
              :title="'点击预览'"
            >
              {{ previewLabel(row[col]) }}
            </span>
            <span
              v-else
              :class="getCellClass(row[col])"
              @dblclick="startEdit(row, col)"
            >
              {{ formatCellValue(row[col]) }}
            </span>
          </template>
        </vxe-column>
      </vxe-table>
    </div>

    <!-- 单元格预览（JSON/BLOB） -->
    <el-dialog v-model="previewVisible" :title="previewTitle" width="640" append-to-body>
      <pre class="preview-content">{{ previewContent }}</pre>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, nextTick, onMounted, onUnmounted } from 'vue'
import { Search, EditPen, Key } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { useThemeStore } from '@/stores/theme'

// === Props ===
const props = defineProps<{
  columns: string[]
  rows: Record<string, any>[]
  /** 是否允许内联编辑（受能力描述控制） */
  editable?: boolean
  /** 编辑上下文：目标表与主键列 */
  editContext?: {
    connectionId: string
    schema: string | null
    table: string
    pkColumns: string[]
  } | null
  /** 行级 JSON 编辑模式（MongoDB 文档）：双击行触发 row-json-edit 而非单元格内联编辑 */
  jsonRowEdit?: boolean
  /** 懒加载列注释映射（col → remarks），提供后工具栏显示「字段名/注释」切换按钮 */
  remarksLoader?: (() => Promise<Record<string, string>>) | null
}>()

const emit = defineEmits<{
  'cell-edited': [payload: { row: Record<string, any>; column: string; value: any; oldValue: any }]
  'row-json-edit': [row: Record<string, any>]
}>()

// === 状态 ===
const tableRef = ref()
const tableHeight = ref(300)
const filterText = ref('')
const containerRef = ref<HTMLElement>()

// 行高跟随密度偏好（V5：舒适 32 / 紧凑 24，与 --row-h 令牌一致）
const themeStore = useThemeStore()
const rowHeight = computed(() => themeStore.density === 'compact' ? 24 : 32)

// 内联编辑状态
const editing = ref<{ row: Record<string, any>; col: string } | null>(null)
const editValue = ref('')

// === 过滤 ===
const filteredRows = computed(() => {
  if (!filterText.value) return props.rows
  const keyword = filterText.value.toLowerCase()
  return props.rows.filter(row =>
    props.columns.some(col => {
      const val = row[col]
      return val !== null && val !== undefined && String(val).toLowerCase().includes(keyword)
    })
  )
})

// === 表头字段名/注释切换 ===
const showRemarks = ref(false)
const remarksLoading = ref(false)
const remarksMap = ref<Record<string, string> | null>(null)

function headerTitle(col: string): string {
  if (showRemarks.value && remarksMap.value?.[col]) return remarksMap.value[col]
  return col
}

async function toggleRemarks() {
  if (showRemarks.value) {
    showRemarks.value = false
    return
  }
  if (remarksMap.value === null && props.remarksLoader) {
    remarksLoading.value = true
    try {
      remarksMap.value = await props.remarksLoader()
    } catch {
      remarksMap.value = {}
    } finally {
      remarksLoading.value = false
    }
  }
  if (!remarksMap.value || Object.keys(remarksMap.value).length === 0) {
    ElMessage.warning('未获取到字段注释（仅支持单表查询且表字段含注释）')
    return
  }
  showRemarks.value = true
}

// 结果列变化（新查询）时重置注释状态，避免跨表错配
watch(() => props.columns, () => {
  showRemarks.value = false
  remarksMap.value = null
})

// === 列类型推断（V3）：抽样非空值判定 number/boolean/date/json/text ===
type ColType = 'number' | 'boolean' | 'date' | 'json' | 'text'

const DATE_RE = /^\d{4}-\d{2}-\d{2}([ T]\d{2}:\d{2}(:\d{2})?)?/

const colTypes = computed<Record<string, ColType>>(() => {
  const result: Record<string, ColType> = {}
  for (const col of props.columns) {
    result[col] = inferColType(col)
  }
  return result
})

function inferColType(col: string): ColType {
  let seen = 0
  let type: ColType | null = null
  for (const row of props.rows) {
    const v = row[col]
    if (v === null || v === undefined) continue
    let cur: ColType
    if (typeof v === 'number') cur = 'number'
    else if (typeof v === 'boolean') cur = 'boolean'
    else if (typeof v === 'object') cur = 'json'
    else if (DATE_RE.test(String(v))) cur = 'date'
    else cur = 'text'
    if (type === null) type = cur
    else if (type !== cur) return 'text'
    if (++seen >= 20) break
  }
  return type ?? 'text'
}

/** 表头类型徽标字符（纯文本字形，非 emoji） */
function typeBadge(t: ColType): string {
  switch (t) {
    case 'number': return '#'
    case 'boolean': return '01'
    case 'date': return 'dt'
    case 'json': return '{}'
    default: return 'Aa'
  }
}

// === 主键判断 ===
function isPk(col: string): boolean {
  return !!props.editContext?.pkColumns.includes(col)
}

// === 内联编辑 ===
function canEditCell(col: string): boolean {
  return !!props.editable && !!props.editContext && !isPk(col)
}

function isEditing(row: Record<string, any>, col: string): boolean {
  return editing.value?.row === row && editing.value?.col === col
}

function startEdit(row: Record<string, any>, col: string) {
  // 行级 JSON 编辑模式：双击任意单元格打开整行文档编辑器
  if (props.jsonRowEdit) {
    emit('row-json-edit', row)
    return
  }
  if (!canEditCell(col)) return
  editing.value = { row, col }
  const v = row[col]
  editValue.value = v === null || v === undefined ? '' : String(v)
  nextTick(() => {
    document.querySelector<HTMLElement>('.cell-editor .el-input__inner')?.focus()
  })
}

function commitEdit() {
  if (!editing.value) return
  const { row, col } = editing.value
  const oldValue = row[col]
  const parsed = parseEditValue(editValue.value, oldValue)
  row[col] = parsed
  editing.value = null
  if (!valuesEqual(parsed, oldValue)) {
    emit('cell-edited', { row, column: col, value: parsed, oldValue })
  }
}

function cancelEdit() {
  editing.value = null
}

function parseEditValue(input: string, oldValue: any): any {
  const trimmed = input.trim()
  if (trimmed === '' || trimmed.toLowerCase() === 'null') return null
  // 保持原值类型：若原值为数字，尝试转数字
  if (typeof oldValue === 'number') {
    const n = Number(trimmed)
    return isNaN(n) ? trimmed : n
  }
  if (typeof oldValue === 'boolean') {
    return trimmed.toLowerCase() === 'true' || trimmed === '1'
  }
  return trimmed
}

function valuesEqual(a: any, b: any): boolean {
  if (a === b) return true
  if (a == null && b == null) return true
  return String(a) === String(b)
}

// === 单元格格式化 ===
function formatCellValue(val: any): string {
  if (val === null || val === undefined) return 'NULL'
  if (typeof val === 'object') return JSON.stringify(val)
  return String(val)
}

// === BLOB/JSON 预览 ===
const previewVisible = ref(false)
const previewContent = ref('')
const previewTitle = ref('预览')

function previewable(val: any): boolean {
  if (val === null || val === undefined) return false
  if (typeof val === 'object') return true
  const s = String(val)
  if (s.length > 512) return true
  const t = s.trimStart()
  return (t.startsWith('{') && t.endsWith('}')) || (t.startsWith('[') && t.endsWith(']'))
}

function previewLabel(val: any): string {
  if (typeof val === 'object') return '[JSON: ' + JSON.stringify(val).length + ' chars]'
  const s = String(val)
  if (s.length > 512) return '[BLOB: ' + s.length + ' chars]'
  return '[JSON]'
}

function openPreview(val: any, col: string) {
  previewTitle.value = `预览 - ${col}`
  let content: string
  if (typeof val === 'object') {
    try { content = JSON.stringify(val, null, 2) } catch { content = String(val) }
  } else {
    const s = String(val).trim()
    if ((s.startsWith('{') && s.endsWith('}')) || (s.startsWith('[') && s.endsWith(']'))) {
      try { content = JSON.stringify(JSON.parse(s), null, 2) } catch { content = s }
    } else {
      content = s
    }
  }
  previewContent.value = content
  previewVisible.value = true
}

function getCellClass(val: any): string {
  if (val === null || val === undefined) return 'cell-null'
  if (typeof val === 'number') return 'cell-number'
  if (typeof val === 'boolean') return 'cell-boolean'
  return ''
}

// === 表格高度自适应 ===
function updateTableHeight() {
  const container = tableRef.value?.$el?.parentElement as HTMLElement
  if (container) {
    tableHeight.value = container.clientHeight - 40 // 减去工具栏高度
  }
}

onMounted(() => {
  nextTick(updateTableHeight)
  window.addEventListener('resize', updateTableHeight)
})

onUnmounted(() => {
  window.removeEventListener('resize', updateTableHeight)
})

// 数据变化时重新计算高度
watch(() => props.rows, () => {
  nextTick(updateTableHeight)
})

// 切换编辑模式时取消正在进行的编辑
watch(() => props.editable, () => {
  editing.value = null
})
</script>

<style scoped>
.data-table-wrapper {
  display: flex;
  flex-direction: column;
  height: 100%;
  overflow: hidden;
}

.table-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 40px;
  padding: 0 var(--space-3);
  background: var(--color-panel-header);
  border-bottom: 1px solid var(--color-border);
  flex-shrink: 0;
}

.row-count {
  font-size: var(--text-caption);
  color: var(--color-muted);
}

.col-count {
  margin-left: var(--space-2);
}

.toolbar-right {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.edit-tag {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.table-container {
  flex: 1;
  overflow: hidden;
}

.pk-icon {
  color: var(--color-warning);
  margin-left: 4px;
  font-size: 12px;
  vertical-align: middle;
}

/* 表头列类型徽标 */
.col-type-badge {
  display: inline-block;
  margin-right: 5px;
  padding: 0 3px;
  font-family: var(--font-mono);
  font-size: 10px;
  font-weight: 600;
  line-height: 14px;
  color: var(--color-text-muted);
  background: var(--color-active);
  border-radius: 3px;
  vertical-align: middle;
}

.cell-editor {
  width: 100%;
}

/* 单元格样式 */
:deep(.cell-null) {
  color: var(--color-text-muted);
  font-style: italic;
  opacity: 0.65;
  font-size: var(--text-code);
}

:deep(.cell-number) {
  font-family: var(--font-mono);
  font-size: var(--text-code);
  font-variant-numeric: tabular-nums;
}

:deep(.cell-boolean) {
  font-family: var(--font-mono);
  color: var(--color-secondary);
}

:deep(.cell-blob) {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--color-secondary);
  cursor: pointer;
  padding: 2px 6px;
  border: 1px solid var(--color-border);
  border-radius: 4px;
  background: var(--color-muted);
}

:deep(.cell-blob:hover) {
  background: var(--color-active);
}

.preview-content {
  margin: 0;
  max-height: 60vh;
  overflow: auto;
  font-family: var(--font-mono, 'JetBrains Mono', monospace);
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-word;
  background: var(--color-muted);
  padding: 12px;
  border-radius: 4px;
}

/* Vxe-table 主题适配 */
:deep(.vxe-table) {
  --vxe-ui-font-family: var(--font-ui);
  --vxe-ui-font-size: var(--text-body);
}

:deep(.vxe-table .vxe-header--column) {
  background: var(--color-panel-header);
  font-weight: 600;
}

:deep(.vxe-table .vxe-body--row) {
  background: var(--color-panel);
}

:deep(.vxe-table .vxe-body--row.row--stripe) {
  background: var(--color-background);
}

:deep(.vxe-table .vxe-body--row.row--hover) {
  background: var(--color-hover);
}
</style>
