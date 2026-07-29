/**
 * 统一文件下载入口。
 *
 * 浏览器使用 Object URL；移动 WebView 通过最小原生桥接保存 Blob，
 * 避免把 blob: URL 交给系统 DownloadManager 后静默失败。
 */
declare global {
    interface Window {
        LyraDBAndroid?: {
            saveBase64: (fileName: string, mimeType: string, base64: string) => void
        }
        LyraDBHarmony?: {
            saveBase64: (fileName: string, mimeType: string, base64: string) => void
        }
        webkit?: {
            messageHandlers?: {
                lyradbDownload?: {
                    postMessage: (payload: { fileName: string; mimeType: string; base64: string }) => void
                }
            }
        }
    }
}

function blobAsBase64(blob: Blob): Promise<string> {
    return new Promise((resolve, reject) => {
        const reader = new FileReader()
        reader.onerror = () => reject(new Error('文件编码失败'))
        reader.onload = () => {
            const value = String(reader.result || '')
            const comma = value.indexOf(',')
            if (comma < 0) {
                reject(new Error('文件编码格式无效'))
                return
            }
            resolve(value.slice(comma + 1))
        }
        reader.readAsDataURL(blob)
    })
}

export async function saveBlob(blob: Blob, fileName: string): Promise<void> {
    const mimeType = blob.type || 'application/octet-stream'
    const iosBridge = window.webkit?.messageHandlers?.lyradbDownload
    const nativeBridge = window.LyraDBAndroid || window.LyraDBHarmony

    if (nativeBridge || iosBridge) {
        const base64 = await blobAsBase64(blob)
        if (nativeBridge) {
            nativeBridge.saveBase64(fileName, mimeType, base64)
        } else {
            iosBridge!.postMessage({ fileName, mimeType, base64 })
        }
        return
    }

    const url = URL.createObjectURL(blob)
    try {
        const link = document.createElement('a')
        link.href = url
        link.download = fileName
        link.rel = 'noopener'
        document.body.appendChild(link)
        link.click()
        link.remove()
    } finally {
        // 延迟释放，避免部分浏览器尚未读取 URL 就被撤销。
        window.setTimeout(() => URL.revokeObjectURL(url), 1000)
    }
}

export {}
