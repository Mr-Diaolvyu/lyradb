<template>
  <div class="bottom-panel" v-show="uiStore.bottomPanelVisible">
    <!-- Tab 栏 -->
    <div class="panel-tabs">
      <div class="panel-tab-item"
        :class="{ active: uiStore.bottomPanelTab === 'results' }"
        @click="uiStore.setBottomPanelTab('results')"
      >
        <span>结果</span>
        <span v-if="resultRowCount" class="badge">{{ resultRowCount }}</span>
      </div>
      <div class="panel-tab-item"
        :class="{ active: uiStore.bottomPanelTab === 'chart' }"
        @click="uiStore.setBottomPanelTab('chart')"
      >
        <span>图表</span>
      </div>
      <div class="panel-tab-item"
        :class="{ active: uiStore.bottomPanelTab === 'messages' }"
        @click="uiStore.setBottomPanelTab('messages')"
      >
        <span>消息</span>
        <span v-if="hasError" class="badge error">!</span>
      </div>
      <div class="panel-tab-item"
        :class="{ active: uiStore.bottomPanelTab === 'history' }"
        @click="uiStore.openHistoryTab()"
      >
        <span>历史</span>
      </div>
      <div class="panel-spacer"></div>
      <div class="panel-actions">
        <el-tooltip :content="editMode ? '退出编辑模式' : '编辑模式（双击单元格）'" placement="top">
          <el-button
            :icon="EditPen"
            size="small"
            text
            :type="editMode ? 'primary' : ''"
            :disabled="!canEdit || !hasResult"
            @click="toggleEdit"
          />
        </el-tooltip>
        <el-tooltip content="复制结果" placement="top">
          <el-button :icon="CopyDocument" size="small" text :disabled="!hasResult" @click="copyResult" />
        </el-tooltip>
        <el-dropdown @command="handleExport" :disabled="!hasResult || exportLoading">
          <el-button :icon="Download" size="small" text :disabled="!hasResult || exportLoading">
            <span>导出</span>
            <el-icon class="el-icon--right"><ArrowDown /></el-icon>
          </el-button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="csv">导出 CSV</el-dropdown-item>
              <el-dropdown-item command="json">导出 JSON</el-dropdown-item>
              <el-dropdown-item command="excel">导出 Excel</el-dropdown-item>
              <el-dropdown-item command="sql">导出 SQL INSERT</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
        <el-tooltip content="关闭面板" placement="top">
          <el-button :icon="Close" size="small" text @click="uiStore.toggleBottomPanel()" />
        </el-tooltip>
      </div>
    </div>

    <!-- 内容区 -->
    <div class="panel-content">
      <!-- 结果 Tab -->
      <div v-show="uiStore.bottomPanelTab === 'results'" class="results-panel">
        <template v-if="editorStore.activeSqlTab?.loading">
          <!-- V4 骨架屏：模拟表头+数据行的微光扫描 -->
          <div class="panel-skeleton">
            <div class="skeleton-toolbar">
              <el-icon class="is-loading"><Loading /></el-icon>
              <span>正在执行查询...</span>
              <el-button size="small" type="danger" plain @click="cancelQuery">取消查询</el-button>
            </div>
            <div class="skeleton-table">
              <div class="skeleton-row is-head">
                <span v-for="j in 5" :key="j" class="sk-cell" :style="{ width: skWidths[0][j - 1] }"></span>
              </div>
              <div v-for="i in 5" :key="i" class="skeleton-row">
                <span v-for="j in 5" :key="j" class="sk-cell" :style="{ width: skWidths[i][j - 1] }"></span>
              </div>
            </div>
          </div>
        </template>
        <template v-else-if="editorStore.activeSqlTab?.result">
          <ExplainTreeView
            v-if="editorStore.activeSqlTab.isExplain"
            :result="editorStore.activeSqlTab.result"
          />
          <DataTable
            v-else
            :columns="editorStore.activeSqlTab.result.columns"
            :rows="editorStore.activeSqlTab.result.rows"
            :editable="editMode"
            :edit-context="editContext"
            :json-row-edit="editMode && activeDbType === 'MONGODB'"
            :remarks-loader="remarksLoader"
            @cell-edited="onCellEdited"
            @row-json-edit="openMongoDocEditor"
          />
        </template>
        <template v-else>
          <EmptyState
            title="暂无查询结果"
            hint="在编辑器中编写 SQL，按 Ctrl+Enter 执行，结果将显示在这里"
            icon="db"
          />
        </template>
      </div>

      <!-- 图表 Tab（SVG 自绘，基于当前结果集） -->
      <div v-show="uiStore.bottomPanelTab === 'chart'" class="chart-panel">
        <ChartView
          v-if="editorStore.activeSqlTab?.result && !editorStore.activeSqlTab.isExplain"
          :columns="editorStore.activeSqlTab.result.columns"
          :rows="editorStore.activeSqlTab.result.rows"
        />
        <EmptyState v-else title="暂无查询结果" hint="执行查询后可将结果集可视化为图表" />
      </div>

      <!-- 消息 Tab -->
      <div v-show="uiStore.bottomPanelTab === 'messages'" class="messages-panel">
        <template v-if="editorStore.activeSqlTab?.error">
          <div class="message-error">
            <el-icon><WarningFilled /></el-icon>
            <pre>{{ editorStore.activeSqlTab.error }}</pre>
          </div>
        </template>
        <template v-else-if="editorStore.activeSqlTab?.result">
          <div class="message-success">
            <el-icon><CircleCheckFilled /></el-icon>
            <span>
              查询成功 - {{ editorStore.activeSqlTab.result.totalRows }} 行,
              耗时 {{ formatElapsed(editorStore.activeSqlTab.result.elapsedMs) }}
            </span>
          </div>
        </template>
        <template v-else>
          <EmptyState title="暂无消息" hint="查询的成功/错误信息将显示在这里" />
        </template>
      </div>
      <!-- 历史 Tab -->
      <div v-show="uiStore.bottomPanelTab === 'history'" class="history-panel">
        <SqlHistory v-if="historyMounted" />
      </div>
    </div>

    <!-- MongoDB 文档 JSON 编辑器 -->
    <el-dialog v-model="mongoDocVisible" title="编辑文档 (JSON)" width="640" append-to-body>
      <el-input
        v-model="mongoDocText"
        type="textarea"
        :rows="16"
        class="mongo-doc-editor"
        placeholder="文档 JSON 内容"
      />
      <template #footer>
        <el-button @click="mongoDocVisible = false">取消</el-button>
        <el-button type="danger" plain :loading="mongoDocSaving" @click="deleteMongoDoc">删除文档</el-button>
        <el-button type="primary" :loading="mongoDocSaving" @click="saveMongoDoc">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { CopyDocument, Download, Close, Loading, WarningFilled, CircleCheckFilled, ArrowDown, EditPen } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { useEditorStore } from '@/stores/editor'
