<template>
  <el-dialog
    v-model="visibleRef"
    :title="dialogTitle"
    width="680px"
    :close-on-click-modal="false"
    @closed="handleClosed"
  >
    <!-- 步骤 1: 选择数据库类型 -->
    <div v-if="step === 1" class="step-type-select">
      <div class="step-title">选择数据库类型</div>
      <div class="db-type-grid">
        <div
          v-for="db in connectionStore.dbTypes"
          :key="db.dbType"
          class="db-type-card"
          :class="{ selected: selectedDbType === db.dbType }"
          @click="selectDbType(db.dbType, db.displayName)"
        >
          <DatabaseIcon :db-type="db.dbType" :size="44" />
          <div class="db-type-name">{{ db.displayName }}</div>
          <div class="db-type-meta">{{ db.driverType.toUpperCase() }} · {{ db.defaultPort }}</div>
        </div>
      </div>
    </div>

    <!-- 步骤 2: 配置连接参数 -->
    <div v-else-if="step === 2" class="step-config">
      <!-- 返回类型选择 -->
      <div class="step-back" @click="step = 1">
        <el-icon><ArrowLeft /></el-icon>
        <span>重新选择类型</span>
      </div>

      <!-- 连接名称 -->
      <el-form ref="formRef" :model="formData" label-width="100px" size="default">
        <el-form-item label="连接名称" prop="name" :rules="[{ required: true, message: '请输入连接名称' }]">
          <el-input v-model="formData.name" placeholder="例如：生产环境MySQL" />
        </el-form-item>

        <!-- 驱动状态提示 + 下载进度 -->
        <div v-if="!driverReady || downloading" class="driver-download-panel">
          <div v-if="downloading" class="download-progress-area">
            <div class="progress-header">
              <DatabaseIcon :db-type="selectedDbType" :size="24" />
              <span class="progress-title">正在下载 {{ selectedDisplayName }} 驱动</span>
            </div>
            <div class="progress-bar-container">
              <div class="progress-bar-animated">
                <div class="progress-bar-fill"></div>
                <div class="progress-bar-fill"></div>
                <div class="progress-bar-fill"></div>
              </div>
            </div>
            <div class="progress-steps">
              <div class="progress-step" :class="{ active: downloadStep >= 1, done: downloadStep > 1 }">
                <el-icon v-if="downloadStep > 1" class="step-done-icon"><Check /></el-icon>
                <span v-else class="step-number">1</span>
                <span class="step-label">解析依赖</span>
              </div>
              <div class="progress-step" :class="{ active: downloadStep >= 2, done: downloadStep > 2 }">
                <el-icon v-if="downloadStep > 2" class="step-done-icon"><Check /></el-icon>
                <span v-else class="step-number">{{ downloadStep >= 2 ? '2' : '2' }}</span>
                <span class="step-label">下载JAR</span>
              </div>
              <div class="progress-step" :class="{ active: downloadStep >= 3, done: downloadStep > 3 }">
                <el-icon v-if="downloadStep > 3" class="step-done-icon"><Check /></el-icon>
                <span v-else class="step-number">3</span>
                <span class="step-label">加载驱动</span>
              </div>
            </div>
            <div class="progress-message">{{ downloadMessage || '正在处理...' }}</div>
          </div>
          <el-alert
            v-else
            :title="driverStatusMessage"
            type="warning"
            :closable="false"
            show-icon
          >
            <template #default>
              <div class="alert-content">
                <span>{{ driverStatusMessage }}</span>
                <el-button
                  size="small"
                  type="primary"
                  @click="downloadDriver"
                >
                  下载驱动
                </el-button>
              </div>
            </template>
          </el-alert>
        </div>

        <!-- 驱动已就绪提示 -->
        <div v-if="driverReady && !downloading && driverChecked" class="driver-ready-banner">
          <el-icon color="var(--color-success)"><CircleCheckFilled /></el-icon>
          <span>{{ selectedDisplayName }} 驱动已就绪</span>
        </div>

        <!-- 动态表单字段 -->
        <el-form-item
          v-for="field in formFields"
          :key="field.name"
          :label="field.label"
          :prop="`params.${field.name}`"
          :rules="field.required ? [{ required: true, message: `请输入${field.label}` }] : []"
        >
          <!-- 文本输入 -->
          <el-input
            v-if="field.type === 'text'"
            v-model="formData.params[field.name]"
            :placeholder="`请输入${field.label}`"
          />

          <!-- 密码输入 -->
          <el-input
            v-else-if="field.type === 'password'"
            v-model="formData.params[field.name]"
            type="password"
            show-password
            :placeholder="`请输入${field.label}`"
          />

          <!-- 数字输入 -->
          <el-input-number
            v-else-if="field.type === 'number'"
            v-model="formData.params[field.name]"
            :placeholder="`请输入${field.label}`"
            :controls="false"
            style="width: 100%"
          />

          <!-- 布尔开关 -->
          <el-switch
            v-else-if="field.type === 'boolean'"
            v-model="formData.params[field.name]"
          />

          <!-- 下拉选择 -->
          <el-select
            v-else-if="field.type === 'select'"
            v-model="formData.params[field.name]"
            :placeholder="`请选择${field.label}`"
            style="width: 100%"
          >
            <el-option
              v-for="opt in field.options"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
        </el-form-item>

        <!-- 高级选项 -->
        <el-collapse v-model="advancedCollapsed" class="advanced-collapse">
          <el-collapse-item title="高级选项" name="advanced">
            <el-form-item label="描述">
              <el-input
                v-model="formData.description"
                type="textarea"
                :rows="2"
                placeholder="可选，连接描述"
              />
            </el-form-item>
            <el-form-item label="标签">
              <el-input
                v-model="formData.tagsInput"
                placeholder="逗号分隔，如：生产,核心,MySQL"
              />
            </el-form-item>
            <el-form-item label="分组">
              <el-input v-model="formData.group" placeholder="可选，用于组织连接" />
            </el-form-item>
            <el-form-item label="收藏">
              <el-switch v-model="formData.favorite" />
              <span class="form-hint">收藏的连接将置顶显示</span>
            </el-form-item>
            <el-form-item label="自动连接">
              <el-switch v-model="formData.autoConnect" />
              <span class="form-hint">启动时自动建立连接</span>
            </el-form-item>
          </el-collapse-item>
        </el-collapse>
      </el-form>
    </div>

    <!-- 底部按钮 -->
    <template #footer>
      <div class="dialog-footer">
        <el-button @click="visibleRef = false">取消</el-button>
        <el-button
          v-if="step === 2"
          :loading="testing"
          :disabled="!driverReady"
          @click="handleTest"
        >
          测试连接
        </el-button>
        <el-button
          v-if="step === 2"
          type="primary"
          :loading="saving"
          :disabled="!driverReady"
          @click="handleSave"
        >
          保存
        </el-button>
      </div>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, computed, watch, reactive } from 'vue'
