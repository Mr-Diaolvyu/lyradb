/**
 * 后台查询任务类型定义（迭代二 E1）
 */

/** 后台任务状态 */
export type TaskStatus = 'RUNNING' | 'DONE' | 'ERROR' | 'CANCELLED'

export interface BackgroundTask {
    id: string
    connectionId: string
    connectionName: string
    sql: string
    status: TaskStatus
    submittedAt: string
    finishedAt: string | null
    elapsedMs: number
    totalRows: number
    errorMessage: string | null
    /** 结果是否仍可回看（服务端暂存区可能已淘汰） */
    resultAvailable: boolean
}

/** /ws/tasks 推送的状态变更消息 */
export interface TaskUpdateMessage {
    taskId: string
    status: TaskStatus
    totalRows: number
    elapsedMs: number
    message: string | null
}
