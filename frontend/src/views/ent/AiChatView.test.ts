// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { shallowMount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import AiChatView from './AiChatView.vue'
import { useAiTasksStore } from '@/stores/aiTasks'
import { entApi } from '@/api/ent'
import { aiTasksApi, type AiTask } from '@/api/aiTasks'
vi.mock('vue-router', () => ({ useRoute: () => ({ query: {} }), useRouter: () => ({ push: vi.fn() }) }))
vi.mock('@/api/ent', () => ({ entApi: { grantsMine: vi.fn(), aiProviders: vi.fn() } }))
vi.mock('@/api/aiTasks', () => ({ aiTasksApi: { submit: vi.fn(), list: vi.fn() }, taskActive: (t: AiTask) => ['QUEUED', 'RUNNING'].includes(t.state) }))
vi.mock('element-plus', () => ({ ElMessage: { error: vi.fn(), warning: vi.fn(), info: vi.fn(), success: vi.fn() } }))
const task: AiTask = { id: 'task-request-000001', kind: 'CHAT', grantedSourceName: 'source', message: '解释项目表', state: 'RUNNING', createdAt: Date.now(), finishedAt: 0, metadataAttached: false }
const wrappers: ReturnType<typeof shallowMount>[] = []
beforeEach(() => {
  setActivePinia(createPinia())
  vi.mocked(entApi.grantsMine).mockResolvedValue([{ id: 'g1', grantedSourceName: 'source', allowedSchemas: '*', allowedTables: '*' } as any])
  vi.mocked(entApi.aiProviders).mockResolvedValue([{} as any])
})
afterEach(() => { wrappers.splice(0).forEach(w => w.unmount()); vi.clearAllMocks() })
async function open() {
  const wrapper = shallowMount(AiChatView, { global: { renderStubDefaultSlot: true, stubs: {
    'el-button': { props: ['disabled', 'loading'], template: '<button :disabled="disabled || loading"><slot /></button>' },
    'el-input': { props: ['modelValue'], emits: ['update:modelValue'], template: '<textarea :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />' },
    'el-dialog': { props: ['modelValue'], template: '<section v-if="modelValue"><slot /><slot name="footer" /></section>' },
    'el-drawer': { props: ['modelValue'], template: '<aside v-if="modelValue"><slot /></aside>' },
  } } })
  wrappers.push(wrapper)
  await flushPromises()
  return wrapper
}
describe('AI 助手关键交互', () => {
  it('恢复运行中任务并展示真实等待状态和后台入口', async () => {
    useAiTasksStore().tasks = [task]
    const wrapper = await open()
    expect(wrapper.text()).toContain('等待 AI 返回')
    expect(wrapper.text()).toContain('后台执行中')
    useAiTasksStore().tasks = [{ ...task, state: 'SUCCEEDED', result: { explanation: '这是结构说明' } }]
    await flushPromises()
    expect(wrapper.text()).toContain('这是结构说明')
    expect(wrapper.text()).not.toContain('等待 AI 返回')
  })
  it('发送期间连续按快捷键只创建一个任务', async () => {
    let finish!: (task: AiTask) => void
    vi.mocked(aiTasksApi.submit).mockImplementation(() => new Promise(resolve => { finish = resolve }))
    const wrapper = await open()
    const textarea = wrapper.find('textarea')
    await textarea.setValue('解释项目表')
    await textarea.trigger('keydown', { key: 'Enter', ctrlKey: true })
    await textarea.trigger('keydown', { key: 'Enter', ctrlKey: true })
    expect(aiTasksApi.submit).toHaveBeenCalledTimes(1)
    finish(task); await flushPromises()
    expect(wrapper.text()).toContain('后台继续')
  })
  it('没有有效范围不能采集，授权通配符不会作为 Schema 选项', async () => {
    const wrapper = await open()
    await wrapper.findAll('button').find(b => b.text() === '添加表结构')!.trigger('click')
    expect(wrapper.findAll('button').find(b => b.text() === '采集表结构')!.attributes('disabled')).toBeDefined()
    expect(wrapper.findAll('el-option-stub').some(option => option.attributes('value') === '*')).toBe(false)
  })
})
