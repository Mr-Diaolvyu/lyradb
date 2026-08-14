<template>
  <section class="table-inspection" v-loading="loading">
    <header class="inspection-header">
      <div class="object-identity">
        <div class="object-orbit" aria-hidden="true">
          <el-icon><Grid /></el-icon>
        </div>
        <div class="identity-copy">
          <div class="eyebrow">{{ isMaxCompute ? 'MAXCOMPUTE TABLE WORKSPACE' : 'TABLE WORKSPACE' }}</div>
          <div class="qualified-name" :title="qualifiedName">{{ qualifiedName }}</div>
          <div class="object-stats">
            <span>{{ columns.length }} 字段</span>
            <span v-if="isMaxCompute">
              {{ inspection?.partitioned ? `${partitionColumns.length} 个分区键` : partitionStateLabel }}
            </span>
            <span>{{ constraints.length }} 项约束</span>
            <span class="object-type">{{ inspection?.objectType || 'TABLE' }}</span>
          </div>
        </div>
      </div>
      <div class="header-actions">
        <el-tooltip content="复制完整表名" placement="bottom">
          <el-button :icon="CopyDocument" circle aria-label="复制完整表名" @click="copyQualifiedName" />
        </el-tooltip>
        <el-button
          :icon="Position"
          :disabled="!inspection?.previewSql && !preview?.sql"
          @click="emitOpenSql"
        >
          在 SQL 中打开
        </el-button>
        <el-button :icon="Refresh" :loading="loading" @click="$emit('refresh')">
          刷新
        </el-button>
      </div>
    </header>

    <el-alert
      v-if="error"
      class="global-error"
      type="error"
      :title="error"
      show-icon
      :closable="false"
    />

    <el-tabs v-model="activeSection" class="inspection-tabs">
      <el-tab-pane name="overview">
        <template #label>
          <span class="tab-label"><el-icon><InfoFilled /></el-icon>概览</span>
        </template>
        <div class="section-content overview-section">
          <div class="overview-grid">
            <article class="overview-card comment-card">
              <div class="card-heading">
                <span>中文注释</span>
                <span :class="['metadata-state', metadataStateClass]">
                  {{ metadataStatusLabel }}
                </span>
              </div>
              <p v-if="tableRemarks" class="table-comment">{{ tableRemarks }}</p>
              <p v-else class="metadata-unavailable">{{ metadataReason }}</p>
              <dl class="metadata-trace">
                <dt>元数据来源</dt>
                <dd>{{ metadataSourceLabel }}</dd>
                <template v-if="inspection?.metadataReason">
                  <dt>采集说明</dt>
                  <dd>{{ inspection.metadataReason }}</dd>
                </template>
                <dt>完整性</dt>
                <dd>{{ inspection?.metadataStatus || 'UNKNOWN' }}</dd>
              </dl>
            </article>

            <article class="overview-card">
              <div class="card-heading"><span>对象信息</span></div>
              <dl class="metadata-trace">
                <dt>类型</dt><dd>{{ inspection?.objectType || 'TABLE' }}</dd>
                <dt>引擎</dt><dd>{{ inspection?.dbType || 'DATABASE' }}</dd>
                <dt>Schema / Project</dt><dd :title="inspection?.schema || ''">{{ inspection?.schema || '—' }}</dd>
                <dt>字段数量</dt><dd>{{ columns.length }}</dd>
              </dl>
            </article>

            <article v-if="isMaxCompute" class="overview-card partition-summary-card">
              <div class="card-heading">
                <span>分区策略</span>
                <span :class="['mini-tag', inspection?.partitioned ? 'primary' : '']">
                  {{ partitionStateLabel }}
                </span>
              </div>
              <template v-if="inspection?.partitioned">
                <p>数据预览必须先选择一个完整分区，服务端会再次校验分区键。</p>
                <div class="partition-key-list">
                  <code v-for="key in partitionColumns" :key="key">{{ key }}</code>
                </div>
                <el-button type="primary" plain @click="activeSection = 'partitions'">选择分区</el-button>
              </template>
              <p v-else-if="inspection?.previewRequiresPartition">
                {{ sectionError('partitions') || '无法可靠确认分区状态，已阻止数据预览。' }}
              </p>
              <p v-else>当前表不需要分区条件，可按需加载有界数据预览。</p>
            </article>
          </div>

          <section class="constraint-summary">
            <div class="section-toolbar">
              <span class="section-caption">索引与约束</span>
              <span class="section-count">{{ constraints.length }} 项</span>
            </div>
            <div v-if="constraints.length" class="constraint-grid">
              <article
                v-for="constraint in constraints"
                :key="`${constraint.type}:${constraint.name}`"
                class="constraint-card"
              >
                <el-icon><component :is="constraint.type === 'FOREIGN_KEY' ? Link : Key" /></el-icon>
                <div>
                  <strong>{{ constraint.name || '未命名约束' }}</strong>
                  <span>{{ constraintTypeLabel(constraint.type) }} · {{ constraint.columns.join(', ') || '—' }}</span>
                </div>
              </article>
            </div>
            <el-empty v-else description="未发现索引或约束" :image-size="52" />
          </section>
        </div>
      </el-tab-pane>

      <el-tab-pane v-if="isMaxCompute" name="partitions">
        <template #label>
          <span class="tab-label"><el-icon><Collection /></el-icon>分区</span>
        </template>
        <div class="section-content partition-section">
          <el-alert
            v-if="!inspection?.partitioned && inspection?.previewRequiresPartition"
            type="warning"
            :title="sectionError('partitions') || '无法可靠确认分区状态'"
            show-icon
            :closable="false"
          />
          <el-empty
            v-else-if="!inspection?.partitioned"
            description="当前表不是分区表"
            :image-size="68"
          />
          <template v-else>
            <div class="section-toolbar partition-toolbar">
              <div>
                <strong>选择数据分区</strong>
                <span class="section-caption">按服务端返回的完整分区表达式选择，不在浏览器拼接 SQL</span>
              </div>
              <div class="partition-actions">
                <el-input
                  v-model="localPartitionFilter"
                  :prefix-icon="Search"
                  clearable
                  placeholder="筛选 ds=.../region=..."
                  aria-label="筛选分区"
                  @keyup.enter="requestPartitions(0)"
                  @clear="requestPartitions(0)"
                />
                <el-button :icon="Search" :loading="partitionLoading" @click="requestPartitions(0)">筛选</el-button>
                <el-button
                  v-if="partitionPage?.suggestedPartition && partitionPage.ordering === 'PARTITION_SPEC_DESC'"
                  type="primary"
                  plain
                  title="按分区规范倒序的首项，不代表最后更新时间"
                  @click="selectPartition(partitionPage.suggestedPartition)"
                >
                  选择倒序首分区
                </el-button>
              </div>
            </div>
            <el-alert
              v-if="partitionError"
              type="warning"
              :title="partitionError"
              show-icon
              :closable="false"
            />
            <el-alert
              v-else-if="partitionPage?.truncated"
              type="warning"
              :title="partitionPage.metadataReason || '分区筛选结果已达到服务端扫描上限，结果可能不完整'"
              show-icon
              :closable="false"
            />
            <el-alert
              v-else-if="partitionPage?.metadataReason && partitionPage.metadataStatus !== 'COMPLETE'"
              type="info"
              :title="partitionPage.metadataReason"
              show-icon
              :closable="false"
            />
            <div v-if="partitionLoading" class="partition-loading" aria-live="polite">正在读取分区元数据…</div>
            <div v-else-if="partitionPage?.partitions.length" class="partition-list" role="listbox" aria-label="可用分区">
              <button
                v-for="partition in partitionPage.partitions"
                :key="partition"
                type="button"
                role="option"
                :aria-selected="selectedPartition === partition"
                :class="['partition-row', { selected: selectedPartition === partition }]"
                :title="partition"
                @click="selectPartition(partition)"
              >
                <el-icon><Collection /></el-icon>
                <code>{{ partition }}</code>
                <span>{{ selectedPartition === partition ? '已选择' : '选择' }}</span>
              </button>
            </div>
            <el-empty v-else description="当前筛选条件下没有分区" :image-size="62" />
            <div v-if="partitionPage" class="partition-pager">
              <span v-if="partitionPage.partitions.length">
                已加载 {{ partitionPage.offset + 1 }}–{{ partitionPage.offset + partitionPage.partitions.length }}
              </span>
              <span v-else>当前页没有分区</span>
              <div>
                <el-button
                  :disabled="partitionPage.offset <= 0 || partitionLoading"
                  @click="requestPartitions(Math.max(0, partitionPage.offset - partitionPage.limit))"
                >上一页</el-button>
                <el-button
                  :disabled="!partitionPage.hasMore || partitionLoading"
                  @click="requestPartitions(partitionPage.offset + partitionPage.limit)"
                >下一页</el-button>
              </div>
            </div>
          </template>
        </div>
      </el-tab-pane>

      <el-tab-pane name="columns">
        <template #label>
          <span class="tab-label"><el-icon><List /></el-icon>字段</span>
        </template>
        <div class="section-content">
          <el-alert
            v-if="sectionError('columns')"
            type="warning"
            :title="sectionError('columns')"
            show-icon
            :closable="false"
          />
          <template v-else>
            <div class="section-toolbar">
              <span class="section-caption">字段定义、可空性与中文注释</span>
              <el-input
                v-model="columnFilter"
                :prefix-icon="Search"
                placeholder="搜索字段、类型或注释"
                clearable
                class="filter-input"
              />
            </div>
            <div class="metadata-table-wrap">
              <table class="metadata-table">
                <thead>
                  <tr>
                    <th>#</th><th>字段</th><th>数据类型</th><th>长度</th>
                    <th>可空</th><th>默认值</th><th>属性</th><th>注释</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="(column, index) in filteredColumns" :key="column.name">
                    <td class="sequence">{{ index + 1 }}</td>
                    <td class="column-name" :title="column.name">
                      <el-icon v-if="column.primaryKey" class="key-icon"><Key /></el-icon>
                      {{ column.name }}
                    </td>
                    <td><code class="type-chip">{{ column.typeName }}</code></td>
                    <td>{{ sizeLabel(column) }}</td>
                    <td><span :class="['nullable-state', column.nullable ? 'yes' : 'no']">{{ column.nullable ? 'YES' : 'NO' }}</span></td>
                    <td><code>{{ column.defaultValue ?? '—' }}</code></td>
                    <td>
                      <span v-if="column.primaryKey" class="mini-tag primary">PK</span>
                      <span v-if="column.autoIncrement" class="mini-tag">AUTO</span>
                      <span v-if="!column.primaryKey && !column.autoIncrement">—</span>
                    </td>
                    <td class="remarks" :title="column.remarks || ''">{{ column.remarks || '—' }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </template>
        </div>
      </el-tab-pane>

      <el-tab-pane name="preview">
        <template #label>
          <span class="tab-label"><el-icon><DataAnalysis /></el-icon>数据预览</span>
        </template>
        <div class="section-content preview-section">
          <div v-if="partitionSelectionRequired" class="preview-gate">
            <el-icon><Lock /></el-icon>
            <h3>{{ inspection?.partitioned ? '必须先选择分区' : '分区状态未确认' }}</h3>
            <p>{{ inspection?.partitioned
              ? '为避免扫描整张数仓表，浏览器和服务端都会阻止无分区预览。'
              : (sectionError('partitions') || '为避免误扫整表，服务端已失败关闭。') }}</p>
            <el-button v-if="inspection?.partitioned" type="primary" @click="activeSection = 'partitions'">前往选择分区</el-button>
          </div>
          <template v-else>
            <div class="section-toolbar preview-toolbar">
              <div>
                <strong>有界数据预览</strong>
                <span v-if="selectedPartition" class="selected-partition" :title="selectedPartition">{{ selectedPartition }}</span>
              </div>
              <el-button
                type="primary"
                :loading="previewLoading"
                :disabled="previewLoading"
                @click="$emit('loadPreview')"
              >
                {{ preview ? '重新加载' : '加载前 100 行' }}
              </el-button>
            </div>
            <el-alert
              v-if="previewError || sectionError('preview')"
              type="warning"
              :title="previewError || sectionError('preview')"
              show-icon
              :closable="false"
            />
            <DataTable
              v-else-if="preview"
              :columns="preview.columns"
              :rows="preview.rows"
              :remarks="columnRemarks"
            />
            <el-empty v-else description="点击“加载前 100 行”后读取数据" :image-size="68" />
          </template>
        </div>
      </el-tab-pane>

      <el-tab-pane name="ddl">
        <template #label>
          <span class="tab-label"><el-icon><Document /></el-icon>DDL</span>
        </template>
        <div class="section-content ddl-section">
          <el-alert
            v-if="sectionError('ddl')"
            type="warning"
            :title="sectionError('ddl')"
            show-icon
            :closable="false"
          />
          <template v-else-if="inspection?.ddl">
            <div class="section-toolbar">
              <span class="section-caption">只读建表定义</span>
              <el-button :icon="CopyDocument" @click="copyDdl">复制 DDL</el-button>
            </div>
            <pre class="ddl-code">{{ inspection.ddl }}</pre>
          </template>
          <el-empty v-else description="当前驱动未返回 DDL" :image-size="68" />
        </div>
      </el-tab-pane>
    </el-tabs>
  </section>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import {
  Collection,
  CopyDocument,
  DataAnalysis,
  Document,
  Grid,
  InfoFilled,
  Key,
  Link,
  List,
  Lock,
  Position,
  Refresh,
  Search,
} from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import DataTable from '@/components/editor/DataTable.vue'
import type { EnterprisePartitionPage } from '@/api/ent'
import type { ColumnMetadata, TableInspection } from '@/types/metadata'

const props = defineProps<{
  inspection: TableInspection | null
  loading?: boolean
  error?: string | null
  partitionPage?: EnterprisePartitionPage | null
  partitionLoading?: boolean
  partitionError?: string | null
  partitionFilter?: string
  selectedPartition?: string | null
  previewLoading?: boolean
  previewError?: string | null
}>()

const emit = defineEmits<{
  refresh: []
  openSql: [sql: string]
  loadPartitions: [request: { filter: string; offset: number; limit: number }]
  selectPartition: [partition: string]
  loadPreview: []
}>()

const activeSection = ref('overview')
const columnFilter = ref('')
const localPartitionFilter = ref(props.partitionFilter || '')
const columns = computed(() => props.inspection?.columns ?? [])
const constraints = computed(() => props.inspection?.constraints ?? [])
const preview = computed(() => props.inspection?.preview ?? null)
const tableRemarks = computed(() =>
  props.inspection?.remarks || props.inspection?.tableComment || null,
)
const isMaxCompute = computed(() =>
  (props.inspection?.dbType || '').toLocaleUpperCase() === 'MAXCOMPUTE',
)
const partitionColumns = computed(() => props.inspection?.partitionColumns ?? [])
const partitionSelectionRequired = computed(() =>
  isMaxCompute.value && Boolean(props.inspection?.previewRequiresPartition)
  && (!props.inspection?.partitioned || !props.selectedPartition),
)
const partitionStateLabel = computed(() => {
  if (props.inspection?.partitioned) return '分区表'
  if (props.inspection?.previewRequiresPartition) return '分区状态不可用'
  return '非分区表'
})
const columnRemarks = computed<Record<string, string>>(() =>
  Object.fromEntries(
    columns.value
      .filter(column => Boolean(column.remarks?.trim()))
      .map(column => [column.name, column.remarks!.trim()]),
  ),
)
const qualifiedName = computed(() => {
  const table = props.inspection?.table || '未选择表'
  return props.inspection?.schema ? `${props.inspection.schema}.${table}` : table
})
const filteredColumns = computed(() => {
  const keyword = columnFilter.value.trim().toLocaleLowerCase()
  if (!keyword) return columns.value
  return columns.value.filter(column =>
    column.name.toLocaleLowerCase().includes(keyword)
    || column.typeName.toLocaleLowerCase().includes(keyword)
    || (column.remarks || '').toLocaleLowerCase().includes(keyword),
  )
})
const metadataStatusLabel = computed(() => {
  const status = (props.inspection?.remarksStatus || '').toLocaleUpperCase()
  if (tableRemarks.value || status === 'AVAILABLE') return '注释已读取'
  if (status === 'EMPTY') return '数据库中为空'
  if ((props.inspection?.metadataStatus || '').toLocaleUpperCase() === 'ERROR') return '读取失败'
  return '注释不可用'
})
const metadataStateClass = computed(() => {
  if (tableRemarks.value || (props.inspection?.remarksStatus || '').toLocaleUpperCase() === 'AVAILABLE') return 'available'
  return (props.inspection?.remarksStatus || '').toLocaleUpperCase() === 'EMPTY'
    ? 'empty' : 'unavailable'
})
const metadataReason = computed(() => {
  const status = (props.inspection?.remarksStatus || '').toLocaleUpperCase()
  if (status === 'EMPTY') return '数据库明确返回空注释。'
  return props.inspection?.metadataReason || '当前元数据链路未能提供表注释，请刷新后重试。'
})
const metadataSourceLabel = computed(() => ({
  TENANT_INFORMATION_SCHEMA: '租户级 INFORMATION_SCHEMA',
  PROJECT_INFORMATION_SCHEMA: 'Project INFORMATION_SCHEMA',
  MAXCOMPUTE_JAVA_SDK: 'MaxCompute Java SDK',
  SHOW_TABLES: 'SHOW TABLES 降级目录',
  DETAIL_METADATA: '单表元数据读取',
  CATALOG: '数据库目录元数据',
} as Record<string, string>)[props.inspection?.metadataSource || '']
  || props.inspection?.metadataSource || '来源不可用')

watch(
  () => `${props.inspection?.schema || ''}.${props.inspection?.table || ''}`,
  () => {
    activeSection.value = 'overview'
    columnFilter.value = ''
    localPartitionFilter.value = props.partitionFilter || ''
  },
)
watch(() => props.partitionFilter, value => {
  localPartitionFilter.value = value || ''
})

function requestPartitions(offset: number) {
  emit('loadPartitions', {
    filter: localPartitionFilter.value.trim(),
    offset,
    limit: props.partitionPage?.limit || 50,
  })
}

function selectPartition(partition: string) {
  emit('selectPartition', partition)
}

function sectionError(section: string): string {
  return props.inspection?.errors?.[section] || ''
}

function sizeLabel(column: ColumnMetadata): string {
  if (!column.columnSize || column.columnSize <= 0) return '—'
  return column.decimalDigits > 0
    ? `${column.columnSize},${column.decimalDigits}`
    : String(column.columnSize)
}

function constraintTypeLabel(type: string): string {
  return ({
    PRIMARY_KEY: '主键', FOREIGN_KEY: '外键', UNIQUE_INDEX: '唯一索引', INDEX: '普通索引',
  } as Record<string, string>)[type] || type
}

async function copyQualifiedName() {
  await copyText(qualifiedName.value, '完整表名已复制')
}

async function copyDdl() {
  if (props.inspection?.ddl) await copyText(props.inspection.ddl, 'DDL 已复制')
}

async function copyText(value: string, message: string) {
  try {
    await navigator.clipboard.writeText(value)
    ElMessage.success(message)
  } catch {
    ElMessage.error('复制失败')
  }
}

function emitOpenSql() {
  const sql = props.inspection?.previewSql || preview.value?.sql
  if (sql) emit('openSql', sql)
}
</script>

<style scoped>
.table-inspection {
  --inspection-accent: var(--color-brand);
  display: flex;
  height: 100%;
  min-height: 0;
  flex-direction: column;
  overflow: hidden;
  background: var(--color-background);
}
.inspection-header {
  display: flex;
  min-height: 88px;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  padding: 14px 20px;
  border-bottom: 1px solid var(--color-border);
  background: var(--color-panel-header);
}
.object-identity, .header-actions, .object-stats, .tab-label, .card-heading,
.section-toolbar, .partition-actions, .partition-pager, .constraint-card {
  display: flex;
  align-items: center;
}
.object-identity { min-width: 0; gap: 14px; }
.header-actions { flex-shrink: 0; gap: 8px; }
.identity-copy { min-width: 0; }
.eyebrow { margin-bottom: 4px; color: var(--inspection-accent); font-size: 10px; font-weight: 760; letter-spacing: .12em; }
.qualified-name { overflow: hidden; font: 650 18px/1.3 var(--font-mono); text-overflow: ellipsis; white-space: nowrap; }
.object-stats { gap: 8px; margin-top: 7px; color: var(--color-text-muted); font-size: 11px; }
.object-stats span + span::before { margin-right: 8px; opacity: .5; content: '·'; }
.object-type { color: var(--inspection-accent); }
.object-orbit { display: grid; width: 46px; height: 46px; flex: 0 0 46px; place-items: center; border: 1px solid var(--color-border); border-radius: 12px; background: var(--color-active); color: var(--inspection-accent); font-size: 22px; }
.global-error { width: auto; margin: 12px 16px 0; }
.inspection-tabs { display: flex; min-height: 0; flex: 1; flex-direction: column; }
.inspection-tabs :deep(.el-tabs__header) { margin: 0; padding: 0 18px; background: var(--color-panel-header); }
.inspection-tabs :deep(.el-tabs__nav-wrap::after) { height: 1px; background: var(--color-border); }
.inspection-tabs :deep(.el-tabs__active-bar) { background: var(--inspection-accent); }
.inspection-tabs :deep(.el-tabs__item.is-active) { color: var(--inspection-accent); }
.inspection-tabs :deep(.el-tabs__content) { min-height: 0; flex: 1; }
.inspection-tabs :deep(.el-tab-pane) { height: 100%; }
.tab-label { gap: 7px; }
.section-content { height: 100%; min-height: 0; overflow: auto; padding: 14px 18px 18px; }
.overview-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; }
.overview-card, .constraint-summary { padding: 14px; border: 1px solid var(--color-border); border-radius: 10px; background: var(--color-panel-header); }
.card-heading { justify-content: space-between; gap: 10px; margin-bottom: 10px; font-weight: 650; }
.table-comment, .metadata-unavailable { min-height: 45px; margin: 0 0 12px; line-height: 1.6; }
.metadata-unavailable { color: var(--color-text-muted); }
.metadata-state, .mini-tag { padding: 2px 7px; border: 1px solid var(--color-border); border-radius: 999px; font-size: 10px; font-weight: 650; }
.metadata-state.available, .mini-tag.primary { border-color: color-mix(in srgb, var(--color-success) 45%, var(--color-border)); color: var(--color-success); }
.metadata-state.empty { color: var(--color-text-muted); }
.metadata-state.unavailable { border-color: color-mix(in srgb, var(--color-warning) 50%, var(--color-border)); color: var(--color-warning); }
.metadata-trace { display: grid; grid-template-columns: 90px minmax(0, 1fr); gap: 7px 10px; margin: 0; font-size: 11px; }
.metadata-trace dt { color: var(--color-text-muted); }
.metadata-trace dd { min-width: 0; margin: 0; overflow-wrap: anywhere; }
.partition-summary-card p { color: var(--color-text-muted); font-size: 11px; line-height: 1.6; }
.partition-key-list { display: flex; flex-wrap: wrap; gap: 6px; margin: 10px 0 14px; }
.partition-key-list code { padding: 3px 7px; border-radius: 5px; background: var(--color-active); color: var(--color-brand); }
.constraint-summary { margin-top: 12px; }
.section-toolbar { min-height: 44px; justify-content: space-between; gap: 16px; margin-bottom: 10px; }
.section-caption, .section-count { display: block; color: var(--color-text-muted); font-size: 11px; }
.constraint-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(260px, 1fr)); gap: 8px; }
.constraint-card { gap: 10px; padding: 10px; border: 1px solid var(--color-border); border-radius: 8px; }
.constraint-card div { display: flex; min-width: 0; flex-direction: column; gap: 3px; }
.constraint-card strong, .constraint-card span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.constraint-card span { color: var(--color-text-muted); font-size: 10px; }
.partition-toolbar { align-items: flex-end; }
.partition-toolbar > div:first-child { min-width: 180px; }
.partition-actions { min-width: 0; gap: 8px; }
.partition-actions .el-input { width: min(420px, 38vw); }
.partition-loading { display: grid; min-height: 180px; place-items: center; color: var(--color-text-muted); }
.partition-list { display: grid; gap: 5px; }
.partition-row { display: grid; width: 100%; min-height: 42px; grid-template-columns: 24px minmax(0, 1fr) auto; align-items: center; gap: 8px; padding: 7px 10px; border: 1px solid var(--color-border); border-radius: 7px; background: transparent; color: inherit; text-align: left; cursor: pointer; }
.partition-row:hover { background: var(--color-hover); }
.partition-row:focus-visible { outline: 2px solid var(--color-brand); outline-offset: 2px; }
.partition-row.selected { border-color: var(--color-brand); background: var(--color-active); box-shadow: inset 3px 0 var(--color-brand); }
.partition-row code { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.partition-row span { color: var(--color-text-muted); font-size: 10px; }
.partition-pager { justify-content: space-between; margin-top: 12px; color: var(--color-text-muted); font-size: 10px; }
.partition-pager div { display: flex; gap: 8px; }
.filter-input { width: min(360px, 42vw); }
.metadata-table-wrap { overflow: auto; border: 1px solid var(--color-border); border-radius: 8px; }
.metadata-table { width: 100%; border-collapse: collapse; font-size: 11px; }
.metadata-table th { position: sticky; z-index: 1; top: 0; padding: 9px 10px; border-bottom: 1px solid var(--color-border); background: var(--color-panel-header); color: var(--color-text-muted); text-align: left; white-space: nowrap; }
.metadata-table td { max-width: 320px; padding: 8px 10px; border-bottom: 1px solid color-mix(in srgb, var(--color-border) 65%, transparent); }
.metadata-table tbody tr:hover { background: var(--color-hover); }
.column-name, .remarks { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.column-name { max-width: 280px; font-family: var(--font-mono); font-weight: 650; }
.sequence, .remarks { color: var(--color-text-muted); }
.key-icon { margin-right: 5px; color: var(--color-warning); }
.type-chip { color: var(--color-brand); }
.nullable-state { font: 650 9px var(--font-mono); }
.nullable-state.yes { color: var(--color-success); }
.nullable-state.no { color: var(--color-warning); }
.preview-section { display: flex; flex-direction: column; overflow: hidden; }
.preview-section :deep(.data-table-wrapper) { min-height: 0; flex: 1; }
.preview-toolbar { flex: 0 0 auto; }
.preview-toolbar > div { display: flex; min-width: 0; align-items: center; gap: 10px; }
.selected-partition { max-width: 46vw; overflow: hidden; padding: 3px 8px; border-radius: 5px; background: var(--color-active); color: var(--color-brand); font: 10px var(--font-mono); text-overflow: ellipsis; white-space: nowrap; }
.preview-gate { display: grid; min-height: 320px; place-items: center; align-content: center; gap: 10px; text-align: center; }
.preview-gate > .el-icon { color: var(--color-warning); font-size: 30px; }
.preview-gate h3, .preview-gate p { margin: 0; }
.preview-gate p { max-width: 520px; color: var(--color-text-muted); line-height: 1.6; }
.ddl-section { display: flex; flex-direction: column; }
.ddl-code { min-height: 0; flex: 1; margin: 0; padding: 14px; overflow: auto; border: 1px solid var(--color-border); border-radius: 8px; background: var(--color-code-bg, var(--color-panel-header)); font: 11px/1.7 var(--font-mono); white-space: pre; }
@media (max-width: 980px) {
  .overview-grid { grid-template-columns: 1fr 1fr; }
  .comment-card { grid-column: 1 / -1; }
  .inspection-header { align-items: flex-start; flex-direction: column; }
  .header-actions { width: 100%; justify-content: flex-end; }
  .partition-toolbar, .partition-actions { align-items: stretch; flex-direction: column; }
  .partition-actions .el-input { width: 100%; }
}
@media (max-width: 680px) {
  .overview-grid { grid-template-columns: 1fr; }
  .comment-card { grid-column: auto; }
  .inspection-header { padding: 12px; }
  .object-orbit { display: none; }
  .header-actions { overflow-x: auto; justify-content: flex-start; }
  .section-content { padding: 10px; }
  .selected-partition { max-width: 65vw; }
}
</style>
