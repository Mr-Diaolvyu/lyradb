/**
 * i18n 国际化入口：中英文双语，语言选择持久化到 localStorage。
 *
 * Element Plus 组件库的语言联动见 App.vue 的 el-config-provider。
 */
import { createI18n } from 'vue-i18n'
import zhCN from './zh-CN'
import enUS from './en-US'

export type AppLocale = 'zh-CN' | 'en-US'

const STORAGE_KEY = 'lyradb_locale'

/** 初始语言：localStorage > 浏览器语言 > 中文 */
function detectLocale(): AppLocale {
    const saved = localStorage.getItem(STORAGE_KEY)
    if (saved === 'zh-CN' || saved === 'en-US') return saved
    return navigator.language?.toLowerCase().startsWith('zh') ? 'zh-CN' : 'en-US'
}

export const i18n = createI18n({
    legacy: false,
    locale: detectLocale(),
    fallbackLocale: 'zh-CN',
    messages: {
        'zh-CN': zhCN,
        'en-US': enUS
    }
})

/** 切换语言并持久化 */
export function setLocale(locale: AppLocale) {
    i18n.global.locale.value = locale
    localStorage.setItem(STORAGE_KEY, locale)
}

/** 当前语言 */
export function getLocale(): AppLocale {
    return i18n.global.locale.value as AppLocale
}
