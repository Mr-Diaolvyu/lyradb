<template>
  <div class="page">
    <div class="page-title"><h2>管理</h2><span class="page-sub">数据源托管 · 授权分配 · 用户管理（仅管理员）</span></div>

    <el-tabs v-model="tab" @tab-change="load">
      <!-- 数据源 -->
      <el-tab-pane label="数据源" name="ds">
        <div class="bar"><el-button type="primary" :icon="Plus" @click="dsCreate.visible = true">注册数据源</el-button></div>
        <el-table :data="dataSources" border size="small" empty-text="无">
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
      <el-tab-pane label="AI" name="ai">
        <div class="bar"><el-button type="primary" :icon="Plus" @click="aiCreate.visible = true">配置 Provider</el-button></div>
        <el-table :data="aiProviders" border size="small" empty-text="未配置">
          <el-table-column prop="displayName" label="名称" width="140" />
          <el-table-column prop="providerKey" label="类型" width="100" />
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
        <el-form-item label="允许表"><el-input v-model="grantCreate.form.allowedTables" placeholder="orders_*，逗号分隔，空=全部" /></el-form-item>
        <el-form-item label="黑名单表"><el-input v-model="grantCreate.form.blockedTables" placeholder="user_secret" /></el-form-item>
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
        <el-form-item label="Base URL"><el-input v-model="aiCreate.form.baseUrl" /></el-form-item>
        <el-form-item label="模型"><el-input v-model="aiCreate.form.model" /></el-form-item>
        <el-form-item label="API KEY"><el-input v-model="aiCreate.form.apiKey" type="password" show-password /></el-form-item>
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
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { entApi, type AdminDataSource, type AdminGrant, type MaskingRule } from '@/api/ent'
import { driverApi } from '@/api/driver'
import type { DatabaseType } from '@/types/driver'

const tab = ref('ds')
const dataSources = ref<AdminDataSource[]>([])
const grants = ref<AdminGrant[]>([])
const users = ref<any[]>([])
const dbTypes = ref<DatabaseType[]>([])
const aiProviders = ref<any[]>([])
const aiPresets = ref<Record<string, any>>({})
const maskRules = ref<MaskingRule[]>([])

const dsCreate = reactive({ visible: false, busy: false, form: { dbType: '', displayName: '', params: { host: '', port: '', username: '', password: '', database: '' } } })
const grantCreate = reactive({ visible: false, busy: false, form: { dataSourceId: '', userId: '', grantedSourceName: '', allowedTables: '', blockedTables: '', sqlCapability: 'READ_ONLY' } })
const userCreate = reactive({ visible: false, busy: false, form: { username: '', password: '', displayName: '', roles: ['ANALYST'] } })
const aiCreate = reactive({ visible: false, busy: false, form: { providerKey: 'deepseek', displayName: '', baseUrl: '', model: '', apiKey: '', isDefault: true } })
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
  if (!aiCreate.form.apiKey || !aiCreate.form.baseUrl) { ElMessage.warning('Base URL 与 API KEY 必填'); return }
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
.page-sub { font-size: 12px; color: var(--color-text-muted, #999); }
.bar { margin-bottom: 10px; }
</style>
