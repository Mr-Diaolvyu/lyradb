export type AdminDataSourceTestPhase =
    | 'CHECKING_DRIVER'
    | 'DOWNLOADING_DRIVER'
    | 'TESTING_CONNECTION'

export const ADMIN_DATA_SOURCE_TEST_PHASE_LABELS:
Record<AdminDataSourceTestPhase, string> = {
    CHECKING_DRIVER: '正在检查驱动状态…',
    DOWNLOADING_DRIVER: '首次使用，正在下载并加载驱动…',
    TESTING_CONNECTION: '驱动已就绪，正在连接数据库…',
}

export interface AdminDataSourceTestTarget {
    id: string
    dbType: string
}

export interface DriverReadiness {
    downloaded: boolean
    downloading?: boolean
}

export interface AdminDataSourceTestResult {
    success: boolean
    message: string
    elapsedMs: number
}

export interface AdminDataSourceTestDependencies {
    getDriverStatus(dbType: string): Promise<DriverReadiness>
    downloadDriver(dbType: string): Promise<{
        success: boolean
        message?: string
    }>
    testDataSource(id: string): Promise<{
        success: boolean
        message: string
        elapsedMs?: number
    }>
    sleep?: (milliseconds: number) => Promise<void>
    now?: () => number
}

export interface AdminDataSourceTestOptions {
    driverDownloadTimeoutMs?: number
    driverStatusPollIntervalMs?: number
}

const DEFAULT_DRIVER_DOWNLOAD_TIMEOUT_MS = 180_000
const DEFAULT_DRIVER_STATUS_POLL_INTERVAL_MS = 1_000

function defaultSleep(milliseconds: number): Promise<void> {
    return new Promise(resolve => window.setTimeout(resolve, milliseconds))
}

export async function runAdminDataSourceTest(
    target: AdminDataSourceTestTarget,
    dependencies: AdminDataSourceTestDependencies,
    onPhase: (phase: AdminDataSourceTestPhase) => void,
    options: AdminDataSourceTestOptions = {},
): Promise<AdminDataSourceTestResult> {
    const now = dependencies.now ?? Date.now
    const sleep = dependencies.sleep ?? defaultSleep
    const timeoutMs = options.driverDownloadTimeoutMs
        ?? DEFAULT_DRIVER_DOWNLOAD_TIMEOUT_MS
    const pollIntervalMs = options.driverStatusPollIntervalMs
        ?? DEFAULT_DRIVER_STATUS_POLL_INTERVAL_MS
    const startedAt = now()

    onPhase('CHECKING_DRIVER')
    let driverStatus = await dependencies.getDriverStatus(target.dbType)
    if (!driverStatus.downloaded || driverStatus.downloading) {
        onPhase('DOWNLOADING_DRIVER')
        if (!driverStatus.downloading) {
            const accepted = await dependencies.downloadDriver(target.dbType)
            if (!accepted.success) {
                throw new Error(accepted.message || '驱动下载请求被拒绝')
            }
        }

        const deadline = now() + timeoutMs
        while (true) {
            if (now() >= deadline) {
                throw new Error('驱动准备超时，请检查服务网络或 Maven 仓库配置')
            }
            await sleep(pollIntervalMs)
            driverStatus = await dependencies.getDriverStatus(target.dbType)
            if (driverStatus.downloaded && !driverStatus.downloading) {
                break
            }
            if (!driverStatus.downloaded && !driverStatus.downloading) {
                throw new Error('驱动下载失败，请检查服务日志后重试')
            }
        }
    }

    onPhase('TESTING_CONNECTION')
    const result = await dependencies.testDataSource(target.id)
    return {
        ...result,
        elapsedMs: result.elapsedMs
            ?? Math.max(0, now() - startedAt),
    }
}
