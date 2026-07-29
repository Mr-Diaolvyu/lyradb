// @vitest-environment jsdom
import { beforeEach, describe, expect, it } from 'vitest'
import {
    buildDesktopWebSocketUrl,
    captureDesktopSessionProof,
    readDesktopSessionProof,
} from './desktopAccess'

const PROOF = 'A'.repeat(43)

describe('桌面会话证明', () => {
    beforeEach(() => {
        sessionStorage.clear()
        localStorage.clear()
        history.replaceState(null, '', '/')
    })

    it('从 bootstrap fragment 捕获证明并立即清理地址栏', () => {
        history.replaceState(null, '', `/?source=tray#desktop_token=${PROOF}`)

        captureDesktopSessionProof()

        expect(readDesktopSessionProof()).toBe(PROOF)
        expect(location.pathname).toBe('/')
        expect(location.search).toBe('?source=tray')
        expect(location.hash).toBe('')
        expect(sessionStorage.length).toBe(1)
        expect(localStorage.length).toBe(0)
    })

    it('拒绝格式错误或重复的证明并清除旧值', () => {
        history.replaceState(null, '', `/#desktop_token=${PROOF}`)
        captureDesktopSessionProof()
        history.replaceState(
            null,
            '',
            `/#desktop_token=bad&desktop_token=${PROOF}`,
        )

        captureDesktopSessionProof()

        expect(readDesktopSessionProof()).toBeNull()
        expect(location.hash).toBe('')
    })

    it('不改写普通 Hash 路由', () => {
        history.replaceState(null, '', '/#/login')

        captureDesktopSessionProof()

        expect(location.hash).toBe('#/login')
        expect(readDesktopSessionProof()).toBeNull()
    })

    it('仅在桌面证明存在时给 WebSocket 添加 query proof', () => {
        const normalUrl = new URL(buildDesktopWebSocketUrl('/api/ws/tasks'))
        expect(normalUrl.protocol).toBe('ws:')
        expect(normalUrl.pathname).toBe('/api/ws/tasks')
        expect(normalUrl.search).toBe('')

        history.replaceState(null, '', `/#desktop_token=${PROOF}`)
        captureDesktopSessionProof()
        const desktopUrl = new URL(buildDesktopWebSocketUrl('/api/ws/drivers'))

        expect(desktopUrl.searchParams.get('desktop_token')).toBe(PROOF)
        expect(localStorage.length).toBe(0)
    })
})
