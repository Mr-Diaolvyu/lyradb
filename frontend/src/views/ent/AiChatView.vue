<template>
  <div class="page">
    <div class="page-title"><h2>AI 数据助手</h2><span class="page-sub">自然语言查数据（仅生成授权可见范围的 SQL，只读直接执行，DML 需审批）</span></div>

    <div class="bar">
      <el-select v-model="source" placeholder="选择数据源" style="width:280px">
        <el-option v-for="g in grants" :key="g.id" :label="g.grantedSourceName" :value="g.grantedSourceName" />
      </el-select>
      <el-tag v-if="providerReady === false" size="small" type="warning">未配置 AI Provider（去「管理-AI」配置）</el-tag>
    </div>

    <div class="chat-box">
      <div v-if="!messages.length" class="chat-empty">问点数据问题，例如「查询最近 7 天的订单量按城市分组」</div>
      <div v-for="(m, i) in messages" :key="i" :class="['msg', m.role]">
        <div class="msg-role">{{ m.role === 'user' ? '我' : 'AI' }}</div>
        <div class="msg-content">
          <div v-if="m.explanation" class="expl">{{ m.explanation }}</div>
          <pre v-if="m.sql" class="sql">{{ m.sql }}</pre>
          <div v-if="m.error" class="err">{{ m.error }}</div>
          <div v-if="m.needsApproval" class="warn">⚠ AI 生成的 DML 需审批：
            <el-button size="small" @click="goApprove(m.sql || '')">去申请</el-button>
          </div>
          <template v-if="m.result">
            <el-collapse style="margin-top:6px">
              <el-collapse-item :title="`结果 ${m.result.totalRows} 行 · ${m.result.elapsedMs}ms`">
                <DataTable :columns="m.result.columns" :rows="m.result.rows" />
              </el-collapse-item>
            </el-collapse>
          </template>
        </div>
      </div>
    </div>

    <div class="input-bar">
      <el-input v-model="input" type="textarea" :rows="2" placeholder="用自然语言描述你想查的数据…"
                @keydown.enter.ctrl="send" />
      <el-button type="primary" :loading="sending" :disabled="!source || !input.trim()" @click="send">发送</el-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import DataTable from '@/components/editor/DataTable.vue'
import { entApi, type LogicalGrant } from '@/api/ent'
import type { QueryResult } from '@/types/metadata'

const router = useRouter()
const grants = ref<LogicalGrant[]>([])
const source = ref('')
const input = ref('')
const sending = ref(false)
const providerReady = ref<boolean | null>(null)

interface Msg { role: string; explanation?: string; sql?: string; error?: string; result?: QueryResult; needsApproval?: boolean }
const messages = ref<Msg[]>([])

async function load() {
  grants.value = await entApi.grantsMine()
  if (grants.value.length) source.value = grants.value[0].grantedSourceName
  try {
    const ps = await entApi.aiProviders()
    providerReady.value = ps.length > 0
  } catch { providerReady.value = false }
}
onMounted(load)

async function send() {
  if (!source.value || !input.value.trim()) return
  const text = input.value
  input.value = ''
  messages.value.push({ role: 'user', explanation: text })
  sending.value = true
  try {
    const history = messages.value.slice(0, -1).map(m => ({ role: m.role, content: m.explanation || m.sql || '' }))
    const res = await entApi.aiChat(source.value, text, history)
    messages.value.push({
      role: 'assistant',
      explanation: res.explanation,
      sql: res.sql,
      error: res.error,
      result: res.result,
      needsApproval: res.needsApproval,
    })
  } catch (e: any) {
    messages.value.push({ role: 'assistant', error: e.message || 'AI 调用失败' })
  } finally {
    sending.value = false
  }
}

function goApprove(sql: string) {
  router.push({ name: 'approvals', query: { sql } })
}
</script>

<style scoped>
.page { max-width: 1000px; margin: 0 auto; display: flex; flex-direction: column; height: 100%; }
.page-title { margin-bottom: 10px; }
.page-title h2 { font-size: 18px; margin: 0; }
.page-sub { font-size: 12px; color: var(--color-text-muted, #999); }
.bar { display: flex; align-items: center; gap: 10px; margin-bottom: 8px; }
.chat-box { flex: 1; overflow-y: auto; border: 1px solid var(--color-border,#e4e7eb); border-radius: 8px; padding: 12px; background: var(--color-background,#f8fafc); }
.chat-empty { color: var(--color-text-muted,#999); text-align: center; padding: 30px; font-size: 13px; }
.msg { margin-bottom: 12px; }
.msg-role { font-size: 11px; color: var(--color-text-muted,#999); margin-bottom: 2px; }
.msg.user .msg-content { color: var(--color-foreground,#333); }
.msg.assistant .msg-content { background: var(--color-panel,#fff); border: 1px solid var(--color-border,#e4e7eb); border-radius: 6px; padding: 8px 10px; }
.expl { font-size: 13px; margin-bottom: 4px; }
.sql { background: var(--color-muted,#0f172a); color: #e2e8f0; padding: 8px; border-radius: 4px; font-size: 12px; overflow-x: auto; margin: 4px 0; }
.err { color: var(--color-destructive,#dc2626); font-size: 12px; }
.warn { color: var(--color-warning,#f59e0b); font-size: 12px; margin-top: 4px; }
.input-bar { display: flex; gap: 8px; align-items: flex-end; margin-top: 8px; }
</style>