import { ArrowLeft, Check, CircleCheckFilled } from '@element-plus/icons-vue'
import { ElMessage, type FormInstance } from 'element-plus'
import { useConnectionStore } from '@/stores/connection'
import { driverApi } from '@/api/driver'
import type { FormField } from '@/types/driver'
import type { ConnectionDTO } from '@/types/connection'
import DatabaseIcon from '@/components/common/DatabaseIcon.vue'

// === Props & Emits ===
const props = defineProps<{
  visible: boolean
  editConnection?: ConnectionDTO | null
}>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
}>()

const connectionStore = useConnectionStore()

// === 状态 ===
const visibleRef = ref(props.visible)
const step = ref<1 | 2>(1)
const selectedDbType = ref('')
const selectedDisplayName = ref('')
const formRef = ref<FormInstance>()
const testing = ref(false)
const saving = ref(false)
const downloading = ref(false)
const driverReady = ref(true)
const driverChecked = ref(false)
const downloadStep = ref(0)
const downloadMessage = ref('')
const advancedCollapsed = ref<string[]>([])

const formData = reactive<{
  name: string
  params: Record<string, any>
  group: string
  autoConnect: boolean
  description: string
  tagsInput: string
  favorite: boolean
}>({
  name: '',
  params: {},
  group: '',
  autoConnect: false,
  description: '',
  tagsInput: '',
  favorite: false,
})

// === Watchers ===
watch(() => props.visible, (val) => {
  visibleRef.value = val
  if (val) {
    if (props.editConnection) {
      selectedDbType.value = props.editConnection.dbType
      selectedDisplayName.value = props.editConnection.displayName
      formData.name = props.editConnection.name
      formData.params = { ...props.editConnection.params }
      formData.group = props.editConnection.group || ''
      formData.autoConnect = props.editConnection.autoConnect || false
      formData.description = props.editConnection.description || ''
      formData.tagsInput = (props.editConnection.tags || []).join(', ')
      formData.favorite = props.editConnection.favorite || false
      step.value = 2
    } else {
      step.value = 1
    }
  }
})

watch(visibleRef, (val) => {
  emit('update:visible', val)
})

// === Computed ===
const dialogTitle = computed(() => props.editConnection ? '编辑连接' : '新建连接')

const formFields = computed<FormField[]>(() => {
  if (!selectedDbType.value) return []
  const driver = connectionStore.getDriverByType(selectedDbType.value)
  return driver?.connectionFormFields || []
})

const driverStatusMessage = computed(() => {
  if (!selectedDbType.value) return ''
  return `${selectedDisplayName.value} 驱动尚未下载，请先下载驱动才能测试连接`
})

