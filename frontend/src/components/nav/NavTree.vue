<template>
  <div class="nav-tree" @contextmenu.prevent>
    <!-- 搜索框 -->
    <div class="nav-search-bar">
      <el-input
        v-model="searchKeyword"
        size="small"
        placeholder="搜索表名/视图名..."
        :prefix-icon="Search"
        clearable
        @input="handleSearchInput"
        @clear="clearSearch"
      />
    </div>

    <!-- 搜索结果面板 -->
    <div v-if="searchActive" class="search-results-panel">
      <div v-if="searchLoading" class="search-loading">
        <el-icon class="is-loading"><Loading /></el-icon>
        <span>搜索中...</span>
      </div>
      <div v-else-if="searchResults.length === 0" class="search-empty">
        <span>未找到匹配的节点</span>
      </div>
      <div v-else class="search-result-list">
        <div
          v-for="node in searchResults"
          :key="node.id"
          class="search-result-item"
          @click="handleSearchResultClick(node)"
        >
          <span class="node-icon" :class="getIconClass(node)">{{ getIconText(node) }}</span>
          <span class="node-label">{{ node.name }}</span>
          <span class="search-result-path">{{ node.path }}</span>
        </div>
      </div>
    </div>

    <!-- 已连接的连接列表 -->
    <div v-show="!searchActive" class="tree-connections">
      <el-tree
        ref="treeRef"
        :data="rootNodes"
        :props="treeProps"
        node-key="id"
        :load="loadNode"
        lazy
        :expand-on-click-node="false"
        :highlight-current="true"
        @node-click="handleNodeClick"
        @node-dblclick="handleNodeDblClick"
        @node-contextmenu="handleContextMenu"
      >
        <template #default="{ node, data }">
          <div
            class="tree-node"
            :draggable="isDraggable(data)"
            @dragstart="handleDragStart($event, data, node)"
            @contextmenu.stop.prevent="handleContextMenu($event, data, node)"
          >
            <!-- 连接节点 -->
            <template v-if="data.treeNodeType === 'connection'">
              <span class="node-icon connection-dot"
                :class="data.status === 'CONNECTED' ? 'connected' : 'disconnected'"
              ></span>
              <el-icon v-if="data.favorite" class="favorite-star"><Star /></el-icon>
              <span class="node-label">{{ data.name }}</span>
              <span class="node-type-badge" :class="`db-text-${data.dbType}`">{{ data.displayName }}</span>
            </template>
            <!-- 树节点 -->
            <template v-else>
              <span class="node-icon" :class="getIconClass(data)">
                {{ getIconText(data) }}
              </span>
              <span class="node-label">{{ data.name }}</span>
            </template>
          </div>
        </template>
      </el-tree>
    </div>

    <!-- 未连接的连接列表 -->
    <div class="disconnected-section" v-if="disconnectedConnections.length > 0">
      <div class="section-divider">
        <span>未连接</span>
      </div>
      <div
        v-for="conn in disconnectedConnections"
        :key="conn.id"
        class="disconnected-item"
        @click="handleConnect(conn)"
      >
        <span class="node-icon connection-dot disconnected"></span>
        <el-icon v-if="conn.favorite" class="favorite-star"><Star /></el-icon>
        <span class="node-label">{{ conn.name }}</span>
        <el-button
          size="small"
          type="primary"
          link
          @click.stop="handleConnect(conn)"
        >
          连接
        </el-button>
      </div>
    </div>

    <!-- 空状态 -->
    <div v-if="connectionStore.connections.length === 0" class="nav-empty">
      <el-empty description="还没有连接" :image-size="60">
        <el-button type="primary" size="small" @click="$emit('newConnection')">
          新建连接
        </el-button>
      </el-empty>
    </div>

    <!-- 右键菜单 -->
    <ul
      v-if="contextMenu.visible"
      class="context-menu"
      :style="{ left: contextMenu.x + 'px', top: contextMenu.y + 'px' }"
    >
      <li
        v-for="item in contextMenuItems"
        :key="item.action"
        @click="handleContextAction(item.action)"
        :class="{ disabled: item.disabled }"
      >
        <el-icon v-if="item.icon"><component :is="item.icon" /></el-icon>
        <span>{{ item.label }}</span>
      </li>
    </ul>

    <!-- 导入数据对话框 -->
    <ImportDialog
      v-model:visible="importVisible"
      :connection-id="importTarget.connectionId"
      :schema="importTarget.schema"
      :table="importTarget.table"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, reactive, watch } from 'vue'
