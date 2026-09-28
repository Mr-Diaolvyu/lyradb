<template>
  <div class="page">
    <div class="page-title">
      <div class="section-kicker">Governed query</div>
      <h1>企业查询</h1>
      <span class="page-sub">选择逻辑数据源，编写并执行 SQL。连接信息与凭据由平台安全托管。</span>
    </div>

    <div class="toolbar glass-surface">
      <div class="source-controls">
        <el-select v-model="source" placeholder="选择数据源" class="source-select" :disabled="executing" @change="onSourceChange">
          <el-option v-for="g in grants" :key="g.id" :label="g.grantedSourceName" :value="g.grantedSourceName" />
        </el-select>
        <el-tag v-if="currentGrant" size="small" :type="currentGrant.sqlCapability === 'DML_ALLOWED' ? 'success' : 'info'">
          {{ currentGrant.sqlCapability === 'DML_ALLOWED' ? '可写' : '只读' }} · 上限 {{ currentGrant.maxRowsPerQuery }} 行
        </el-tag>
      </div>
      <div class="execution-actions" role="group" aria-label="SQL 执行方式">
        <el-button :icon="VideoPlay" type="primary" :loading="executing" :disabled="!source || !sql.trim()"
          title="执行光标所在语句或选中内容，快捷键 Ctrl+Enter" @click="execute()">
          执行当前
        </el-button>
        <el-button :disabled="executing || !source || !sql.trim()"
          title="按顺序执行编辑器中的全部语句，快捷键 Ctrl+Shift+Enter" @click="executeScript">执行全部脚本</el-button>
        <el-button v-if="executing" type="warning" :disabled="cancelRequested" @click="cancelQuery">
          {{ cancelRequested ? '正在取消…' : '取消执行' }}
        </el-button>
      </div>
      <div class="toolbar-secondary">
        <el-button :icon="Grid" :disabled="!source" @click="openDatabaseWorkspace">数据库工作区</el-button>
        <el-button :icon="Share" :disabled="!source" @click="openErFromWorkspace()">ER 图</el-button>
        <el-button :icon="Download" :disabled="executing || !source || !sql.trim()" @click="openExportRequest">申请导出</el-button>
        <el-button :disabled="!source || !sql.trim()" @click="saveCurrentScript">保存脚本</el-button>
        <el-button @click="openLibrary">脚本与历史</el-button>
      </div>
    </div>

    <div class="query-workspace">
      <aside class="query-navigator data-card">
        <div class="navigator-title">授权对象 <el-button size="small" text @click="refreshNavigation">刷新</el-button></div>
        <el-input v-model="navFilter" size="small" clearable placeholder="搜索当前数据源全部授权表" aria-label="搜索当前数据源的全部授权表" />
        <el-button size="small" text @click="openDatabaseWorkspace">打开目录工作区</el-button>
        <el-alert v-if="navError" type="warning" :title="navError" :closable="false" />
        <div class="navigator-list" v-loading="navSearchLoading">
          <template v-if="navFilter.trim().length >= 2">
            <button v-for="table in navSearchResults" :key="table.qualifiedName" type="button"
              class="nav-search-result" :title="table.qualifiedName"
              @click="selectedWorkspaceTable = table" @dblclick="openTableFromWorkspace(table)">
              <strong>{{ table.name }}</strong><small>{{ table.schema }}</small>
            </button>
            <div v-if="navSearchHasMore" class="nav-search-tip">结果超过 100 个，请输入更具体的名称</div>
            <el-empty v-else-if="!navSearchLoading && !navSearchResults.length && !navError"
              description="未找到匹配的授权表" :image-size="54" />
          </template>
          <div v-else-if="navFilter.trim()" class="nav-search-tip">至少输入 2 个字符，搜索全部授权表</div>
          <el-tree v-else-if="source" ref="navigationTreeRef" :key="`${source}:${navRevision}`" lazy node-key="path"
            :load="loadNavigationNode" :props="navigationTreeProps" @node-click="onNavigationClick">
            <template #default="{ data }">
              <span v-if="data.type === 'MORE'" class="nav-node-more">继续加载…</span>
              <span v-else class="nav-node-label" :draggable="Boolean(data.table)"
                :title="data.table ? `拖入 SQL 编辑器：${data.table.qualifiedName}` : data.path"
                @dragstart="data.table && dragTable($event, data.table)"
                @dblclick="data.table && openTableFromWorkspace(data.table)">
                {{ data.table ? (data.type === 'VIEW' ? '◇' : '▦') : '▣' }} {{ data.name }}
              </span>
            </template>
          </el-tree>
        </div>
        <el-button v-if="selectedWorkspaceTable" size="small" @click="openTableFromWorkspace(selectedWorkspaceTable)">打开表详情</el-button>
      </aside>
      <section class="query-center" :class="{ 'results-expanded': resultExpanded }">
        <div class="sql-tab-strip">
          <button v-for="tab in sqlTabs" :key="tab.id" type="button" :class="{ selected: tab.id === activeSqlTabId }"
            @click="activeSqlTabId = tab.id">{{ tab.title }}<span @click.stop="closeSqlTab(tab.id)"> ×</span></button>
          <el-button size="small" text @click="newSqlTab()">＋ SQL</el-button>
        </div>
        <div class="editor-wrap data-card">
          <SqlEditor ref="sqlEditorRef"
            :model-value="sql"
            :db-type="currentGrant?.dbType || catalog?.dbType"
            :completion-tables="catalog?.tables || loadedNavigationTables"
            :columns-loader="loadCompletionColumns"
            metadata-scope="authorized"
            @update:model-value="(v: string) => sql = v"
            @execute="execute"
            @execute-script="executeScript"
          />
        </div>
        <div class="result-wrap data-card">
          <div class="result-tab-strip">
            <button v-for="tab in resultTabs" :key="tab.id" type="button" :class="{ selected: tab.id === activeResultId }"
              @click="activeResultId = tab.id">{{ tab.title }}<span v-if="tab.loading" class="result-tab-spinner" aria-hidden="true"></span><span v-else @click.stop="closeResultTab(tab.id)"> ×</span></button>
            <el-button class="result-expand" size="small" text @click="resultExpanded = !resultExpanded">
              {{ resultExpanded ? '显示编辑器' : '展开结果' }}
            </el-button>
          </div>
          <template v-if="activeResult">
            <div v-if="activeResult.loading" class="query-progress" role="status" aria-live="polite">
              <span class="query-progress-indicator" aria-hidden="true"></span>
              <strong>正在执行 SQL</strong>
              <span class="query-progress-note">正在等待数据源返回结果，请稍候…</span>
              <span class="query-progress-track" aria-hidden="true"></span>
            </div>
            <div v-if="activeResult.result" class="result-bar">
              <span>{{ activeResult.result.totalRows }} 行 · {{ activeResult.result.elapsedMs }}ms</span>
              <span v-if="activeResult.result.truncated" class="warn">结果已截断</span>
            </div>
            <el-alert v-if="activeResult.error" type="error" :title="activeResult.error" :closable="false" />
            <el-button v-if="activeResult.approvalId" size="small" type="primary" @click="openApproval(activeResult.approvalId)">查看审批 {{ activeResult.approvalStatus }}</el-button>
            <DataTable v-if="activeResult.result" :columns="activeResult.result.columns"
              :rows="activeResult.result.rows" :remarks-loader="resultRemarksLoader" />
          </template>
          <el-empty v-else description="执行 SQL 后在此查看结果" :image-size="60" />
        </div>
      </section>
    </div>

    <el-drawer v-model="libraryOpen" title="脚本与查询历史" size="430px">
      <el-tabs v-model="libraryTab">
        <el-tab-pane label="已保存脚本" name="scripts">
          <div v-for="script in savedScripts" :key="script.id" class="library-row">
            <button type="button" @click="openSavedScript(script)"><strong>{{ script.title }}</strong><small>{{ script.grantedSourceName }} · {{ fmtTime(script.updatedAt) }}</small></button>
            <el-button size="small" text type="danger" @click="deleteSavedScript(script.id)">删除</el-button>
          </div>
        </el-tab-pane>
        <el-tab-pane label="查询历史" name="history">
          <div v-for="item in queryHistory" :key="item.id" class="library-row">
            <button type="button" @click="openHistory(item)"><strong>{{ item.succeeded ? '成功' : '失败' }} · {{ item.grantedSourceName }}</strong><small>{{ fmtTime(item.createdAt) }} · {{ item.sql.slice(0, 110) }}</small></button>
          </div>
        </el-tab-pane>
      </el-tabs>
    </el-drawer>

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
      title="表工作台"
      width="92%"
      top="4vh"
      destroy-on-close
      append-to-body
      class="enterprise-table-dialog"
    >
      <div class="table-dialog-shell">
        <div
          v-if="tableTabs.length"
          class="table-tab-strip"
          role="tablist"
          aria-label="已打开的表"
        >
          <div
            v-for="(tab, index) in tableTabs"
            :key="tab.id"
            :class="['table-workspace-tab', { selected: tab.id === activeTableTabId }]"
          >
            <button
              type="button"
              role="tab"
              :aria-selected="tab.id === activeTableTabId"
              :tabindex="tab.id === activeTableTabId ? 0 : -1"
              :title="tab.table.qualifiedName"
              @click="activateTableTab(tab.id)"
              @keydown="onTableTabKeydown($event, index)"
            >
              <span class="table-tab-title">{{ tab.table.qualifiedName }}</span>
            </button>
            <button
              type="button"
              class="table-tab-close"
              :aria-label="`关闭 ${tab.table.qualifiedName}`"
              title="关闭标签"
              @click.stop="closeTableTab(tab.id)"
            >×</button>
          </div>
        </div>
        <div v-if="activeTableTab" class="enterprise-inspection">
          <div class="table-mode-bar">
            <el-button size="small" :type="tablePane === 'inspection' ? 'primary' : 'default'" @click="tablePane = 'inspection'">结构与预览</el-button>
            <el-button size="small" :type="tablePane === 'grid' ? 'primary' : 'default'" @click="tablePane = 'grid'">表格增改删</el-button>
          </div>
          <TableInspectionView
            v-if="tablePane === 'inspection'"
            :key="activeTableTab.id"
            :inspection="activeTableTab.inspection"
            :loading="activeTableTab.loading"
            :error="activeTableTab.error"
            :partition-page="activeTableTab.partitionPage"
            :partition-loading="activeTableTab.partitionLoading"
            :partition-error="activeTableTab.partitionError"
            :partition-filter="activeTableTab.partitionFilter"
            :selected-partition="activeTableTab.selectedPartition"
            :preview-loading="activeTableTab.previewLoading"
            :preview-error="activeTableTab.previewError"
            @refresh="refreshActiveTable"
            @open-sql="applyInspectionSql"
            @load-partitions="loadActiveTablePartitions"
            @select-partition="selectActiveTablePartition"
            @load-preview="loadActiveTablePreview"
          />
          <EnterpriseTableGrid v-else :key="activeTableTab.id" :source="source"
            :schema="activeTableTab.table.schema" :table="activeTableTab.table.name" />
        </div>
        <el-empty v-else description="尚未打开表" :image-size="68" />
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
import { ref, computed, nextTick, onMounted, onUnmounted, shallowRef, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Download, Grid, Share, VideoPlay } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import SqlEditor from '@/components/editor/SqlEditor.vue'
import DataTable from '@/components/editor/DataTable.vue'
import TableInspectionView from '@/components/editor/TableInspectionView.vue'
import EnterpriseTableGrid from '@/components/editor/EnterpriseTableGrid.vue'
import EnterpriseDatabaseWorkspace from '@/components/editor/EnterpriseDatabaseWorkspace.vue'
import EnterpriseErDiagramView from '@/components/editor/EnterpriseErDiagramView.vue'
import {
  entApi,
  type EnterpriseMetadataCatalog,
  type EnterpriseMetadataTable,
  type EnterprisePartitionPage,
  type LogicalGrant,
  type SavedSql,
  type EnterpriseQueryHistory,
  type EnterpriseNavigationNode,
} from '@/api/ent'
import type { ApiError } from '@/api/index'
import { useAuthStore } from '@/stores/auth'
import { splitSqlStatements } from '@/utils/sqlStatements'
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
import {
  normalizedTableIdentity,
  resolveTabNavigationIndex,
} from '@/utils/workspaceTabs'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const grants = ref<LogicalGrant[]>([])
const source = ref('')
interface SqlTab { id: string; title: string; sql: string; savedId?: string }
const sqlTabs = ref<SqlTab[]>([{ id: 'sql-1', title: 'SQL 1', sql: '' }])
const activeSqlTabId = ref('sql-1')
const currentSqlTab = computed(() => sqlTabs.value.find(tab => tab.id === activeSqlTabId.value) || sqlTabs.value[0])
const sql = computed({
  get: () => currentSqlTab.value?.sql || '',
  set: (value: string) => { if (currentSqlTab.value) currentSqlTab.value.sql = value },
})
const sqlEditorRef = ref<InstanceType<typeof SqlEditor> | null>(null)
const executing = ref(false)
interface ResultTab { id: string; title: string; result: QueryResult | null;
  loading: boolean; error?: string; approvalId?: string; approvalStatus?: string }