import { useUiStore } from '@/stores/ui'
import { useConnectionStore } from '@/stores/connection'
import { queryApi, metadataApi } from '@/api/metadata'
import DataTable from '@/components/editor/DataTable.vue'
import SqlHistory from '@/components/editor/SqlHistory.vue'
import ExplainTreeView from '@/components/editor/ExplainTreeView.vue'
import ChartView from '@/components/editor/ChartView.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import { saveBlob } from '@/utils/download'

const editorStore = useEditorStore()
const uiStore = useUiStore()
const connectionStore = useConnectionStore()

/** V4 骨架屏各行单元格宽度（固定随机序列，避免重渲染闪动） */
const skWidths = [
  ['12%', '18%', '14%', '20%', '10%'],
  ['10%', '22%', '12%', '16%', '14%'],
  ['14%', '16%', '18%', '12%', '10%'],
  ['11%', '20%', '10%', '18%', '13%'],
  ['13%', '15%', '16%', '14%', '12%'],
  ['9%', '19%', '13%', '17%', '11%'],
]

const hasResult = computed(() => !!editorStore.activeSqlTab?.result)
const hasError = computed(() => !!editorStore.activeSqlTab?.error)
const resultRowCount = computed(() => editorStore.activeSqlTab?.result?.totalRows || 0)
const exportLoading = ref(false)

/** 历史面板懒挂载：首次切到「历史」tab 才挂载组件 */
const historyMounted = ref(false)
watch(() => uiStore.bottomPanelTab, (tab) => {
  if (tab === 'history') historyMounted.value = true
})

