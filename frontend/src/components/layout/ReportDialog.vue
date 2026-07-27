<template>
  <!-- 报表订阅管理（迭代二 PM2） -->
  <el-dialog
    :model-value="visible"
    :title="t('reports.title')"
    width="860"
    @update:model-value="emit('update:visible', $event)"
    @open="load"
  >
    <div class="bar">
      <el-button type="primary" size="small" :icon="Plus" @click="openEdit()">
        {{ t('reports.create') }}
      </el-button>
    </div>
    <el-table :data="schedules" border size="small" :empty-text="t('reports.empty')">
      <el-table-column prop="name" :label="t('reports.name')" width="140" show-overflow-tooltip />
      <el-table-column prop="connectionName" :label="t('reports.connection')" width="120" show-overflow-tooltip />
      <el-table-column :label="t('reports.schedule')" width="150">
        <template #default="{ row }">{{ scheduleLabel(row) }}</template>
      </el-table-column>
      <el-table-column prop="webhookUrl" label="Webhook" show-overflow-tooltip />
      <el-table-column :label="t('reports.lastRun')" width="150">
        <template #default="{ row }">
          <template v-if="row.lastRunAt">
            {{ fmt(row.lastRunAt) }}
            <el-tag :type="row.lastStatus === 'SUCCESS' ? 'success' : 'danger'" size="small">{{ row.lastStatus }}</el-tag>
          </template>
        </template>
      </el-table-column>
      <el-table-column :label="t('reports.enabled')" width="70">
        <template #default="{ row }"><el-switch :model-value="row.enabled" size="small" @change="toggle(row)" /></template>
      </el-table-column>
      <el-table-column :label="t('reports.actions')" width="230">
        <template #default="{ row }">
          <el-button size="small" link type="primary" @click="trigger(row)">{{ t('reports.trigger') }}</el-button>
          <el-button size="small" link @click="showRuns(row)">{{ t('reports.runs') }}</el-button>
          <el-button size="small" link @click="openEdit(row)">{{ t('common.edit') }}</el-button>
          <el-button size="small" link type="danger" @click="del(row)">{{ t('common.delete') }}</el-button>
        </template>
      </el-table-column>
    </el-table>
  </el-dialog>

  <!-- 订阅编辑 -->
  <el-dialog v-model="edit.visible" :title="edit.form.id ? t('reports.editTitle') : t('reports.create')" width="560" append-to-body>
    <el-form label-width="110px">
      <el-form-item :label="t('reports.name')"><el-input v-model="edit.form.name" /></el-form-item>
      <el-form-item :label="t('reports.connection')">
        <el-select v-model="edit.form.connectionId" style="width:100%">
          <el-option v-for="c in connectionStore.connections" :key="c.id" :label="c.name" :value="c.id" />
        </el-select>
      </el-form-item>
      <el-form-item label="SQL">
        <el-input v-model="edit.form.sql" type="textarea" :rows="4" :placeholder="t('reports.sqlHint')" />
      </el-form-item>
      <el-form-item :label="t('reports.defaultDb')"><el-input v-model="edit.form.defaultDatabase" /></el-form-item>
      <el-form-item :label="t('reports.schedule')">
        <el-radio-group v-model="edit.form.scheduleType">
          <el-radio value="HOURLY">{{ t('reports.typeHourly') }}</el-radio>
          <el-radio value="DAILY">{{ t('reports.typeDaily') }}</el-radio>
          <el-radio value="WEEKLY">{{ t('reports.typeWeekly') }}</el-radio>
        </el-radio-group>
      </el-form-item>
      <el-form-item v-if="edit.form.scheduleType === 'WEEKLY'" :label="t('reports.weekday')">
        <el-select v-model="edit.form.weekday" style="width:160px">
          <el-option v-for="n in 7" :key="n" :label="t(`reports.weekdays.w${n}`)" :value="n" />
        </el-select>
      </el-form-item>
      <el-form-item v-if="edit.form.scheduleType !== 'HOURLY'" :label="t('reports.runHour')">
        <el-input-number v-model="edit.form.runHour" :min="0" :max="23" />
      </el-form-item>
      <el-form-item :label="t('reports.runMinute')">
        <el-input-number v-model="edit.form.runMinute" :min="0" :max="59" />
      </el-form-item>
      <el-form-item label="Webhook">
        <el-input v-model="edit.form.webhookUrl" placeholder="https://..." />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="edit.visible = false">{{ t('common.cancel') }}</el-button>
      <el-button type="primary" :loading="edit.busy" @click="save">{{ t('common.save') }}</el-button>
    </template>
  </el-dialog>

  <!-- 执行记录 -->
  <el-dialog v-model="runsDialog.visible" :title="t('reports.runsTitle', { name: runsDialog.name })" width="720" append-to-body>
    <el-table :data="runsDialog.runs" border size="small" :empty-text="t('reports.empty')">
      <el-table-column :label="t('reports.runAt')" width="160">
        <template #default="{ row }">{{ fmt(row.runAt) }}</template>
      </el-table-column>
      <el-table-column :label="t('reports.result')" width="90">
        <template #default="{ row }">
          <el-tag :type="row.success ? 'success' : 'danger'" size="small">{{ row.success ? t('reports.success') : t('reports.failed') }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="rowCount" :label="t('reports.rowCount')" width="90" />
      <el-table-column :label="t('reports.elapsed')" width="100">
        <template #default="{ row }">{{ row.elapsedMs }}ms</template>
      </el-table-column>
      <el-table-column prop="pushStatus" :label="t('reports.pushStatus')" width="110" />
      <el-table-column prop="errorMessage" :label="t('reports.error')" show-overflow-tooltip />
    </el-table>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, reactive } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { reportApi, type ReportSchedule, type ReportRun } from '@/api/report'
