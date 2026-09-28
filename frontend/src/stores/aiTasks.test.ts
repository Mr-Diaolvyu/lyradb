// @vitest-environment jsdom
import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { nextTick } from 'vue'
import { useAiTasksStore } from './aiTasks'
import { useAuthStore } from './auth'
import { aiTasksApi, type AiTask } from '@/api/aiTasks'
vi.mock('@/api/aiTasks', () => ({ aiTasksApi: { list: vi.fn(), submit: vi.fn() }, taskActive: (task: AiTask) => ['QUEUED', 'RUNNING'].includes(task.state) }))
vi.mock('element-plus', () => ({ ElMessage: { warning: vi.fn(), success: vi.fn() } }))
const task: AiTask = { id: 'task-request-000001', kind: 'CHAT', grantedSourceName: 'source', state: 'RUNNING', createdAt: 1, finishedAt: 0, metadataAttached: false }
beforeEach(() => {
  setActivePinia(createPinia())
  useAuthStore().user = { username: 'alice', roles: [], currentWorkspaceId: 'w1', workspaces: [], canApprove: false, effectiveApproverRole: 'STEWARD', canViewWorkspaceAudit: false }
})
afterEach(() => vi.clearAllMocks())
describe('AI 后台任务恢复与隔离', () => {
  it('新页面会话从服务端恢复正在执行的任务与结果', async () => {
    vi.mocked(aiTasksApi.list).mockResolvedValue([task])
    const store = useAiTasksStore()
    await store.refresh()
    expect(store.running).toBe(1)
    vi.mocked(aiTasksApi.list).mockResolvedValue([{ ...task, state: 'SUCCEEDED', result: { explanation: '完成' } }])
    await store.refresh()
    expect(store.running).toBe(0)
    expect(store.tasks[0].result).toEqual({ explanation: '完成' })
  })
  it('切换空间后丢弃旧空间尚未返回的结果', async () => {
    let resolve!: (value: AiTask[]) => void
    vi.mocked(aiTasksApi.list).mockImplementation(() => new Promise(r => { resolve = r }))
    const store = useAiTasksStore()
    const pending = store.refresh()
    useAuthStore().user!.currentWorkspaceId = 'w2'
    await nextTick()
    resolve([task]); await pending
    expect(store.tasks).toEqual([])
  })
  it('状态刷新失败保留任务，避免误认为取消或重新执行', async () => {
    const store = useAiTasksStore()
    store.tasks = [task]
    vi.mocked(aiTasksApi.list).mockRejectedValue(new Error('断网'))
    await store.refresh()
    expect(store.tasks).toEqual([task])
    expect(store.error).toBe('断网')
  })
})
