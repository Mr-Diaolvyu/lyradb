<template>
  <div class="table-detail-tab" v-loading="loading">
    <template v-if="!loading && columns.length > 0">
      <!-- 表信息头部 + 操作按钮 -->
      <div class="detail-header">
        <div class="header-info">
          <h3 class="table-title">
            <span v-if="schema" class="table-schema">{{ schema }}.</span>
            <span class="table-name">{{ tableName }}</span>
          </h3>
          <div class="table-meta">
            <el-tag size="small" type="info">{{ columns.length }} 列</el-tag>
            <el-tag v-if="pkColumns.length > 0" size="small" type="danger" effect="dark">
              PK: {{ pkColumns.join(', ') }}
            </el-tag>
          </div>
        </div>
        <div class="header-actions">
          <el-button-group>
            <el-tooltip content="复制 DDL" placement="bottom">
              <el-button :icon="CopyDocument" size="small" @click="copyDdl" />
            </el-tooltip>
            <el-tooltip content="复制表名" placement="bottom">
              <el-button :icon="DocumentCopy" size="small" @click="copyTableName" />
            </el-tooltip>
            <el-tooltip content="生成 SELECT" placement="bottom">
              <el-button :icon="Search" size="small" @click="genSelect" />
            </el-tooltip>
            <el-tooltip content="导出 CSV" placement="bottom">
              <el-button :icon="Download" size="small" @click="exportCsv" />
            </el-tooltip>
          </el-button-group>
        </div>
      </div>

      <!-- 内容区：字段表格 + DDL -->
      <div class="detail-body">
        <!-- 字段列表 -->
        <div class="detail-section">
          <div class="section-header">
            <span class="section-title">字段列表</span>
            <el-input
              v-model="filterText"
              size="small"
              placeholder="过滤字段..."
              :prefix-icon="Search"
              clearable
              style="width: 200px"
            />
          </div>
          <div class="columns-table-wrapper">
            <table class="columns-table">
              <thead>
                <tr>
                  <th class="col-idx">#</th>
                  <th class="col-name">字段名</th>
                  <th class="col-type">数据类型</th>
                  <th class="col-size">长度</th>
                  <th class="col-null">可空</th>
                  <th class="col-default">默认值</th>
                  <th class="col-key">键</th>
                  <th class="col-extra">额外</th>
                  <th class="col-remarks">注释</th>
                </tr>
              </thead>
              <tbody>
                <tr
                  v-for="(col, idx) in filteredColumns"
                  :key="col.name"
                  :class="{ 'row-pk': col.primaryKey }"
                >
                  <td class="col-idx">{{ idx + 1 }}</td>
                  <td class="col-name">
                    <span :class="{ 'pk-name': col.primaryKey }">{{ col.name }}</span>
                  </td>
                  <td class="col-type">
                    <span class="type-badge">{{ col.typeName }}</span>
                  </td>
                  <td class="col-size">
                    <span v-if="col.columnSize > 0">{{ col.columnSize }}{{ col.decimalDigits > 0 ? ',' + col.decimalDigits : '' }}</span>
                    <span v-else class="text-dash">—</span>
                  </td>
                  <td class="col-null">
                    <span :class="col.nullable ? 'tag-yes' : 'tag-no'">
                      {{ col.nullable ? 'YES' : 'NO' }}
                    </span>
                  </td>
                  <td class="col-default">
                    <code v-if="col.defaultValue !== null && col.defaultValue !== undefined">{{ col.defaultValue }}</code>
                    <span v-else class="text-dash">—</span>
                  </td>
                  <td class="col-key">
                    <span v-if="col.primaryKey" class="key-badge pk">PK</span>
                    <span v-if="col.autoIncrement" class="key-badge ai">AI</span>
                  </td>
                  <td class="col-extra">
                    <span v-if="col.autoIncrement" class="extra-text">auto_increment</span>
                    <span v-else class="text-dash">—</span>
                  </td>
                  <td class="col-remarks">
                    <span v-if="col.remarks" class="remarks-text">{{ col.remarks }}</span>
                    <span v-else class="text-dash">—</span>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <!-- DDL 语句 -->
        <div class="detail-section" v-if="ddl">
          <div class="section-header">
            <span class="section-title">DDL</span>
            <el-button :icon="CopyDocument" size="small" text @click="copyDdl">复制</el-button>
          </div>
          <div class="ddl-block">
            <pre>{{ ddl }}</pre>
          </div>
        </div>
      </div>
    </template>

    <!-- 加载中/空状态 -->
    <div v-if="!loading && columns.length === 0" class="detail-empty">
      <el-empty description="无法获取表字段信息" :image-size="80" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { CopyDocument, DocumentCopy, Search, Download } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import type { ColumnMetadata } from '@/types/metadata'
