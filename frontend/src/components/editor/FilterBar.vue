<template>
  <div class="filter-bar" v-if="editorStore.activeSqlTab">
    <!-- 折叠头：过滤器开关 -->
    <div class="filter-head" @click="expanded = !expanded">
      <el-icon class="filter-chevron" :class="{ open: expanded }"><ArrowRight /></el-icon>
      <el-icon><Filter /></el-icon>
      <span class="filter-title">{{ t('filter.title') }}</span>
      <el-tag v-if="appliedCount > 0" size="small" type="primary" effect="plain">{{ appliedCount }}</el-tag>
      <span class="filter-hint" v-if="!hasResult">{{ t('filter.noResult') }}</span>
    </div>

    <!-- 展开面板 -->
    <div class="filter-body" v-show="expanded">
      <!-- 条件行 -->
      <div class="filter-row" v-for="(cond, idx) in conditions" :key="cond.id">
        <el-select
          v-model="cond.column"
          size="small"
          filterable
          :placeholder="t('filter.column')"
          class="w-col"
        >
          <el-option v-for="col in columns" :key="col" :label="col" :value="col" />
        </el-select>
        <el-select v-model="cond.op" size="small" class="w-op">
          <el-option v-for="op in OPERATORS" :key="op.key" :label="op.label" :value="op.key" />
        </el-select>
        <template v-if="arity(cond.op) >= 1">
          <el-input
            v-model="cond.v1"
            size="small"
            :placeholder="t('filter.value')"
            class="w-val"
            @keyup.enter="apply"
          />
        </template>
        <template v-if="arity(cond.op) === 2">
          <span class="filter-and">AND</span>
          <el-input
            v-model="cond.v2"
            size="small"
            :placeholder="t('filter.value')"
            class="w-val"
            @keyup.enter="apply"
          />
        </template>
        <el-button
          :icon="Delete"
          size="small"
          text
          class="row-del"
          :disabled="conditions.length <= 1"
          @click="removeCondition(idx)"
        />
      </div>

      <!-- 操作行：组合符 + 添加 + WHERE 预览 + 应用/清除 -->
      <div class="filter-actions">
        <el-radio-group v-model="combinator" size="small">
          <el-radio-button value="AND">AND</el-radio-button>
          <el-radio-button value="OR">OR</el-radio-button>
        </el-radio-group>
        <el-button :icon="Plus" size="small" text @click="addCondition">
          {{ t('filter.addCondition') }}
        </el-button>
        <div class="where-preview" :title="whereClause">
          <span class="where-kw" v-if="whereClause">WHERE</span>
          <code>{{ whereClause || t('filter.previewEmpty') }}</code>
        </div>
        <el-button size="small" type="primary" :disabled="!whereClause || !hasResult" @click="apply">
          {{ t('filter.apply') }}
        </el-button>
        <el-button size="small" :disabled="appliedCount === 0" @click="clear">
          {{ t('filter.clear') }}
        </el-button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ArrowRight, Filter, Delete, Plus } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { useEditorStore } from '@/stores/editor'

const { t } = useI18n()
const editorStore = useEditorStore()

/** 操作符定义：arity 表示需要的值数量（0/1/2），sql 负责拼接片段 */
interface OperatorDef {
  key: string
  label: string
  arity: 0 | 1 | 2
  sql: (col: string, v1: string, v2: string) => string
}

const OPERATORS: OperatorDef[] = [
  { key: 'eq', label: '=', arity: 1, sql: (c, v) => `${c} = ${v}` },
  { key: 'ne', label: '<>', arity: 1, sql: (c, v) => `${c} <> ${v}` },
  { key: 'like', label: 'LIKE', arity: 1, sql: (c, v) => `${c} LIKE ${v}` },
  { key: 'gt', label: '>', arity: 1, sql: (c, v) => `${c} > ${v}` },
  { key: 'lt', label: '<', arity: 1, sql: (c, v) => `${c} < ${v}` },
  { key: 'between', label: 'BETWEEN', arity: 2, sql: (c, v1, v2) => `${c} BETWEEN ${v1} AND ${v2}` },
  { key: 'null', label: 'IS NULL', arity: 0, sql: c => `${c} IS NULL` },
  { key: 'notnull', label: 'IS NOT NULL', arity: 0, sql: c => `${c} IS NOT NULL` },
]

interface Condition {
  id: number
  column: string
  op: string
  v1: string
  v2: string
}

let seq = 0
function blankCondition(): Condition {
  return { id: ++seq, column: '', op: 'eq', v1: '', v2: '' }
}

const expanded = ref(false)
const conditions = ref<Condition[]>([blankCondition()])
const combinator = ref<'AND' | 'OR'>('AND')
/** 已应用的条件数（用于折叠头徽标与「清除」可用性） */
const appliedCount = ref(0)
/** 应用过滤前的原始 SQL，按 tabId 记录以便清除时还原 */
const baseSqlMap = new Map<string, string>()

const activeTab = computed(() => editorStore.activeSqlTab)
const hasResult = computed(() => !!activeTab.value?.result)
const columns = computed(() => activeTab.value?.result?.columns ?? [])

