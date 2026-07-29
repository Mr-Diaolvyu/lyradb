const DESKTOP_PROOF_FRAGMENT = 'desktop_token'
const DESKTOP_PROOF_HEADER = 'X-LyraDB-Desktop-Token'
const DESKTOP_PROOF_STORAGE_KEY = 'lyradb.desktop.session-proof.v1'
const SESSION_PROOF_PATTERN = /^[A-Za-z0-9_-]{43}$/

function isValidSessionProof(value: string | null): value is string {
    return value !== null && SESSION_PROOF_PATTERN.test(value)
}

function removeStoredProof(storage: Storage): void {
    try {
        storage.removeItem(DESKTOP_PROOF_STORAGE_KEY)
    } catch {
        // 浏览器禁用 Storage 时保持失败关闭。
    }
}

/**
 * 消费桌面 bootstrap 重定向放在 URL fragment 中的会话证明。
 *
 * 证明只写入当前标签页的 sessionStorage，并在读取后立即从地址栏移除；
 * 普通 Web/移动端 Hash 路由不会被改写。
 */
export function captureDesktopSessionProof(
    location: Location = window.location,
    history: History = window.history,
    storage: Storage = window.sessionStorage,
): void {
    const rawFragment = location.hash.startsWith('#')
        ? location.hash.slice(1)
        : location.hash
    const params = new URLSearchParams(rawFragment)
    const candidates = params.getAll(DESKTOP_PROOF_FRAGMENT)
    if (candidates.length === 0) {
        return
    }

    const proof = candidates.length === 1 ? candidates[0] : null
    if (isValidSessionProof(proof)) {
        try {
            storage.setItem(DESKTOP_PROOF_STORAGE_KEY, proof)
        } catch {
            removeStoredProof(storage)
        }
    } else {
        removeStoredProof(storage)
    }

    history.replaceState(
        history.state,
        '',
        `${location.pathname}${location.search}`,
    )
}

/** 读取当前标签页的桌面会话证明；被篡改的值会立即删除。 */
export function readDesktopSessionProof(
    storage: Storage = window.sessionStorage,
): string | null {
    try {
        const proof = storage.getItem(DESKTOP_PROOF_STORAGE_KEY)
        if (isValidSessionProof(proof)) {
            return proof
        }
        removeStoredProof(storage)
    } catch {
        return null
    }
    return null
}

/** 为同源 WebSocket 构造 URL，并在桌面模式下附加会话证明。 */
export function buildDesktopWebSocketUrl(
    path: `/api/ws/${string}`,
    location: Location = window.location,
    storage: Storage = window.sessionStorage,
): string {
    const url = new URL(path, location.origin)
    url.protocol = location.protocol === 'https:' ? 'wss:' : 'ws:'
    const proof = readDesktopSessionProof(storage)
    if (proof) {
        url.searchParams.set(DESKTOP_PROOF_FRAGMENT, proof)
    }
    return url.toString()
}

export { DESKTOP_PROOF_HEADER }