// === 内联编辑 ===
interface EditContext {
  connectionId: string
  dbType: string
  schema: string | null
  table: string
  pkColumns: string[]
}
const editMode = ref(false)
const editContext = ref<EditContext | null>(null)

/** 当前激活 SQL Tab 对应连接的数据库类型 */
const activeDbType = computed(() => {
  const tab = editorStore.activeSqlTab
  if (!tab) return undefined
  return connectionStore.connections.find(c => c.id === tab.connectionId)?.dbType
})

/** 当前连接是否支持 DML（受能力描述控制，OLAP 只读则禁用；Redis/MongoDB 显式允许） */
const canEdit = computed(() => {
  const cap = uiStore.capabilities
  if (cap?.readOnly) return false
  if (activeDbType.value === 'REDIS' || activeDbType.value === 'MONGODB') return true
  return !!cap && !!cap.supportsDML
})

/** 从 SQL 推断目标表（仅支持简单单表 SELECT） */
function inferTable(sql: string): { schema: string | null; table: string } | null {
  const m = sql.match(/from\s+([a-zA-Z_][\w]*(?:\.[a-zA-Z_][\w]*)?)/i)
  if (!m) return null
  const ref = m[1]
  if (ref.includes('.')) {
    const [schema, table] = ref.split('.')
    return { schema, table }
  }
  return { schema: null, table: ref }
}

/** 表头注释切换：从当前 SQL 推断单表，拉取列元数据组装 col→remarks 映射 */
async function loadColumnRemarks(): Promise<Record<string, string>> {
  const tab = editorStore.activeSqlTab
  if (!tab?.sql) return {}
  const inferred = inferTable(tab.sql)
  if (!inferred) return {}
  const cols = await metadataApi.getTableColumns(tab.connectionId, inferred.schema, inferred.table)
  const map: Record<string, string> = {}
  for (const c of cols) {
    if (c.remarks) map[c.name] = c.remarks
  }
  return map
}

const remarksLoader = () => loadColumnRemarks()

async function toggleEdit() {
  if (!canEdit.value) {
    ElMessage.warning('当前数据库为只读模式（OLAP），不支持编辑')
    return
  }
  if (editMode.value) {
    editMode.value = false
    editContext.value = null
    return
  }
  const tab = editorStore.activeSqlTab
  if (!tab?.sql) {
    ElMessage.warning('没有可编辑的查询')
    return
  }

  // Redis：对 GET 之类 [key,value] 结果直接支持 SET 行内编辑
  if (activeDbType.value === 'REDIS') {
    const cols = tab.result?.columns || []
    if (cols.includes('key') && cols.includes('value')) {
      editContext.value = {
        connectionId: tab.connectionId,
        dbType: 'REDIS',
        schema: null,
        table: '<redis>',
        pkColumns: ['key'],
      }
      editMode.value = true
      ElMessage.success('已进入编辑模式，双击 value 编辑（SET key value）')
    } else {
      ElMessage.warning('Redis 仅支持对 GET 结果(key,value) 的值进行行内编辑')
    }
    return
  }

  // MongoDB：文档 JSON 编辑模式（双击行打开 JSON 编辑器）
  if (activeDbType.value === 'MONGODB') {
    const target = parseMongoTarget(tab.sql)
    if (!target) {
      ElMessage.warning('无法从查询语句解析目标集合（格式: db.collection）')
      return
    }
    if (!tab.result?.columns.includes('_id')) {
      ElMessage.warning('结果中缺少 _id 字段，无法定位文档')
      return
    }
    mongoTarget.value = target
    editContext.value = {
      connectionId: tab.connectionId,
      dbType: 'MONGODB',
      schema: target.db,
      table: target.collection,
      pkColumns: ['_id'],
    }
    editMode.value = true
    ElMessage.success('已进入文档编辑模式，双击行打开 JSON 编辑器')
    return
  }

  const inferred = inferTable(tab.sql)
  if (!inferred) {
    ElMessage.warning('无法从 SQL 推断目标表（仅支持单表 SELECT）')
    return
  }
  try {
    const cols = await metadataApi.getTableColumns(tab.connectionId, inferred.schema, inferred.table)
    const pks = cols.filter(c => c.primaryKey).map(c => c.name)
    if (pks.length === 0) {
      ElMessage.warning('该表无主键，无法定位行，不支持行内编辑')
      return
    }
    editContext.value = {
      connectionId: tab.connectionId,
      dbType: activeDbType.value || '',
      schema: inferred.schema,
      table: inferred.table,
      pkColumns: pks,
    }
    editMode.value = true
    ElMessage.success('已进入编辑模式，双击单元格编辑')
  } catch (e: any) {
    ElMessage.error('加载表结构失败: ' + (e.message || '未知错误'))
  }
}