import {
  Document, View, Refresh, CopyDocument, DataLine, Delete, Link,
  Search, Loading, Star, EditPen, Upload,
} from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import ImportDialog from '@/components/editor/ImportDialog.vue'
import type Node from 'element-plus/es/components/tree/src/model/node'
import { useConnectionStore } from '@/stores/connection'
import { useUiStore } from '@/stores/ui'
import { useEditorStore } from '@/stores/editor'
import { metadataApi } from '@/api/metadata'
import type { TreeNode } from '@/types/metadata'
import type { ConnectionDTO } from '@/types/connection'

const emit = defineEmits<{ newConnection: [], nodeSelect: [node: TreeNode] }>()

const connectionStore = useConnectionStore()
const uiStore = useUiStore()
const editorStore = useEditorStore()

// === 搜索状态 ===
const searchKeyword = ref('')
const searchActive = ref(false)
const searchLoading = ref(false)
const searchResults = ref<TreeNode[]>([])
let searchTimer: ReturnType<typeof setTimeout> | null = null

function handleSearchInput() {
  const kw = searchKeyword.value.trim()
  if (!kw) {
    clearSearch()
    return
  }
  searchActive.value = true
  if (searchTimer) clearTimeout(searchTimer)
  searchTimer = setTimeout(() => doSearch(kw), 300)
}

async function doSearch(keyword: string) {
  const connId = connectionStore.activeConnectionId
  if (!connId) {
    searchResults.value = []
    return
  }
  searchLoading.value = true
  try {
    searchResults.value = await metadataApi.searchNodes(connId, keyword)
  } catch (e: any) {
    console.error('搜索失败:', e)
    searchResults.value = []
  } finally {
    searchLoading.value = false
  }
}

function clearSearch() {
  searchActive.value = false
  searchResults.value = []
  searchKeyword.value = ''
  if (searchTimer) clearTimeout(searchTimer)
}

function handleSearchResultClick(node: TreeNode) {
  uiStore.setSelectedNode(node)
  const type = node.type
  if (type === 'TABLE' || type === 'VIEW' || type === 'COLLECTION') {
    const pathSegments = (node.path || '').split('/').filter((p: string) => p)
    if (pathSegments.length > 0) {
      const dbFromPath = pathSegments[0]
      if (uiStore.databases.includes(dbFromPath)) {
        uiStore.setCurrentDatabase(dbFromPath)
      }
    }
    emit('nodeSelect', node)
  }
  clearSearch()
}

// === 拖拽支持 ===
function isDraggable(data: any): boolean {
  if (data.treeNodeType === 'connection') return false
  const type = data.type as string
  return type === 'TABLE' || type === 'VIEW' || type === 'COLLECTION'
}

function handleDragStart(e: DragEvent, data: any, node: Node) {
  if (!isDraggable(data)) return
  const tableName = data.name
  const schema = data.path?.split('/').find((p: string) => p) || null
  const dragData = {
    name: tableName,
    schema,
    path: data.path,
    connectionId: getConnectionId(node),
  }
  e.dataTransfer?.setData('application/json', JSON.stringify(dragData))
  e.dataTransfer!.effectAllowed = 'copy'
}

// === 树配置 ===
const treeRef = ref()
const treeProps = {
  label: 'name',
  children: 'children',
  isLeaf: (data: any) => !data.hasChildren,
}

// === 根节点 ===
interface RootNode extends ConnectionDTO {
  treeNodeType: 'connection'
  isLeaf: boolean
  hasChildren: boolean
}

