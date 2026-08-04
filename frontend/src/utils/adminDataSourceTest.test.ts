import { describe, expect, it, vi } from 'vitest'
import {
    runAdminDataSourceTest,
    type AdminDataSourceTestPhase,
} from './adminDataSourceTest'

describe('runAdminDataSourceTest', () => {
    it('驱动已就绪时立即进入真实连接测试', async () => {
        const phases: AdminDataSourceTestPhase[] = []
        const downloadDriver = vi.fn()
        const testDataSource = vi.fn().mockResolvedValue({
            success: true,
            message: '连接成功',
            elapsedMs: 18,
        })

        const result = await runAdminDataSourceTest(
            { id: 'source-1', dbType: 'MYSQL' },
            {
                getDriverStatus: vi.fn().mockResolvedValue({
                    downloaded: true,
                    downloading: false,
                }),
                downloadDriver,
                testDataSource,
            },
            phase => phases.push(phase),
        )

        expect(phases).toEqual([
            'CHECKING_DRIVER',
            'TESTING_CONNECTION',
        ])
        expect(downloadDriver).not.toHaveBeenCalled()
        expect(testDataSource).toHaveBeenCalledWith('source-1')
        expect(result).toEqual({
            success: true,
            message: '连接成功',
            elapsedMs: 18,
        })
    })

    it('首次测试会等待异步驱动下载完成再连接', async () => {
        const phases: AdminDataSourceTestPhase[] = []
        const getDriverStatus = vi.fn()
            .mockResolvedValueOnce({
                downloaded: false,
                downloading: false,
            })
            .mockResolvedValueOnce({
                downloaded: true,
                downloading: true,
            })
            .mockResolvedValueOnce({
                downloaded: true,
                downloading: false,
            })
        const sleep = vi.fn().mockResolvedValue(undefined)
        const testDataSource = vi.fn().mockResolvedValue({
            success: false,
            message: '访问白名单未放行',
            elapsedMs: 35,
        })

        const result = await runAdminDataSourceTest(
            { id: 'source-2', dbType: 'MAXCOMPUTE' },
            {
                getDriverStatus,
                downloadDriver: vi.fn().mockResolvedValue({
                    success: true,
                    message: '开始下载',
                }),
                testDataSource,
                sleep,
            },
            phase => phases.push(phase),
            { driverStatusPollIntervalMs: 1 },
        )

        expect(phases).toEqual([
            'CHECKING_DRIVER',
            'DOWNLOADING_DRIVER',
            'TESTING_CONNECTION',
        ])
        expect(sleep).toHaveBeenCalledTimes(2)
        expect(testDataSource).toHaveBeenCalledWith('source-2')
        expect(result.success).toBe(false)
        expect(result.message).toBe('访问白名单未放行')
    })

    it('驱动下载被拒绝时不执行连接测试', async () => {
        const testDataSource = vi.fn()

        await expect(runAdminDataSourceTest(
            { id: 'source-3', dbType: 'ORACLE' },
            {
                getDriverStatus: vi.fn().mockResolvedValue({
                    downloaded: false,
                    downloading: false,
                }),
                downloadDriver: vi.fn().mockResolvedValue({
                    success: false,
                    message: '驱动下载队列已满，请稍后重试',
                }),
                testDataSource,
            },
            () => undefined,
        )).rejects.toThrow('驱动下载队列已满，请稍后重试')

        expect(testDataSource).not.toHaveBeenCalled()
    })
})
