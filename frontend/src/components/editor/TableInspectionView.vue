<template>
  <section class="table-inspection" v-loading="loading">
    <header class="inspection-header">
      <div class="object-identity">
        <div class="object-orbit" aria-hidden="true">
          <el-icon><Grid /></el-icon>
        </div>
        <div class="identity-copy">
          <div class="eyebrow">TABLE WORKSPACE</div>
          <div class="qualified-name" :title="qualifiedName">{{ qualifiedName }}</div>
          <div class="object-stats">
            <span>{{ columns.length }} 字段</span>
            <span>{{ preview?.totalRows ?? 0 }} 条预览</span>
            <span>{{ constraints.length }} 项约束</span>
            <span class="object-type">{{ inspection?.objectType || 'TABLE' }}</span>
          </div>
        </div>
      </div>
      <div class="header-actions">
        <el-tooltip content="复制完整表名" placement="bottom">
          <el-button :icon="CopyDocument" circle @click="copyQualifiedName" />
        </el-tooltip>
        <el-button
          :icon="Position"
          :disabled="!inspection?.previewSql && !preview?.sql"
          @click="emitOpenSql"
        >
          在 SQL 中打开
        </el-button>
        <el-button :icon="Refresh" :loading="loading" @click="$emit('refresh')">
          刷新
        </el-button>
      </div>
    </header>

    <el-alert
      v-if="error"
      class="global-error"
      type="error"
      :title="error"
      show-icon
      :closable="false"
    />

    <el-tabs v-model="activeSection" class="inspection-tabs">
      <el-tab-pane name="preview">
        <template #label>
          <span class="tab-label"><el-icon><DataAnalysis /></el-icon>数据预览</span>
        </template>
        <div class="section-content preview-section">
          <el-alert
            v-if="sectionError('preview')"
            type="warning"
            :title="sectionError('preview')"
            show-icon
            :closable="false"
          />
          <DataTable
            v-else-if="preview"
            :columns="preview.columns"
            :rows="preview.rows"
            :remarks="columnRemarks"
          />
          <el-empty v-else description="暂无可预览数据" :image-size="68" />
        </div>
      </el-tab-pane>

      <el-tab-pane name="columns">
        <template #label>
          <span class="tab-label"><el-icon><List /></el-icon>字段结构</span>
        </template>
        <div class="section-content">
          <el-alert
            v-if="sectionError('columns')"
            type="warning"
            :title="sectionError('columns')"
            show-icon
            :closable="false"
          />
          <template v-else>
            <div class="section-toolbar">
              <span class="section-caption">字段定义与可空、默认值、主键属性</span>
              <el-input
                v-model="columnFilter"
                :prefix-icon="Search"
                placeholder="搜索字段、类型或注释"
                clearable
                class="filter-input"
              />
            </div>
            <div class="metadata-table-wrap">
              <table class="metadata-table">
                <thead>
                  <tr>
                    <th>#</th>
                    <th>字段</th>
                    <th>数据类型</th>
                    <th>长度</th>
                    <th>可空</th>
                    <th>默认值</th>
                    <th>属性</th>
                    <th>注释</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="(column, index) in filteredColumns" :key="column.name">
                    <td class="sequence">{{ index + 1 }}</td>
                    <td class="column-name">
                      <el-icon v-if="column.primaryKey" class="key-icon"><Key /></el-icon>
                      {{ column.name }}
                    </td>
                    <td><code class="type-chip">{{ column.typeName }}</code></td>
                    <td>{{ sizeLabel(column) }}</td>
                    <td>
                      <span :class="['nullable-state', column.nullable ? 'yes' : 'no']">
                        {{ column.nullable ? 'YES' : 'NO' }}
                      </span>
                    </td>
                    <td><code>{{ column.defaultValue ?? '—' }}</code></td>
                    <td>
                      <span v-if="column.primaryKey" class="mini-tag primary">PK</span>
                      <span v-if="column.autoIncrement" class="mini-tag">AUTO</span>
                      <span v-if="!column.primaryKey && !column.autoIncrement">—</span>
                    </td>
                    <td class="remarks">{{ column.remarks || '—' }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </template>
        </div>
      </el-tab-pane>

      <el-tab-pane name="constraints">
        <template #label>
          <span class="tab-label"><el-icon><Key /></el-icon>索引与约束</span>
        </template>
        <div class="section-content">
          <el-alert
            v-if="sectionError('constraints')"
            type="warning"
            :title="sectionError('constraints')"
            show-icon
            :closable="false"
          />
          <div v-else-if="constraints.length" class="constraint-grid">
            <article
              v-for="constraint in constraints"
              :key="`${constraint.type}:${constraint.name}`"
              class="constraint-card"
            >
              <div class="constraint-mark">
                <el-icon><component :is="constraint.type === 'FOREIGN_KEY' ? Link : Key" /></el-icon>
              </div>
              <div class="constraint-main">
                <div class="constraint-heading">
                  <span>{{ constraint.name || '未命名约束' }}</span>
                  <span class="constraint-type">{{ constraintTypeLabel(constraint.type) }}</span>
                </div>
                <code>{{ constraint.columns.join(', ') || '—' }}</code>
                <div v-if="constraint.referencedTable" class="reference-line">
                  → {{ constraint.referencedTable }}
                  <span v-if="constraint.referencedColumns.length">
                    ({{ constraint.referencedColumns.join(', ') }})
                  </span>
                </div>
              </div>
            </article>
          </div>
          <el-empty v-else description="未发现索引或约束" :image-size="68" />
        </div>
      </el-tab-pane>

      <el-tab-pane name="ddl">
        <template #label>
          <span class="tab-label"><el-icon><Document /></el-icon>DDL</span>
        </template>
        <div class="section-content ddl-section">
          <el-alert
            v-if="sectionError('ddl')"
            type="warning"
            :title="sectionError('ddl')"
            show-icon
            :closable="false"
          />
          <template v-else-if="inspection?.ddl">
            <div class="section-toolbar">
              <span class="section-caption">只读建表定义</span>
              <el-button :icon="CopyDocument" @click="copyDdl">复制 DDL</el-button>
            </div>
            <pre class="ddl-code">{{ inspection.ddl }}</pre>
          </template>
          <el-empty v-else description="当前驱动未返回 DDL" :image-size="68" />
        </div>
      </el-tab-pane>
    </el-tabs>
  </section>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import {
  CopyDocument,
  DataAnalysis,
  Document,
  Grid,
  Key,
  Link,
  List,
  Position,
  Refresh,
  Search,
} from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import DataTable from '@/components/editor/DataTable.vue'
import type { ColumnMetadata, TableInspection } from '@/types/metadata'

const props = defineProps<{
  inspection: TableInspection | null
  loading?: boolean
  error?: string | null
}>()

const emit = defineEmits<{
  refresh: []
  openSql: [sql: string]
}>()

const activeSection = ref('preview')
const columnFilter = ref('')
const columns = computed(() => props.inspection?.columns ?? [])
const columnRemarks = computed<Record<string, string>>(() =>
  Object.fromEntries(
    columns.value
      .filter(column => Boolean(column.remarks?.trim()))
      .map(column => [column.name, column.remarks!.trim()]),
  ),
)
const constraints = computed(() => props.inspection?.constraints ?? [])
const preview = computed(() => props.inspection?.preview ?? null)
const qualifiedName = computed(() => {
  const table = props.inspection?.table || '未选择表'
  return props.inspection?.schema ? `${props.inspection.schema}.${table}` : table
})
const filteredColumns = computed(() => {
  const keyword = columnFilter.value.trim().toLocaleLowerCase()
  if (!keyword) return columns.value
  return columns.value.filter(column =>
    column.name.toLocaleLowerCase().includes(keyword)
    || column.typeName.toLocaleLowerCase().includes(keyword)
    || (column.remarks || '').toLocaleLowerCase().includes(keyword)
  )
})

function sectionError(section: string): string {
  return props.inspection?.errors?.[section] || ''
}

function sizeLabel(column: ColumnMetadata): string {
  if (!column.columnSize || column.columnSize <= 0) return '—'
  return column.decimalDigits > 0
    ? `${column.columnSize},${column.decimalDigits}`
    : String(column.columnSize)
}

function constraintTypeLabel(type: string): string {
  return ({
    PRIMARY_KEY: '主键',
    FOREIGN_KEY: '外键',
    UNIQUE_INDEX: '唯一索引',
    INDEX: '普通索引',
  } as Record<string, string>)[type] || type
}

async function copyQualifiedName() {
  await copyText(qualifiedName.value, '完整表名已复制')
}

async function copyDdl() {
  if (props.inspection?.ddl) {
    await copyText(props.inspection.ddl, 'DDL 已复制')
  }
}

async function copyText(value: string, message: string) {
  try {
    await navigator.clipboard.writeText(value)
    ElMessage.success(message)
  } catch {
    ElMessage.error('复制失败')
  }
}

function emitOpenSql() {
  const sql = props.inspection?.previewSql || preview.value?.sql
  if (sql) emit('openSql', sql)
}
</script>

<style scoped>
.table-inspection {
  --inspection-accent: #795cff;
  position: relative;
  display: flex;
  flex-direction: column;
  min-height: 0;
  height: 100%;
  overflow: hidden;
  background:
    radial-gradient(circle at 8% -15%, color-mix(in srgb, var(--inspection-accent) 18%, transparent), transparent 31%),
    radial-gradient(circle at 92% 8%, rgba(56, 189, 248, 0.08), transparent 26%),
    var(--color-background);
}

.inspection-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  min-height: 88px;
  padding: 14px 20px;
  border-bottom: 1px solid var(--color-border);
  background: color-mix(in srgb, var(--color-panel-header) 84%, transparent);
  backdrop-filter: blur(18px) saturate(125%);
}

