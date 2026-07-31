<template>
  <div class="app-header glass-surface">
    <div class="header-left">
      <svg class="logo-mark" viewBox="0 0 32 32" aria-hidden="true">
        <rect x="1" y="1" width="30" height="30" rx="9" class="logo-tile" />
        <ellipse cx="14" cy="10" rx="6.5" ry="2.8" class="logo-db" />
        <path d="M7.5 10v8.5c0 1.6 2.9 2.9 6.5 2.9s6.5-1.3 6.5-2.9V10M7.5 14.3c0 1.6 2.9 2.9 6.5 2.9 2.2 0 4.1-.5 5.3-1.2" class="logo-db" />
        <circle cx="23.5" cy="22.5" r="2" class="logo-star" />
        <path d="m20.9 20.2 1.1 1m3.8-1.1-1.1 1.1" class="logo-orbit" />
      </svg>
      <span class="logo-copy">
        <span class="logo">LyraDB</span>
        <span class="logo-edition">DATA WORKSPACE</span>
      </span>
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
        v-if="editorStore.activeSqlTab"
        :icon="Clock"
        size="small"
        :disabled="editorStore.activeSqlTab?.loading"
        @click="runInBackground"
      >
        {{ t('header.runBackground') }}
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
      <!-- 报表订阅入口 -->
      <el-tooltip :content="t('reports.title')" placement="bottom">
        <el-button :icon="Calendar" size="small" circle @click="reportVisible = true" />
      </el-tooltip>
      <!-- 后台任务面板入口 -->
      <el-badge :value="taskStore.unreadCount" :hidden="taskStore.unreadCount === 0" class="task-badge">
        <el-button :icon="List" size="small" circle @click="taskStore.openPanel()" />
      </el-badge>
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
      <!-- 外观设置：主题三态 + 强调色预设 -->
      <el-popover placement="bottom-end" :width="250" trigger="click">
        <template #reference>
          <el-button :icon="isDark ? Moon : Sunny" size="small" circle :title="t('appearance.title')" />
        </template>
        <div class="appearance-pop">
          <div class="ap-label">{{ t('appearance.theme') }}</div>
          <el-radio-group :model-value="themeStore.mode" size="small" @update:model-value="themeStore.setMode($event as ThemeMode)">
            <el-radio-button value="light">{{ t('appearance.light') }}</el-radio-button>
            <el-radio-button value="dark">{{ t('appearance.dark') }}</el-radio-button>
            <el-radio-button value="system">{{ t('appearance.system') }}</el-radio-button>
          </el-radio-group>
          <div class="ap-label">{{ t('appearance.accent') }}</div>
          <div class="accent-row">
            <button
              v-for="a in ACCENTS"
              :key="a.value"
              class="accent-swatch"
              :class="{ on: themeStore.accent === a.value }"
              :style="{ background: a.color }"
              :title="t(`appearance.${a.value}`)"
              @click="themeStore.setAccent(a.value)"
            />
          </div>
          <div class="ap-label">{{ t('appearance.density') }}</div>
          <el-radio-group :model-value="themeStore.density" size="small" @update:model-value="themeStore.setDensity($event as Density)">
            <el-radio-button value="comfortable">{{ t('appearance.comfortable') }}</el-radio-button>
            <el-radio-button value="compact">{{ t('appearance.compact') }}</el-radio-button>
          </el-radio-group>
        </div>
      </el-popover>
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

  <!-- 后台任务面板 -->
  <TaskPanel />

  <!-- 报表订阅管理 -->
  <ReportDialog v-model:visible="reportVisible" />
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { Connection, DocumentAdd, VideoPlay, Setting, Sunny, Moon, Coin, Aim, Share, Sort, Clock, List, Calendar } from '@element-plus/icons-vue'
import { useThemeStore, type ThemeMode, type AccentPreset, type Density } from '@/stores/theme'
import { useConnectionStore } from '@/stores/connection'
import { useEditorStore } from '@/stores/editor'
import { useUiStore } from '@/stores/ui'
import { useTaskStore } from '@/stores/tasks'
import { setLocale, type AppLocale } from '@/i18n'
import ConnectionDialog from '@/components/connection/ConnectionDialog.vue'
import ErDiagramView from '@/components/editor/ErDiagramView.vue'
import MigrationDialog from '@/components/connection/MigrationDialog.vue'
import TaskPanel from '@/components/layout/TaskPanel.vue'
import ReportDialog from '@/components/layout/ReportDialog.vue'

const { t, locale } = useI18n()

function switchLocale(target: string) {
  setLocale(target as AppLocale)
}

const themeStore = useThemeStore()
const connectionStore = useConnectionStore()
const editorStore = useEditorStore()
const uiStore = useUiStore()
const taskStore = useTaskStore()

const isDark = computed(() => themeStore.isDark)

/** 强调色预设（色块取亮色主题主操作色，仅作选择器展示） */
const ACCENTS: { value: AccentPreset; color: string }[] = [
  { value: 'navy', color: '#1E3A5F' },
  { value: 'emerald', color: '#047857' },
  { value: 'amber', color: '#B45309' },
  { value: 'violet', color: '#6D28D9' },
]

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

function runInBackground() {
  if (editorStore.activeTabId) {
    editorStore.runInBackground(editorStore.activeTabId)
  }
}

const erVisible = ref(false)
function openErDiagram() {
  if (!connectionStore.activeConnectionId) return
  erVisible.value = true
}

const migrationVisible = ref(false)
const reportVisible = ref(false)
</script>

<style scoped>
.app-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: var(--header-height);
  padding: 0 var(--space-4);
  border-width: 0 0 1px;
  box-shadow: none;
  flex-shrink: 0;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 8px;
}

.logo-mark {
  width: 28px;
  height: 28px;
}

.logo-tile { fill: var(--color-active); stroke: var(--color-panel-border); }
.logo-db { fill: none; stroke: var(--color-brand); stroke-width: 1.55; stroke-linecap: round; stroke-linejoin: round; }
.logo-star { fill: var(--color-accent); }
.logo-orbit { fill: none; stroke: var(--color-accent); stroke-width: 1.2; stroke-linecap: round; }

.logo-copy {
  display: flex;
  flex-direction: column;
  line-height: 1;
}

.header-left .logo {
  color: var(--color-foreground);
  font-size: 14px;
  font-weight: 720;
  letter-spacing: -0.01em;
}

.logo-edition {
  margin-top: 3px;
  color: var(--color-text-muted);
  font-size: 7px;
  font-weight: 700;
  letter-spacing: 0.12em;
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

.task-badge :deep(.el-badge__content) {
  transform: translateY(-2px) translateX(100%) scale(0.8);
}
</style>

<style>
/* 外观设置弹出层（popover 渲染在 body，不能用 scoped） */
.appearance-pop .ap-label {
  font-size: var(--text-caption);
  color: var(--color-text-muted);
  margin: var(--space-2) 0 var(--space-1);
}

.appearance-pop .ap-label:first-child {
  margin-top: 0;
}

.appearance-pop .accent-row {
  display: flex;
  gap: var(--space-2);
}

.appearance-pop .accent-swatch {
  width: 24px;
  height: 24px;
  border-radius: 50%;
  border: 2px solid transparent;
  cursor: pointer;
  padding: 0;
  transition: transform var(--transition-fast), border-color var(--transition-normal);
}

.appearance-pop .accent-swatch:hover {
  transform: scale(1.1);
}

.appearance-pop .accent-swatch.on {
  border-color: var(--color-foreground);
}
</style>