function arity(opKey: string): number {
  return OPERATORS.find(o => o.key === opKey)?.arity ?? 1
}

function addCondition() {
  conditions.value.push(blankCondition())
}

function removeCondition(idx: number) {
  conditions.value.splice(idx, 1)
}

/** 判断列是否为数值列：抽样当前结果集该列的非空值 */
function isNumericColumn(col: string): boolean {
  const rows = activeTab.value?.result?.rows ?? []
  let seen = 0
  for (const row of rows) {
    const v = row[col]
    if (v === null || v === undefined) continue
    if (typeof v !== 'number') return false
    if (++seen >= 20) break
  }
  return seen > 0
}

/** 按列类型给值加引号：数值列裸值（非法数字回退引号），其余单引号并转义 */
function quote(col: string, raw: string): string {
  const v = raw.trim()
  if (isNumericColumn(col) && v !== '' && !isNaN(Number(v))) return v
  return `'${v.replace(/'/g, "''")}'`
}

/** 实时 WHERE 预览（仅拼接填写完整的条件） */
const whereClause = computed(() => {
  const parts: string[] = []
  for (const cond of conditions.value) {
    const op = OPERATORS.find(o => o.key === cond.op)
    if (!op || !cond.column) continue
    if (op.arity >= 1 && cond.v1.trim() === '') continue
    if (op.arity === 2 && cond.v2.trim() === '') continue
    const v1 = op.arity >= 1 ? quote(cond.column, cond.v1) : ''
    const v2 = op.arity === 2 ? quote(cond.column, cond.v2) : ''
    parts.push(op.sql(cond.column, v1, v2))
  }
  if (parts.length === 0) return ''
  return parts.length === 1 ? parts[0] : parts.map(p => `(${p})`).join(` ${combinator.value} `)
})

/** 应用：以原始 SQL 为子查询包装 WHERE 后执行 */
function apply() {
  const tab = activeTab.value
  if (!tab || !whereClause.value) return
  if (!baseSqlMap.has(tab.id)) {
    baseSqlMap.set(tab.id, tab.sql)
  }
  const base = (baseSqlMap.get(tab.id) ?? tab.sql).trim().replace(/;\s*$/, '')
  const filtered = `SELECT * FROM (\n${base}\n) _f\nWHERE ${whereClause.value}`
  editorStore.updateSql(tab.id, filtered)
  appliedCount.value = conditions.value.filter(c => {
    const op = OPERATORS.find(o => o.key === c.op)
    if (!op || !c.column) return false
    if (op.arity >= 1 && c.v1.trim() === '') return false
    if (op.arity === 2 && c.v2.trim() === '') return false
    return true
  }).length
  editorStore.executeSql(tab.id)
}

/** 清除：还原原始 SQL 并重新执行 */
function clear() {
  const tab = activeTab.value
  if (!tab) return
  const base = baseSqlMap.get(tab.id)
  if (base !== undefined) {
    editorStore.updateSql(tab.id, base)
    baseSqlMap.delete(tab.id)
    editorStore.executeSql(tab.id)
  }
  conditions.value = [blankCondition()]
  appliedCount.value = 0
  ElMessage.success(t('filter.cleared'))
}

// 切换 Tab 时重置过滤状态（baseSqlMap 按 tab 保留）
watch(() => editorStore.activeTabId, () => {
  conditions.value = [blankCondition()]
  combinator.value = 'AND'
  appliedCount.value = 0
  expanded.value = false
})
</script>

<style scoped>
.filter-bar {
  flex-shrink: 0;
  border-top: 1px solid var(--color-border);
  background: var(--color-panel);
}

.filter-head {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  height: 30px;
  padding: 0 var(--space-3);
  cursor: pointer;
  font-size: var(--text-label);
  color: var(--color-text-muted);
  user-select: none;
}

.filter-head:hover {
  color: var(--color-foreground);
  background: var(--color-hover);
}

.filter-chevron {
  font-size: 12px;
  transition: transform var(--transition-fast);
}

.filter-chevron.open {
  transform: rotate(90deg);
}

.filter-title {
  font-weight: 600;
}

.filter-hint {
  margin-left: auto;
  font-size: 12px;
  color: var(--color-text-muted);
}

.filter-body {
  padding: var(--space-2) var(--space-3) var(--space-3);
  border-top: 1px solid var(--color-border);
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.filter-row {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.w-col {
  width: 180px;
}

.w-op {
  width: 130px;
}

.w-val {
  width: 160px;
}

.filter-and {
  font-size: 12px;
  color: var(--color-text-muted);
}

.row-del {
  color: var(--color-text-muted);
}

.filter-actions {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.where-preview {
  flex: 1;
  display: flex;
  align-items: center;
  gap: var(--space-1);
  min-width: 0;
  padding: 4px 8px;
  background: var(--color-background);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  overflow: hidden;
}

.where-kw {
  font-size: 11px;
  font-weight: 700;
  color: var(--color-secondary);
  flex-shrink: 0;
}

.where-preview code {
  font-family: var(--font-mono);
  font-size: var(--text-code);
  color: var(--color-foreground);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
</style>