const rootNodes = computed<RootNode[]>(() => {
  return connectionStore.connections
    .filter(c => c.status === 'CONNECTED')
    .map(c => ({
      ...c,
      treeNodeType: 'connection' as const,
      isLeaf: false,
      hasChildren: true,
    }))
})

const disconnectedConnections = computed(() =>
  connectionStore.connections.filter(c => c.status !== 'CONNECTED')
)

// === 懒加载节点 ===
async function loadNode(node: Node, resolve: (data: TreeNode[]) => void) {
  // 根节点已由 rootNodes 提供
  if (node.level === 0) {
    resolve([])
    return
  }

  const data = node.data as any
  const connectionId = getConnectionId(node)
  if (!connectionId) {
    resolve([])
    return
  }

  try {
    const path = data.treeNodeType === 'connection' ? undefined : data.path
    const nodes = await metadataApi.getTreeNodes(connectionId, path)
    resolve(nodes)
  } catch (e: any) {
    ElMessage.error(`加载失败: ${e.message}`)
    resolve([])
  }
}

/** 递归获取连接ID */
function getConnectionId(node: Node): string | null {
  let current: Node | null = node
  while (current) {
    const data = current.data as any
    if (data.treeNodeType === 'connection' || data.id?.startsWith('conn-')) {
      return data.id?.replace('conn-', '') || data.id
    }
    current = current.parent
  }
  return null
}

// === 节点点击 ===
function handleNodeClick(data: any, node: Node) {
  if (data.treeNodeType === 'connection') return

  // 设置选中节点
  uiStore.setSelectedNode(data as TreeNode)

  const type = data.type as string

  // 点击 DATABASE 节点时切换当前数据库
  if (type === 'DATABASE') {
    uiStore.setCurrentDatabase(data.name)
  }

  // 表/视图节点：加载列信息和DDL，并自动切换到表所属数据库
  if (type === 'TABLE' || type === 'VIEW' || type === 'COLLECTION') {
    // 从路径中提取数据库名（第一段）
    const pathSegments = (data.path || '').split('/').filter((p: string) => p)
    if (pathSegments.length > 0) {
      const dbFromPath = pathSegments[0]
      // 仅当该数据库在列表中时才切换
      if (uiStore.databases.includes(dbFromPath)) {
        uiStore.setCurrentDatabase(dbFromPath)
      }
    }

    loadColumns(node)
    if (type === 'TABLE' || type === 'VIEW') {
      loadDdl(node)
    }
  }
}

// === 节点双击 ===
function handleNodeDblClick(data: any, node: Node) {
  if (data.treeNodeType === 'connection') return
  const type = data.type as string
  if (type === 'TABLE' || type === 'VIEW') {
    const connId = getConnectionId(node)
    if (!connId) return
    const schema = data.path?.split('/').find((p: string) => p) || null
    editorStore.createTableDetailTab(connId, data.name, schema)
  }
}

async function loadColumns(node: Node) {
  const connectionId = getConnectionId(node)
  if (!connectionId) return
  const data = node.data as TreeNode

  uiStore.columnsLoading = true
  try {
    const schema = data.path?.split('/').find((p: string) => p) || null
    const tableName = data.name
    const cols = await metadataApi.getTableColumns(connectionId, schema, tableName)
    uiStore.setColumns(cols)
  } catch (e: any) {
    console.error('加载列信息失败:', e)
    uiStore.setColumns([])
  } finally {
    uiStore.columnsLoading = false
  }
}

async function loadDdl(node: Node) {
  const connectionId = getConnectionId(node)
  if (!connectionId) return
  const data = node.data as TreeNode

  uiStore.ddlLoading = true
  try {
    const schema = data.path?.split('/').find((p: string) => p) || null
    const tableName = data.name
    const ddlText = await metadataApi.getTableDDL(connectionId, schema, tableName)
    uiStore.setDdl(ddlText)
  } catch (e: any) {
    console.error('加载DDL失败:', e)
    uiStore.setDdl('')
  } finally {
    uiStore.ddlLoading = false
  }
}