import { useConnectionStore } from '@/stores/connection'

defineProps<{ visible: boolean }>()
const emit = defineEmits<{ (e: 'update:visible', v: boolean): void }>()

const { t } = useI18n()
const connectionStore = useConnectionStore()

const schedules = ref<ReportSchedule[]>([])

const emptyForm = (): Partial<ReportSchedule> => ({
  name: '', connectionId: '', sql: '', defaultDatabase: '',
  scheduleType: 'DAILY', runMinute: 0, runHour: 9, weekday: 1, webhookUrl: '', enabled: true,
})
const edit = reactive({ visible: false, busy: false, form: emptyForm() })
const runsDialog = reactive({ visible: false, name: '', runs: [] as ReportRun[] })

async function load() {
  try { schedules.value = await reportApi.list() } catch {}
}

function openEdit(row?: ReportSchedule) {
  edit.form = row ? { ...row } : emptyForm()
  edit.visible = true
}

async function save() {
  if (!edit.form.name?.trim() || !edit.form.connectionId || !edit.form.sql?.trim() || !edit.form.webhookUrl?.trim()) {
    ElMessage.warning(t('reports.formIncomplete'))
    return
  }
  edit.busy = true
  try {
    const conn = connectionStore.connections.find(c => c.id === edit.form.connectionId)
    await reportApi.save({ ...edit.form, connectionName: conn?.name })
    ElMessage.success(t('common.saved'))
    edit.visible = false
    load()
  } catch (e: any) { ElMessage.error(e.message || t('common.failed')) }
  finally { edit.busy = false }
}

async function toggle(row: ReportSchedule) {
  try {
    await reportApi.save({ ...row, enabled: !row.enabled })
    load()
  } catch (e: any) { ElMessage.error(e.message || t('common.failed')) }
}

async function trigger(row: ReportSchedule) {
  try {
    const run = await reportApi.trigger(row.id!)
    if (run.success) ElMessage.success(t('reports.triggerDone', { rows: run.rowCount }))
    else ElMessage.error(run.errorMessage || t('common.failed'))
    load()
  } catch (e: any) { ElMessage.error(e.message || t('common.failed')) }
}

async function showRuns(row: ReportSchedule) {
  try {
    runsDialog.runs = await reportApi.runs(row.id!)
    runsDialog.name = row.name
    runsDialog.visible = true
  } catch (e: any) { ElMessage.error(e.message || t('common.failed')) }
}

async function del(row: ReportSchedule) {
  try { await ElMessageBox.confirm(t('reports.deleteConfirm'), t('common.confirm'), { type: 'warning' }) } catch { return }
  await reportApi.remove(row.id!)
  ElMessage.success(t('common.deleted'))
  load()
}

function scheduleLabel(row: ReportSchedule) {
  const mm = String(row.runMinute).padStart(2, '0')
  const hh = String(row.runHour).padStart(2, '0')
  if (row.scheduleType === 'HOURLY') return t('reports.labelHourly', { mm })
  if (row.scheduleType === 'WEEKLY') return `${t(`reports.weekdays.w${row.weekday}`)} ${hh}:${mm}`
  return t('reports.labelDaily', { hh, mm })
}

function fmt(d?: string) { return d ? new Date(d).toLocaleString() : '' }
</script>

<style scoped>
.bar {
  margin-bottom: var(--space-3);
}
</style>
