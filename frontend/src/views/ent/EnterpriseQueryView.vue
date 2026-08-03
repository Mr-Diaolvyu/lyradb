<template>
  <div class="page">
    <div class="page-title">
      <div class="section-kicker">Governed query</div>
      <h1>企业查询</h1>
      <span class="page-sub">选择逻辑数据源，编写并执行 SQL。连接信息与凭据由平台安全托管。</span>
    </div>

    <div class="toolbar glass-surface">
      <el-select v-model="source" placeholder="选择数据源" class="source-select" @change="onSourceChange">
        <el-option v-for="g in grants" :key="g.id" :label="g.grantedSourceName" :value="g.grantedSourceName" />
      </el-select>
      <el-tag v-if="currentGrant" size="small" :type="currentGrant.sqlCapability === 'DML_ALLOWED' ? 'success' : 'info'">
        {{ currentGrant.sqlCapability === 'DML_ALLOWED' ? '可写' : '只读' }} · 上限 {{ currentGrant.maxRowsPerQuery }} 行
      </el-tag>
      <div class="spacer"></div>
      <el-button :icon="Grid" :disabled="!source" @click="openDatabaseWorkspace">
        数据库工作区
      </el-button>
      <el-button :icon="Share" :disabled="!source" @click="openErFromWorkspace()">
        ER 图
      </el-button>
      <el-button :icon="Download" :disabled="executing || !source || !sql.trim()" @click="openExportRequest">
        申请导出
      </el-button>
      <el-button :icon="VideoPlay" type="primary" :loading="executing" :disabled="!source || !sql.trim()" @click="execute">
        执行 (Ctrl+Enter)
      </el-button>
    </div>

    <div class="editor-wrap data-card">
      <SqlEditor
        :model-value="sql"
        :db-type="currentGrant?.dbType || catalog?.dbType"
        :completion-tables="catalog?.tables || []"
        :columns-loader="loadCompletionColumns"
        metadata-scope="authorized"
        @update:model-value="(v: string) => sql = v"
        @execute="execute"
      />
    </div>

    <div v-if="result" class="result-wrap data-card">
      <div class="result-bar">
        <span>{{ result.totalRows }} 行 · {{ result.elapsedMs }}ms</span>
        <span v-if="result.truncated" class="warn">结果已截断</span>
      </div>
      <DataTable
        :columns="result.columns"
        :rows="result.rows"
        :remarks-loader="resultRemarksLoader"
      />
    </div>
    <el-empty v-else-if="!executing" description="执行查询后在此查看结果" :image-size="60" />

    <el-dialog
      v-model="workspaceDialogOpen"
      :title="`数据库工作区 · ${source || '未选择数据源'}`"
      width="94%"
      top="3vh"
      destroy-on-close
      append-to-body
      class="enterprise-workspace-dialog"
    >
      <EnterpriseDatabaseWorkspace
        :catalog="catalog"
        :loading="catalogLoading"
        :error="catalogError"
        @refresh="loadCatalog(true)"
        @open-table="openTableFromWorkspace"
        @open-sql="openSqlFromWorkspace"
        @open-er="openErFromWorkspace"
      />
    </el-dialog>

    <el-dialog
      v-model="tableDialogOpen"
      :title="`表工作台 · ${tableForm.displaySchema || tableForm.schema}.${tableForm.table}`"
      width="92%"
      top="4vh"
      destroy-on-close
      append-to-body
      class="enterprise-table-dialog"
    >
      <div class="table-dialog-shell">
        <div class="enterprise-inspection">
          <TableInspectionView
            :inspection="tableInspection"
            :loading="tableInspectionLoading"
            :error="tableInspectionError"
            @refresh="loadTableInspection"
            @open-sql="applyInspectionSql"
          />
        </div>
      </div>
    </el-dialog>

    <el-dialog v-model="exportDialogOpen" title="申请导出" width="520">
      <el-form label-width="90px">
        <el-form-item label="数据源">
          <el-input :model-value="exportForm.grantedSourceName" disabled />
        </el-form-item>
        <el-form-item label="SQL">
          <el-input :model-value="exportForm.sql" type="textarea" :rows="6" readonly />
        </el-form-item>
        <el-form-item label="导出格式">
          <el-radio-group v-model="exportForm.format">
            <el-radio-button value="csv">CSV</el-radio-button>
            <el-radio-button value="json">JSON</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="申请理由">
          <el-input v-model="exportForm.reason" type="textarea" :rows="2" maxlength="500" show-word-limit />
        </el-form-item>
      </el-form>
      <el-alert type="info" :closable="false" title="审批通过后，请在「审批中心 → 我的申请」下载；审批单只能使用一次。" />
      <template #footer>
        <el-button @click="exportDialogOpen = false">取消</el-button>
        <el-button type="primary" :loading="submittingExport" @click="submitExportRequest">提交申请</el-button>
      </template>
    </el-dialog>

    <EnterpriseErDiagramView
      v-model:visible="erDialogOpen"
      :grants="grants"
      :initial-source="source"
      :initial-schema="erInitialSchema"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, shallowRef } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Download, Grid, Share, VideoPlay } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import SqlEditor from '@/components/editor/SqlEditor.vue'
