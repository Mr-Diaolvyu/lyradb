<template>
  <el-dialog v-model="visibleRef" title="跨库数据迁移" width="620px" :close-on-click-modal="false">
    <el-form :model="form" label-width="120px" size="default">
      <el-divider content-position="left">源</el-divider>
      <el-form-item label="源连接">
        <el-select v-model="form.sourceConnectionId" placeholder="选择已连接的源" style="width: 100%">
          <el-option
            v-for="c in connectionStore.connectedConnections"
            :key="c.id"
            :label="`${c.name} (${c.dbType})`"
            :value="c.id!"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="源 schema/库">
        <el-input v-model="form.sourceSchema" placeholder="可选" />
      </el-form-item>
      <el-form-item label="源表">
        <el-input v-model="form.sourceTable" placeholder="源表名" />
      </el-form-item>

      <el-divider content-position="left">目标</el-divider>
      <el-form-item label="目标连接">
        <el-select v-model="form.targetConnectionId" placeholder="选择已连接的目标" style="width: 100%">
          <el-option
            v-for="c in connectionStore.connectedConnections"
            :key="c.id"
            :label="`${c.name} (${c.dbType})`"
            :value="c.id!"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="目标 schema/库">
        <el-input v-model="form.targetSchema" placeholder="可选" />
      </el-form-item>
      <el-form-item label="目标表">
        <el-input v-model="form.targetTable" placeholder="目标表名" />
      </el-form-item>

      <el-divider content-position="left">选项</el-divider>
      <el-form-item label="模式">
        <el-radio-group v-model="form.mode">
          <el-radio value="append">追加到已存在表</el-radio>
          <el-radio value="create">先建表再写入</el-radio>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="批量大小">
        <el-input-number v-model="form.batchSize" :min="100" :step="500" :controls="false" style="width: 200px" />
      </el-form-item>
      <el-form-item label="最大行数">
        <el-input-number v-model="form.maxRows" :min="1000" :step="10000" :controls="false" style="width: 200px" />
      </el-form-item>
    </el-form>

    <div v-if="result" class="migration-result">
      <el-alert
        :title="result.success ? '迁移完成' : '迁移完成（含错误）'"
        :type="result.success ? 'success' : 'warning'"
        :closable="false"
        show-icon
      >
        <div>读取 {{ result.rowsRead }} 行，写入 {{ result.rowsWritten }} 行，耗时 {{ result.elapsedMs }}ms</div>
      </el-alert>
      <div v-if="result.errors?.length" class="migration-errors">
        <div v-for="(e, i) in result.errors" :key="i" class="err-line">{{ e }}</div>
      </div>
    </div>

    <template #footer>
      <el-button @click="visibleRef = false">关闭</el-button>
      <el-button
        type="primary"
        :loading="running"
        :disabled="!canRun"
        @click="run"
      >
        开始迁移
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useConnectionStore } from '@/stores/connection'
import { migrationApi, type MigrationRequest, type MigrationResult } from '@/api/migration'

const props = defineProps<{ visible: boolean }>()
const emit = defineEmits<{ 'update:visible': [boolean] }>()

const connectionStore = useConnectionStore()

const visibleRef = ref(props.visible)
watch(() => props.visible, (v) => { visibleRef.value = v })
watch(visibleRef, (v) => emit('update:visible', v))

const form = ref<MigrationRequest>({
  sourceConnectionId: '',
  targetConnectionId: '',
  sourceSchema: '',
  sourceTable: '',
  targetSchema: '',
  targetTable: '',
  mode: 'append',
  batchSize: 1000,
  maxRows: 100000,
})

const running = ref(false)
const result = ref<MigrationResult | null>(null)

const canRun = computed(() =>
  !!form.value.sourceConnectionId &&
  !!form.value.targetConnectionId &&
  !!form.value.sourceTable &&
  !!form.value.targetTable
)

async function run() {
  result.value = null
  running.value = true
  try {
    result.value = await migrationApi.migrate(form.value)
    if (result.value.success) {
      ElMessage.success(`迁移完成：写入 ${result.value.rowsWritten} 行`)
    } else {
      ElMessage.warning('迁移完成，但存在错误，请查看详情')
    }
  } catch (e: any) {
    ElMessage.error('迁移失败: ' + (e.message || ''))
  } finally {
    running.value = false
  }
}
</script>

<style scoped>
.migration-result {
  margin-top: 16px;
}
.migration-errors {
  margin-top: 8px;
  max-height: 160px;
  overflow-y: auto;
  font-family: var(--font-mono, monospace);
  font-size: 12px;
}
.err-line {
  color: var(--color-destructive);
  margin-bottom: 4px;
}
</style>
