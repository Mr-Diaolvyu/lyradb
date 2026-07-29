(function restoreThemePreference() {
    try {
        var mode = localStorage.getItem('theme') || 'system'
        var dark = mode === 'dark'
            || (mode === 'system' && window.matchMedia('(prefers-color-scheme: dark)').matches)
        var root = document.documentElement
        root.setAttribute('data-theme', dark ? 'dark' : 'light')
        if (dark) root.classList.add('dark')

        var accent = localStorage.getItem('accent')
        if (accent) root.setAttribute('data-accent', accent)

        var density = localStorage.getItem('density')
        if (density) root.setAttribute('data-density', density)
    } catch (_) {
        // 本地偏好不可用时保留 HTML 的安全默认主题。
    }
})()
