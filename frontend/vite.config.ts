import { brotliCompressSync, constants as zlibConstants, gzipSync } from 'node:zlib'
import { resolve } from 'node:path'
import type { OutputBundle } from 'rollup'
import { defineConfig, type Plugin } from 'vite'
import vue from '@vitejs/plugin-vue'

const KIB = 1024
const COMPRESSION_BUDGET = {
    entryGzip: 32 * KIB,
    entryBrotli: 28 * KIB,
    jsChunkGzip: 1000 * KIB,
    jsChunkBrotli: 880 * KIB,
    totalGzip: 2050 * KIB,
    totalBrotli: 1820 * KIB,
} as const

interface CompressedAsset {
    fileName: string
    isEntry: boolean
    isJavaScript: boolean
    gzipBytes: number
    brotliBytes: number
}

function formatKiB(bytes: number): string {
    return `${(bytes / KIB).toFixed(1)} KiB`
}

/**
 * 在全部 generateBundle 转换完成并写盘后，对最终 Rollup 输出计算 gzip 与 Brotli 体积并执行硬预算。
 * 该插件由 npm run build、Docker 构建及 CI 共同复用，超限会直接终止构建。
 */
function compressionBudgetPlugin(): Plugin {
    return {
        name: 'lyradb-compression-budget',
        apply: 'build',
        enforce: 'post',
        writeBundle(_options, bundle: OutputBundle) {
            const assets: CompressedAsset[] = Object.values(bundle)
                .filter(item => /\.(?:js|css)$/.test(item.fileName))
                .map(item => {
                    const source = item.type === 'chunk' ? item.code : item.source
                    const bytes = Buffer.from(source)
                    return {
                        fileName: item.fileName,
                        isEntry: item.type === 'chunk' && item.isEntry,
                        isJavaScript: item.fileName.endsWith('.js'),
                        gzipBytes: gzipSync(bytes, { level: 9 }).byteLength,
                        brotliBytes: brotliCompressSync(bytes, {
                            params: {
                                [zlibConstants.BROTLI_PARAM_QUALITY]: 5,
                            },
                        }).byteLength,
                    }
                })

            const entries = assets.filter(asset => asset.isEntry && asset.isJavaScript)
            const javaScript = assets.filter(asset => asset.isJavaScript)
            if (entries.length === 0 || javaScript.length === 0) {
                this.error('压缩体积预算无法执行：构建结果缺少入口或 JavaScript chunk')
            }

            const largestBy = (
                candidates: CompressedAsset[],
                key: 'gzipBytes' | 'brotliBytes',
            ): CompressedAsset => candidates.reduce(
                (largest, current) => current[key] > largest[key] ? current : largest,
            )
            const largestEntryGzip = largestBy(entries, 'gzipBytes')
            const largestEntryBrotli = largestBy(entries, 'brotliBytes')
            const largestChunkGzip = largestBy(javaScript, 'gzipBytes')
            const largestChunkBrotli = largestBy(javaScript, 'brotliBytes')
            const totalGzip = assets.reduce((sum, asset) => sum + asset.gzipBytes, 0)
            const totalBrotli = assets.reduce((sum, asset) => sum + asset.brotliBytes, 0)
            const violations: string[] = []

            for (const entry of entries) {
                if (entry.gzipBytes > COMPRESSION_BUDGET.entryGzip) {
                    violations.push(
                        `入口 ${entry.fileName} gzip ${formatKiB(entry.gzipBytes)} > ${formatKiB(COMPRESSION_BUDGET.entryGzip)}`,
                    )
                }
                if (entry.brotliBytes > COMPRESSION_BUDGET.entryBrotli) {
                    violations.push(
                        `入口 ${entry.fileName} Brotli ${formatKiB(entry.brotliBytes)} > ${formatKiB(COMPRESSION_BUDGET.entryBrotli)}`,
                    )
                }
            }
            for (const chunk of javaScript) {
                if (chunk.gzipBytes > COMPRESSION_BUDGET.jsChunkGzip) {
                    violations.push(
                        `JS chunk ${chunk.fileName} gzip ${formatKiB(chunk.gzipBytes)} > ${formatKiB(COMPRESSION_BUDGET.jsChunkGzip)}`,
                    )
                }
                if (chunk.brotliBytes > COMPRESSION_BUDGET.jsChunkBrotli) {
                    violations.push(
                        `JS chunk ${chunk.fileName} Brotli ${formatKiB(chunk.brotliBytes)} > ${formatKiB(COMPRESSION_BUDGET.jsChunkBrotli)}`,
                    )
                }
            }
            if (totalGzip > COMPRESSION_BUDGET.totalGzip) {
                violations.push(
                    `JS+CSS 总 gzip ${formatKiB(totalGzip)} > ${formatKiB(COMPRESSION_BUDGET.totalGzip)}`,
                )
            }
            if (totalBrotli > COMPRESSION_BUDGET.totalBrotli) {
                violations.push(
                    `JS+CSS 总 Brotli ${formatKiB(totalBrotli)} > ${formatKiB(COMPRESSION_BUDGET.totalBrotli)}`,
                )
            }

            this.info(
                [
                    `入口最大 gzip ${formatKiB(largestEntryGzip.gzipBytes)} (${largestEntryGzip.fileName})`,
                    `入口最大 Brotli ${formatKiB(largestEntryBrotli.brotliBytes)} (${largestEntryBrotli.fileName})`,
                    `JS 最大 gzip ${formatKiB(largestChunkGzip.gzipBytes)} (${largestChunkGzip.fileName})`,
                    `JS 最大 Brotli ${formatKiB(largestChunkBrotli.brotliBytes)} (${largestChunkBrotli.fileName})`,
                    `JS+CSS 总量 gzip ${formatKiB(totalGzip)} / Brotli ${formatKiB(totalBrotli)}`,
                ].join('；'),
            )

            if (violations.length > 0) {
                this.error(`压缩体积预算超限：\n- ${violations.join('\n- ')}`)
            }
        },
    }
}