const resultTabs = ref<ResultTab[]>([])
const activeResultId = ref('')
const activeResult = computed(() => resultTabs.value.find(tab => tab.id === activeResultId.value) || null)
const resultExpanded = ref(false)
const result = computed<QueryResult | null>({
  get: () => activeResult.value?.result || null,
  set: value => { if (activeResult.value) activeResult.value.result = value },
})
const navFilter = ref('')
const navRevision = ref(0)
const navError = ref('')
const navSearchResults = ref<EnterpriseMetadataTable[]>([])
const navSearchLoading = ref(false)
const navSearchHasMore = ref(false)
let navSearchTimer: ReturnType<typeof setTimeout> | undefined
let navSearchVersion = 0
const navigationTreeRef = ref<any>(null)
const loadedNavigationTables = ref<EnterpriseMetadataTable[]>([])
interface NavNode extends EnterpriseNavigationNode { offset?: number; parentPath?: string; leaf?: boolean }
const navigationTreeProps = { label: 'name', isLeaf: 'leaf' }
const libraryOpen = ref(false)
const libraryTab = ref('scripts')
const savedScripts = ref<SavedSql[]>([])
const queryHistory = ref<EnterpriseQueryHistory[]>([])
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
const tablePane = ref<'inspection' | 'grid'>('inspection')
interface EnterpriseTableTabState {
  id: string
  table: EnterpriseMetadataTable
  inspection: TableInspection | null
  loading: boolean
  error: string | null
  partitionPage: EnterprisePartitionPage | null
  partitionLoading: boolean
  partitionError: string | null
  partitionFilter: string
  selectedPartition: string | null
  previewLoading: boolean
  previewError: string | null
}
const tableTabs = ref<EnterpriseTableTabState[]>([])
const activeTableTabId = ref<string | null>(null)
const activeTableTab = computed(() => tableTabs.value.find(
  tab => tab.id === activeTableTabId.value,
) || null)

