<template>
  <el-drawer
    v-model="taskStore.panelVisible"
    :title="t('tasks.title')"
    size="420px"
    append-to-body
  >
    <div v-if="taskStore.tasks.length === 0" class="task-empty">
      {{ t('tasks.empty') }}
    </div>
    <div v-else class="task-list">
      <div v-for="task in taskStore.tasks" :key="task.id" class="task-item">
        <div class="task-item-header">
          <el-tag :type="statusTagType(task.status)" size="small">
            {{ t(`tasks.status.${task.status}`) }}
          </el-tag>
          <span class="task-conn">{{ task.connectionName }}</span>
          <span class="task-time">{{ formatTime(task.submittedAt) }}</span>
        </div>
        <div class="task-sql" :title="task.sql">{{ task.sql }}</div>
        <div class="task-meta">
          <template v-if="task.status === 'DONE'">
            {{ t('tasks.rows', { rows: task.totalRows }) }} · {{ task.elapsedMs }}ms
          </template>
          <template v-else-if="task.status === 'ERROR'">
            <span class="task-error">{{ task.errorMessage }}</span>
          </template>
        </div>
        <div class="task-actions">
          <el-button
            v-if="task.status === 'DONE' && task.resultAvailable"
            size="small"
            type="primary"
            link
            @click="viewResult(task)"
          >
            {{ t('tasks.viewResult') }}
          </el-button>
          <el-button
            v-if="task.status === 'RUNNING'"
            size="small"
            type="warning"
            link
            @click="taskStore.cancel(task.id)"
          >
            {{ t('tasks.cancel') }}
          </el-button>
          <el-button
            v-if="task.status !== 'RUNNING'"
            size="small"
            type="danger"
            link
            @click="taskStore.remove(task.id)"
          >
            {{ t('tasks.remove') }}
          </el-button>
        </div>
      </div>
    </div>
  </el-drawer>
</template>

<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { useTaskStore } from '@/stores/tasks'
import { useEditorStore } from '@/stores/editor'
import type { BackgroundTask, TaskStatus } from '@/types/task'

const { t } = useI18n()
const taskStore = useTaskStore()
const editorStore = useEditorStore()

function statusTagType(status: TaskStatus) {
  switch (status) {
    case 'RUNNING': return 'primary'
    case 'DONE': return 'success'
    case 'ERROR': return 'danger'
    default: return 'info'
  }
}

function formatTime(iso: string) {
  if (!iso) return ''
  const d = new Date(iso)
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}:${String(d.getSeconds()).padStart(2, '0')}`
}

async function viewResult(task: BackgroundTask) {
  await editorStore.openTaskResult(task)
  taskStore.panelVisible = false
}
</script>

<style scoped>
.task-empty {
  padding: var(--space-4);
  text-align: center;
  color: var(--color-text-secondary);
}

.task-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

.task-item {
  padding: var(--space-3);
  border: 1px solid var(--color-border);
  border-radius: 6px;
  background: var(--color-panel);
}

.task-item-header {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.task-conn {
  font-size: 12px;
  font-weight: 600;
}

.task-time {
  margin-left: auto;
  font-size: 12px;
  color: var(--color-text-secondary);
}

.task-sql {
  margin-top: var(--space-2);
  font-family: var(--font-mono, monospace);
  font-size: 12px;
  color: var(--color-text-secondary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.task-meta {
  margin-top: var(--space-1);
  font-size: 12px;
  color: var(--color-text-secondary);
}

.task-error {
  color: var(--el-color-danger);
}

.task-actions {
  margin-top: var(--space-2);
  display: flex;
  gap: var(--space-2);
}
</style>
