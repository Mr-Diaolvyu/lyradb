/**
 * 应用入口文件
 */
import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
// 暗色主题变量（由 html.dark 类激活，见 stores/theme.ts）
import 'element-plus/theme-chalk/dark/css-vars.css'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'

// Vxe-table 注册
import VxeUIAll from 'vxe-pc-ui'
import 'vxe-pc-ui/lib/style.css'
import VxeUITable from 'vxe-table'
import 'vxe-table/lib/style.css'

import App from './App.vue'
import router from './router'
import { i18n } from './i18n'
import './styles/global.css'
import './styles/tokens.css'

const app = createApp(App)

// 注册Element Plus图标
for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
    app.component(key, component)
}

app.use(createPinia())
app.use(router)
app.use(ElementPlus)
app.use(i18n)

// 注册 Vxe-table
app.use(VxeUIAll)
app.use(VxeUITable)

app.mount('#app')

// V7 启动画面：应用挂载后淡出移除（与 index.html 中的 #splash 配合）
const splash = document.getElementById('splash')
if (splash) {
    splash.classList.add('splash-leave')
    setTimeout(() => splash.remove(), 400)
}