const exportDialogOpen = ref(false)
const submittingExport = ref(false)
const exportForm = ref({
  grantedSourceName: '',
  sql: '',
  format: 'csv' as 'csv' | 'json',
  reason: '',
})

const currentGrant = computed(() => grants.value.find(g => g.grantedSourceName === source.value))
function navigationNodes(parentPath: string | undefined, offset: number,
  page: { nodes: EnterpriseNavigationNode[]; hasMore: boolean; limit: number }): NavNode[] {
  for (const node of page.nodes) {
    if (node.table && !loadedNavigationTables.value.some(table => table.qualifiedName === node.table?.qualifiedName)) {
      loadedNavigationTables.value.push(node.table)
    }
  }
  const nodes: NavNode[] = page.nodes.map(node => ({ ...node, leaf: !node.hasChildren }))
  if (page.hasMore) nodes.push({ name: '继续加载…', type: 'MORE', path: `more:${parentPath || 'root'}:${offset + page.limit}`,
    hasChildren: false, leaf: true, offset: offset + page.limit, parentPath })
  return nodes
}

async function loadNavigationNode(node: any, resolve: (nodes: NavNode[]) => void) {
  const parentPath = node.level === 0 ? undefined : (node.data as NavNode).path
  try {
    const page = await entApi.metadataNavigation(source.value, parentPath)
    resolve(navigationNodes(parentPath, 0, page))
    navError.value = ''
  } catch (failure: any) {
    resolve([])
    navError.value = failure.message || '授权对象加载失败'
  }
}