import { useEditorStore, type TableDetailTab } from '@/stores/editor'
import { saveBlob } from '@/utils/download'

const props = defineProps<{
  tab: TableDetailTab
}>()

const editorStore = useEditorStore()
const filterText = ref('')

const loading = computed(() => props.tab.loading)
const columns = computed(() => props.tab.columns)
const ddl = computed(() => props.tab.ddl)
const tableName = computed(() => props.tab.tableName)
const schema = computed(() => props.tab.schema)
const connectionId = computed(() => props.tab.connectionId)

const pkColumns = computed(() =>
  columns.value.filter(c => c.primaryKey).map(c => c.name)
)

const filteredColumns = computed(() => {
  if (!filterText.value) return columns.value
  const q = filterText.value.toLowerCase()
  return columns.value.filter(c =>
    c.name.toLowerCase().includes(q) ||
    c.typeName.toLowerCase().includes(q) ||
    (c.remarks?.toLowerCase().includes(q) ?? false)
  )
})

async function copyDdl() {
  if (!ddl.value) {
    ElMessage.warning('无 DDL 可复制')
    return
  }
  try {
    await navigator.clipboard.writeText(ddl.value)
    ElMessage.success('DDL 已复制')
  } catch {
    ElMessage.error('复制失败')
  }
}

async function copyTableName() {
  const full = schema.value ? `${schema.value}.${tableName.value}` : tableName.value
  try {
    await navigator.clipboard.writeText(full)
    ElMessage.success('表名已复制')
  } catch {
    ElMessage.error('复制失败')
  }
}

function genSelect() {
  const full = schema.value ? `${schema.value}.${tableName.value}` : tableName.value
  const colList = columns.value.map(c => c.name).join(', ')
  const sql = `SELECT\n  ${colList}\nFROM ${full}\nLIMIT 100;`
  const tabId = editorStore.createTab(connectionId.value, `SELECT: ${tableName.value}`)
  editorStore.updateSql(tabId, sql)
}

async function exportCsv() {
  const headers = ['name', 'type', 'size', 'nullable', 'default', 'key', 'extra', 'remarks']
  const rows = columns.value.map(c => [
    c.name,
    c.typeName,
    c.columnSize > 0 ? String(c.columnSize) : '',
    c.nullable ? 'YES' : 'NO',
    c.defaultValue ?? '',
    c.primaryKey ? 'PK' : '',
    c.autoIncrement ? 'auto_increment' : '',
    c.remarks ?? '',
  ])
  const csv = [headers, ...rows]
    .map(row => row.map(cell => `"${String(cell).replace(/"/g, '""')}"`).join(','))
    .join('\n')
  const bom = '\uFEFF'
  const blob = new Blob([bom + csv], { type: 'text/csv;charset=utf-8' })
  await saveBlob(blob, `${tableName.value}_columns.csv`)
  ElMessage.success('CSV 已导出')
}
</script>

<style scoped>
.table-detail-tab {
  display: flex;
  flex-direction: column;
  height: 100%;
  overflow: hidden;
}

/* === 头部 === */
.detail-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  border-bottom: 1px solid var(--color-border);
  flex-shrink: 0;
}

.table-title {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: var(--color-foreground);
}

.table-schema {
  color: var(--color-muted);
  font-weight: 400;
}

.table-meta {
  display: flex;
  gap: 6px;
  margin-top: 4px;
}

.header-actions {
  display: flex;
  gap: 8px;
}

/* === 内容区 === */
.detail-body {
  flex: 1;
  overflow-y: auto;
  padding: 0;
}

.detail-section {
  border-bottom: 1px solid var(--color-border);
}

