<template>
  <aside class="side-nav glass-surface">
    <div class="nav-header">
      <div class="nav-heading">
        <span class="section-kicker">Navigator</span>
        <div class="nav-title-row">
          <span class="nav-title">资源管理器</span>
          <span class="connection-count">{{ connectionStore.connections.length }}</span>
        </div>
      </div>
      <div class="nav-header-actions">
        <el-tooltip :content="t('sideNav.exportConnections')" placement="bottom">
          <el-button :icon="Upload" size="small" text @click="handleExport" />
        </el-tooltip>
        <el-tooltip :content="t('sideNav.importConnections')" placement="bottom">
          <el-button :icon="Download" size="small" text @click="triggerImport" />
        </el-tooltip>
        <el-tooltip :content="t('sideNav.newConnection')" placement="bottom">
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
  </aside>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { Upload, Download, Plus } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import NavTree from '@/components/nav/NavTree.vue'
import { useUiStore } from '@/stores/ui'
import { useConnectionStore } from '@/stores/connection'
import { saveBlob } from '@/utils/download'

const { t } = useI18n()
const uiStore = useUiStore()
const connectionStore = useConnectionStore()
const fileInputRef = ref<HTMLInputElement>()

function handleNewConnection() {
  uiStore.openConnectionDialog()
}

async function handleExport() {
  try {
    const data = await connectionStore.exportConnections()
    if (!data?.length) {
      ElMessage.warning(t('sideNav.noExportable'))
      return
    }
    const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' })
    await saveBlob(blob, `db_connections_${Date.now()}.json`)
    ElMessage.success(t('sideNav.exported', { count: data.length }))
  } catch (e: any) {
    ElMessage.error(t('sideNav.exportFailed', { msg: e.message || t('common.unknownError') }))
  }
}

function triggerImport() {
  fileInputRef.value?.click()
}

async function handleImport(event: Event) {
  const input = event.target as HTMLInputElement
  if (!input.files?.length) return

  try {
    const data = JSON.parse(await input.files[0].text())
    if (!Array.isArray(data)) {
      ElMessage.error('无效的配置文件格式')
      return
    }
    const result = await connectionStore.importConnections(data)
    if (result.failed > 0) {
      ElMessage.warning(`导入完成：成功 ${result.success} 个，失败 ${result.failed} 个`)
    } else {
      ElMessage.success(`导入成功：${result.success} 个连接`)
    }
  } catch (e: any) {
    ElMessage.error('导入失败：' + (e.message || '未知错误'))
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
  overflow: hidden;
  border-width: 0 1px 0 0;
  border-radius: 0;
  box-shadow: none;
}

.nav-header {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  min-height: 62px;
  padding: 10px 10px 9px 14px;
  border-bottom: 1px solid var(--color-panel-border);
}

.nav-heading {
  min-width: 0;
  flex: 1;
}

.nav-title-row {
  display: flex;
  align-items: center;
  gap: 7px;
  margin-top: 1px;
}

.nav-title {
  font-size: 13px;
  font-weight: 680;
}

.connection-count {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 20px;
  height: 18px;
  padding: 0 5px;
  border-radius: 6px;
  background: var(--color-muted);
  color: var(--color-text-muted);
  font-size: 10px;
  font-variant-numeric: tabular-nums;
}

.nav-header-actions {
  display: flex;
  align-items: center;
  gap: 1px;
}

.nav-tree-container {
  min-height: 0;
  flex: 1;
  overflow-y: auto;
}
</style>
