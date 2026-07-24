/**
 * 驱动 API
 */
import apiClient from './index'
import type { DriverInfo, DriverStatus, DatabaseType } from '@/types/driver'

export const driverApi = {
    /** 获取所有驱动配置 */
    getAllDrivers(): Promise<DriverInfo[]> {
        return apiClient.get('/drivers')
    },

    /** 获取指定驱动配置 */
    getDriver(dbType: string): Promise<DriverInfo> {
        return apiClient.get(`/drivers/${dbType}`)
    },

    /** 检查驱动下载状态 */
    getDriverStatus(dbType: string): Promise<DriverStatus> {
        return apiClient.get(`/drivers/${dbType}/status`)
    },

    /** 预下载驱动（异步：进度走 /ws/drivers WebSocket） */
    downloadDriver(dbType: string): Promise<{
        success: boolean
        message: string
        dbType?: string
        displayName?: string
        elapsedMs?: number
        alreadyExists?: boolean
        async?: boolean
        mavenCoords?: string
    }> {
        return apiClient.post(`/drivers/${dbType}/download`)
    },

    /** 获取支持的数据库类型列表 */
    getSupportedTypes(): Promise<DatabaseType[]> {
        return apiClient.get('/drivers/types')
    },
}
