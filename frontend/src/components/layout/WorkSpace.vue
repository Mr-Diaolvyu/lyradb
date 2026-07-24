<template>
  <div class="work-space">
    <!-- Tab栏 -->
    <div class="tab-bar" v-if="editorStore.tabs.length > 0">
      <div class="tab-tabs">
        <div
          v-for="tab in editorStore.tabs"
          :key="tab.id"
          class="tab-item"
          :class="{ active: tab.id === editorStore.activeTabId }"
          @click="editorStore.setActiveTab(tab.id)"
        >
          <span class="tab-icon" :class="tab.type === 'table-detail' ? 'tab-icon-table' : 'tab-icon-sql'">
            {{ tab.type === 'table-detail' ? 'T' : 'S' }}
          </span>
          <span class="tab-title">{{ tab.title }}</span>
          <el-icon class="tab-close" @click.stop="editorStore.closeTab(tab.id)">
            <Close />
          </el-icon>
        </div>
      </div>
    </div>

    <!-- 内容区：根据 Tab 类型渲染 -->
    <template v-if="editorStore.activeTab">
      <!-- SQL 编辑器 Tab -->
      <div
        class="editor-area"
        v-if="editorStore.activeTab.type === 'sql'"
        @dragover.prevent
        @drop="handleDrop"
      >
        <SqlEditor
          :model-value="(editorStore.activeTab as SqlTab).sql"
          :db-type="activeDbType"
          :connection-id="(editorStore.activeTab as SqlTab).connectionId"
          @update:model-value="(val: string) => editorStore.updateSql(editorStore.activeTabId!, val)"
          @execute="executeSql"
          @explain="explainSql"
        />
      </div>

      <!-- 表详情 Tab -->
      <div class="table-detail-area" v-else-if="editorStore.activeTab.type === 'table-detail'">
        <TableDetailTabView :tab="editorStore.activeTab as TableDetailTab" />
      </div>
    </template>

    <!-- 底部结果面板（仅 SQL Tab 时显示） -->
    <BottomPanel v-if="editorStore.activeSqlTab" />

    <!-- 空状态 -->
    <div class="empty-state" v-if="editorStore.tabs.length === 0">
      <el-empty description="点击「新建查询」开始编写SQL，或双击导航树中的表查看详情" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { Close } from '@element-plus/icons-vue'
import { computed } from 'vue'
import { useEditorStore, type SqlTab, type TableDetailTab } from '@/stores/editor'
import { useConnectionStore } from '@/stores/connection'
import SqlEditor from '@/components/editor/SqlEditor.vue'
import TableDetailTabView from '@/components/editor/TableDetailTab.vue'
import BottomPanel from '@/components/layout/BottomPanel.vue'

const editorStore = useEditorStore()
const connectionStore = useConnectionStore()

/** 获取当前活动 SQL Tab 的连接类型 */
const activeDbType = computed(() => {
  const tab = editorStore.activeTab
  if (tab && tab.type === 'sql') {
    const conn = connectionStore.connections.find(c => c.id === tab.connectionId)
    return conn?.dbType
  }
  return undefined
})

function executeSql() {
  if (editorStore.activeTabId) {
    editorStore.executeSql(editorStore.activeTabId)
  }
}

function explainSql() {
  if (editorStore.activeTabId) {
    editorStore.explainSql(editorStore.activeTabId)
  }
}

function handleDrop(e: DragEvent) {
  const json = e.dataTransfer?.getData('application/json')
  if (!json) return
  try {
    const data = JSON.parse(json)
    const tableName = data.name as string
    const schema = data.schema as string | null
    const sql = `SELECT *\nFROM ${schema ? `${schema}.` : ''}${tableName}\nLIMIT 100;`
    if (editorStore.activeTabId) {
      editorStore.updateSql(editorStore.activeTabId, sql)
    }
  } catch {
    // ignore parse errors
  }
}
</script>

<style scoped>
.work-space {
  display: flex;
  flex-direction: column;
  flex: 1;
  overflow: hidden;
  background: var(--color-background);
}

.tab-bar {
  display: flex;
  height: var(--tab-height);
  background: var(--color-panel-header);
  border-bottom: 1px solid var(--color-border);
  flex-shrink: 0;
}

.tab-tabs {
  display: flex;
  align-items: center;
  height: 100%;
  overflow-x: auto;
}

.tab-item {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  height: 100%;
  padding: 0 var(--space-3);
  cursor: pointer;
  border-right: 1px solid var(--color-border);
  transition: background var(--transition-fast);
  white-space: nowrap;
}

.tab-item:hover {
  background: var(--color-hover);
}

.tab-item.active {
  background: var(--color-panel);
  border-bottom: 2px solid var(--color-secondary);
}

.tab-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 18px;
  height: 18px;
  font-size: 10px;
  font-weight: 700;
  border-radius: 3px;
  flex-shrink: 0;
}

.tab-icon-sql {
  background: #DBEAFE;
  color: #2563EB;
}

.tab-icon-table {
  background: #D1FAE5;
  color: #059669;
}

.tab-title {
  font-size: var(--text-label);
}

.tab-close {
  cursor: pointer;
  font-size: 12px;
  color: var(--color-text-muted);
}

.tab-close:hover {
  color: var(--color-destructive);
}

.editor-area {
  flex: 1;
  overflow: hidden;
  min-height: 200px;
}

.table-detail-area {
  flex: 1;
  overflow: hidden;
}

.empty-state {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
}
</style>
