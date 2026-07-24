/**
 * 主题管理 Store
 */
import { defineStore } from 'pinia'
import { ref, watch } from 'vue'

export const useThemeStore = defineStore('theme', () => {
    const isDark = ref(false)

    function toggleTheme() {
        isDark.value = !isDark.value
        applyTheme()
    }

    function applyTheme() {
        document.documentElement.setAttribute('data-theme', isDark.value ? 'dark' : 'light')
    }

    function initTheme() {
        const saved = localStorage.getItem('theme')
        if (saved === 'dark') {
            isDark.value = true
        }
        applyTheme()
    }

    watch(isDark, () => {
        localStorage.setItem('theme', isDark.value ? 'dark' : 'light')
    })

    return {
        isDark,
        toggleTheme,
        initTheme,
        applyTheme,
    }
})