function sqlLiteral(v: any): string {
  if (v === null || v === undefined) return 'NULL'
  if (typeof v === 'number' || typeof v === 'boolean') return String(v)
  // 字符串：转义单引号
  return "'" + String(v).replace(/'/g, "''") + "'"
}

async function onCellEdited(payload: { row: Record<string, any>; column: string; value: any; oldValue: any }) {
  const ctx = editContext.value
  if (!ctx) return
  const { row, column, value, oldValue } = payload

  // Redis：构造 SET / EXPIRE / PERSIST 命令（支持 value 与 ttl 列编辑）
  if (ctx.dbType === 'REDIS') {
    const key = String(row['key'] ?? '')
    let cmd: string
    if (column === 'value') {
      cmd = `SET ${key} ${value}`
    } else if (column === 'ttl') {
      const ttlNum = Number(value)
      // TTL 置为负数/空 → 移除过期时间（PERSIST），否则 EXPIRE
      cmd = (!value || isNaN(ttlNum) || ttlNum < 0) ? `PERSIST ${key}` : `EXPIRE ${key} ${ttlNum}`
    } else {
      row[column] = oldValue
      return
    }
    try {
      const res = await queryApi.executeUpdate(ctx.connectionId, cmd)
      if (res.success) {
        ElMessage.success(column === 'value' ? `已更新 Key: ${key}` : `已更新 TTL: ${key}`)
      } else {
        row[column] = oldValue
        ElMessage.error('更新失败: ' + (res.message || '未知错误'))
      }
    } catch (e: any) {
      row[column] = oldValue
      ElMessage.error('更新失败: ' + (e.message || '未知错误'))
    }
    return
  }

  // 通用 SQL：UPDATE schema.table SET col=? WHERE pk=?
  const tableRef = ctx.schema ? `${ctx.schema}.${ctx.table}` : ctx.table
  const setClause = `${column} = ${sqlLiteral(value)}`
  const where = ctx.pkColumns.map(pk => `${pk} = ${sqlLiteral(row[pk])}`).join(' AND ')
  const sql = `UPDATE ${tableRef} SET ${setClause} WHERE ${where}`
  try {
    const res = await queryApi.executeUpdate(ctx.connectionId, sql, uiStore.currentDatabase || undefined)
    if (res.success) {
      ElMessage.success(`已更新 ${res.affectedRows ?? 0} 行`)
    } else {
      // 失败回滚
      row[column] = oldValue
      ElMessage.error('更新失败: ' + (res.message || '未知错误'))
    }
  } catch (e: any) {
    row[column] = oldValue
    ElMessage.error('更新失败: ' + (e.message || '未知错误'))
  }
}

// 切换 SQL Tab 或断开连接时退出编辑模式
watch(() => editorStore.activeTabId, () => {
  editMode.value = false
  editContext.value = null
})
watch(() => connectionStore.activeConnectionId, () => {
  editMode.value = false
  editContext.value = null
})

// === MongoDB 文档 JSON 编辑 ===
const mongoDocVisible = ref(false)
const mongoDocText = ref('')
const mongoDocSaving = ref(false)
const mongoDocRow = ref<Record<string, any> | null>(null)
const mongoTarget = ref<{ db: string; collection: string } | null>(null)

/** 从 Mongo 查询语句解析 db 与 collection（格式: db.coll 或 db/coll） */
function parseMongoTarget(sql: string): { db: string; collection: string } | null {
  const parts = sql.trim().replace('/', '.').split('.')
  if (parts.length < 2 || !parts[0] || !parts[1]) return null
  return { db: parts[0], collection: parts[1] }
}

function openMongoDocEditor(row: Record<string, any>) {
  mongoDocRow.value = row
  mongoDocText.value = JSON.stringify(row, null, 2)
  mongoDocVisible.value = true
}

async function saveMongoDoc() {
  const ctx = editContext.value
  const target = mongoTarget.value
  const row = mongoDocRow.value
  if (!ctx || !target || !row) return

  let doc: Record<string, any>
  try {
    doc = JSON.parse(mongoDocText.value)
  } catch {
    ElMessage.error('JSON 格式错误，请检查后重试')
    return
  }
  const id = row['_id']
  if (id === null || id === undefined || id === '') {
    ElMessage.error('文档缺少 _id，无法更新')
    return
  }
  // _id 不可修改，从 $set 中排除
  const { _id, ...fields } = doc

  mongoDocSaving.value = true
  try {
    const dsl = JSON.stringify({
      op: 'update',
      db: target.db,
      collection: target.collection,
      filter: { _id: id },
      update: { $set: fields },
    })
    const res = await queryApi.executeUpdate(ctx.connectionId, dsl)
    if (res.success) {
      // 同步更新表格行显示
      for (const [k, v] of Object.entries(fields)) {
        row[k] = typeof v === 'object' && v !== null ? JSON.stringify(v) : v
      }
      mongoDocVisible.value = false
      ElMessage.success(`已更新 ${res.affectedRows ?? 0} 个文档`)
    } else {
      ElMessage.error('更新失败: ' + (res.message || '未知错误'))
    }
  } catch (e: any) {
    ElMessage.error('更新失败: ' + (e.message || '未知错误'))
  } finally {
    mongoDocSaving.value = false
  }
}

async function deleteMongoDoc() {
  const ctx = editContext.value
  const target = mongoTarget.value
  const row = mongoDocRow.value
  if (!ctx || !target || !row) return
  const id = row['_id']
  if (id === null || id === undefined || id === '') {
    ElMessage.error('文档缺少 _id，无法删除')
    return
  }
  mongoDocSaving.value = true
  try {
    const dsl = JSON.stringify({
      op: 'delete',
      db: target.db,
      collection: target.collection,
      filter: { _id: id },
    })
    const res = await queryApi.executeUpdate(ctx.connectionId, dsl)
    if (res.success) {
      mongoDocVisible.value = false
      ElMessage.success(`已删除 ${res.affectedRows ?? 0} 个文档，请重新执行查询刷新`)
    } else {
      ElMessage.error('删除失败: ' + (res.message || '未知错误'))
    }
  } catch (e: any) {
    ElMessage.error('删除失败: ' + (e.message || '未知错误'))
  } finally {
    mongoDocSaving.value = false
  }
}

function formatElapsed(ms: number): string {
  if (ms < 1000) return `${ms}ms`
  return `${(ms / 1000).toFixed(2)}s`
}

/** 取消正在执行的查询 */
async function cancelQuery() {
  if (!editorStore.activeTabId) return
  await editorStore.cancelQuery(editorStore.activeTabId)
  ElMessage.info('已发出取消请求')
}

/** 复制查询结果到剪贴板 */
async function copyResult() {
  const result = editorStore.activeSqlTab?.result
  if (!result) return

  const header = result.columns.join('\t')
  const rows = result.rows.map((row: Record<string, any>) =>
    result.columns.map((col: string) => String(row[col] ?? '')).join('\t')
  )
  const text = [header, ...rows].join('\n')

  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('已复制到剪贴板')
  } catch {
    ElMessage.error('复制失败')
  }
}