import DataTable from '@/components/editor/DataTable.vue'
import TableInspectionView from '@/components/editor/TableInspectionView.vue'
import EnterpriseDatabaseWorkspace from '@/components/editor/EnterpriseDatabaseWorkspace.vue'
import EnterpriseErDiagramView from '@/components/editor/EnterpriseErDiagramView.vue'
import {
  entApi,
  type EnterpriseMetadataCatalog,
  type EnterpriseMetadataTable,
  type LogicalGrant,
} from '@/api/ent'
import type {
  ColumnMetadata,
  QueryResult,
  TableInspection,
} from '@/types/metadata'
import { LatestRequestGate } from '@/utils/requestControl'
import {
  parseSqlCompletionContext,
  type SqlCompletionTable,
} from '@/utils/sqlCompletion'

const route = useRoute()
const router = useRouter()
const grants = ref<LogicalGrant[]>([])
const source = ref('')
const sql = ref('')
const executing = ref(false)
const result = ref<QueryResult | null>(null)
const requestGate = new LatestRequestGate()
const REQUEST_KEY = 'enterprise-query'
const TABLE_INSPECTION_KEY = 'enterprise-table-inspection'
const CATALOG_KEY = 'enterprise-metadata-catalog'

// 授权目录可包含数千张表，不对每个表对象创建深层响应式代理。
const catalog = shallowRef<EnterpriseMetadataCatalog | null>(null)
const catalogLoading = ref(false)
const catalogError = ref<string | null>(null)
const columnCache = new Map<string, ColumnMetadata[]>()
const workspaceDialogOpen = ref(false)
const erDialogOpen = ref(false)
const erInitialSchema = ref('')
const selectedWorkspaceTable =
  ref<EnterpriseMetadataTable | null>(null)

const tableDialogOpen = ref(false)
const tableInspectionLoading = ref(false)
const tableInspection = ref<TableInspection | null>(null)
const tableInspectionError = ref<string | null>(null)
const tableForm = ref({
  schema: '',
  displaySchema: '',
  table: '',
  objectType: 'TABLE',
})

const exportDialogOpen = ref(false)
const submittingExport = ref(false)
const exportForm = ref({
  grantedSourceName: '',
  sql: '',
  format: 'csv' as 'csv' | 'json',
  reason: '',
})

const currentGrant = computed(() => grants.value.find(g => g.grantedSourceName === source.value))

async function loadCatalog(refresh = false) {
  if (!source.value) {
    catalog.value = null
    return
  }
  const sourceSnapshot = source.value
  const version = requestGate.begin(CATALOG_KEY)
  catalogLoading.value = true
  catalogError.value = null
  try {
    const next = await entApi.metadataCatalog(
      sourceSnapshot, refresh)
    if (requestGate.isCurrent(CATALOG_KEY, version)
      && sourceSnapshot === source.value) {
      catalog.value = next
    }
  } catch (error: any) {
    if (requestGate.isCurrent(CATALOG_KEY, version)) {
      catalog.value = null
      catalogError.value = error.message || '授权元数据目录加载失败'
    }
  } finally {
    if (requestGate.isCurrent(CATALOG_KEY, version)) {
      catalogLoading.value = false
    }
  }
}

async function loadGrants() {
  try {
    grants.value = await entApi.grantsMine()
  } catch {
    grants.value = []
  }
  const querySource = route.query.source as string | undefined
  if (querySource && grants.value.some(g => g.grantedSourceName === querySource)) {
    source.value = querySource
  } else if (grants.value.length) {
    source.value = grants.value[0].grantedSourceName
  }
  const stateSql = window.history.state?.sql
  if (typeof stateSql === 'string') sql.value = stateSql
  if (source.value) await loadCatalog(false)
}
onMounted(loadGrants)

