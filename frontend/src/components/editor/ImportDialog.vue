<template>
  <el-dialog v-model="visibleRef" title="导入数据" width="640" @open="onOpen">
    <el-form label-width="90px">
      <el-form-item label="目标表">
        <el-input :model-value="tableRef" disabled />
      </el-form-item>
      <el-form-item label="格式">
        <el-radio-group v-model="format">
          <el-radio value="csv">CSV（首行表头）</el-radio>
          <el-radio value="json">JSON 数组</el-radio>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="数据">
        <el-input v-model="raw" type="textarea" :rows="10" placeholder="CSV: 首行表头,逗号分隔；或 JSON 数组 [{'id':1}]" />
      </el-form-item>
      <div v-if="preview.length" class="preview">
        <span>预览：{{ rows.length }} 行，列：{{ columns.join(', ') }}</span>
        <el-table :data="preview" size="small" border max-height="180" style="margin-top:6px">
          <el-table-column v-for="c in columns" :key="c" :prop="c" :label="c" show-overflow-tooltip />
        </el-table>
      </div>
    </el-form>
    <template #footer>
      <el-button @click="parse" :disabled="!raw">解析预览</el-button>
      <el-button @click="visibleRef = false">取消</el-button>
      <el-button type="primary" :loading="importing" :disabled="!rows.length" @click="doImport">导入</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { queryApi } from '@/api/metadata'

const props = defineProps<{ visible: boolean; connectionId: string; schema: string | null; table: string }>()
const emit = defineEmits<{ 'update:visible': [boolean] }>()

const visibleRef = ref(props.visible)
watch(() => props.visible, (v) => { visibleRef.value = v })
watch(visibleRef, (v) => emit('update:visible', v))

const tableRef = ref(props.table)
watch(() => props.table, (v) => { tableRef.value = v })

const format = ref<'csv' | 'json'>('csv')
const raw = ref('')
const rows = ref<Record<string, any>[]>([])
const columns = ref<string[]>([])
const preview = ref<Record<string, any>[]>([])
const importing = ref(false)

function onOpen() {
  raw.value = ''; rows.value = []; preview.value = []; columns.value = []
}

function parse() {
  rows.value = []; preview.value = []; columns.value = []
  const text = raw.value.trim()
  if (!text) { ElMessage.warning('请粘贴数据'); return }
  try {
    if (format.value === 'json') {
      const arr = JSON.parse(text)
      if (!Array.isArray(arr)) throw new Error('JSON 须为数组')
      rows.value = arr as Record<string, any>[]
    } else {
      const lines = text.split(/\r?\n/).filter(l => l.length)
      const header = lines[0].split(',').map(h => h.trim())
      const data: Record<string, any>[] = []
      for (let i = 1; i < lines.length; i++) {
        const cells = lines[i].split(',')
        const row: Record<string, any> = {}
        header.forEach((h, idx) => { row[h] = (cells[idx] ?? '').trim() })
        data.push(row)
      }
      rows.value = data
    }
    const colSet = new Set<string>()
    rows.value.forEach(r => Object.keys(r).forEach(k => colSet.add(k)))
    columns.value = Array.from(colSet)
    preview.value = rows.value.slice(0, 10)
    ElMessage.success(`解析 ${rows.value.length} 行`)
  } catch (e: any) {
    ElMessage.error('解析失败: ' + (e.message || ''))
  }
}

async function doImport() {
  importing.value = true
  try {
    const res = await queryApi.importRows(props.connectionId, props.schema, props.table, rows.value)
    if (res.success) ElMessage.success(`导入成功：${res.inserted}/${res.total} 行`)
    else ElMessage.warning(`导入 ${res.inserted} 行，含错误`)
    visibleRef.value = false
  } catch (e: any) {
    ElMessage.error(e.message || '导入失败')
  } finally {
    importing.value = false
  }
}
</script>

<style scoped>
.preview { margin-top: 8px; font-size: 12px; color: var(--color-text-muted, #666); }
</style>
