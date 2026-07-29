/**
 * 维护“仅最新请求可提交结果”的代次门。
 * 取消请求会推进代次，使无法真正中止的旧网络响应也不能覆盖新状态。
 */
export class LatestRequestGate {
    private readonly versions = new Map<string, number>()

    begin(key: string): number {
        const version = (this.versions.get(key) ?? 0) + 1
        this.versions.set(key, version)
        return version
    }

    isCurrent(key: string, version: number): boolean {
        return this.versions.get(key) === version
    }

    invalidate(key: string): void {
        this.versions.set(key, (this.versions.get(key) ?? 0) + 1)
    }
}

/** 仅真实业务请求的 401 才触发会话清理，登录与 CSRF 初始化自行呈现错误。 */
export function shouldExpireSession(status: number | undefined, url: string | undefined): boolean {
    if (status !== 401) return false
    const path = url || ''
    return !path.includes('/auth/login') && !path.includes('/auth/csrf')
}

/**
 * 仅当提示框得到确认时执行写操作。
 * Element Plus 的取消以 rejected Promise 表达，此处统一转为 false。
 */
export async function runPromptedAction(
    prompt: () => Promise<{ value: string }>,
    action: (value: string) => Promise<unknown>,
): Promise<boolean> {
    let value: string
    try {
        value = (await prompt()).value
    } catch {
        return false
    }
    await action(value)
    return true
}


/** 移除会破坏单行日志结构的 ASCII 控制字符。 */
export function stripLogControlCharacters(value: string): string {
    return Array.from(value)
        .filter(character => {
            const code = character.charCodeAt(0)
            return code >= 32 && code !== 127
        })
        .join('')
}

/** 将 Axios URL 收敛为无查询参数、无片段与控制字符的日志路径。 */
export function safeRequestPath(value: unknown): string | undefined {
    if (typeof value !== 'string' || !value.trim()) return undefined
    const withoutControlCharacters = stripLogControlCharacters(value).trim()
    if (!withoutControlCharacters) return undefined
    try {
        return new URL(withoutControlCharacters, 'https://lyradb.invalid').pathname.slice(0, 256)
    } catch {
        const path = withoutControlCharacters.split(/[?#]/, 1)[0].slice(0, 256)
        return path || undefined
    }
}


/**
 * 校验公开 edition 探测响应。认证语义与 edition 不一致时必须失败关闭，
 * 不能依赖 TypeScript 声明把异常 JSON 当作个人免认证模式。
 */
export function validateAppInfoProbe(value: unknown): string | null {
    if (!value || typeof value !== 'object') return '应用信息响应格式无效'
    const probe = value as Record<string, unknown>
    if (probe.edition !== 'personal' && probe.edition !== 'enterprise') {
        return '服务端返回了不支持的 edition'
    }
    if (typeof probe.authRequired !== 'boolean') {
        return '服务端未返回有效的认证要求'
    }
    const expectedAuth = probe.edition === 'enterprise'
    if (probe.authRequired !== expectedAuth) {
        return '服务端 edition 与认证要求不一致'
    }
    return null
}
