/// <reference types="vite/client" />

declare module '*.vue' {
    import type { DefineComponent } from 'vue'
    const component: DefineComponent<{}, {}, any>
    export default component
}

// Monaco Editor Worker
declare module '*?worker' {
    const workerConstructor: { new(): Worker }
    export default workerConstructor
}

// Vxe-table 模块声明（如有需要）
declare module 'vxe-table'
declare module 'vxe-pc-ui'
