<template>
  <div class="scope-picker">
    <div class="scope-field">
      <div class="scope-heading">
        <span>允许库 / Schema</span>
        <el-button link size="small" :disabled="!options || options.truncated" @click="selectAllSchemas">全选已加载项</el-button>
      </div>
      <el-select
        v-model="selectedSchemas"
        multiple filterable allow-create default-first-option clearable
        collapse-tags collapse-tags-tooltip
        :loading="loading"
        :disabled="!dataSourceId"
        placeholder="选择库，或输入 sales* 等前缀通配符"
        aria-label="允许库或 Schema"
        style="width:100%"
        @visible-change="onVisibleChange"
      >
        <el-option label="所有库 / Schema（*，含后续新增）" value="*" />
        <el-option v-for="option in schemaOptions" :key="option.value"
          :label="option.label" :value="option.value" />
      </el-select>
    </div>
    <div class="scope-field">
      <div class="scope-heading">
        <span>允许表</span>
        <el-button link size="small" :disabled="!options || options.truncated" @click="selectAllTables">全选已加载项</el-button>
      </div>
      <el-select
        v-model="selectedTables"
        multiple filterable allow-create default-first-option clearable
        collapse-tags collapse-tags-tooltip
        :loading="loading"
        :disabled="!dataSourceId"
        placeholder="选择表，或输入 sales.orders_* 等前缀通配符"
        aria-label="允许表"
        style="width:100%"
        @visible-change="onVisibleChange"
      >
        <el-option label="所有库中的所有表（*.*，含后续新增）" value="*.*" />
        <el-option v-for="prefix in tablePrefixes" :key="prefix"
          :label="`${prefix}.*（此库全部表，含后续新增）`" :value="`${prefix}.*`" />
        <el-option v-for="table in visibleTables" :key="table" :label="table" :value="table" />
      </el-select>
    </div>
    <small v-if="loading" class="scope-note">正在读取数据源的库和表名称…</small>
    <small v-else-if="error" class="scope-error">{{ error }}；仍可输入完整名称或通配符。</small>
    <small v-else-if="options?.truncated" class="scope-note">元数据选项过多，列表已截断；可输入明确的前缀通配符。</small>
    <small v-else-if="options && !options.namespaces.length && !options.tables.length" class="scope-note">此数据源未返回可选库表；可输入完整名称或通配符。</small>
    <small v-else class="scope-note">“全选”保存当前已加载项；通配符会覆盖将来新增的匹配对象。最终授权以预览为准。</small>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { entApi, type AdminGrantScopeOptions } from '@/api/ent'

const props = defineProps<{
  dataSourceId: string
  schemas: string
  tables: string
}>()
const emit = defineEmits<{
  'update:schemas': [value: string]
  'update:tables': [value: string]
}>()

const options = ref<AdminGrantScopeOptions | null>(null)
const loading = ref(false)
const error = ref('')
let requestVersion = 0

watch(() => props.dataSourceId, () => {
  requestVersion++
  options.value = null
  error.value = ''
  loading.value = false
})

function csvValues(value: string) {
  return value.split(',').map(part => part.trim()).filter(Boolean)
}

const selectedSchemas = computed({
  get: () => csvValues(props.schemas),
  set: (values: string[]) => emit('update:schemas',
    values.includes('*') ? '*' : [...new Set(values)].join(',')),
})
const selectedTables = computed({
  get: () => csvValues(props.tables),
  set: (values: string[]) => emit('update:tables',
    values.includes('*.*') ? '*.*' : [...new Set(values)].join(',')),
})
const schemaOptions = computed(() => {
  const unique = new Map<string, { value: string; label: string }>()
  for (const option of options.value?.namespaces || []) {
    if (!unique.has(option.value)) unique.set(option.value, { value: option.value, label: option.value })
  }
  return [...unique.values()]
})
function schemaSelected(schema: string) {
  const patterns = selectedSchemas.value
  return !patterns.length || patterns.some(pattern => pattern === '*'
    || (pattern.endsWith('*')
      ? schema.toLowerCase().startsWith(pattern.slice(0, -1).toLowerCase())
      : schema.toLowerCase() === pattern.toLowerCase()))
}
const visibleTables = computed(() => (options.value?.tables || []).filter(table => {
  const parts = table.split('.')
  return parts.length > 1 && schemaSelected(parts[parts.length - 2])
}))
const tablePrefixes = computed(() => [...new Set(
  options.value?.namespaces.filter(option => schemaSelected(option.value))
    .map(option => option.tablePrefix) || [],
)])

async function loadOptions() {
  if (!props.dataSourceId || options.value || loading.value) return
  const sourceId = props.dataSourceId
  const version = ++requestVersion
  loading.value = true
  error.value = ''
  try {
    const result = await entApi.adminGrantScope(sourceId)
    if (version === requestVersion) options.value = result
  } catch (cause: any) {
    if (version === requestVersion) error.value = cause.message || '元数据读取失败'
  } finally {
    if (version === requestVersion) loading.value = false
  }
}

function onVisibleChange(visible: boolean) {
  if (visible) void loadOptions()
}

function selectAllSchemas() {
  const values = [...new Set(options.value?.namespaces.map(option => option.value) || [])]
  if (!values.length) return
  const csv = values.join(',')
  if (csv.length > 500) {
    ElMessage.warning('库数量超过授权字段容量，请缩小范围或使用通配符')
    return
  }
  emit('update:schemas', csv)
}

function selectAllTables() {
  const values = visibleTables.value
  if (!values.length) return
  const csv = values.join(',')
  if (csv.length > 1000) {
    ElMessage.warning('表数量超过授权字段容量，请缩小范围或使用通配符')
    return
  }
  emit('update:tables', csv)
}
</script>

<style scoped>
.scope-picker { display: grid; grid-template-columns: repeat(auto-fit, minmax(210px, 1fr)); gap: 10px; min-width: 0; width: 100%; }
.scope-field { min-width: 0; }
.scope-heading { display: flex; align-items: center; justify-content: space-between; gap: 8px; margin-bottom: 4px; font-size: 12px; }
.scope-note { grid-column: 1 / -1; color: var(--color-text-muted); line-height: 1.4; }
.scope-error { grid-column: 1 / -1; color: var(--el-color-danger); line-height: 1.4; }
</style>
