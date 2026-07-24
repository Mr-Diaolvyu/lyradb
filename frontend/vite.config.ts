import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'path'

// https://vitejs.dev/config/
export default defineConfig({
    plugins: [
        vue(),
    ],
    resolve: {
        alias: {
            '@': resolve(__dirname, 'src'),
        },
    },
    server: {
        port: 5173,
        proxy: {
            '/api': {
                target: 'http://localhost:8080',
                changeOrigin: true,
                ws: true, // WebSocket 代理（驱动下载进度 /ws/drivers）
            },
        },
    },
    build: {
        target: 'es2020',
        outDir: 'dist',
        chunkSizeWarningLimit: 1500,
        rollupOptions: {
            output: {
                manualChunks: {
                    'monaco': ['monaco-editor'],
                    'element-plus': ['element-plus', '@element-plus/icons-vue'],
                    'vxe-table': ['vxe-table', 'vxe-pc-ui'],
                },
            },
        },
    },
    optimizeDeps: {
        include: [
            'vue',
            'vue-router',
            'pinia',
            'axios',
            'element-plus',
            '@element-plus/icons-vue',
        ],
    },
})
