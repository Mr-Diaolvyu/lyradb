// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useThemeStore } from './theme'

describe('企业版主题切换', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
    document.documentElement.setAttribute('data-theme', 'dark')
    document.documentElement.classList.add('dark')
    vi.stubGlobal('matchMedia', vi.fn(() => ({
      matches: true,
      addEventListener: vi.fn(),
    })))
  })

  afterEach(() => vi.unstubAllGlobals())

  it('从首屏已显示的系统深色主题第一次点击就切换到浅色', () => {
    const theme = useThemeStore()
    theme.initTheme()

    expect(theme.mode).toBe('system')
    expect(theme.isDark).toBe(true)
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark')

    theme.toggleTheme()

    expect(theme.isDark).toBe(false)
    expect(document.documentElement.getAttribute('data-theme')).toBe('light')
    expect(document.documentElement.classList.contains('dark')).toBe(false)
    expect(localStorage.getItem('theme')).toBe('light')
  })

  it('从已保存的深色主题第一次点击也切换到浅色', () => {
    localStorage.setItem('theme', 'dark')
    vi.stubGlobal('matchMedia', vi.fn(() => ({
      matches: false,
      addEventListener: vi.fn(),
    })))
    const theme = useThemeStore()
    theme.initTheme()

    expect(theme.mode).toBe('dark')
    expect(theme.isDark).toBe(true)

    theme.toggleTheme()

    expect(theme.isDark).toBe(false)
    expect(document.documentElement.getAttribute('data-theme')).toBe('light')
  })
})