// === 连接操作 ===
async function handleConnect(conn: ConnectionDTO) {
  const success = await connectionStore.connect(conn.id)
  if (success) {
    ElMessage.success(`已连接: ${conn.name}`)
  } else {
    ElMessage.error('连接失败')
  }
}

// === 图标 ===
function getIconClass(data: TreeNode): string {
  const type = data.type
  switch (type) {
    case 'DATABASE': return 'icon-db'
    case 'SCHEMA': return 'icon-schema'
    case 'TABLE': return 'icon-table'
    case 'VIEW': return 'icon-view'
    case 'COLLECTION': return 'icon-collection'
    case 'PARTITION': return 'icon-partition'
    case 'KEY_GROUP': return 'icon-key-group'
    case 'KEY': return 'icon-key'
    case 'INDEX_GROUP': return 'icon-index-group'
    case 'INDEX': return 'icon-index'
    default: return 'icon-info'
  }
}

function getIconText(data: TreeNode): string {
  const type = data.type
  switch (type) {
    case 'DATABASE': return 'DB'
    case 'SCHEMA': return 'SC'
    case 'TABLE': return 'T'
    case 'VIEW': return 'V'
    case 'COLLECTION': return 'C'
    case 'PARTITION': return 'P'
    case 'KEY_GROUP': return 'KG'
    case 'KEY': return 'K'
    case 'INDEX_GROUP': return 'IX'
    case 'INDEX': return 'I'
    default: return 'I'
  }
}

// === 右键菜单 ===
const contextMenu = reactive({
  visible: false,
  x: 0,
  y: 0,
  node: null as TreeNode | null,
  connectionId: '' as string,
})

interface ContextMenuItem {
  label: string
  action: string
  icon: any
  disabled?: boolean
}

// === 导入对话框 ===
const importVisible = ref(false)
const importTarget = reactive({
  connectionId: '',
  schema: null as string | null,
  table: '',
})

const contextMenuItems = computed<ContextMenuItem[]>(() => {
  const node = contextMenu.node
  if (!node) return []

  const items: ContextMenuItem[] = []
  const type = node.type
  const isConnection = (node as any).treeNodeType === 'connection'

  // Connection-level context menu
  if (isConnection) {
    const conn = connectionStore.connections.find(c => c.id === contextMenu.connectionId)
    if (conn) {
      items.push({ label: conn.favorite ? '取消收藏' : '收藏', action: 'toggle-favorite', icon: Star })
      items.push({ label: '编辑连接', action: 'edit-connection', icon: EditPen })
      items.push({ label: '复制配置', action: 'duplicate-connection', icon: CopyDocument })
      items.push({ label: '删除连接', action: 'delete-connection', icon: Delete })
    }
    return items
  }

  if (type === 'TABLE' || type === 'VIEW') {
    items.push({ label: '查看表详情', action: 'view-detail', icon: View })
    items.push({ label: '查看数据', action: 'view-data', icon: DataLine })
    items.push({ label: '查看DDL', action: 'view-ddl', icon: Document })
    items.push({ label: '复制表名', action: 'copy-name', icon: CopyDocument })
    items.push({ label: '生成SELECT', action: 'gen-select', icon: Document })
    if (type === 'TABLE') {
      items.push({ label: '导入数据', action: 'import-data', icon: Upload })
    }
  }

  if (type === 'COLLECTION') {
    items.push({ label: '查看集合数据', action: 'view-data', icon: DataLine })
    items.push({ label: '复制名称', action: 'copy-name', icon: CopyDocument })
  }

  if (contextMenu.connectionId) {
    items.push({ label: '刷新', action: 'refresh', icon: Refresh })
  }

  return items
})

function handleContextMenu(e: MouseEvent, data: any, node?: Node) {
  // 查找连接ID
  let connectionId = ''
  if (data.treeNodeType === 'connection') {
    connectionId = data.id?.replace('conn-', '') || data.id
  } else if (node) {
    connectionId = getConnectionId(node) || ''
  }

  contextMenu.node = data as TreeNode
  contextMenu.connectionId = connectionId
  contextMenu.x = e.clientX
  contextMenu.y = e.clientY
  contextMenu.visible = true
}