/** 导出查询结果 (后端导出: CSV/JSON/Excel/SQL) */
async function handleExport(format: string) {
  const tab = editorStore.activeSqlTab
  if (!tab || !tab.sql.trim()) {
    ElMessage.warning('没有可导出的SQL')
    return
  }

  exportLoading.value = true
  try {
    const blob = await queryApi.export(tab.connectionId, {
      sql: tab.sql,
      format: format as 'csv' | 'json' | 'excel' | 'sql',
      defaultDatabase: uiStore.currentDatabase || undefined,
      tableName: extractTableName(tab.sql),
    })

    const ext = format === 'excel' ? 'xlsx' : format
    const filename = `query_result_${Date.now()}.${ext}`
    await saveBlob(blob, filename)
    ElMessage.success(`${format.toUpperCase()} 导出成功`)
  } catch (e: any) {
    ElMessage.error('导出失败: ' + (e.message || '未知错误'))
  } finally {
    exportLoading.value = false
  }
}

/** 从SQL中提取表名 (用于SQL INSERT导出) */
function extractTableName(sql: string): string {
  const match = sql.match(/FROM\s+([^\s;]+)/i)
  return match ? match[1] : ''
}
</script>

<style scoped>
.bottom-panel {
  display: flex;
  flex-direction: column;
  height: var(--bottompanel-height);
  min-height: var(--bottompanel-min-height);
  background: var(--color-panel);
  border-top: 1px solid var(--color-border);
  flex-shrink: 0;
}

