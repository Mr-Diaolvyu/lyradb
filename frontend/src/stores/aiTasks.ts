import { computed, ref, watch } from 'vue'
import { defineStore } from 'pinia'
import { ElMessage } from 'element-plus'
import { aiTasksApi, taskActive, type AiTask, type AiTaskRequest } from '@/api/aiTasks'
import { useAuthStore } from './auth'

export const useAiTasksStore = defineStore('aiTasks', () => {
  const auth = useAuthStore()
  const tasks = ref<AiTask[]>([])
  const error = ref('')
  const running = computed(() => tasks.value.filter(taskActive).length)
  let timer: ReturnType<typeof setTimeout> | undefined
  let generation = 0
  let mutations = 0
  let users = 0
  let inFlight: Promise<void> | undefined
  const scope = computed(() => `${auth.user?.username || ''}:${auth.user?.currentWorkspaceId || ''}`)

  async function refresh() {
    if (inFlight) return inFlight
    if (!auth.user) return
    const version = generation
    const revision = mutations
    const pending = (async () => {
      try {
        const result = await aiTasksApi.list()
        if (version !== generation || revision !== mutations) return
        for (const task of result) {
          const previous = tasks.value.find(item => item.id === task.id)
          if (previous && taskActive(previous) && !taskActive(task)) {
            if (task.state === 'FAILED') ElMessage.warning('AI 任务失败，可在 AI 数据助手中查看原因')
            else ElMessage.success('AI 任务已完成，可在 AI 数据助手中查看结果')
          }
        }
        tasks.value = result
        error.value = ''
      } catch (reason: unknown) {
        if (version === generation) error.value = reason instanceof Error ? reason.message : '任务状态暂时不可用'
      }
    })()
    inFlight = pending
    try { await pending } finally { if (inFlight === pending) inFlight = undefined }
  }

  async function poll() {
    const version = generation
    await refresh()
    if (users > 0 && version === generation) timer = setTimeout(poll, 3000)
  }
  function start() {
    users++
    if (users === 1) void poll()
  }
  function stop() {
    users = Math.max(0, users - 1)
    if (!users) { clearTimeout(timer); generation++; inFlight = undefined }
  }
  watch(scope, () => {
    generation++
    clearTimeout(timer)
    inFlight = undefined
    tasks.value = []
    error.value = ''
    if (users && auth.user) void poll()
  })
  async function submit(request: AiTaskRequest) {
    const version = generation
    const task = await aiTasksApi.submit(request)
    if (version === generation) {
      mutations++
      tasks.value = [task, ...tasks.value.filter(item => item.id !== task.id)]
    }
    return task
  }
  return { tasks, error, running, refresh, start, stop, submit }
})