function closeContextMenu() {
  contextMenu.visible = false
}

async function handleContextAction(action: string) {
  const node = contextMenu.node
  const connId = contextMenu.connectionId
  closeContextMenu()

  if (!node || !connId) return

  switch (action) {
    case 'view-detail': {
      const tableName = node.name
      const schema = node.path?.split('/').find((p: string) => p) || null
      editorStore.createTableDetailTab(connId, tableName, schema)
      break
    }
    case 'view-data': {
      const tableName = node.name
      const schema = node.path?.split('/').find((p: string) => p) || null
      const sql = `SELECT * FROM ${schema ? `${schema}.` : ''}${tableName} LIMIT 100`
      const tabId = editorStore.createTab(connId, `查询: ${tableName}`)
      editorStore.updateSql(tabId, sql)
      break
    }
    case 'import-data': {
      importTarget.connectionId = connId
      importTarget.schema = node.path?.split('/').find((p: string) => p) || null
      importTarget.table = node.name
      importVisible.value = true
      break
    }
    case 'copy-name':
      try {
        await navigator.clipboard.writeText(node.name)
        ElMessage.success('已复制')
      } catch {
        ElMessage.error('复制失败')
      }
      break
    case 'gen-select': {
      const tableName = node.name
      const schema = node.path?.split('/').find((p: string) => p) || null
      const sql = `SELECT *\nFROM ${schema ? `${schema}.` : ''}${tableName}\nLIMIT 100;`
      const tabId = editorStore.createTab(connId, `SELECT: ${tableName}`)
      editorStore.updateSql(tabId, sql)
      break
    }
    case 'view-ddl': {
      const tableName = node.name
      const schema = node.path?.split('/').find((p: string) => p) || null
      editorStore.createTableDetailTab(connId, tableName, schema)
      break
    }
    case 'refresh':
      // 刷新当前节点的子节点
      if (treeRef.value) {
        const treeNode = treeRef.value.getNode(node.id)
        if (treeNode) {
          treeNode.loaded = false
          treeNode.expand()
        }
      }
      break
    case 'toggle-favorite':
      await connectionStore.toggleFavorite(connId)
      break
    case 'edit-connection': {
      const conn = connectionStore.connections.find(c => c.id === connId) || null
      uiStore.openConnectionDialog(conn)
      break
    }
    case 'duplicate-connection':
      await connectionStore.duplicateConnection(connId)
      ElMessage.success('已复制连接配置')
      break
    case 'delete-connection':
      try {
        await ElMessageBox.confirm('确定删除此连接配置吗？', '确认', { type: 'warning' })
        await connectionStore.deleteConnection(connId)
        ElMessage.success('已删除连接')
      } catch { /* cancelled */ }
      break
  }
}

// === 数据库列表自动加载 ===
watch(() => connectionStore.activeConnectionId, async (newId) => {
  if (newId) {
    await uiStore.loadDatabases(newId)
  } else {
    uiStore.clearDatabases()
  }
})

// === 生命周期 ===
onMounted(() => {
  document.addEventListener('click', closeContextMenu)
  // 如果已有活跃连接，加载数据库列表
  if (connectionStore.activeConnectionId) {
    uiStore.loadDatabases(connectionStore.activeConnectionId)
  }
})

onUnmounted(() => {
  document.removeEventListener('click', closeContextMenu)
})
</script>

<style scoped>
.nav-tree {
  height: 100%;
  overflow-y: auto;
  user-select: none;
  position: relative;
}

/* 搜索栏 */
.nav-search-bar {
  padding: var(--space-2) var(--space-3);
  border-bottom: 1px solid var(--color-border);
  flex-shrink: 0;
}

/* 搜索结果面板 */
.search-results-panel {
  max-height: 400px;
  overflow-y: auto;
}

.search-loading {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-3);
  color: var(--color-text-muted);
  font-size: var(--text-caption);
}

.search-empty {
  padding: var(--space-3);
  text-align: center;
  color: var(--color-text-muted);
  font-size: var(--text-caption);
}

