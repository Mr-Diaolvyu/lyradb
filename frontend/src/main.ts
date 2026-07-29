/**
 * 应用入口文件
 */
import { createApp } from 'vue'
import { createPinia } from 'pinia'
import {
    ElAlert,
    ElBadge,
    ElButton,
    ElButtonGroup,
    ElCheckbox,
    ElCheckboxGroup,
    ElCollapse,
    ElCollapseItem,
    ElConfigProvider,
    ElDialog,
    ElDivider,
    ElDrawer,
    ElDropdown,
    ElDropdownItem,
    ElDropdownMenu,
    ElEmpty,
    ElForm,
    ElFormItem,
    ElIcon,
    ElInput,
    ElInputNumber,
    ElLoading,
    ElOption,
    ElPagination,
    ElPopover,
    ElRadio,
    ElRadioButton,
    ElRadioGroup,
    ElSelect,
    ElSwitch,
    ElTable,
    ElTableColumn,
    ElTabPane,
    ElTabs,
    ElTag,
    ElTooltip,
    ElTree,
} from 'element-plus'
import 'element-plus/dist/index.css'
// 暗色主题变量（由 html.dark 类激活，见 stores/theme.ts）
import 'element-plus/theme-chalk/dark/css-vars.css'

import App from './App.vue'
import router from './router'
import { i18n } from './i18n'
import { captureDesktopSessionProof } from './utils/desktopAccess'
import './styles/global.css'
import './styles/tokens.css'

captureDesktopSessionProof()

const app = createApp(App)

// 只注册项目实际使用的 Element Plus 组件；图标均由各视图局部导入。
const elementPlugins = [
    ElAlert,
    ElBadge,
    ElButton,
    ElButtonGroup,
    ElCheckbox,
    ElCheckboxGroup,
    ElCollapse,
    ElCollapseItem,
    ElConfigProvider,
    ElDialog,
    ElDivider,
    ElDrawer,
    ElDropdown,
    ElDropdownItem,
    ElDropdownMenu,
    ElEmpty,
    ElForm,
    ElFormItem,
    ElIcon,
    ElInput,
    ElInputNumber,
    ElLoading,
    ElOption,
    ElPagination,
    ElPopover,
    ElRadio,
    ElRadioButton,
    ElRadioGroup,
    ElSelect,
    ElSwitch,
    ElTable,
    ElTableColumn,
    ElTabPane,
    ElTabs,
    ElTag,
    ElTooltip,
    ElTree,
]
for (const plugin of elementPlugins) app.use(plugin)

app.use(createPinia())
app.use(router)
app.use(i18n)

app.mount('#app')

// V7 启动画面：应用挂载后淡出移除（与 index.html 中的 #splash 配合）
const splash = document.getElementById('splash')
if (splash) {
    splash.classList.add('splash-leave')
    setTimeout(() => splash.remove(), 400)
}