.object-identity,
.header-actions,
.object-stats,
.tab-label,
.constraint-heading {
  display: flex;
  align-items: center;
}

.object-identity { min-width: 0; gap: 14px; }
.header-actions { gap: 8px; flex-shrink: 0; }
.identity-copy { min-width: 0; }
.eyebrow {
  margin-bottom: 4px;
  color: var(--inspection-accent);
  font-size: 10px;
  font-weight: 760;
  letter-spacing: .13em;
}
.qualified-name {
  overflow: hidden;
  color: var(--color-foreground);
  font: 650 18px/1.25 var(--font-mono, 'JetBrains Mono', monospace);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.object-stats { gap: 8px; margin-top: 7px; color: var(--color-muted); font-size: 11px; }
.object-stats span + span::before { content: '·'; margin-right: 8px; opacity: .5; }
.object-stats .object-type { color: color-mix(in srgb, var(--inspection-accent) 74%, var(--color-foreground)); }

.object-orbit {
  position: relative;
  display: grid;
  width: 46px;
  height: 46px;
  flex: 0 0 46px;
  place-items: center;
  border: 1px solid color-mix(in srgb, var(--inspection-accent) 42%, var(--color-border));
  border-radius: 14px;
  background: linear-gradient(145deg, color-mix(in srgb, var(--inspection-accent) 22%, transparent), transparent);
  color: color-mix(in srgb, var(--inspection-accent) 74%, white);
  box-shadow: inset 0 1px rgba(255,255,255,.12), 0 12px 30px rgba(37, 21, 92, .18);
  font-size: 21px;
}
.object-orbit::after {
  position: absolute;
  width: 54px;
  height: 22px;
  border: 1px solid color-mix(in srgb, var(--inspection-accent) 30%, transparent);
  border-radius: 50%;
  content: '';
  transform: rotate(-20deg);
}
.global-error { margin: 12px 16px 0; width: auto; }
.inspection-tabs { display: flex; min-height: 0; flex: 1; flex-direction: column; }
.inspection-tabs :deep(.el-tabs__header) {
  margin: 0;
  padding: 0 18px;
  background: color-mix(in srgb, var(--color-panel-header) 72%, transparent);
}
.inspection-tabs :deep(.el-tabs__nav-wrap::after) { height: 1px; background: var(--color-border); }
.inspection-tabs :deep(.el-tabs__active-bar) { background: var(--inspection-accent); }
.inspection-tabs :deep(.el-tabs__item.is-active) { color: var(--inspection-accent); }
.inspection-tabs :deep(.el-tabs__content) { min-height: 0; flex: 1; }
.inspection-tabs :deep(.el-tab-pane) { height: 100%; }
.tab-label { gap: 7px; }
.section-content { height: 100%; min-height: 0; overflow: auto; padding: 14px 18px 18px; }
.preview-section { overflow: hidden; }
.preview-section :deep(.data-table-wrapper) { height: 100%; }
.section-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 12px;
}
.section-caption { color: var(--color-muted); font-size: 12px; }
.filter-input { width: min(320px, 44vw); }
.metadata-table-wrap {
  overflow: auto;
  border: 1px solid var(--color-border);
  border-radius: 12px;
  background: color-mix(in srgb, var(--color-panel-header) 66%, transparent);
  box-shadow: inset 0 1px rgba(255,255,255,.04);
}
.metadata-table { width: 100%; border-collapse: collapse; font-size: 12px; }
.metadata-table th {
  position: sticky;
  top: 0;
  z-index: 1;
  padding: 10px 12px;
  border-bottom: 1px solid var(--color-border);
  background: var(--color-panel-header);
  color: var(--color-muted);
  font-weight: 650;
  text-align: left;
  white-space: nowrap;
}
.metadata-table td {
  padding: 9px 12px;
  border-bottom: 1px solid color-mix(in srgb, var(--color-border) 72%, transparent);
  color: var(--color-foreground);
  white-space: nowrap;
}
.metadata-table tbody tr:hover { background: color-mix(in srgb, var(--inspection-accent) 8%, transparent); }
.metadata-table code { font: 11px var(--font-mono, monospace); }
.sequence { color: var(--color-muted); text-align: center; }
.column-name { font-family: var(--font-mono, monospace); font-weight: 600; }
.key-icon { margin-right: 5px; color: #f4b942; vertical-align: -2px; }
.type-chip {
  padding: 2px 7px;
  border: 1px solid color-mix(in srgb, var(--inspection-accent) 25%, var(--color-border));
  border-radius: 999px;
  color: color-mix(in srgb, var(--inspection-accent) 72%, var(--color-foreground));
  background: color-mix(in srgb, var(--inspection-accent) 8%, transparent);
}
.nullable-state.yes { color: #34c995; }
.nullable-state.no { color: #ff746c; }
.mini-tag {
  display: inline-block;
  margin-right: 4px;
  padding: 1px 5px;
  border-radius: 4px;
  background: rgba(52, 201, 149, .14);
  color: #34c995;
  font-size: 9px;
  font-weight: 750;
}
.mini-tag.primary { background: rgba(244, 185, 66, .14); color: #f4b942; }
.remarks { max-width: 280px; overflow: hidden; color: var(--color-muted) !important; text-overflow: ellipsis; }
.constraint-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(310px, 1fr)); gap: 10px; }
.constraint-card {
  display: flex;
  gap: 11px;
  padding: 13px;
  border: 1px solid var(--color-border);
  border-radius: 12px;
  background: linear-gradient(145deg, color-mix(in srgb, var(--inspection-accent) 7%, var(--color-panel-header)), var(--color-panel-header));
}
.constraint-mark {
  display: grid;
  width: 34px;
  height: 34px;
  flex: 0 0 34px;
  place-items: center;
  border-radius: 10px;
  background: color-mix(in srgb, var(--inspection-accent) 14%, transparent);
  color: var(--inspection-accent);
}
.constraint-main { min-width: 0; flex: 1; }
.constraint-heading { justify-content: space-between; gap: 8px; margin-bottom: 7px; font-size: 12px; font-weight: 650; }
.constraint-type { color: var(--inspection-accent); font-size: 10px; }
.constraint-main code { color: var(--color-foreground); font: 11px var(--font-mono, monospace); }
.reference-line { margin-top: 7px; color: var(--color-muted); font-size: 11px; }
.ddl-section { display: flex; flex-direction: column; }
.ddl-code {
  min-height: 0;
  flex: 1;
  margin: 0;
  overflow: auto;
  padding: 16px 18px;
  border: 1px solid var(--color-border);
  border-radius: 12px;
  background: color-mix(in srgb, #060811 88%, var(--color-background));
  color: #cbd5e1;
  font: 12px/1.7 var(--font-mono, 'JetBrains Mono', monospace);
  white-space: pre;
}

@media (max-width: 760px) {
  .inspection-header { align-items: flex-start; flex-direction: column; }
  .header-actions { width: 100%; flex-wrap: wrap; }
  .object-stats { flex-wrap: wrap; }
  .section-toolbar { align-items: stretch; flex-direction: column; }
  .filter-input { width: 100%; }
}
</style>
