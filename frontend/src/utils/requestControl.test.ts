import { describe, expect, it, vi } from 'vitest'
import { LatestRequestGate, runPromptedAction, safeRequestPath, shouldExpireSession, validateAppInfoProbe } from './requestControl'

describe('LatestRequestGate', () => {
    it('新请求开始后拒绝旧响应提交结果', () => {
        const gate = new LatestRequestGate()
        const first = gate.begin('tab-1')
        const second = gate.begin('tab-1')

        expect(gate.isCurrent('tab-1', first)).toBe(false)
        expect(gate.isCurrent('tab-1', second)).toBe(true)
    })

    it('取消后使在途响应失效', () => {
        const gate = new LatestRequestGate()
        const running = gate.begin('tab-1')
        gate.invalidate('tab-1')

        expect(gate.isCurrent('tab-1', running)).toBe(false)
    })
})

describe('shouldExpireSession', () => {
    it('业务请求 401 会清理会话', () => {
        expect(shouldExpireSession(401, '/ent/query')).toBe(true)
    })

    it('登录失败和非 401 不触发全局会话清理', () => {
        expect(shouldExpireSession(401, '/auth/login')).toBe(false)
        expect(shouldExpireSession(403, '/ent/query')).toBe(false)
    })
})

describe('runPromptedAction', () => {
    it('用户取消提示框时写操作调用次数为零', async () => {
        const writeAction = vi.fn(async () => undefined)
        const completed = await runPromptedAction(
            async () => { throw new Error('cancel') },
            writeAction,
        )

        expect(completed).toBe(false)
        expect(writeAction).not.toHaveBeenCalled()
    })

    it('确认后只调用一次写操作并传递意见', async () => {
        const writeAction = vi.fn(async () => undefined)
        const completed = await runPromptedAction(
            async () => ({ value: '同意' }),
            writeAction,
        )

        expect(completed).toBe(true)
        expect(writeAction).toHaveBeenCalledOnce()
        expect(writeAction).toHaveBeenCalledWith('同意')
    })
})


describe('safeRequestPath', () => {
    it('日志路径会移除查询参数、片段与控制字符', () => {
        expect(safeRequestPath('/history/search?keyword=secret-sql#result')).toBe('/history/search')
        expect(safeRequestPath('/api/ent/export\n?token=secret')).toBe('/api/ent/export')
    })

    it('非字符串和空值不进入日志', () => {
        expect(safeRequestPath(undefined)).toBeUndefined()
        expect(safeRequestPath({ url: '/secret' })).toBeUndefined()
    })
})


describe('validateAppInfoProbe', () => {
    it('接受认证语义一致的两个 edition', () => {
        expect(validateAppInfoProbe({ edition: 'personal', authRequired: false })).toBeNull()
        expect(validateAppInfoProbe({ edition: 'enterprise', authRequired: true })).toBeNull()
    })

    it('拒绝未知 edition 和缺失认证标志', () => {
        expect(validateAppInfoProbe({ edition: 'community', authRequired: false })).toContain('edition')
        expect(validateAppInfoProbe({ edition: 'enterprise' })).toContain('认证')
    })

    it('拒绝 edition 与认证要求矛盾的响应', () => {
        expect(validateAppInfoProbe({ edition: 'enterprise', authRequired: false })).toContain('不一致')
        expect(validateAppInfoProbe({ edition: 'personal', authRequired: true })).toContain('不一致')
    })
})
