<template>
  <div class="side-nav">
    <div class="nav-header">
      <el-input
        v-model="searchText"
        placeholder="搜索..."
        :prefix-icon="Search"
        size="small"
        clearable
        class="nav-search-input"
      />
      <div class="nav-header-actions">
        <el-tooltip content="导出连接" placement="bottom">
          <el-button :icon="Upload" size="small" text @click="handleExport" />
        </el-tooltip>
        <el-tooltip content="导入连接" placement="bottom">
          <el-button :icon="Download" size="small" text @click="triggerImport" />
        </el-tooltip>
        <el-tooltip content="新建连接" placement="bottom">
          <el-button :icon="Plus" size="small" text @click="handleNewConnection" />
        </el-tooltip>
      </div>
      <input
        ref="fileInputRef"
        type="file"
        accept=".json"
        style="display: none"
        @change="handleImport"
      />
    </div>

    <div class="nav-tree-container">
      <NavTree @new-connection="handleNewConnection" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { Search, Upload, Download, Plus } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import NavTree from '@/components/nav/NavTree.vue'
import { useUiStore } from '@/stores/ui'
import { useConnectionStore } from '@/stores/connection'

const uiStore = useUiStore()
const connectionStore = useConnectionStore()
const searchText = ref('')
const fileInputRef = ref<HTMLInputElement>()

function handleNewConnection() {
  uiStore.openConnectionDialog()
}

/** 导出连接配置 */
async function handleExport() {
  try {
    const data = await connectionStore.exportConnections()
    if (!data || data.length === 0) {
      ElMessage.warning('没有可导出的连接')
      return
    }
    const json = JSON.stringify(data, null, 2)
    const blob = new Blob([json], { type: 'application/json' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `db_connections_${Date.now()}.json`
    a.click()
    URL.revokeObjectURL(url)
    ElMessage.success(`已导出 ${data.length} 个连接配置`)
  } catch (e: any) {
    ElMessage.error('导出失败: ' + (e.message || '未知错误'))
  }
}

/** 触发文件选择 */
function triggerImport() {
  fileInputRef.value?.click()
}

/** 导入连接配置 */
async function handleImport(event: Event) {
  const input = event.target as HTMLInputElement
  if (!input.files || input.files.length === 0) return

  const file = input.files[0]
  try {
    const text = await file.text()
    const data = JSON.parse(text)
    if (!Array.isArray(data)) {
      ElMessage.error('无效的配置文件格式')
      return
    }
    const result = await connectionStore.importConnections(data)
    if (result.failed > 0) {
      ElMessage.warning(`导入完成: 成功 ${result.success} 个, 失败 ${result.failed} 个`)
    } else {
      ElMessage.success(`导入成功: ${result.success} 个连接`)
    }
  } catch (e: any) {
    ElMessage.error('导入失败: ' + (e.message || '未知错误'))
  } finally {
    input.value = ''
  }
}
</script>

<style scoped>
.side-nav {
  display: flex;
  flex-direction: column;
  width: var(--sidenav-width);
  min-width: var(--sidenav-min-width);
  max-width: var(--sidenav-max-width);
  background: var(--color-panel);
  border-right: 1px solid var(--color-border);
  overflow: hidden;
}

.nav-header {
  display: flex;
  align-items: center;
  gap: var(--space-1);
  padding: var(--space-2);
  border-bottom: 1px solid var(--color-border);
  flex-wrap: wrap;
}

.nav-search-input {
  flex: 1;
  min-width: 100px;
}

.nav-header-actions {
  display: flex;
  align-items: center;
  gap: 2px;
}

.nav-tree-container {
  flex: 1;
  overflow-y: auto;
}
</style>