.panel-tabs {
  display: flex;
  align-items: center;
  height: var(--tab-height);
  background: var(--color-panel-header);
  border-bottom: 1px solid var(--color-border);
  padding: 0 var(--space-2);
}

.panel-tab-item {
  display: flex;
  align-items: center;
  gap: var(--space-1);
  height: 100%;
  padding: 0 var(--space-3);
  cursor: pointer;
  font-size: var(--text-label);
  color: var(--color-text-muted);
  border-bottom: 2px solid transparent;
  transition: all var(--transition-normal);
}

.panel-tab-item:hover {
  color: var(--color-foreground);
}

.panel-tab-item.active {
  color: var(--color-secondary);
  border-bottom-color: var(--color-secondary);
}

.panel-spacer {
  flex: 1;
}

.panel-actions {
  display: flex;
  align-items: center;
  gap: var(--space-1);
}

.badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 18px;
  height: 18px;
  padding: 0 4px;
  font-size: 11px;
  background: var(--color-active);
  color: var(--color-secondary);
  border-radius: 9px;
}

.badge.error {
  background: var(--color-error);
  color: #fff;
}

.panel-content {
  flex: 1;
  overflow: hidden;
}

.results-panel {
  height: 100%;
  overflow: auto;
}

.panel-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-2);
  height: 100%;
  color: var(--color-text-muted);
  font-size: var(--text-body);
}

/* === V4 骨架屏 === */
.panel-skeleton {
  display: flex;
  flex-direction: column;
  height: 100%;
  overflow: hidden;
}

.skeleton-toolbar {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-3);
  color: var(--color-text-muted);
  font-size: var(--text-label);
  flex-shrink: 0;
}

.skeleton-table {
  flex: 1;
  padding: 0 var(--space-3) var(--space-3);
  overflow: hidden;
}

.skeleton-row {
  display: flex;
  gap: var(--space-3);
  align-items: center;
  height: var(--row-h, 32px);
  border-bottom: 1px solid var(--color-border);
}

.skeleton-row.is-head .sk-cell {
  height: 12px;
  opacity: 0.9;
}

.sk-cell {
  height: 10px;
  border-radius: 3px;
  background: linear-gradient(
    90deg,
    var(--color-active) 25%,
    var(--color-hover) 50%,
    var(--color-active) 75%
  );
  background-size: 200% 100%;
  animation: sk-shimmer 1.4s ease-in-out infinite;
}

@keyframes sk-shimmer {
  0% { background-position: 200% 0; }
  100% { background-position: -200% 0; }
}

.messages-panel {
  height: 100%;
  overflow-y: auto;
  padding: var(--space-3);
}

.history-panel {
  height: 100%;
  overflow: hidden;
}

.chart-panel {
  height: 100%;
  overflow: hidden;
}

.mongo-doc-editor :deep(textarea) {
  font-family: var(--font-mono);
  font-size: var(--text-code);
}

.message-error {
  display: flex;
  align-items: flex-start;
  gap: var(--space-2);
  color: var(--color-destructive);
  font-family: var(--font-mono);
  font-size: var(--text-code);
}

.message-error :deep(.el-icon) {
  color: var(--color-destructive);
  flex-shrink: 0;
  margin-top: 2px;
}

.message-error pre {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
}

.message-success {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  color: var(--color-success);
  font-size: var(--text-body);
}

.message-success :deep(.el-icon) {
  color: var(--color-success);
  flex-shrink: 0;
}
</style>
