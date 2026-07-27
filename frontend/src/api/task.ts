/**
 * 后台查询任务 API（迭代二 E1）
 */
import apiClient from './index'
import type { BackgroundTask } from '@/types/task'
import type { QueryResult } from '@/types/metadata'

export const taskApi = {
    /** 提交后台查询任务 */
    submit(params: {
        connectionId: string
        connectionName?: string
        sql: string
        defaultDatabase?: string
        force?: boolean
    }): Promise<BackgroundTask> {
        return apiClient.post('/tasks', params)
    },

    /** 任务列表（按提交时间倒序） */
    list(): Promise<BackgroundTask[]> {
        return apiClient.get('/tasks')
    },

    /** 回取任务结果（DONE 且未被暂存区淘汰时可用） */
    getResult(taskId: string): Promise<QueryResult> {
        return apiClient.get(`/tasks/${taskId}/result`)
    },

    /** 取消运行中任务 */
    cancel(taskId: string): Promise<{ cancelled: boolean }> {
        return apiClient.post(`/tasks/${taskId}/cancel`)
    },

    /** 删除终态任务记录 */
    remove(taskId: string): Promise<{ success: boolean }> {
        return apiClient.delete(`/tasks/${taskId}`)
    },
}