function onSourceChange() {
  requestGate.invalidate(REQUEST_KEY)
  requestGate.invalidate(TABLE_INSPECTION_KEY)
  requestGate.invalidate(CATALOG_KEY)
  executing.value = false
  result.value = null
  tableInspection.value = null
  catalog.value = null
  catalogError.value = null
  columnCache.clear()
  void loadCatalog(false)
}

function openDatabaseWorkspace() {
  workspaceDialogOpen.value = true
  if (!catalog.value && !catalogLoading.value) {
    void loadCatalog(false)
  }
}

async function openTableFromWorkspace(
  table: EnterpriseMetadataTable,
) {
  selectedWorkspaceTable.value = table
  tableForm.value = {
    schema: table.namespace || table.schema,
    displaySchema: table.schema,
    table: table.name,
    objectType: table.type || 'TABLE',
  }
  tableInspection.value = null
  tableInspectionError.value = null
  tableDialogOpen.value = true
  workspaceDialogOpen.value = false
  await loadTableInspection()
}

function openSqlFromWorkspace(
  table: EnterpriseMetadataTable,
) {
  sql.value = `SELECT * FROM ${table.qualifiedName}`
  result.value = null
  workspaceDialogOpen.value = false
}

function openErFromWorkspace(schemaName?: string) {
  erInitialSchema.value = schemaName
    || selectedWorkspaceTable.value?.schema
    || catalog.value?.schemas[0]
    || ''
  erDialogOpen.value = true
}

async function loadTableInspection() {
  const schema = tableForm.value.schema.trim()
  const table = tableForm.value.table.trim()
  if (!source.value || !schema || !table || tableInspectionLoading.value) return
  const version = requestGate.begin(TABLE_INSPECTION_KEY)
  tableInspectionLoading.value = true
  tableInspectionError.value = null
  try {
    const inspection = await entApi.inspectTable(
      source.value, schema, table,
      tableForm.value.objectType || 'TABLE', 200)
    if (requestGate.isCurrent(TABLE_INSPECTION_KEY, version)) {
      tableInspection.value = {
        ...inspection,
        schema: tableForm.value.displaySchema
          || inspection.schema,
      }
    }
  } catch (error: any) {
    if (requestGate.isCurrent(TABLE_INSPECTION_KEY, version)) {
      tableInspection.value = null
      tableInspectionError.value = error.message || '表工作台加载失败'
    }
  } finally {
    if (requestGate.isCurrent(TABLE_INSPECTION_KEY, version)) {
      tableInspectionLoading.value = false
    }
  }
}

function applyInspectionSql(previewSql: string) {
  sql.value = previewSql
  tableDialogOpen.value = false
  result.value = null
}

function resultCatalogTable(): SqlCompletionTable | null {
  const query = result.value?.sql || sql.value
  if (!query.trim() || !catalog.value?.tables.length) return null
  const context = parseSqlCompletionContext(query, query.length)
  const unique = new Map<string, { schema: string | null; table: string }>()
  for (const reference of Object.values(context.references)) {
    const key = `${reference.schema || ''}.${reference.table}`
      .toLocaleLowerCase()
    unique.set(key, reference)
  }
  if (unique.size !== 1) return null
  const reference = [...unique.values()][0]
  return catalog.value.tables.find(table =>
    table.name.toLocaleLowerCase()
      === reference.table.toLocaleLowerCase()
    && (!reference.schema
      || table.schema.toLocaleLowerCase()
        === reference.schema.toLocaleLowerCase()),
  ) || null
}

async function loadCompletionColumns(
  table: SqlCompletionTable,
): Promise<ColumnMetadata[]> {
  const sourceSnapshot = source.value
  const namespace = table.namespace || table.schema
  const key = `${sourceSnapshot}:${namespace}:${table.name}`
  const cached = columnCache.get(key)
  if (cached) return cached
  const columns = await entApi.metadataColumns(
    sourceSnapshot, namespace, table.name)
  if (sourceSnapshot !== source.value) {
    return []
  }
  columnCache.set(key, columns)
  return columns
}

const resultRemarksLoader = computed(() => {
  const table = resultCatalogTable()
  if (!table) return null
  return async () => Object.fromEntries(
    (await loadCompletionColumns(table))
      .filter(column => Boolean(column.remarks?.trim()))
      .map(column => [column.name, column.remarks!.trim()]),
  )
})

