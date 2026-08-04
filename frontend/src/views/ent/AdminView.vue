<template>
  <div class="page">
    <div class="page-title"><h2>管理</h2><span class="page-sub">数据源 · 授权 · 用户 · 模型与智库治理（仅管理员）</span></div>

    <el-tabs v-model="tab" @tab-change="load">
      <!-- 数据源 -->
      <el-tab-pane label="数据源" name="ds">
        <div class="bar">
          <el-button type="primary" :icon="Plus" @click="dsCreate.visible = true">注册数据源</el-button>
          <el-button :icon="Download" :disabled="!selectedDataSources.length" @click="openExport">申请导出所选连接</el-button>
          <el-button :icon="Upload" @click="openImportFile">导入连接</el-button>
          <el-button :icon="Download" @click="downloadImportTemplate">下载 Excel 模板</el-button>
          <input ref="importFileInput" class="sr-only" type="file" accept=".json,.lyradb,.xlsx" aria-label="选择连接导入文件" @change="onImportFile" />
          <span v-if="selectedDataSources.length" class="selection-count">已选择 {{ selectedDataSources.length }} 项</span>
        </div>
        <el-table :data="dataSources" border size="small" empty-text="无" @selection-change="onDataSourceSelection">
          <el-table-column type="selection" width="44" />
          <el-table-column prop="displayName" label="名称" width="160" />
          <el-table-column prop="dbType" label="类型" width="120" />
          <el-table-column label="参数（已掩码）" show-overflow-tooltip>
            <template #default="{ row }">{{ summaryParams(row.params) }}</template>
          </el-table-column>
          <el-table-column prop="createdAt" label="创建时间" width="160"><template #default="{ row }">{{ fmt(row.createdAt) }}</template></el-table-column>
          <el-table-column label="操作" width="180">
            <template #default="{ row }">
              <el-button size="small" @click="testDs(row.id)">测试</el-button>
              <el-button size="small" type="danger" @click="delDs(row.id)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- 授权 -->
      <el-tab-pane label="授权" name="grants">
        <div class="bar"><el-button type="primary" :icon="Plus" @click="grantCreate.visible = true">分配授权</el-button></div>
        <el-table :data="grants" border size="small" empty-text="无">
          <el-table-column prop="grantedSourceName" label="逻辑名" width="160" />
          <el-table-column prop="dataSourceId" label="真实数据源" width="200" show-overflow-tooltip />
          <el-table-column prop="userId" label="用户ID" width="200" show-overflow-tooltip />
          <el-table-column prop="sqlCapability" label="能力" width="100" />
          <el-table-column prop="allowedTables" label="允许表" show-overflow-tooltip />
          <el-table-column label="操作" width="100"><template #default="{ row }"><el-button size="small" type="danger" @click="delGrant(row.id)">删除</el-button></template></el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- 用户 -->
      <el-tab-pane label="用户" name="users">
        <div class="bar"><el-button type="primary" :icon="Plus" @click="userCreate.visible = true">新建用户</el-button></div>
        <el-table :data="users" border size="small" empty-text="无">
          <el-table-column prop="username" label="用户名" width="140" />
          <el-table-column prop="displayName" label="显示名" width="140" />
          <el-table-column prop="email" label="邮箱" />
          <el-table-column label="角色" width="280"><template #default="{ row }"><el-tag v-for="r in row.roles" :key="r" size="small" style="margin-right:4px">{{ r }}</el-tag></template></el-table-column>
          <el-table-column prop="enabled" label="状态" width="80"><template #default="{ row }">{{ row.enabled ? '启用' : '禁用' }}</template></el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- AI Provider -->
      <el-tab-pane label="模型与 AI" name="ai">
        <div class="bar"><el-button type="primary" :icon="Plus" @click="aiCreate.visible = true">配置 Provider</el-button></div>
        <el-table :data="aiProviders" border size="small" empty-text="未配置">
          <el-table-column prop="displayName" label="名称" width="140" />
          <el-table-column prop="providerKey" label="类型" width="100" />
          <el-table-column label="部署" width="100">
            <template #default="{ row }">
              <el-tag size="small" :type="row.deploymentMode === 'PRIVATE' ? 'warning' : 'info'">{{ row.deploymentMode }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="baseUrl" label="Base URL" show-overflow-tooltip />
          <el-table-column prop="model" label="模型" width="160" />
          <el-table-column label="KEY" width="100"><template #default="{ row }">{{ row.apiKey ? '已配置' : '空' }}</template></el-table-column>
          <el-table-column label="默认" width="80"><template #default="{ row }">{{ row.isDefault ? '是' : '' }}</template></el-table-column>
          <el-table-column label="操作" width="200">
            <template #default="{ row }">
              <el-button size="small" :disabled="row.isDefault" @click="setDefaultAi(row.id)">设默认</el-button>
              <el-button size="small" type="danger" @click="delAi(row.id)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- 脱敏 -->
      <el-tab-pane label="脱敏" name="masking">
        <div class="bar"><el-button type="primary" :icon="Plus" @click="maskCreate.visible = true">新建脱敏规则</el-button></div>
        <el-table :data="maskRules" border size="small" empty-text="无">
          <el-table-column label="数据源" width="180" show-overflow-tooltip>
            <template #default="{ row }">{{ dsName(row.dataSourceId) }}</template>
          </el-table-column>
          <el-table-column prop="tablePattern" label="表匹配" width="140"><template #default="{ row }">{{ row.tablePattern || '不限' }}</template></el-table-column>
          <el-table-column prop="columnPattern" label="列匹配" show-overflow-tooltip />
          <el-table-column prop="maskType" label="方式" width="90"><template #default="{ row }">{{ maskTypeLabel(row.maskType) }}</template></el-table-column>
          <el-table-column prop="remark" label="说明" width="160" show-overflow-tooltip />
          <el-table-column label="启用" width="80">
            <template #default="{ row }"><el-switch :model-value="row.enabled" size="small" @change="toggleMask(row)" /></template>
          </el-table-column>
          <el-table-column label="操作" width="100"><template #default="{ row }"><el-button size="small" type="danger" @click="delMask(row.id)">删除</el-button></template></el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <!-- 数据源创建 -->
    <el-dialog v-model="dsCreate.visible" title="注册数据源" width="520">
      <el-form label-width="100px">
        <el-form-item label="数据库类型">
          <el-select v-model="dsCreate.form.dbType" style="width:100%" @change="onDbTypeChange">
            <el-option v-for="t in dbTypes" :key="t.dbType" :label="t.displayName" :value="t.dbType" />
          </el-select>
        </el-form-item>
        <el-form-item label="显示名"><el-input v-model="dsCreate.form.displayName" /></el-form-item>
        <el-form-item label="host"><el-input v-model="dsCreate.form.params.host" /></el-form-item>
        <el-form-item label="port"><el-input v-model="dsCreate.form.params.port" /></el-form-item>
        <el-form-item label="username"><el-input v-model="dsCreate.form.params.username" /></el-form-item>
        <el-form-item label="password"><el-input v-model="dsCreate.form.params.password" type="password" show-password /></el-form-item>
        <el-form-item label="database"><el-input v-model="dsCreate.form.params.database" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="dsCreate.visible=false">取消</el-button><el-button type="primary" :loading="dsCreate.busy" @click="createDs">保存</el-button></template>
    </el-dialog>

    <!-- 授权创建 -->
    <el-dialog v-model="grantCreate.visible" title="分配授权" width="520">
      <el-form label-width="100px">
        <el-form-item label="数据源">
          <el-select v-model="grantCreate.form.dataSourceId" style="width:100%">
            <el-option v-for="d in dataSources" :key="d.id" :label="`${d.displayName} (${d.dbType})`" :value="d.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="授予用户">
          <el-select v-model="grantCreate.form.userId" filterable style="width:100%">
            <el-option v-for="u in users" :key="u.id" :label="u.username" :value="u.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="逻辑名"><el-input v-model="grantCreate.form.grantedSourceName" /></el-form-item>
        <el-form-item label="允许 Schema" required>
          <el-input
            v-model="grantCreate.form.allowedSchemas"
            placeholder="如 sales、reporting；逗号分隔"
          />
        </el-form-item>
        <el-form-item label="允许表（完整限定名）" required><el-input v-model="grantCreate.form.allowedTables" placeholder="如 sales.orders、prod.sales.orders_*；逗号分隔，空=不授权" /></el-form-item>
        <el-form-item label="黑名单表"><el-input v-model="grantCreate.form.blockedTables" placeholder="如 sales.user_secret" /></el-form-item>
        <el-form-item label="能力">
          <el-radio-group v-model="grantCreate.form.sqlCapability">
            <el-radio value="READ_ONLY">只读</el-radio><el-radio value="DML_ALLOWED">可写</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer><el-button @click="grantCreate.visible=false">取消</el-button><el-button type="primary" :loading="grantCreate.busy" @click="createGrant">分配</el-button></template>
    </el-dialog>

    <!-- 用户创建 -->
    <el-dialog v-model="userCreate.visible" title="新建用户" width="460">
      <el-form label-width="100px">
        <el-form-item label="用户名"><el-input v-model="userCreate.form.username" /></el-form-item>
        <el-form-item label="密码"><el-input v-model="userCreate.form.password" type="password" show-password /></el-form-item>
        <el-form-item label="显示名"><el-input v-model="userCreate.form.displayName" /></el-form-item>
        <el-form-item label="角色">
          <el-checkbox-group v-model="userCreate.form.roles">
            <el-checkbox value="PLATFORM_ADMIN">平台管理员</el-checkbox>
            <el-checkbox value="DS_ADMIN">数据源管理员</el-checkbox>
            <el-checkbox value="STEWARD">数据管家</el-checkbox>
            <el-checkbox value="ANALYST">分析师</el-checkbox>
            <el-checkbox value="AUDITOR">审计员</el-checkbox>
          </el-checkbox-group>
        </el-form-item>
      </el-form>
      <template #footer><el-button @click="userCreate.visible=false">取消</el-button><el-button type="primary" :loading="userCreate.busy" @click="createUser">创建</el-button></template>
    </el-dialog>

    <!-- AI Provider 配置 -->
    <el-dialog v-model="aiCreate.visible" title="配置 AI Provider" width="520">
      <el-form label-width="100px">
        <el-form-item label="类型">
          <el-select v-model="aiCreate.form.providerKey" style="width:100%" @change="onAiPreset">
            <el-option v-for="(p, k) in aiPresets" :key="k" :label="p.displayName" :value="k as string" />
          </el-select>
        </el-form-item>
        <el-form-item label="显示名"><el-input v-model="aiCreate.form.displayName" /></el-form-item>
        <el-form-item label="部署模式">
          <el-radio-group v-model="aiCreate.form.deploymentMode">
            <el-radio value="PUBLIC">公网 Provider</el-radio>
            <el-radio value="PRIVATE">私有模型</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-alert
          v-if="aiCreate.form.deploymentMode === 'PRIVATE'"
          title="私有模型必须由管理员在服务端显式启用，并把目标主机加入精确白名单；通配符不会生效。"
          type="warning"
          :closable="false"
          show-icon
          class="provider-alert"
        />
        <el-form-item label="Base URL"><el-input v-model="aiCreate.form.baseUrl" /></el-form-item>
        <el-form-item label="模型"><el-input v-model="aiCreate.form.model" /></el-form-item>
        <el-form-item :label="aiCreate.form.deploymentMode === 'PRIVATE' ? 'API KEY（可选）' : 'API KEY'">
          <el-input v-model="aiCreate.form.apiKey" type="password" show-password />
        </el-form-item>
        <el-form-item label="设为默认"><el-switch v-model="aiCreate.form.isDefault" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="aiCreate.visible=false">取消</el-button><el-button type="primary" :loading="aiCreate.busy" @click="createAi">保存</el-button></template>
    </el-dialog>

    <!-- 脱敏规则创建 -->
    <el-dialog v-model="maskCreate.visible" title="新建脱敏规则" width="520">
      <el-form label-width="100px">
        <el-form-item label="数据源">
          <el-select v-model="maskCreate.form.dataSourceId" clearable placeholder="空 = 全局规则" style="width:100%">
            <el-option v-for="d in dataSources" :key="d.id" :label="`${d.displayName} (${d.dbType})`" :value="d.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="表匹配"><el-input v-model="maskCreate.form.tablePattern" placeholder="user_*，空 = 不限表" /></el-form-item>
        <el-form-item label="列匹配"><el-input v-model="maskCreate.form.columnPattern" placeholder="phone, id_card, *_secret，逗号分隔" /></el-form-item>
        <el-form-item label="脱敏方式">
          <el-radio-group v-model="maskCreate.form.maskType">
            <el-radio value="FULL">全遮盖</el-radio><el-radio value="PARTIAL">保留首尾</el-radio><el-radio value="HASH">摘要</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="说明"><el-input v-model="maskCreate.form.remark" placeholder="如：手机号脱敏" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="maskCreate.visible=false">取消</el-button><el-button type="primary" :loading="maskCreate.busy" @click="createMask">保存</el-button></template>
    </el-dialog>
    <el-dialog v-model="connectionExport.visible" title="申请导出连接配置" width="600" destroy-on-close>
      <el-form label-width="110px">
        <el-form-item label="已选连接">
          <div class="selected-list">{{ selectedDataSourceNames.join('、') }}</div>
        </el-form-item>
        <el-form-item label="凭据处理">
          <el-radio-group v-model="connectionExport.mode">
            <el-radio value="OMIT">不导出凭据</el-radio>
            <el-radio value="PASSWORD_ENCRYPTED">使用密码加密</el-radio>
            <el-radio value="PLAINTEXT">明文导出</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-alert
          v-if="connectionExport.mode === 'PASSWORD_ENCRYPTED'"
          title="审批通过并下载时再输入加密密码；密码不会写入审批单或保存到服务器。"
          type="info"
          :closable="false"
          show-icon
        />
        <el-alert
          v-if="connectionExport.mode === 'PLAINTEXT'"
          title="高风险：导出文件会包含可直接使用的数据库凭据。审批人与下载人都会看到风险提示。"
          type="error"
          :closable="false"
          show-icon
        />
        <el-form-item v-if="connectionExport.mode === 'PLAINTEXT'" class="risk-confirm">
          <el-checkbox v-model="connectionExport.plaintextConfirmed">我了解并确认导出明文凭据</el-checkbox>
        </el-form-item>
        <el-form-item label="申请理由">
          <el-input v-model="connectionExport.reason" type="textarea" :rows="3" maxlength="500" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="connectionExport.visible = false">取消</el-button>
        <el-button type="primary" :loading="connectionExport.busy" @click="submitConnectionExport">提交审批</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="connectionImport.visible" title="导入连接配置" width="860" destroy-on-close @closed="resetConnectionImport">
      <div class="import-toolbar">
        <span class="file-name">{{ connectionImport.file?.name || '尚未选择文件' }}</span>
        <el-input
          v-model="connectionImport.password"
          type="password"
          show-password
          clearable
          :disabled="isExcelImport"
          placeholder="加密 JSON 包密码（Excel 模板无需填写）"
          aria-label="连接包密码"
        />
        <el-button type="primary" :loading="connectionImport.previewing" :disabled="!connectionImport.file" @click="previewConnectionImport">解析并预览</el-button>
        <el-button v-if="connectionImport.previewing" @click="cancelImportPreview">取消解析</el-button>
      </div>
      <el-alert v-if="connectionImport.error" :title="connectionImport.error" type="error" :closable="false" show-icon />
      <template v-if="connectionImport.preview">
        <el-alert
          :title="`凭据模式：${credentialPolicyLabel(connectionImport.preview.credentialPolicy)}${connectionImport.preview.riskCode ? ` · 风险标识：${connectionImport.preview.riskCode}` : ''}`"
          type="info"
          :closable="false"
          show-icon
        />

        <el-table :data="connectionImport.preview.items" border size="small" max-height="420" empty-text="导入文件中没有可导入连接">
          <el-table-column prop="displayName" label="连接名" min-width="150" />
          <el-table-column prop="dbType" label="类型" width="100" />
          <el-table-column label="配置键" min-width="140" show-overflow-tooltip>
            <template #default="{ row }">{{ row.parameterKeys.join('、') || '—' }}</template>
          </el-table-column>
          <el-table-column label="凭据" min-width="130" show-overflow-tooltip>
            <template #default="{ row }">{{ row.credentialsIncluded ? (row.credentialKeys.join('、') || '已包含') : '未包含' }}</template>
          </el-table-column>
          <el-table-column label="冲突" min-width="150">
            <template #default="{ row }">
              <el-tag :type="row.conflict ? 'warning' : 'success'" size="small">{{ row.conflict ? (row.existingDisplayName ? `已存在：${row.existingDisplayName}` : '存在同名连接') : '无' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="处理方式" width="150">
            <template #default="{ row }">
              <el-select v-model="importChoices[row.entryKey].action" size="small" aria-label="冲突处理方式">
                <el-option label="跳过" value="SKIP" />
                <el-option label="重命名导入" value="RENAME" />
                <el-option :label="row.conflict ? '覆盖' : '直接导入'" value="OVERWRITE" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="导入名称" min-width="170">
            <template #default="{ row }">
              <el-input
                v-if="importChoices[row.entryKey].action === 'RENAME'"
                v-model="importChoices[row.entryKey].renameTo"
                size="small"
                maxlength="120"
                aria-label="重命名后的连接名"
              />
              <span v-else>—</span>
            </template>
          </el-table-column>
        </el-table>
      </template>
      <template #footer>
        <el-button @click="connectionImport.visible = false">取消</el-button>
        <el-button type="primary" :loading="connectionImport.applying" :disabled="!connectionImport.preview" @click="applyConnectionImport">确认导入</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Download, Plus, Upload } from '@element-plus/icons-vue'
import {
  entApi,
  type AdminDataSource,
  type AdminGrant,
  type ConnectionImportPreview,
  type CredentialExportMode,
  type ImportConflictAction,
  type MaskingRule,
} from '@/api/ent'
import {
  buildImportDecisions,
  CONNECTION_IMPORT_TEMPLATE_FILE_NAME,
  isExcelConnectionImportFile,
  isSupportedConnectionImportFile,
} from '@/utils/enterpriseTransfer'
import { saveBlob } from '@/utils/download'
import { driverApi } from '@/api/driver'
import type { DatabaseType } from '@/types/driver'
import type { AiProviderDeploymentMode, AiProviderView } from '@/types/ai'

const tab = ref('ds')
const dataSources = ref<AdminDataSource[]>([])
const grants = ref<AdminGrant[]>([])
const users = ref<any[]>([])
const dbTypes = ref<DatabaseType[]>([])
const aiProviders = ref<AiProviderView[]>([])
const aiPresets = ref<Record<string, any>>({})
const maskRules = ref<MaskingRule[]>([])
const selectedDataSources = ref<AdminDataSource[]>([])
const importFileInput = ref<HTMLInputElement | null>(null)
const connectionExport = reactive({
  visible: false,
  busy: false,
  mode: 'OMIT' as CredentialExportMode,
  plaintextConfirmed: false,
  reason: '',
})
const connectionImport = reactive({
  visible: false,
  file: null as File | null,
  password: '',
  preview: null as ConnectionImportPreview | null,
  previewing: false,
  applying: false,
  error: '',
})
const isExcelImport = computed(() =>
  isExcelConnectionImportFile(connectionImport.file?.name),
)
const importChoices = reactive<Record<string, { action: ImportConflictAction; renameTo: string }>>({})
let importPreviewController: AbortController | null = null

const dsCreate = reactive({ visible: false, busy: false, form: { dbType: '', displayName: '', params: { host: '', port: '', username: '', password: '', database: '' } } })
const grantCreate = reactive({ visible: false, busy: false, form: { dataSourceId: '', userId: '', grantedSourceName: '', allowedSchemas: '', allowedTables: '', blockedTables: '', sqlCapability: 'READ_ONLY' } })
const userCreate = reactive({ visible: false, busy: false, form: { username: '', password: '', displayName: '', roles: ['ANALYST'] } })
const aiCreate = reactive({
  visible: false, busy: false,
  form: { providerKey: 'deepseek', displayName: '', baseUrl: '', model: '', apiKey: '', isDefault: true, deploymentMode: 'PUBLIC' as AiProviderDeploymentMode },
})
const maskCreate = reactive({ visible: false, busy: false, form: { dataSourceId: '', tablePattern: '', columnPattern: '', maskType: 'PARTIAL', remark: '' } })

async function load() {
  try {
    if (tab.value === 'ds') dataSources.value = await entApi.adminDataSources()
    else if (tab.value === 'grants') grants.value = await entApi.adminGrants('')
    else if (tab.value === 'users') users.value = await entApi.adminUsers()
    else if (tab.value === 'ai') aiProviders.value = await entApi.adminAiProviders()
    else if (tab.value === 'masking') {
      maskRules.value = await entApi.adminMaskingRules()
      if (!dataSources.value.length) dataSources.value = await entApi.adminDataSources()
    }
  } catch {}
}
onMounted(async () => {
  dbTypes.value = await driverApi.getSupportedTypes()
  aiPresets.value = await entApi.aiPresets()
  load()
})

function onDbTypeChange() {
  const t = dbTypes.value.find(x => x.dbType === dsCreate.form.dbType)
  if (t) dsCreate.form.params.port = t.defaultPort
  if (!dsCreate.form.displayName) dsCreate.form.displayName = t?.displayName || ''
}

async function createDs() {
  dsCreate.busy = true
  try {
    await entApi.adminCreateDataSource({ dbType: dsCreate.form.dbType, displayName: dsCreate.form.displayName, params: dsCreate.form.params, description: '' })
    ElMessage.success('已保存'); dsCreate.visible = false; load()
  } catch (e: any) { ElMessage.error(e.message || '保存失败') }
  finally { dsCreate.busy = false }
}
async function testDs(id: string) {
  try { const r = await entApi.adminTestDataSource(id); ElMessage[r.success ? 'success' : 'error'](r.message) } catch (e: any) { ElMessage.error(e.message) }
}
async function delDs(id: string) {
  try { await ElMessageBox.confirm('删除该数据源？', '确认', { type: 'warning' }) }
  catch { return }
  await entApi.adminDeleteDataSource(id); ElMessage.success('已删除'); load()
}

async function createGrant() {
  if (!grantCreate.form.dataSourceId || !grantCreate.form.userId || !grantCreate.form.grantedSourceName) { ElMessage.warning('请补全'); return }
  if (!grantCreate.form.allowedSchemas.trim()) { ElMessage.warning('必须填写至少一个允许的 Schema'); return }
  if (!grantCreate.form.allowedTables.trim()) { ElMessage.warning('必须填写至少一个 schema.table 或 catalog.schema.table（可在表名段使用受控通配）；空值表示不授权任何表'); return }
  grantCreate.busy = true
  try {
    await entApi.adminCreateGrant(grantCreate.form)
    ElMessage.success('已分配'); grantCreate.visible = false; load()
  } catch (e: any) { ElMessage.error(e.message || '失败') }
  finally { grantCreate.busy = false }
}
async function delGrant(id: string) {
  await entApi.adminDeleteGrant(id); ElMessage.success('已删除'); load()
}

async function createUser() {
  if (!userCreate.form.username || !userCreate.form.password) { ElMessage.warning('用户名/密码必填'); return }
  userCreate.busy = true
  try {
    await entApi.adminCreateUser(userCreate.form)
    ElMessage.success('已创建'); userCreate.visible = false; load()
  } catch (e: any) { ElMessage.error(e.message || '失败') }
  finally { userCreate.busy = false }
}

function onAiPreset() {
  const p = aiPresets.value[aiCreate.form.providerKey]
  if (p) {
    aiCreate.form.displayName = p.displayName
    aiCreate.form.baseUrl = p.baseUrl
    aiCreate.form.model = p.model
  }
}
async function createAi() {
  if (!aiCreate.form.baseUrl) { ElMessage.warning('Base URL 必填'); return }
  if (aiCreate.form.deploymentMode === 'PUBLIC' && !aiCreate.form.apiKey) {
    ElMessage.warning('公网 Provider 的 API KEY 必填'); return
  }
  aiCreate.busy = true
  try {
    await entApi.adminCreateAiProvider(aiCreate.form)
    ElMessage.success('已保存'); aiCreate.visible = false; load()
  } catch (e: any) { ElMessage.error(e.message || '失败') }
  finally { aiCreate.busy = false }
}
async function setDefaultAi(id: string) {
  await entApi.adminSetDefaultAiProvider(id); ElMessage.success('已设默认'); load()
}
async function delAi(id: string) {
  try { await ElMessageBox.confirm('删除该 Provider？', '确认', { type: 'warning' }) } catch { return }
  await entApi.adminDeleteAiProvider(id); ElMessage.success('已删除'); load()
}

function dsName(id?: string) {
  if (!id) return '全局'
  return dataSources.value.find(d => d.id === id)?.displayName || id
}
function maskTypeLabel(t: string) {
  return t === 'FULL' ? '全遮盖' : t === 'HASH' ? '摘要' : '保留首尾'
}
async function createMask() {
  if (!maskCreate.form.columnPattern.trim()) { ElMessage.warning('列匹配必填'); return }
  maskCreate.busy = true
  try {
    await entApi.adminSaveMaskingRule({ ...maskCreate.form, dataSourceId: maskCreate.form.dataSourceId || undefined, enabled: true })
    ElMessage.success('已保存'); maskCreate.visible = false; load()
  } catch (e: any) { ElMessage.error(e.message || '保存失败') }
  finally { maskCreate.busy = false }
}
async function toggleMask(row: MaskingRule) {
  try {
    await entApi.adminSaveMaskingRule({ ...row, enabled: !row.enabled })
    load()
  } catch (e: any) { ElMessage.error(e.message || '操作失败') }
}
async function delMask(id: string) {
  try { await ElMessageBox.confirm('删除该脱敏规则？', '确认', { type: 'warning' }) } catch { return }
  await entApi.adminDeleteMaskingRule(id); ElMessage.success('已删除'); load()
}

function credentialPolicyLabel(policy: CredentialExportMode): string {
  if (policy === 'PLAINTEXT') return '明文凭据'
  if (policy === 'PASSWORD_ENCRYPTED') return '密码加密凭据'
  return '不含凭据'
}
function onDataSourceSelection(rows: AdminDataSource[]) {
  selectedDataSources.value = rows
}

const selectedDataSourceNames = computed(() => selectedDataSources.value.map(row => row.displayName))

function openExport() {
  connectionExport.mode = 'OMIT'
  connectionExport.plaintextConfirmed = false
  connectionExport.reason = ''
  connectionExport.visible = true
}

async function submitConnectionExport() {
  if (!selectedDataSources.value.length) {
    ElMessage.warning('请先选择需要导出的连接')
    return
  }
  if (connectionExport.mode === 'PLAINTEXT' && !connectionExport.plaintextConfirmed) {
    ElMessage.warning('请确认已了解明文凭据风险')
    return
  }
  if (connectionExport.mode === 'PLAINTEXT') {
    try {
      await ElMessageBox.confirm(
        '明文导出会把数据库凭据直接写入文件。提交后仍需审批，审批通过下载时还会再次确认。是否提交？',
        '再次确认明文导出风险',
        { type: 'error', confirmButtonText: '确认提交', cancelButtonText: '取消' },
      )
    } catch {
      return
    }
  }
  connectionExport.busy = true
  try {
    await entApi.adminRequestDataSourceExport({
      dataSourceIds: selectedDataSources.value.map(row => row.id),
      credentialMode: connectionExport.mode,
      plaintextRiskConfirmed: connectionExport.mode === 'PLAINTEXT' && connectionExport.plaintextConfirmed,
      reason: connectionExport.reason.trim() || undefined,
    })
    ElMessage.success('连接导出申请已提交，请前往审批中心查看进度')
    connectionExport.visible = false
  } catch (e: any) {
    ElMessage.error(e.message || '提交连接导出申请失败')
  } finally {
    connectionExport.busy = false
  }
}

async function downloadImportTemplate() {
  try {
    const blob = await entApi.adminDownloadDataSourceImportTemplate()
    await saveBlob(blob, CONNECTION_IMPORT_TEMPLATE_FILE_NAME)
    ElMessage.success('Excel 连接导入模板已下载')
  } catch (e: any) {
    ElMessage.error(e.message || '模板下载失败')
  }
}

function openImportFile() {
  importFileInput.value?.click()
}

function onImportFile(event: Event) {
  const inputElement = event.target as HTMLInputElement
  const file = inputElement.files?.[0]
  inputElement.value = ''
  if (!file) return
  if (!isSupportedConnectionImportFile(file.name)) {
    ElMessage.error('请选择 .xlsx 或 LyraDB JSON 连接文件')
    return
  }
  if (file.size > 10 * 1024 * 1024) {
    ElMessage.error('连接导入文件不得超过 10 MiB')
    return
  }
  resetConnectionImport()
  connectionImport.file = file
  connectionImport.visible = true
}

async function previewConnectionImport() {
  if (!connectionImport.file) return
  importPreviewController?.abort()
  const controller = new AbortController()
  importPreviewController = controller
  const file = connectionImport.file
  const password = connectionImport.password || undefined
  connectionImport.previewing = true
  connectionImport.error = ''
  try {
    const preview = await entApi.adminPreviewDataSourceImport(file, password, controller.signal)
    if (importPreviewController !== controller) return
    connectionImport.password = ''
    connectionImport.preview = preview
    for (const key of Object.keys(importChoices)) delete importChoices[key]
    for (const item of preview.items) {
      importChoices[item.entryKey] = {
        action: item.conflict ? 'SKIP' : 'OVERWRITE',
        renameTo: item.displayName,
      }
    }
  } catch (e: any) {
    if (importPreviewController === controller) {
      connectionImport.error = controller.signal.aborted ? '已取消解析' : (e.message || '连接导入文件解析失败')
    }
  } finally {
    if (importPreviewController === controller) {
      connectionImport.previewing = false
      importPreviewController = null
    }
  }
}

function cancelImportPreview() {
  importPreviewController?.abort()
}

async function applyConnectionImport() {
  if (!connectionImport.preview) return
  let decisions
  try {
    decisions = buildImportDecisions(connectionImport.preview.items, importChoices)
  } catch {
    ElMessage.warning('重命名导入时必须填写新连接名')
    return
  }
  connectionImport.applying = true
  try {
    const result = await entApi.adminApplyDataSourceImport(connectionImport.preview.previewToken, decisions)
    ElMessage.success(`导入完成：新增 ${result.created}，覆盖 ${result.overwritten}，跳过 ${result.skipped}`)
    connectionImport.visible = false
    await load()
  } catch (e: any) {
    connectionImport.error = e.message || '连接导入失败'
  } finally {
    connectionImport.applying = false
  }
}

function resetConnectionImport() {
  importPreviewController?.abort()
  importPreviewController = null
  connectionImport.file = null
  connectionImport.password = ''
  connectionImport.preview = null
  connectionImport.previewing = false
  connectionImport.applying = false
  connectionImport.error = ''
  for (const key of Object.keys(importChoices)) delete importChoices[key]
}
function summaryParams(p: any) {
  if (!p) return ''
  return Object.entries(p).map(([k, v]) => `${k}=${v}`).join(', ')
}
function fmt(d?: string) { return d ? new Date(d).toLocaleString() : '' }
</script>

<style scoped>
.page { max-width: 1200px; margin: 0 auto; }
.page-title { margin-bottom: 12px; }
.page-title h2 { font-size: 18px; margin: 0; }
.page-sub { font-size: 12px; color: var(--color-text-muted); }
.bar { margin-bottom: 10px; display: flex; flex-wrap: wrap; align-items: center; gap: 8px; }
.bar :deep(.el-button + .el-button) { margin-left: 0; }
.selection-count, .file-name { color: var(--color-text-muted); font-size: 12px; }
.selected-list { max-height: 96px; overflow: auto; }
.import-toolbar { display: grid; grid-template-columns: minmax(140px, 1fr) minmax(220px, 1.5fr) auto auto; gap: 8px; align-items: center; margin-bottom: 12px; }
.risk-confirm { margin-top: 12px; }
.sr-only { position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px; overflow: hidden; clip: rect(0, 0, 0, 0); white-space: nowrap; border: 0; }
@media (max-width: 768px) {
  .import-toolbar { grid-template-columns: 1fr; }
}
</style>
