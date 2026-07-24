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
          <div class="panel-loading">
            <el-icon class="is-loading"><Loading /></el-icon>
            <span>正在执行查询...</span>
          </div>
        </template>
        <template v-else-if="editorStore.activeSqlTab?.result">
          <DataTable
            :columns="editorStore.activeSqlTab.result.columns"
            :rows="editorStore.activeSqlTab.result.rows"
            :editable="editMode"
            :edit-context="editContext"
            @cell-edited="onCellEdited"
          />
        </template>
        <template v-else>
          <el-empty description="暂无查询结果" :image-size="60" />
        </template>
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
          <el-empty description="暂无消息" :image-size="60" />
        </template>
      </div>
      <!-- 历史 Tab -->
      <div v-show="uiStore.bottomPanelTab === 'history'" class="history-panel">
        <SqlHistory v-if="historyMounted" />
      </div>
    </div>
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

const editorStore = useEditorStore()
const uiStore = useUiStore()
const connectionStore = useConnectionStore()

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

/** 当前连接是否支持 DML（受能力描述控制，OLAP 只读则禁用；Redis 显式允许） */
const canEdit = computed(() => {
  const cap = uiStore.capabilities
  if (cap?.readOnly) return false
  if (activeDbType.value === 'REDIS') return true
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

  // MongoDB：暂不支持表格内联编辑（建议命令式操作）
  if (activeDbType.value === 'MONGODB') {
    ElMessage.info('MongoDB 暂不支持表格内联编辑，可通过 executeUpdate JSON DSL 命令式操作文档')
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

  // Redis：构造 SET key value 命令（仅编辑 value 列）
  if (ctx.dbType === 'REDIS') {
    if (column !== 'value') return
    const key = String(row['key'] ?? '')
    const cmd = `SET ${key} ${value}`
    try {
      const res = await queryApi.executeUpdate(ctx.connectionId, cmd)
      if (res.success) {
        ElMessage.success(`已更新 Key: ${key}`)
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

function formatElapsed(ms: number): string {
  if (ms < 1000) return `${ms}ms`
  return `${(ms / 1000).toFixed(2)}s`
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
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = filename
    a.click()
    URL.revokeObjectURL(url)
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

.messages-panel {
  height: 100%;
  overflow-y: auto;
  padding: var(--space-3);
}

.history-panel {
  height: 100%;
  overflow: hidden;
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