async function onNavigationClick(data: NavNode, node: any) {
  if (data.table) { selectedWorkspaceTable.value = data.table; return }
  if (data.type !== 'MORE') return
  try {
    const page = await entApi.metadataNavigation(source.value, data.parentPath, data.offset || 0)
    const parent = node.parent
    navigationTreeRef.value?.remove(node)
    for (const item of navigationNodes(data.parentPath, data.offset || 0, page)) {
      navigationTreeRef.value?.append(item, parent)
    }
  } catch (failure: any) { navError.value = failure.message || '继续加载失败' }
}

watch(navFilter, value => {
  clearTimeout(navSearchTimer)
  const version = ++navSearchVersion
  const query = value.trim()
  navSearchResults.value = []
  navSearchHasMore.value = false
  navSearchLoading.value = false
  navError.value = ''
  if (query.length < 2 || !source.value) return
  const selectedSource = source.value
  navSearchLoading.value = true
  navSearchTimer = setTimeout(async () => {
    try {
      const page = await entApi.metadataSearch(selectedSource, query)
      if (version !== navSearchVersion || selectedSource !== source.value) return
      navSearchResults.value = page.tables
      navSearchHasMore.value = page.hasMore
      for (const table of page.tables) {
        if (!loadedNavigationTables.value.some(item => item.qualifiedName === table.qualifiedName)) {
          loadedNavigationTables.value.push(table)
        }
      }
    } catch (failure: any) {
      if (version === navSearchVersion) navError.value = failure.message || '授权表搜索失败'
    } finally {
      if (version === navSearchVersion) navSearchLoading.value = false
    }
  }, 300)
})
onUnmounted(() => clearTimeout(navSearchTimer))

function refreshNavigation() {
  clearTimeout(navSearchTimer)
  navSearchVersion++
  navFilter.value = ''
  navSearchResults.value = []
  navSearchLoading.value = false
  navSearchHasMore.value = false
  loadedNavigationTables.value = []
  selectedWorkspaceTable.value = null
  navRevision.value++
  navError.value = ''
}

function dragTable(event: DragEvent, table: EnterpriseMetadataTable) {
  event.dataTransfer?.setData('text/plain', table.qualifiedName)
}

function newSqlTab(content = '', title?: string, savedId?: string) {
  if (sqlTabs.value.length >= 10) { ElMessage.warning('最多打开 10 个 SQL 页签'); return }
  const id = `sql-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`
  sqlTabs.value.push({ id, title: title || `SQL ${sqlTabs.value.length + 1}`, sql: content, savedId })
  activeSqlTabId.value = id
}

function closeSqlTab(id: string) {
  const index = sqlTabs.value.findIndex(tab => tab.id === id)
  if (index < 0) return
  if (sqlTabs.value.length === 1) {
    sqlTabs.value[0].sql = ''
    sqlTabs.value[0].savedId = undefined
    sqlTabs.value[0].title = 'SQL 1'
    return
  }
  sqlTabs.value.splice(index, 1)
  if (activeSqlTabId.value === id) activeSqlTabId.value = sqlTabs.value[Math.min(index, sqlTabs.value.length - 1)].id
}

function closeResultTab(id: string) {
  const index = resultTabs.value.findIndex(tab => tab.id === id)
  if (index < 0) return
  resultTabs.value.splice(index, 1)
  if (activeResultId.value === id) activeResultId.value = resultTabs.value[Math.min(index, resultTabs.value.length - 1)]?.id || ''
}

function addResultTab(statement: string): ResultTab {
  const id = `result-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`
  const tab: ResultTab = { id, title: statement.replace(/\s+/g, ' ').slice(0, 25), result: null, loading: true }
  resultTabs.value.push(tab)
  if (resultTabs.value.length > 5) resultTabs.value.shift()
  activeResultId.value = id
  return tab
}

async function openLibrary() {
  libraryOpen.value = true
  try {
    [savedScripts.value, queryHistory.value] = await Promise.all([
      entApi.savedSqlScripts(), entApi.enterpriseQueryHistory(),
    ])
  } catch (failure: any) { ElMessage.error(failure.message || '脚本与历史加载失败') }
}

async function saveCurrentScript() {
  if (!source.value || !sql.value.trim()) return
  let title = currentSqlTab.value.title
  if (!currentSqlTab.value.savedId) {
    try {
      const answer = await ElMessageBox.prompt('脚本名称', '保存 SQL 脚本', {
        inputValue: title, inputValidator: value => !!value?.trim() || '请输入脚本名称',
      })
      title = answer.value.trim()
    } catch { return }
  }
  try {
    const saved = await entApi.saveSqlScript({ id: currentSqlTab.value.savedId,
      title, grantedSourceName: source.value, sql: sql.value })
    currentSqlTab.value.savedId = saved.id
    currentSqlTab.value.title = saved.title
    savedScripts.value = await entApi.savedSqlScripts()
    ElMessage.success('脚本已保存')
  } catch (failure: any) { ElMessage.error(failure.message || '保存脚本失败') }
}

