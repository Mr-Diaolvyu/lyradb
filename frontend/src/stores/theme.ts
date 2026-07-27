/**
 * 主题管理 Store —— 外观首选项中心
 * 迭代三 V1/V5/V6：主题三态（亮/暗/跟随系统）、强调色预设、数据行密度
 */
import { defineStore } from 'pinia'
import { ref } from 'vue'
import { VxeUI } from 'vxe-pc-ui'

export type ThemeMode = 'light' | 'dark' | 'system'
export type AccentPreset = 'navy' | 'emerald' | 'amber' | 'violet'
export type Density = 'comfortable' | 'compact'

export const useThemeStore = defineStore('theme', () => {
    const mode = ref<ThemeMode>('light')
    /** 当前实际是否为暗色（system 模式下由系统偏好解析而来） */
    const isDark = ref(false)
    const accent = ref<AccentPreset>('navy')
    const density = ref<Density>('comfortable')

    const mq = window.matchMedia('(prefers-color-scheme: dark)')

    function applyTheme() {
        isDark.value = mode.value === 'dark' || (mode.value === 'system' && mq.matches)
        const el = document.documentElement
        el.setAttribute('data-theme', isDark.value ? 'dark' : 'light')
        // Element Plus 暗色变量依赖 html.dark 类（dark/css-vars.css）
        el.classList.toggle('dark', isDark.value)
        // vxe-table 主题联动
        VxeUI.setTheme(isDark.value ? 'dark' : 'light')
    }

    function setMode(m: ThemeMode) {
        mode.value = m
        localStorage.setItem('theme', m)
        applyTheme()
    }

    /** 兼容旧调用（命令面板/头部按钮）：在亮暗间切换 */
    function toggleTheme() {
        setMode(isDark.value ? 'light' : 'dark')
    }

    function setAccent(a: AccentPreset) {
        accent.value = a
        localStorage.setItem('accent', a)
        document.documentElement.setAttribute('data-accent', a)
    }

    function setDensity(d: Density) {
        density.value = d
        localStorage.setItem('density', d)
        document.documentElement.setAttribute('data-density', d)
    }

    function initTheme() {
        const savedTheme = localStorage.getItem('theme')
        if (savedTheme === 'light' || savedTheme === 'dark' || savedTheme === 'system') {
            mode.value = savedTheme
        }
        const savedAccent = localStorage.getItem('accent') as AccentPreset | null
        if (savedAccent && ['navy', 'emerald', 'amber', 'violet'].includes(savedAccent)) {
            accent.value = savedAccent
        }
        const savedDensity = localStorage.getItem('density') as Density | null
        if (savedDensity === 'comfortable' || savedDensity === 'compact') {
            density.value = savedDensity
        }
        // 系统主题变化时，system 模式实时跟随
        mq.addEventListener('change', () => {
            if (mode.value === 'system') applyTheme()
        })
        document.documentElement.setAttribute('data-accent', accent.value)
        document.documentElement.setAttribute('data-density', density.value)
        applyTheme()
    }

    return {
        mode,
        isDark,
        accent,
        density,
        setMode,
        toggleTheme,
        setAccent,
        setDensity,
        initTheme,
        applyTheme,
    }
})