// === Actions ===

function selectDbType(dbType: string, displayName: string) {
  selectedDbType.value = dbType
  selectedDisplayName.value = displayName
  formData.params = {}
  for (const field of formFields.value) {
    if (field.defaultValue !== undefined) {
      formData.params[field.name] = field.defaultValue
    } else if (field.type === 'boolean') {
      formData.params[field.name] = false
    } else if (field.type === 'number') {
      formData.params[field.name] = null
    } else {
      formData.params[field.name] = ''
    }
  }
  if (!formData.name) {
    formData.name = `${displayName}-${Date.now().toString(36)}`
  }
  checkDriverStatus(dbType)
  step.value = 2
}

async function checkDriverStatus(dbType: string) {
  driverChecked.value = false
  try {
    const status = await driverApi.getDriverStatus(dbType)
    driverReady.value = status.downloaded
    driverChecked.value = true
  } catch {
    driverReady.value = true
    driverChecked.value = true
  }
}

async function downloadDriver() {
  if (!selectedDbType.value) return
  downloading.value = true
  downloadStep.value = 1
  downloadMessage.value = '正在解析 Maven 依赖...'

  // 通过 WebSocket 接收实时下载进度
  const wsUrl = `${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/api/ws/drivers`
  let ws: WebSocket | null = null
  try {
    ws = new WebSocket(wsUrl)
  } catch (e) {
    // WS 不可用则回退到仅等待 HTTP 结果
  }

  const closeWs = () => {
    if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) {
      ws.close()
    }
  }

  if (ws) {
    ws.onmessage = (ev) => {
      try {
        const data = JSON.parse(ev.data)
        if (data.dbType && data.dbType.toUpperCase() !== selectedDbType.value.toUpperCase()) return
        if (typeof data.percent === 'number') {
          // 将百分比映射到三步进度
          if (data.percent >= 100) downloadStep.value = 3
          else if (data.percent >= 80) downloadStep.value = 3
          else if (data.percent >= 10) downloadStep.value = 2
        }
        if (data.message) downloadMessage.value = data.message
        if (data.status === 'done') {
          driverReady.value = true
          driverChecked.value = true
          setTimeout(() => { downloading.value = false }, 400)
          closeWs()
          ElMessage.success(data.message || `${selectedDisplayName.value} 驱动就绪`)
        } else if (data.status === 'error') {
          downloading.value = false
          driverReady.value = false
          closeWs()
          ElMessage.error(data.message || '驱动下载失败')
        }
      } catch { /* ignore parse error */ }
    }
    ws.onerror = () => { closeWs() }
  }

  try {
    const result = await driverApi.downloadDriver(selectedDbType.value)
    // 已存在的情形：后端直接返回 alreadyExists 并推送 done
    if (result.alreadyExists) {
      downloadStep.value = 3
      downloadMessage.value = result.message || '驱动已就绪'
      driverReady.value = true
      driverChecked.value = true
      setTimeout(() => { downloading.value = false }, 400)
      closeWs()
    } else if (result.async) {
      // 异步：进度由 WebSocket 推送，此处不结束 downloading
      downloadMessage.value = result.message || '开始下载...'
    } else if (!result.success) {
      downloading.value = false
      driverReady.value = false
      closeWs()
      ElMessage.error(result.message || '驱动下载失败')
    }
  } catch (e: any) {
    downloading.value = false
    closeWs()
    ElMessage.error(e.message || '驱动下载失败')
  }
}

async function handleTest() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  testing.value = true
  try {
    const result = await connectionStore.testConnection({
      dbType: selectedDbType.value,
      params: formData.params,
    })
    if (result.success) {
      ElMessage.success('连接测试成功')
    } else {
      ElMessage.error(`连接失败: ${result.message}`)
    }
  } catch (e: any) {
    ElMessage.error(e.message || '测试失败')
  } finally {
    testing.value = false
  }
}

async function handleSave() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  saving.value = true
  try {
    const dto: Partial<ConnectionDTO> = {
      name: formData.name,
      dbType: selectedDbType.value,
      displayName: selectedDisplayName.value,
      params: formData.params,
      group: formData.group || undefined,
      autoConnect: formData.autoConnect,
      description: formData.description || undefined,
      tags: formData.tagsInput
        ? formData.tagsInput.split(',').map((t: string) => t.trim()).filter(Boolean)
        : undefined,
      favorite: formData.favorite,
    }

    if (props.editConnection) {
      await connectionStore.updateConnection(props.editConnection.id, dto)
      ElMessage.success('连接已更新')
    } else {
      await connectionStore.createConnection(dto)
      ElMessage.success('连接已创建')
    }
    visibleRef.value = false
  } catch (e: any) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    saving.value = false
  }
}

