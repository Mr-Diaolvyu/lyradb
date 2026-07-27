<template>
  <div class="app-header">
    <div class="header-left">
      <span class="logo">LyraDB</span>
    </div>

    <div class="header-center">
      <el-button-group>
        <el-button :icon="Connection" size="small" @click="uiStore.openConnectionDialog()">
          {{ t('header.newConnection') }}
        </el-button>
        <el-button :icon="DocumentAdd" size="small" @click="newSqlTab" :disabled="!connectionStore.activeConnectionId">
          {{ t('header.newQuery') }}
        </el-button>
      </el-button-group>

      <!-- 数据库切换下拉菜单 -->
      <el-select
        v-if="uiStore.databases.length > 0"
        v-model="selectedDatabase"
        size="small"
        :placeholder="t('header.selectDatabase')"
        class="db-select"
        @change="onDatabaseChange"
      >
        <template #prefix>
          <el-icon><Coin /></el-icon>
        </template>
        <el-option
          v-for="db in uiStore.databases"
          :key="db"
          :label="db"
          :value="db"
        />
      </el-select>

      <el-button
        v-if="editorStore.activeTab"
        :icon="VideoPlay"
        size="small"
        type="primary"
        @click="executeCurrentSql"
        :loading="editorStore.activeTab?.loading"
      >
        {{ t('header.execute') }}
      </el-button>
      <el-button
        v-if="editorStore.activeSqlTab"
        :icon="Aim"
        size="small"
        :disabled="editorStore.activeSqlTab?.loading"
        @click="explainCurrentSql"
      >
        {{ t('header.explain') }}
      </el-button>
      <el-button
        v-if="connectionStore.activeConnectionId"
        :icon="Share"
        size="small"
        @click="openErDiagram"
      >
        {{ t('header.erDiagram') }}
      </el-button>
      <el-button
        v-if="connectionStore.connectedConnections.length >= 1"
        :icon="Sort"
        size="small"
        @click="migrationVisible = true"
      >
        {{ t('header.migration') }}
      </el-button>
    </div>

    <div class="header-right">
      <!-- 语言切换 -->
      <el-dropdown @command="switchLocale">
        <el-button size="small" circle>
          <span class="locale-abbr">{{ locale === 'en-US' ? 'En' : '中' }}</span>
        </el-button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="zh-CN" :disabled="locale === 'zh-CN'">简体中文</el-dropdown-item>
            <el-dropdown-item command="en-US" :disabled="locale === 'en-US'">English</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
      <el-button
        :icon="isDark ? Sunny : Moon"
        size="small"
        circle
        @click="themeStore.toggleTheme()"
      />
      <el-button :icon="Setting" size="small" circle />
    </div>
  </div>

  <!-- 连接对话框 -->
  <ConnectionDialog v-model:visible="uiStore.connectionDialogVisible" :edit-connection="uiStore.editingConnection" />

  <!-- ER 图对话框 -->
  <ErDiagramView
    v-if="connectionStore.activeConnectionId"
    v-model:visible="erVisible"
    :connection-id="connectionStore.activeConnectionId"
  />

  <!-- 迁移对话框 -->
  <MigrationDialog v-model:visible="migrationVisible" />
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { Connection, DocumentAdd, VideoPlay, Setting, Sunny, Moon, Coin, Aim, Share, Sort } from '@element-plus/icons-vue'
import { useThemeStore } from '@/stores/theme'
import { useConnectionStore } from '@/stores/connection'
import { useEditorStore } from '@/stores/editor'
import { useUiStore } from '@/stores/ui'
import { setLocale, type AppLocale } from '@/i18n'
import ConnectionDialog from '@/components/connection/ConnectionDialog.vue'
import ErDiagramView from '@/components/editor/ErDiagramView.vue'
import MigrationDialog from '@/components/connection/MigrationDialog.vue'

const { t, locale } = useI18n()

function switchLocale(target: string) {
  setLocale(target as AppLocale)
}

const themeStore = useThemeStore()
const connectionStore = useConnectionStore()
const editorStore = useEditorStore()
const uiStore = useUiStore()

const isDark = computed(() => themeStore.isDark)

/** 数据库下拉菜单的双向绑定 */
const selectedDatabase = computed({
  get: () => uiStore.currentDatabase,
  set: (val) => uiStore.setCurrentDatabase(val)
})

function onDatabaseChange(db: string) {
  uiStore.setCurrentDatabase(db)
}

function newSqlTab() {
  if (connectionStore.activeConnectionId) {
    editorStore.createTab(connectionStore.activeConnectionId)
  }
}

function executeCurrentSql() {
  if (editorStore.activeTabId) {
    editorStore.executeSql(editorStore.activeTabId)
  }
}

function explainCurrentSql() {
  if (editorStore.activeTabId) {
    editorStore.explainSql(editorStore.activeTabId)
  }
}

const erVisible = ref(false)
function openErDiagram() {
  if (!connectionStore.activeConnectionId) return
  erVisible.value = true
}

const migrationVisible = ref(false)
</script>

<style scoped>
.app-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: var(--header-height);
  padding: 0 var(--space-4);
  background: var(--color-panel);
  border-bottom: 1px solid var(--color-border);
  flex-shrink: 0;
}

.header-left .logo {
  font-size: var(--text-title);
  font-weight: 700;
  color: var(--color-primary);
  letter-spacing: 0.5px;
}

.header-center {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}

.db-select {
  width: 180px;
}

.header-right {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.locale-abbr {
  font-size: 12px;
  line-height: 1;
}
</style>