async function execute() {
  if (executing.value || !source.value || !sql.value.trim()) return
  const version = requestGate.begin(REQUEST_KEY)
  const sourceSnapshot = source.value
  const sqlSnapshot = sql.value.trim()
  executing.value = true
  result.value = null
  try {
    const nextResult = await entApi.query(sourceSnapshot, sqlSnapshot)
    if (requestGate.isCurrent(REQUEST_KEY, version)) result.value = nextResult
  } catch (e: any) {
    if (!requestGate.isCurrent(REQUEST_KEY, version)) return
    // 后端把查询失败通常包装成结果；传输/认证异常才进入此处。
    result.value = {
      columns: ['error'],
      rows: [{ error: e.message || '执行失败' }],
      elapsedMs: 0,
      totalRows: 1,
      truncated: false,
      sql: sqlSnapshot,
    }
  } finally {
    if (requestGate.isCurrent(REQUEST_KEY, version)) executing.value = false
  }
}

function openExportRequest() {
  if (executing.value || !source.value || !sql.value.trim()) return
  exportForm.value = {
    grantedSourceName: source.value,
    sql: sql.value.trim(),
    format: 'csv',
    reason: '',
  }
  exportDialogOpen.value = true
}

async function submitExportRequest() {
  if (!exportForm.value.sql || !exportForm.value.grantedSourceName) return
  submittingExport.value = true
  try {
    await entApi.createApproval({
      operationType: 'EXPORT',
      grantedSourceName: exportForm.value.grantedSourceName,
      payloadJson: JSON.stringify({
        sql: exportForm.value.sql,
        format: exportForm.value.format,
        defaultDatabase: null,
      }),
      reason: exportForm.value.reason,
    })
    ElMessage.success('导出申请已提交')
    exportDialogOpen.value = false
    await router.push({ name: 'approvals', query: { tab: 'mine' } })
  } catch (e: any) {
    ElMessage.error(e.message || '提交导出申请失败')
  } finally {
    submittingExport.value = false
  }
}
</script>

<style scoped>
.page {
  max-width: 1240px;
  height: 100%;
  margin: 0 auto;
  display: flex;
  flex-direction: column;
}
.page-title { margin-bottom: 14px; }
.page-title h1 {
  margin: 4px 0 5px;
  font-size: 24px;
  font-weight: 720;
  letter-spacing: -0.03em;
}
.page-sub { color: var(--color-text-muted); font-size: 12px; }
.toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 54px;
  margin-bottom: 10px;
  padding: 8px 10px;
  border-radius: 12px;
}
.source-select { width: 280px; }
.spacer { flex: 1; }
.editor-wrap {
  min-height: 240px;
  flex: 1;
  overflow: hidden;
  border-radius: 14px;
  transform: none;
}
.result-wrap {
  max-height: 44%;
  margin-top: 12px;
  overflow: hidden;
  border-radius: 14px;
  transform: none;
}
.result-bar {
  display: flex;
  gap: 12px;
  padding: 8px 12px;
  border-bottom: 1px solid var(--color-panel-border);
  background: var(--color-panel-header);
  color: var(--color-text-muted);
  font-size: 11px;
  font-variant-numeric: tabular-nums;
}
.warn { color: var(--color-destructive); }

:global(.enterprise-table-dialog .el-dialog__body) {
  padding: 0 18px 18px;
}
.table-dialog-shell {
  display: flex;
  height: min(78vh, 850px);
  min-height: 520px;
  flex-direction: column;
  gap: 10px;
}
.table-locator {
  display: grid;
  grid-template-columns: minmax(180px, .7fr) auto minmax(260px, 1.2fr) auto;
  align-items: center;
  gap: 8px;
  padding: 10px;
  border-radius: 12px;
}
.locator-dot {
  color: var(--color-text-muted);
  font: 700 16px var(--font-mono, monospace);
}
.enterprise-inspection {
  min-height: 0;
  flex: 1;
  overflow: hidden;
  border: 1px solid var(--color-panel-border);
  border-radius: 14px;
}

@media (max-width: 768px) {
  .page { min-height: 0; }
  .page-title { flex-shrink: 0; }
  .toolbar { flex-wrap: wrap; align-items: flex-start; }
  .source-select { width: 100%; }
  .spacer { display: none; }
  .editor-wrap { min-height: 42vh; }
  .result-wrap { flex-shrink: 0; max-height: 45vh; }
  .table-dialog-shell { min-height: 70vh; }
  .table-locator { grid-template-columns: 1fr; }
  .locator-dot { display: none; }
}
</style>