function openSavedScript(script: SavedSql) {
  if (!grants.value.some(grant => grant.grantedSourceName === script.grantedSourceName)) {
    ElMessage.warning('脚本所用数据源当前无授权')
    return
  }
  if (source.value !== script.grantedSourceName) {
    source.value = script.grantedSourceName
    onSourceChange()
  }
  newSqlTab(script.sql, script.title, script.id)
  libraryOpen.value = false
}

function openHistory(item: EnterpriseQueryHistory) {
  if (!grants.value.some(grant => grant.grantedSourceName === item.grantedSourceName)) {
    ElMessage.warning('历史 SQL 所用数据源当前无授权')
    return
  }
  if (source.value !== item.grantedSourceName) {
    source.value = item.grantedSourceName
    onSourceChange()
  }
  newSqlTab(item.sql, '历史 SQL')
  libraryOpen.value = false
}

async function deleteSavedScript(id: string) {
  try {
    await ElMessageBox.confirm('删除这个已保存脚本？', '删除脚本')
    await entApi.deleteSqlScript(id)
    savedScripts.value = await entApi.savedSqlScripts()
  } catch { /* 用户取消或接口返回明确错误，不执行后续操作 */ }
}

function fmtTime(value: string) { return new Date(value).toLocaleString() }

function openApproval(id: string) {
  void router.push({ name: 'approvals', query: { tab: 'mine', id } })
}

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
  if (source.value) refreshNavigation()
}
onMounted(loadGrants)
watch(() => auth.user?.currentWorkspaceId, async (next, previous) => {
  if (!previous || next === previous) return
  source.value = ''
  onSourceChange()
  savedScripts.value = []
  queryHistory.value = []
  await loadGrants()
})