.search-result-list {
  padding: var(--space-1) 0;
}

.search-result-item {
  display: flex;
  align-items: center;
  gap: var(--space-1);
  padding: var(--space-1) var(--space-3);
  cursor: pointer;
  transition: background var(--transition-fast);
}

.search-result-item:hover {
  background: var(--color-hover);
}

.search-result-path {
  margin-left: auto;
  font-size: 10px;
  color: var(--color-text-muted);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 120px;
}

.tree-connections {
  padding: var(--space-1) 0;
}

.tree-node {
  display: flex;
  align-items: center;
  gap: var(--space-1);
  padding: 2px 0;
  flex: 1;
  overflow: hidden;
}

.node-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  font-size: 10px;
  font-weight: 700;
  border-radius: var(--radius-sm);
  flex-shrink: 0;
}

.connection-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--color-disconnected);
}

.connection-dot.connected {
  background: var(--color-connected);
  box-shadow: 0 0 4px var(--color-connected);
}

.connection-dot.disconnected {
  background: var(--color-disconnected);
}

.favorite-star {
  color: #F59E0B;
  font-size: 12px;
  flex-shrink: 0;
}

.node-label {
  font-size: var(--text-label);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.node-type-badge {
  font-size: 10px;
  color: var(--color-text-muted);
  margin-left: auto;
  padding-right: var(--space-2);
}

/* 节点图标颜色 */
.icon-db { background: var(--color-active); color: var(--color-secondary); }
.icon-schema { background: var(--color-muted); color: var(--color-foreground); }
.icon-table { background: #DBEAFE; color: #2563EB; }
.icon-view { background: #D1FAE5; color: #059669; }
.icon-collection { background: #FED7AA; color: #C2410C; }
.icon-partition { background: #E9D5FF; color: #7C3AED; }
.icon-key-group { background: #FCE7F3; color: #DB2777; }
.icon-key { background: #FEE2E2; color: #DC2626; }
.icon-index-group { background: #E0E7FF; color: #4338CA; }
.icon-index { background: #C7D2FE; color: #3730A3; }
.icon-info { background: var(--color-muted); color: var(--color-muted); }

/* 数据库文字颜色 */
.db-text-mysql { color: var(--db-mysql); }
.db-text-postgresql { color: var(--db-postgresql); }
.db-text-oracle { color: var(--db-oracle); }
.db-text-mssql { color: var(--db-mssql); }
.db-text-sqlite { color: var(--db-sqlite); }
.db-text-maxcompute { color: var(--db-maxcompute); }
.db-text-clickhouse { color: var(--db-clickhouse); }
.db-text-mongodb { color: var(--db-mongodb); }
.db-text-redis { color: var(--db-redis); }

.disconnected-section {
  margin-top: var(--space-2);
}

.section-divider {
  padding: var(--space-2) var(--space-3);
  font-size: var(--text-caption);
  color: var(--color-text-muted);
  border-top: 1px solid var(--color-border);
}

.disconnected-item {
  display: flex;
  align-items: center;
  gap: var(--space-1);
  padding: var(--space-1) var(--space-3);
  cursor: pointer;
  transition: background var(--transition-fast);
}

.disconnected-item:hover {
  background: var(--color-hover);
}

.nav-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
}

/* 右键菜单 */
.context-menu {
  position: fixed;
  z-index: 9999;
  background: var(--color-panel);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  box-shadow: var(--shadow-lg);
  padding: var(--space-1) 0;
  min-width: 160px;
  list-style: none;
  margin: 0;
}

.context-menu li {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-3);
  cursor: pointer;
  font-size: var(--text-label);
  transition: background var(--transition-fast);
}

.context-menu li:hover {
  background: var(--color-hover);
}

.context-menu li.disabled {
  color: var(--color-muted);
  cursor: not-allowed;
}

.context-menu li.disabled:hover {
  background: transparent;
}

:deep(.el-tree) {
  background: transparent;
}

:deep(.el-tree-node__content) {
  height: 30px;
}
</style>
