/**
 * 后台任务 Store（迭代二 E1）
 * 管理后台查询任务列表、/ws/tasks 状态订阅与完成通知
 */
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { ElNotification } from 'element-plus'
import { taskApi } from '@/api/task'
import type { BackgroundTask, TaskUpdateMessage } from '@/types/task'
import type { QueryResult } from '@/types/metadata'

export const useTaskStore = defineStore('tasks', () => {
    // === State ===
    const tasks = ref<BackgroundTask[]>([])
    const panelVisible = ref(false)
    /** 未查看的已完成任务数（面板徽标） */
    const unreadCount = ref(0)

    let ws: WebSocket | null = null

    // === Getters ===
    const runningCount = computed(() => tasks.value.filter(t => t.status === 'RUNNING').length)

    // === Actions ===

    /** 拉取任务列表 */
    async function refresh() {
        try {
            tasks.value = await taskApi.list()
        } catch (e) {
            console.warn('拉取后台任务列表失败:', e)
        }
    }

    /** 建立 /ws/tasks 订阅（幂等） */
    function connectWs() {
        if (ws) return
        const wsUrl = `${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/api/ws/tasks`
        try {
            ws = new WebSocket(wsUrl)
        } catch {
            return
        }
        ws.onmessage = (ev) => {
            try {
                const msg: TaskUpdateMessage = JSON.parse(ev.data)
                onTaskUpdate(msg)
            } catch {
                // 忽略非 JSON 消息
            }
        }
        ws.onclose = () => {
            ws = null
        }
    }

    function onTaskUpdate(msg: TaskUpdateMessage) {
        const task = tasks.value.find(t => t.id === msg.taskId)
        if (task) {
            task.status = msg.status
            task.totalRows = msg.totalRows
            task.elapsedMs = msg.elapsedMs
            task.errorMessage = msg.message
            if (msg.status === 'DONE') task.resultAvailable = true
        } else {
            refresh()
        }
        if (msg.status === 'DONE') {
            unreadCount.value++
            ElNotification({
                title: '后台查询完成',
                message: `共 ${msg.totalRows} 行，耗时 ${msg.elapsedMs}ms，点击任务面板回看结果`,
                type: 'success',
                duration: 5000,
            })
        } else if (msg.status === 'ERROR') {
            unreadCount.value++
            ElNotification({
                title: '后台查询失败',
                message: msg.message || '未知错误',
                type: 'error',
                duration: 8000,
            })
        }
    }

    /** 提交后台查询任务 */
    async function submit(params: {
        connectionId: string
        connectionName?: string
        sql: string
        defaultDatabase?: string
        force?: boolean
    }): Promise<BackgroundTask> {
        const task = await taskApi.submit(params)
        tasks.value.unshift(task)
        connectWs()
        return task
    }

    /** 回取任务结果 */
    function loadResult(taskId: string): Promise<QueryResult> {
        return taskApi.getResult(taskId)
    }

    /** 取消运行中任务 */
    async function cancel(taskId: string) {
        await taskApi.cancel(taskId)
        await refresh()
    }

    /** 删除终态任务记录 */
    async function remove(taskId: string) {
        await taskApi.remove(taskId)
        tasks.value = tasks.value.filter(t => t.id !== taskId)
    }

    /** 打开任务面板（清空未读徽标） */
    function openPanel() {
        panelVisible.value = true
        unreadCount.value = 0
        refresh()
        connectWs()
    }

    return {
        tasks,
        panelVisible,
        unreadCount,
        runningCount,
        refresh,
        connectWs,
        submit,
        loadResult,
        cancel,
        remove,
        openPanel,
    }
})