function handleClosed() {
  step.value = 1
  selectedDbType.value = ''
  selectedDisplayName.value = ''
  formData.name = ''
  formData.params = {}
  formData.group = ''
  formData.autoConnect = false
  formData.description = ''
  formData.tagsInput = ''
  formData.favorite = false
  advancedCollapsed.value = []
  driverReady.value = true
  driverChecked.value = false
  downloading.value = false
  downloadStep.value = 0
  downloadMessage.value = ''
}
</script>

<style scoped>
.step-type-select {
  min-height: 400px;
}

.step-title {
  font-size: var(--text-subtitle, 16px);
  font-weight: 600;
  margin-bottom: var(--space-4, 16px);
  color: var(--color-foreground, #1a1a2e);
}

.db-type-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: var(--space-3, 12px);
}

.db-type-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: var(--space-3, 12px);
  border: 2px solid var(--color-border, #e0e0e0);
  border-radius: var(--radius-md, 8px);
  cursor: pointer;
  transition: all 0.2s ease;
  text-align: center;
}

.db-type-card:hover {
  border-color: var(--color-secondary, #059669);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.08);
  transform: translateY(-2px);
}

.db-type-card.selected {
  border-color: var(--color-secondary, #059669);
  background: var(--color-active, #e8f5e9);
}

.db-type-name {
  font-size: var(--text-body, 14px);
  font-weight: 600;
}

.db-type-meta {
  font-size: var(--text-caption, 11px);
  color: var(--color-muted, #999);
  margin-top: 2px;
}

.step-config {
  min-height: 300px;
}

.step-back {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: var(--text-caption, 12px);
  color: var(--color-secondary, #059669);
  cursor: pointer;
  margin-bottom: var(--space-3, 12px);
}

.step-back:hover {
  text-decoration: underline;
}

/* === 驱动下载面板 === */
.driver-download-panel {
  margin-bottom: 16px;
}

.alert-content {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

/* 下载进度动画区域 */
.download-progress-area {
  padding: 16px;
  background: var(--color-panel, #f8f9fa);
  border: 1px solid var(--color-border, #e0e0e0);
  border-radius: 8px;
}

.progress-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}

.progress-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--color-foreground, #1a1a2e);
}

/* 进度条动画 */
.progress-bar-container {
  height: 6px;
  background: var(--color-border, #e0e0e0);
  border-radius: 3px;
  overflow: hidden;
  margin-bottom: 16px;
}

.progress-bar-animated {
  display: flex;
  height: 100%;
  gap: 4px;
}

.progress-bar-fill {
  flex: 1;
  height: 100%;
  background: linear-gradient(90deg, var(--color-primary, #1E3A5F), var(--color-secondary, #059669));
  border-radius: 3px;
  animation: progress-slide 1.4s ease-in-out infinite;
}

.progress-bar-fill:nth-child(2) {
  animation-delay: 0.2s;
}

.progress-bar-fill:nth-child(3) {
  animation-delay: 0.4s;
}

@keyframes progress-slide {
  0% {
    transform: translateX(-100%);
    opacity: 0.3;
  }
  50% {
    opacity: 1;
  }
  100% {
    transform: translateX(100%);
    opacity: 0.3;
  }
}

/* 进度步骤 */
.progress-steps {
  display: flex;
  justify-content: space-between;
  margin-bottom: 8px;
}

.progress-step {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  color: var(--color-muted, #999);
  transition: color 0.3s ease;
}

.progress-step.active {
  color: var(--color-secondary, #059669);
}

.progress-step.done {
  color: var(--color-success, #10b981);
}

.step-number {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 18px;
  height: 18px;
  border-radius: 50%;
  border: 1.5px solid currentColor;
  font-size: 10px;
  font-weight: 600;
}

.progress-step.active .step-number {
  background: var(--color-secondary, #059669);
  color: #fff;
  border-color: var(--color-secondary, #059669);
}

.step-done-icon {
  font-size: 18px;
}

.step-label {
  font-weight: 500;
}

.progress-message {
  font-size: 12px;
  color: var(--color-muted, #999);
  font-family: 'JetBrains Mono', monospace;
  padding-top: 4px;
  border-top: 1px solid var(--color-border, #e0e0e0);
}

/* 驱动已就绪横幅 */
.driver-ready-banner {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 12px;
  margin-bottom: 16px;
  background: rgba(16, 185, 129, 0.08);
  border-radius: 6px;
  font-size: 13px;
  color: var(--color-foreground, #1a1a2e);
}

.advanced-collapse {
  margin-top: var(--space-3, 12px);
}

.form-hint {
  margin-left: var(--space-2, 8px);
  font-size: var(--text-caption, 11px);
  color: var(--color-muted, #999);
}

.dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: var(--space-2, 8px);
}
</style>