// https://vitejs.dev/config/
export default defineConfig({
    plugins: [
        vue(),
        compressionBudgetPlugin(),
    ],
    define: {
        // Vue I18n 9.3+ 的 CSP 兼容 JIT 使用 AST 解释器，避免运行时 new Function。
        __INTLIFY_JIT_COMPILATION__: true,
        __INTLIFY_DROP_MESSAGE_COMPILER__: false,
        __VUE_I18N_LEGACY_API__: false,
        __INTLIFY_PROD_DEVTOOLS__: false,
    },
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
                manualChunks(id) {
                    const normalizedId = id.replace(/\\\\/g, '/')
                    // Vite 的动态导入 helper 若被 Rollup 并入重型 chunk，会让入口反向静态依赖该 chunk。
                    if (normalizedId.includes('vite/preload-helper')) return 'app-runtime'
                    if (normalizedId.includes('/node_modules/monaco-editor/')) return 'monaco'
                    if (
                        normalizedId.includes('/node_modules/vxe-table/')
                        || normalizedId.includes('/node_modules/vxe-pc-ui/')
                    ) return 'vxe-table'
                    if (
                        normalizedId.includes('/node_modules/vue/')
                        || normalizedId.includes('/node_modules/@vue/')
                        || normalizedId.includes('/node_modules/vue-router/')
                        || normalizedId.includes('/node_modules/vue-i18n/')
                        || normalizedId.includes('/node_modules/pinia/')
                    ) return 'app-vendor'
                    if (
                        normalizedId.includes('/node_modules/element-plus/')
                        || normalizedId.includes('/node_modules/@element-plus/')
                    ) return 'app-vendor'
                    if (normalizedId.includes('/node_modules/@vue-flow/')) return 'vue-flow'
                    return undefined
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
