/**
 * 定时报表订阅 API（迭代二 PM2）
 */
import apiClient from './index'

export interface ReportSchedule {
    id?: string
    name: string
    connectionId: string
    connectionName?: string
    sql: string
    defaultDatabase?: string
    scheduleType: string
    runMinute: number
    runHour: number
    weekday: number
    webhookUrl: string
    enabled: boolean
    createdAt?: string
    lastRunAt?: string
    lastStatus?: string
}

export interface ReportRun {
    id: string
    scheduleId: string
    runAt: string
    success: boolean
    rowCount: number
    elapsedMs: number
    pushStatus?: string
    errorMessage?: string
}

export const reportApi = {
    /** 订阅列表 */
    list(): Promise<ReportSchedule[]> {
        return apiClient.get('/reports')
    },

    /** 新建/更新订阅（带 id 即更新） */
    save(body: Partial<ReportSchedule>): Promise<ReportSchedule> {
        return apiClient.post('/reports', body)
    },

    /** 删除订阅（连带执行记录） */
    remove(id: string): Promise<void> {
        return apiClient.delete(`/reports/${id}`)
    },

    /** 最近 20 次执行记录 */
    runs(id: string): Promise<ReportRun[]> {
        return apiClient.get(`/reports/${id}/runs`)
    },

    /** 立即执行一次 */
    trigger(id: string): Promise<ReportRun> {
        return apiClient.post(`/reports/${id}/trigger`)
    },
}