function onSourceChange() {
  requestGate.invalidate(REQUEST_KEY)
  requestGate.invalidate(TABLE_INSPECTION_KEY)
  requestGate.invalidate(CATALOG_KEY)
  executing.value = false
  resultTabs.value = []
  activeResultId.value = ''
  sqlTabs.value = [{ id: 'sql-1', title: 'SQL 1', sql: '' }]
  activeSqlTabId.value = 'sql-1'
  refreshNavigation()
  for (const tab of tableTabs.value) {
    requestGate.invalidate(tableRequestKey(tab.id))
    requestGate.invalidate(partitionRequestKey(tab.id))
    requestGate.invalidate(previewRequestKey(tab.id))
  }
  tableTabs.value = []
  activeTableTabId.value = null
  tableDialogOpen.value = false
  catalog.value = null
  catalogError.value = null
  columnCache.clear()
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
  const tableKey = enterpriseTableKey(table)
  let tab = tableTabs.value.find(candidate =>
    enterpriseTableKey(candidate.table) === tableKey,
  )
  if (!tab) {
    tab = {
      id: `enterprise-table-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
      table,
      inspection: null,
      loading: false,
      error: null,
      partitionPage: null,
      partitionLoading: false,
      partitionError: null,
      partitionFilter: '',
      selectedPartition: null,
      previewLoading: false,
      previewError: null,
    }
    tableTabs.value.push(tab)
  }
  activeTableTabId.value = tab.id
  tablePane.value = 'inspection'
  tableDialogOpen.value = true
  workspaceDialogOpen.value = false
  if (!tab.inspection && !tab.loading) {
    await loadTableInspection(tab.id)
  }
}

function openSqlFromWorkspace(
  table: EnterpriseMetadataTable,
) {
  if (table.partitioned) {
    ElMessage.info('MaxCompute 分区表请先在表工作台选择完整分区')
    void openTableFromWorkspace(table)
    return
  }
  newSqlTab(`SELECT * FROM ${table.qualifiedName}`, table.name)
  workspaceDialogOpen.value = false
}

function openErFromWorkspace(schemaName?: string) {
  erInitialSchema.value = schemaName
    || selectedWorkspaceTable.value?.schema
    || catalog.value?.schemas[0]
    || ''
  erDialogOpen.value = true
}

async function loadTableInspection(tabId: string) {
  const tab = findTableTab(tabId)
  if (!source.value || !tab || tab.loading) return
  const sourceSnapshot = source.value
  const requestKey = tableRequestKey(tabId)
  const version = requestGate.begin(requestKey)
  tab.loading = true
  tab.error = null
  tab.previewError = null
  try {
    const inspection = await entApi.inspectTable(
      sourceSnapshot,
      tab.table.namespace || tab.table.schema,
      tab.table.name,
      tab.table.type || 'TABLE',
      100,
      { includePreview: false },
    )
    if (requestGate.isCurrent(requestKey, version)
      && sourceSnapshot === source.value) {
      const current = findTableTab(tabId)
      if (!current) return
      current.inspection = {
        ...inspection,
        schema: current.table.schema || inspection.schema,
        remarks: inspection.remarks ?? inspection.tableComment ?? current.table.remarks ?? null,
      }
      current.selectedPartition = null
      current.partitionPage = null
      if (inspection.partitioned) {
        void loadTablePartitions(tabId, { filter: '', offset: 0, limit: 50 })
      }
    }
  } catch (error: any) {
    if (requestGate.isCurrent(requestKey, version)) {
      const current = findTableTab(tabId)
      if (current) {
        current.inspection = null
        current.error = error.message || '表工作台加载失败'
      }
    }
  } finally {
    if (requestGate.isCurrent(requestKey, version)) {
      const current = findTableTab(tabId)
      if (current) current.loading = false
    }
  }
}

async function loadTablePartitions(
  tabId: string,
  request: { filter: string; offset: number; limit: number },
) {
  const tab = findTableTab(tabId)
  if (!source.value || !tab || tab.partitionLoading) return
  const requestKey = partitionRequestKey(tabId)
  const version = requestGate.begin(requestKey)
  const sourceSnapshot = source.value
  tab.partitionLoading = true
  tab.partitionError = null
  tab.partitionFilter = request.filter
  try {
    const page = await entApi.tablePartitions(
      sourceSnapshot,
      tab.table.namespace || tab.table.schema,
      tab.table.name,
      request.offset,
      request.limit,
      request.filter,
    )
    if (requestGate.isCurrent(requestKey, version)
      && sourceSnapshot === source.value) {
      const current = findTableTab(tabId)
      if (current) current.partitionPage = page
    }
  } catch (error: any) {
    if (requestGate.isCurrent(requestKey, version)) {
      const current = findTableTab(tabId)
      if (current) current.partitionError = error.message || '分区元数据加载失败'
    }
  } finally {
    if (requestGate.isCurrent(requestKey, version)) {
      const current = findTableTab(tabId)
      if (current) current.partitionLoading = false
    }
  }
}

function selectTablePartition(tabId: string, partition: string) {
  const tab = findTableTab(tabId)
  if (!tab || !partition) return
  tab.selectedPartition = partition
  tab.previewError = null
  if (tab.inspection) {
    tab.inspection = {
      ...tab.inspection,
      preview: null,
      previewSql: '',
      selectedPartition: partition,
      errors: { ...tab.inspection.errors, preview: '' },
    }
  }
}

async function loadTablePreview(tabId: string) {
  const tab = findTableTab(tabId)
  if (!source.value || !tab || tab.previewLoading) return
  if (tab.inspection?.previewRequiresPartition
    && (!tab.inspection.partitioned || !tab.selectedPartition)) {
    tab.previewError = tab.inspection.partitioned
      ? '必须先选择完整分区，已阻止无分区预览'
      : '无法确认分区状态，已阻止数据预览'
    return
  }
  const requestKey = previewRequestKey(tabId)
  const version = requestGate.begin(requestKey)
  const sourceSnapshot = source.value
  const partitionSnapshot = tab.selectedPartition
  tab.previewLoading = true
  tab.previewError = null
  try {
    const inspection = await entApi.inspectTable(
      sourceSnapshot,
      tab.table.namespace || tab.table.schema,
      tab.table.name,
      tab.table.type || 'TABLE',
      100,
      { includePreview: true, partitionSpec: partitionSnapshot },
    )
    if (requestGate.isCurrent(requestKey, version)
      && sourceSnapshot === source.value) {
      const current = findTableTab(tabId)
      if (!current || current.selectedPartition !== partitionSnapshot) return
      current.inspection = {
        ...(current.inspection || inspection),
        preview: inspection.preview,
        previewSql: inspection.previewSql,
        selectedPartition: partitionSnapshot,
        errors: inspection.errors,
      }
      current.previewError = inspection.errors?.preview || null
    }
  } catch (error: any) {
    if (requestGate.isCurrent(requestKey, version)) {
      const current = findTableTab(tabId)
      if (current) current.previewError = error.message || '数据预览加载失败'
    }
  } finally {
    if (requestGate.isCurrent(requestKey, version)) {
      const current = findTableTab(tabId)
      if (current) current.previewLoading = false
    }
  }
}

function refreshActiveTable() {
  if (activeTableTabId.value) void loadTableInspection(activeTableTabId.value)
}

function loadActiveTablePartitions(request: { filter: string; offset: number; limit: number }) {
  if (activeTableTabId.value) void loadTablePartitions(activeTableTabId.value, request)
}

function selectActiveTablePartition(partition: string) {
  if (activeTableTabId.value) selectTablePartition(activeTableTabId.value, partition)
}

function loadActiveTablePreview() {
  if (activeTableTabId.value) void loadTablePreview(activeTableTabId.value)
}

function activateTableTab(tabId: string, focus = false) {
  if (!findTableTab(tabId)) return
  activeTableTabId.value = tabId
  if (focus) {
    void nextTick(() => {
      document.querySelector<HTMLElement>(
        `.table-workspace-tab.selected [role="tab"]`,
      )?.focus()
    })
  }
}

function closeTableTab(tabId: string) {
  const index = tableTabs.value.findIndex(tab => tab.id === tabId)
  if (index < 0) return
  requestGate.invalidate(tableRequestKey(tabId))
  requestGate.invalidate(partitionRequestKey(tabId))
  requestGate.invalidate(previewRequestKey(tabId))
  tableTabs.value.splice(index, 1)
  if (activeTableTabId.value === tabId) {
    activeTableTabId.value = tableTabs.value[Math.min(index, tableTabs.value.length - 1)]?.id || null
  }
  if (!tableTabs.value.length) tableDialogOpen.value = false
}

function onTableTabKeydown(event: KeyboardEvent, index: number) {
  const nextIndex = resolveTabNavigationIndex(
    event.key, index, tableTabs.value.length,
  )
  if (nextIndex === null) return
  event.preventDefault()
  const next = tableTabs.value[nextIndex]
  if (next) activateTableTab(next.id, true)
}

function findTableTab(tabId: string) {
  return tableTabs.value.find(tab => tab.id === tabId) || null
}

function enterpriseTableKey(table: EnterpriseMetadataTable) {
  return normalizedTableIdentity(
    source.value, table.namespace || table.schema, table.name,
  )
}

function tableRequestKey(tabId: string) { return `${TABLE_INSPECTION_KEY}:${tabId}` }
function partitionRequestKey(tabId: string) { return `${TABLE_INSPECTION_KEY}:partitions:${tabId}` }
function previewRequestKey(tabId: string) { return `${TABLE_INSPECTION_KEY}:preview:${tabId}` }

function applyInspectionSql(previewSql: string) {
  newSqlTab(previewSql, '表数据预览')
  tableDialogOpen.value = false
}

function resultCatalogTable(): SqlCompletionTable | null {
  const query = result.value?.sql || sql.value
  const candidateTables = catalog.value?.tables || loadedNavigationTables.value
  if (!query.trim() || !candidateTables.length) return null
  const context = parseSqlCompletionContext(query, query.length)
  const unique = new Map<string, { schema: string | null; table: string }>()
  for (const reference of Object.values(context.references)) {
    const key = `${reference.schema || ''}.${reference.table}`
      .toLocaleLowerCase()
    unique.set(key, reference)
  }
  if (unique.size !== 1) return null
  const reference = [...unique.values()][0]
  return candidateTables.find(table =>
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

const activeExecutionId = ref('')
const cancelRequested = ref(false)

async function cancelQuery() {
  cancelRequested.value = true
  if (!activeExecutionId.value) return
  try {
    await entApi.cancelQuery(activeExecutionId.value)
  } catch (error: any) {
    ElMessage.warning(error.message || '取消请求未确认，已停止后续脚本；请等待当前执行结果')
  }
}

async function execute(statement?: string) {
  if (executing.value || !source.value || !sql.value.trim()) return
  const target = statement?.trim() || sqlEditorRef.value?.currentStatement()?.trim() || sql.value.trim()
  if (!target) return
  cancelRequested.value = false
  executing.value = true
  try { await executeSingle(target) }
  finally { executing.value = false }
}

async function executeScript() {
  if (executing.value || !source.value) return
  const statements = splitSqlStatements(sql.value)
  if (!statements.length) return
  if (statements.length > 50) { ElMessage.warning('每次最多执行 50 条语句'); return }
  const sourceSnapshot = source.value
  cancelRequested.value = false
  executing.value = true
  try {
    for (const statement of statements) {
      if (cancelRequested.value || source.value !== sourceSnapshot || !await executeSingle(statement.sql, sourceSnapshot)) break
    }
  } finally { executing.value = false }
}

async function executeSingle(sqlSnapshot: string, sourceSnapshot = source.value): Promise<boolean> {
  const version = requestGate.begin(REQUEST_KEY)
  const tab = addResultTab(sqlSnapshot)
  try {
    const prepared = await entApi.prepareQuery(sourceSnapshot)
    activeExecutionId.value = prepared.executionId
    if (cancelRequested.value) await entApi.cancelQuery(prepared.executionId)
    const nextResult = await entApi.query(sourceSnapshot, sqlSnapshot, undefined, prepared.executionId)
    if (requestGate.isCurrent(REQUEST_KEY, version)) tab.result = nextResult
    return true
  } catch (e: any) {
    if (requestGate.isCurrent(REQUEST_KEY, version)) {
      tab.error = e.message || '执行失败'
      const approval = e as ApiError
      if (approval.code === 'APPROVAL_REQUIRED') {
        tab.approvalId = approval.approvalRequestId
        tab.approvalStatus = approval.approvalStatus
      }
    }
    return false
  } finally {
    activeExecutionId.value = ''
    tab.loading = false
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
  width: 100%;
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
  flex-wrap: wrap;
  align-items: center;
  gap: 8px 14px;
  min-height: 54px;
  margin-bottom: 10px;
  padding: 8px 10px;
  border-radius: 12px;
}
.source-controls, .execution-actions, .toolbar-secondary { display: flex; align-items: center; gap: 8px; }
.source-controls { min-width: 0; }
.source-select { width: clamp(220px, 18vw, 300px); }
.execution-actions { flex: 0 0 auto; padding-left: 14px; border-left: 1px solid var(--color-panel-border); }
.toolbar-secondary { flex-wrap: wrap; margin-left: auto; }
.toolbar :deep(.el-button + .el-button) { margin-left: 0; }
.query-workspace { display: flex; gap: 10px; flex: 1; min-height: 0; }
.query-navigator { display: flex; flex: 0 0 230px; flex-direction: column; gap: 8px; min-height: 0; overflow: hidden; padding: 10px; border-radius: 12px; }
.navigator-title { display: flex; align-items: center; justify-content: space-between; font-weight: 600; }
.navigator-list { min-height: 0; flex: 1; overflow: auto; }
.nav-search-result { display: flex; width: 100%; flex-direction: column; padding: 7px 6px; border: 0; border-bottom: 1px solid var(--color-panel-border); background: transparent; color: var(--color-foreground); text-align: left; cursor: pointer; }
.nav-search-result:hover, .nav-search-result:focus-visible { background: var(--color-hover); }
.nav-search-result strong, .nav-search-result small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.nav-search-result small, .nav-search-tip { color: var(--color-text-muted); font-size: 11px; }
.nav-search-tip { padding: 10px 5px; }
.navigator-schema button, .navigator-tables button { display: block; width: 100%; padding: 5px 4px; border: 0; background: transparent; color: var(--color-foreground); text-align: left; cursor: pointer; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.navigator-schema button:hover, .navigator-tables button:hover { background: var(--color-hover); }
.navigator-tables { margin-left: 13px; }
.navigator-tables small { color: var(--color-text-muted); }
.query-center { display: flex; flex: 1; flex-direction: column; min-width: 0; min-height: 0; }
.sql-tab-strip, .result-tab-strip { display: flex; min-height: 32px; overflow-x: auto; gap: 3px; }
.sql-tab-strip button, .result-tab-strip button { flex: 0 0 auto; max-width: 180px; padding: 4px 10px; border: 1px solid var(--color-panel-border); border-radius: 7px 7px 0 0; background: var(--color-panel-header); color: var(--color-text-muted); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; cursor: pointer; }
.sql-tab-strip button.selected, .result-tab-strip button.selected { color: var(--color-foreground); border-bottom: 2px solid var(--color-brand); }
.sql-tab-strip button span, .result-tab-strip button span { margin-left: 6px; }
.result-tab-spinner {
  display: inline-block;
  width: 11px;
  height: 11px;
  border: 2px solid var(--color-panel-border);
  border-top-color: var(--color-brand);
  border-radius: 50%;
  vertical-align: -1px;
  animation: query-spin .8s linear infinite;
}
.result-expand { margin-left: auto; flex: 0 0 auto; }
.library-row { display: flex; align-items: center; border-bottom: 1px solid var(--color-panel-border); }
.library-row > button:first-child { flex: 1; min-width: 0; padding: 10px 4px; border: 0; background: transparent; color: var(--color-foreground); text-align: left; cursor: pointer; }
.library-row strong, .library-row small { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.library-row small { color: var(--color-text-muted); }
.editor-wrap {
  min-height: 180px;
  flex: 0 0 36%;
  overflow: hidden;
  border-radius: 14px;
  transform: none;
}
.result-wrap {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 260px;
  margin-top: 10px;
  overflow: hidden;
  border-radius: 14px;
  transform: none;
}
.result-wrap :deep(.data-table-wrapper) { min-height: 0; flex: 1; }
.results-expanded .sql-tab-strip, .results-expanded .editor-wrap { display: none; }
.query-progress {
  display: flex;
  flex: 1;
  min-height: 180px;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  padding: 24px;
  color: var(--color-foreground);
  text-align: center;
}
.query-progress-indicator {
  width: 48px;
  height: 48px;
  margin-bottom: 4px;
  border: 3px solid var(--color-panel-border);
  border-top-color: var(--color-brand);
  border-right-color: var(--color-brand);
  border-radius: 50%;
  animation: query-spin 1s linear infinite;
}
.query-progress strong { font-size: 14px; }
.query-progress-note { color: var(--color-text-muted); font-size: 12px; }
.query-progress-track {
  width: min(200px, 80%);
  height: 3px;
  margin-top: 4px;
  overflow: hidden;
  border-radius: 3px;
  background: var(--color-panel-border);
}
.query-progress-track::after {
  display: block;
  width: 40%;
  height: 100%;
  border-radius: inherit;
  background: var(--color-brand);
  content: '';
  animation: query-slide 1.6s ease-in-out infinite;
}
@keyframes query-spin { to { transform: rotate(360deg); } }
@keyframes query-slide {
  from { transform: translateX(-100%); }
  to { transform: translateX(250%); }
}
@media (prefers-reduced-motion: reduce) {
  .result-tab-spinner, .query-progress-indicator, .query-progress-track::after { animation: none; }
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
.table-tab-strip {
  display: flex;
  min-height: 44px;
  overflow-x: auto;
  border: 1px solid var(--color-panel-border);
  border-radius: 10px;
  background: var(--color-panel-header);
  scrollbar-width: thin;
}
.table-workspace-tab {
  display: grid;
  min-width: 180px;
  max-width: 360px;
  grid-template-columns: minmax(0, 1fr) 36px;
  align-items: stretch;
  border-right: 1px solid var(--color-panel-border);
  color: var(--color-text-muted);
}
.table-workspace-tab.selected {
  background: var(--color-active);
  color: var(--color-foreground);
  box-shadow: inset 0 -3px var(--color-brand);
}
.table-workspace-tab > button {
  min-height: 42px;
  border: 0;
  background: transparent;
  color: inherit;
  cursor: pointer;
}
.table-workspace-tab > button[role="tab"] {
  min-width: 0;
  padding: 0 8px 0 12px;
  text-align: left;
}
.table-workspace-tab > button:focus-visible {
  z-index: 1;
  outline: 2px solid var(--color-brand);
  outline-offset: -3px;
}
.table-tab-title {
  display: block;
  overflow: hidden;
  font: 11px var(--font-mono);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.table-tab-close { font-size: 18px; opacity: .62; }
.table-tab-close:hover { background: var(--color-hover); opacity: 1; }
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
.table-mode-bar { display: flex; gap: 8px; padding: 8px; border-bottom: 1px solid var(--color-panel-border); }

@media (max-width: 1100px) {
  .page { min-height: 0; }
  .page-title { flex-shrink: 0; }
  .toolbar { flex-wrap: wrap; align-items: flex-start; }
  .source-controls { flex-wrap: wrap; }
  .source-select { width: min(100%, 300px); }
  .execution-actions { padding-left: 0; border-left: 0; }
  .toolbar-secondary { margin-left: 0; }
  .query-workspace { flex-direction: column; overflow: auto; }
  .query-navigator { flex-basis: auto; max-height: 200px; }
  .query-center { min-height: 60vh; }
  .editor-wrap { flex-basis: 220px; }
  .result-wrap { flex-shrink: 0; height: 360px; }
  .table-dialog-shell { min-height: 70vh; }
  .table-locator { grid-template-columns: 1fr; }
  .locator-dot { display: none; }
}
</style>