.section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 16px;
  background: var(--color-panel-header);
  border-bottom: 1px solid var(--color-border);
}

.section-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--color-secondary);
}

/* === 字段表格 === */
.columns-table-wrapper {
  overflow-x: auto;
}

.columns-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}

.columns-table th {
  text-align: left;
  padding: 8px 12px;
  font-weight: 600;
  font-size: 12px;
  color: var(--color-foreground);
  background: var(--color-panel-header);
  border-bottom: 2px solid var(--color-border);
  white-space: nowrap;
  position: sticky;
  top: 0;
  z-index: 1;
}

.columns-table td {
  padding: 7px 12px;
  border-bottom: 1px solid var(--color-border);
  color: var(--color-foreground);
  vertical-align: middle;
}

.columns-table tbody tr:hover {
  background: var(--color-hover);
}

.columns-table tbody tr.row-pk {
  background: rgba(220, 38, 38, 0.04);
}

.columns-table tbody tr.row-pk:hover {
  background: rgba(220, 38, 38, 0.08);
}

/* 列宽 */
.col-idx { width: 36px; text-align: center; color: var(--color-muted); }
.col-name { min-width: 140px; }
.col-type { min-width: 120px; }
.col-size { width: 80px; }
.col-null { width: 60px; }
.col-default { min-width: 120px; }
.col-key { width: 60px; }
.col-extra { width: 120px; }
.col-remarks { min-width: 160px; }

/* 字段名样式 */
.pk-name {
  color: #DC2626;
  font-weight: 700;
}

/* 类型徽章 */
.type-badge {
  font-family: var(--font-mono, 'JetBrains Mono', monospace);
  font-size: 12px;
  color: #2563EB;
  background: rgba(37, 99, 235, 0.08);
  padding: 1px 6px;
  border-radius: 3px;
}

/* 可空标记 */
.tag-yes {
  color: #059669;
  font-weight: 500;
}

.tag-no {
  color: #DC2626;
  font-weight: 500;
}

/* 键标记 */
.key-badge {
  display: inline-block;
  font-size: 10px;
  font-weight: 700;
  padding: 1px 4px;
  border-radius: 3px;
  margin-right: 2px;
}

.key-badge.pk {
  color: #fff;
  background: #DC2626;
}

.key-badge.ai {
  color: #fff;
  background: #059669;
}

/* 默认值 */
.col-default code {
  font-family: var(--font-mono, 'JetBrains Mono', monospace);
  font-size: 12px;
  color: var(--color-foreground);
  background: var(--color-hover);
  padding: 1px 4px;
  border-radius: 3px;
}

/* 破折号 */
.text-dash {
  color: var(--color-muted);
}

.extra-text {
  font-size: 11px;
  color: #059669;
}

.remarks-text {
  font-size: 12px;
  color: var(--color-foreground);
  opacity: 0.8;
}

/* === DDL 区 === */
.ddl-block {
  margin: 8px 16px 16px;
  background: var(--color-background);
  border: 1px solid var(--color-border);
  border-radius: 6px;
  overflow: hidden;
}

.ddl-block pre {
  margin: 0;
  padding: 12px 16px;
  font-family: var(--font-mono, 'JetBrains Mono', monospace);
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
  color: var(--color-foreground);
  max-height: 400px;
  overflow-y: auto;
}

/* === 空状态 === */
.detail-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
}

/* === 暗色主题适配 === */
:root[data-theme="dark"] .type-badge {
  color: #60A5FA;
  background: rgba(96, 165, 250, 0.12);
}

:root[data-theme="dark"] .columns-table tbody tr.row-pk {
  background: rgba(248, 113, 113, 0.06);
}

:root[data-theme="dark"] .columns-table tbody tr.row-pk:hover {
  background: rgba(248, 113, 113, 0.12);
}

:root[data-theme="dark"] .tag-yes {
  color: #34D399;
}

:root[data-theme="dark"] .tag-no {
  color: #F87171;
}

:root[data-theme="dark"] .pk-name {
  color: #F87171;
}

:root[data-theme="dark"] .key-badge.pk {
  background: #DC2626;
}

:root[data-theme="dark"] .key-badge.ai {
  background: #059669;
}

:root[data-theme="dark"] .extra-text {
  color: #34D399;
}
</style>
