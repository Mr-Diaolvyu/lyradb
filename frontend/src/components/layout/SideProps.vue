<template>
  <div class="side-props">
    <!-- 面板头部 -->
    <div class="props-header">
      <span class="props-title">属性</span>
      <el-tooltip content="关闭面板" placement="top">
        <el-button :icon="Close" size="small" text @click="uiStore.toggleSideProps()" />
      </el-tooltip>
    </div>

    <!-- 面板内容 -->
    <div class="props-content">
      <template v-if="uiStore.selectedNode">
        <!-- 节点信息 -->
        <div class="props-section">
          <div class="section-title">基本信息</div>
          <div class="prop-row">
            <span class="prop-label">名称</span>
            <span class="prop-value">{{ uiStore.selectedNode.name }}</span>
          </div>
          <div class="prop-row">
            <span class="prop-label">类型</span>
            <span class="prop-value">{{ nodeTypeLabel }}</span>
          </div>
          <div class="prop-row">
            <span class="prop-label">路径</span>
            <span class="prop-value mono">{{ uiStore.selectedNode.path }}</span>
          </div>
        </div>

        <!-- 表结构（列信息） -->
        <div class="props-section" v-if="showColumns">
          <div class="section-title">
            <span>列信息</span>
            <el-tag size="small" type="info">{{ uiStore.columns.length }}</el-tag>
          </div>
          <div class="columns-list" v-loading="uiStore.columnsLoading">
            <div
              v-for="col in uiStore.columns"
              :key="col.name"
              class="column-item"
            >
              <div class="column-header">
                <span class="column-name" :class="{ pk: col.primaryKey }">
                  {{ col.name }}
                </span>
                <el-tag size="small" :type="col.nullable ? 'info' : 'warning'">
                  {{ col.nullable ? 'NULL' : 'NOT NULL' }}
                </el-tag>
              </div>
              <div class="column-meta">
                <span class="column-type">{{ col.typeName }}</span>
                <span v-if="col.columnSize" class="column-size">({{ col.columnSize }})</span>
                <el-tag v-if="col.primaryKey" size="small" type="danger" effect="dark">PK</el-tag>
                <el-tag v-if="col.autoIncrement" size="small" type="success">AI</el-tag>
              </div>
              <div class="column-remarks" v-if="col.remarks">
                {{ col.remarks }}
              </div>
            </div>
            <el-empty v-if="uiStore.columns.length === 0 && !uiStore.columnsLoading" description="无列信息" :image-size="40" />
          </div>
        </div>

        <!-- DDL 预览 -->
        <div class="props-section" v-if="showDdl">
          <div class="section-title">
            <span>DDL</span>
            <el-button
              :icon="CopyDocument"
              size="small"
              text
              @click="copyDdl"
            />
          </div>
          <div class="ddl-content" v-loading="uiStore.ddlLoading">
            <pre v-if="uiStore.ddl">{{ uiStore.ddl }}</pre>
            <el-empty v-else description="无DDL" :image-size="40" />
          </div>
        </div>
      </template>

      <!-- 空状态 -->
      <div v-else class="props-empty">
        <el-empty description="在导航树中选择一个对象查看属性" :image-size="60" />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { Close, CopyDocument } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { useUiStore } from '@/stores/ui'

const uiStore = useUiStore()

const nodeTypeLabel = computed(() => {
  if (!uiStore.selectedNode) return ''
  const labels: Record<string, string> = {
    'CONNECTION': '连接',
    'DATABASE': '数据库',
    'SCHEMA': '模式',
    'TABLE': '表',
    'VIEW': '视图',
    'COLLECTION': '集合',
    'PARTITION': '分区',
    'KEY_GROUP': '键组',
    'KEY': '键',
    'INFO': '信息',
  }
  return labels[uiStore.selectedNode.type] || uiStore.selectedNode.type
})

const showColumns = computed(() => {
  const t = uiStore.selectedNode?.type
  return t === 'TABLE' || t === 'VIEW' || t === 'COLLECTION'
})

const showDdl = computed(() => {
  const t = uiStore.selectedNode?.type
  return t === 'TABLE' || t === 'VIEW'
})

async function copyDdl() {
  if (!uiStore.ddl) return
  try {
    await navigator.clipboard.writeText(uiStore.ddl)
    ElMessage.success('DDL已复制')
  } catch {
    ElMessage.error('复制失败')
  }
}
</script>

<style scoped>
.side-props {
  display: flex;
  flex-direction: column;
  width: var(--sideprops-width);
  min-width: var(--sideprops-min-width);
  background: var(--color-panel);
  border-left: 1px solid var(--color-border);
  overflow: hidden;
}

.props-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: var(--tab-height);
  padding: 0 var(--space-3);
  background: var(--color-panel-header);
  border-bottom: 1px solid var(--color-border);
  flex-shrink: 0;
}

.props-title {
  font-size: var(--text-label);
  font-weight: 600;
}

.props-content {
  flex: 1;
  overflow-y: auto;
}

.props-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
}

.props-section {
  border-bottom: 1px solid var(--color-border);
  padding: var(--space-3);
}

.section-title {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  font-size: var(--text-label);
  font-weight: 600;
  margin-bottom: var(--space-2);
  color: var(--color-secondary);
}

.prop-row {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  padding: var(--space-1) 0;
  font-size: var(--text-caption);
}

.prop-label {
  color: var(--color-text-muted);
  flex-shrink: 0;
  width: 50px;
  font-weight: 500;
}

.prop-value {
  color: var(--color-foreground);
  text-align: right;
  word-break: break-all;
  flex: 1;
}

.prop-value.mono {
  font-family: var(--font-mono);
  font-size: var(--text-code);
}

.columns-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}

.column-item {
  padding: var(--space-2);
  background: var(--color-background);
  border-radius: var(--radius-sm);
  border: 1px solid var(--color-border);
}

.column-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.column-name {
  font-weight: 500;
  font-size: var(--text-label);
}

.column-name.pk {
  color: var(--color-destructive);
  font-weight: 700;
}

.column-meta {
  display: flex;
  align-items: center;
  gap: var(--space-1);
  margin-top: 2px;
  font-size: var(--text-caption);
  color: var(--color-text-muted);
}

.column-type {
  font-family: var(--font-mono);
  color: var(--color-secondary);
  font-weight: 500;
}

.column-size {
  color: var(--color-text-muted);
}

.column-remarks {
  margin-top: var(--space-1);
  font-size: var(--text-caption);
  color: var(--color-text-muted);
  font-style: italic;
}

.ddl-content {
  max-height: 300px;
  overflow-y: auto;
  background: var(--color-background);
  border-radius: var(--radius-sm);
  border: 1px solid var(--color-border);
}

.ddl-content pre {
  margin: 0;
  padding: var(--space-2);
  font-family: var(--font-mono);
  font-size: var(--text-code);
  white-space: pre-wrap;
  word-break: break-word;
  color: var(--color-foreground);
}
</style>
