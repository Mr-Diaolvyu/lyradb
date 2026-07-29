package io.github.lexaquila.lyradb.mobile

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.view.Menu
import android.view.MenuItem
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import java.io.ByteArrayInputStream
import java.io.File

/**
 * WebView 外壳主界面。发布构建只加载用户配置的同源 HTTPS 服务，
 * 不允许混合内容、文件协议或第三方 Cookie。
 */
class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var serverUri: Uri

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val storedUrl = PrefsManager.getServerUrl(this)
        val serverUrl = storedUrl?.let {
            ServerUrlPolicy.canonicalAppUrl(it, BuildConfig.DEBUG)
        }
        if (serverUrl == null) {
            if (!storedUrl.isNullOrBlank()) {
                Toast.makeText(this, "服务端地址无效，请重新配置", Toast.LENGTH_LONG).show()
            }
            openServerConfig()
            return
        }
        if (serverUrl != storedUrl) {
            // 兼容曾保存的裸 origin 或 /api 地址，并迁移为统一的根页面入口。
            PrefsManager.setServerUrl(this, serverUrl)
        }
        serverUri = Uri.parse(serverUrl)

        webView = WebView(this)
        setContentView(webView)
        setupWebView()

        if (PrefsManager.isBiometricEnabled(this)) {
            if (canUseBiometric()) {
                showBiometricUnlock { webView.loadUrl(serverUrl) }
            } else {
                // 用户主动开启的应用锁必须失败关闭，不能因能力异常自动绕过。
                Toast.makeText(this, "设备认证不可用，LyraDB 保持锁定", Toast.LENGTH_LONG).show()
                finish()
            }
        } else {
            webView.loadUrl(serverUrl)
        }

        onBackPressedDispatcher.addCallback(
                this,
                object : OnBackPressedCallback(true) {
                    override fun handleOnBackPressed() {
                        if (webView.canGoBack()) webView.goBack() else finish()
                    }
                }
        )
    }

    private fun openServerConfig() {
        startActivity(Intent(this, ServerConfigActivity::class.java))
        finish()
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun setupWebView() {
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = false
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.allowFileAccessFromFileURLs = false
        settings.allowUniversalAccessFromFileURLs = false
        settings.safeBrowsingEnabled = true

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)
        webView.addJavascriptInterface(DownloadBridge(), "LyraDBAndroid")

        webView.webViewClient =
                object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest
                    ): Boolean {
                        val uri = request.url
                        if (isTrustedUri(uri)) return false
                        if (uri.scheme.equals("https", ignoreCase = true)) {
                            try {
                                startActivity(Intent(Intent.ACTION_VIEW, uri))
                            } catch (_: Exception) {
                                Toast.makeText(this@MainActivity, "无法打开外部链接", Toast.LENGTH_SHORT).show()
                            }
                        }
                        return true
                    }

                    override fun shouldInterceptRequest(
                            view: WebView,
                            request: WebResourceRequest
                    ): WebResourceResponse? {
                        val uri = request.url
                        val isHttp =
                                uri.scheme.equals("http", ignoreCase = true) ||
                                        uri.scheme.equals("https", ignoreCase = true)
                        if (isHttp && !isTrustedUri(uri)) {
                            return WebResourceResponse(
                                    "text/plain",
                                    "UTF-8",
                                    403,
                                    "Forbidden",
                                    emptyMap(),
                                    ByteArrayInputStream(ByteArray(0))
                            )
                        }
                        return super.shouldInterceptRequest(view, request)
                    }
                }

        // 普通 HTTPS 下载交给系统；前端 Blob 下载由 LyraDBAndroid 桥处理。
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            if (url.startsWith("blob:", ignoreCase = true)) {
                saveBlobUrl(url, URLUtil.guessFileName(url, contentDisposition, mimeType), mimeType)
                return@setDownloadListener
            }
            val uri = Uri.parse(url)
            if (!isTrustedUri(uri)) {
                Toast.makeText(this, "已阻止非同源下载", Toast.LENGTH_SHORT).show()
                return@setDownloadListener
            }
            try {
                val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
                val request = DownloadManager.Request(uri)
                request.setMimeType(mimeType)
                request.addRequestHeader("User-Agent", userAgent)
                CookieManager.getInstance().getCookie(url)?.let {
                    request.addRequestHeader("Cookie", it)
                }
                request.setTitle(fileName)
                request.setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    request.setDestinationInExternalPublicDir(
                            Environment.DIRECTORY_DOWNLOADS,
                            fileName
                    )
                } else {
                    request.setDestinationInExternalFilesDir(
                            this,
                            Environment.DIRECTORY_DOWNLOADS,
                            fileName
                    )
                }
                val manager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
                manager.enqueue(request)
                Toast.makeText(this, "开始下载 $fileName", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "下载失败：${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun saveBlobUrl(url: String, fileName: String, mimeType: String) {
        val quotedUrl = org.json.JSONObject.quote(url)
        val quotedName = org.json.JSONObject.quote(fileName)
        val quotedMime = org.json.JSONObject.quote(mimeType)
        webView.evaluateJavascript(
                """
                (async function () {
                  try {
                    const response = await fetch($quotedUrl);
                    const blob = await response.blob();
                    const reader = new FileReader();
                    reader.onloadend = function () {
                      const encoded = String(reader.result || '').split(',')[1] || '';
                      window.LyraDBAndroid.saveBase64($quotedName, $quotedMime, encoded);
                    };
                    reader.readAsDataURL(blob);
                  } catch (_) {
                    window.LyraDBAndroid.saveBase64($quotedName, $quotedMime, '');
                  }
                })();
                """.trimIndent(),
                null
        )
    }

    private fun isTrustedUri(uri: Uri): Boolean {
        return uri.scheme.equals(serverUri.scheme, ignoreCase = true) &&
                uri.host.equals(serverUri.host, ignoreCase = true) &&
                effectivePort(uri) == effectivePort(serverUri)
    }

    private fun effectivePort(uri: Uri): Int =
            if (uri.port >= 0) uri.port else if (uri.scheme == "https") 443 else 80

    /** 由前端统一下载工具调用的最小原生桥。 */
    private inner class DownloadBridge {
        @JavascriptInterface
        fun saveBase64(fileName: String, mimeType: String, encoded: String) {
            if (encoded.isBlank()) {
                showToast("下载失败：文件内容为空")
                return
            }
            if (encoded.length > MAX_BASE64_CHARS) {
                showToast("文件过大，请改用桌面浏览器导出")
                return
            }
            try {
                val bytes = Base64.decode(encoded, Base64.DEFAULT)
                val safeName = sanitizeFileName(fileName)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values =
                            ContentValues().apply {
                                put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
                                put(MediaStore.MediaColumns.MIME_TYPE, mimeType.take(120))
                                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                                put(MediaStore.MediaColumns.IS_PENDING, 1)
                            }
                    val uri =
                            contentResolver.insert(
                                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                                    values
                            ) ?: throw IllegalStateException("无法创建下载文件")
                    contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                            ?: throw IllegalStateException("无法写入下载文件")
                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    contentResolver.update(uri, values, null, null)
                } else {
                    val directory =
                            getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
                    directory.mkdirs()
                    File(directory, safeName).outputStream().use { it.write(bytes) }
                }
                showToast("已保存 $safeName")
            } catch (e: Exception) {
                showToast("保存失败：${e.message}")
            }
        }
    }

    private fun sanitizeFileName(value: String): String {
        val cleaned =
                value.substringAfterLast('/').replace(Regex("[^\\p{L}\\p{N}._-]"), "_").take(120)
        return if (cleaned.isBlank() || cleaned == "." || cleaned == "..") {
            "lyradb-export.bin"
        } else {
            cleaned
        }
    }

    private fun showToast(message: String) {
        runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    }

    private fun canUseBiometric(): Boolean =
            BiometricManager.from(this).canAuthenticate(AUTHENTICATORS) ==
                    BiometricManager.BIOMETRIC_SUCCESS

    private fun authenticateAndEnableBiometric() {
        if (!canUseBiometric()) {
            Toast.makeText(this, "设备未设置生物特征或锁屏凭据", Toast.LENGTH_SHORT).show()
            return
        }
        val prompt =
                BiometricPrompt(
                        this,
                        ContextCompat.getMainExecutor(this),
                        object : BiometricPrompt.AuthenticationCallback() {
                            override fun onAuthenticationSucceeded(
                                    result: BiometricPrompt.AuthenticationResult
                            ) {
                                PrefsManager.setBiometricEnabled(this@MainActivity, true)
                                Toast.makeText(
                                                this@MainActivity,
                                                "已启用设备认证解锁",
                                                Toast.LENGTH_SHORT
                                        )
                                        .show()
                                invalidateOptionsMenu()
                            }

                            override fun onAuthenticationError(
                                    errorCode: Int,
                                    errString: CharSequence
                            ) {
                                Toast.makeText(
                                                this@MainActivity,
                                                "身份验证未通过，应用锁未启用",
                                                Toast.LENGTH_SHORT
                                        )
                                        .show()
                            }
                        }
                )
        val info =
                BiometricPrompt.PromptInfo.Builder()
                        .setTitle("验证并启用 LyraDB 应用锁")
                        .setAllowedAuthenticators(AUTHENTICATORS)
                        .build()
        prompt.authenticate(info)
    }

    private fun showBiometricUnlock(onSuccess: () -> Unit) {
        val prompt =
                BiometricPrompt(
                        this,
                        ContextCompat.getMainExecutor(this),
                        object : BiometricPrompt.AuthenticationCallback() {
                            override fun onAuthenticationSucceeded(
                                    result: BiometricPrompt.AuthenticationResult
                            ) = onSuccess()

                            override fun onAuthenticationError(
                                    errorCode: Int,
                                    errString: CharSequence
                            ) {
                                Toast.makeText(
                                                this@MainActivity,
                                                "解锁失败：$errString",
                                                Toast.LENGTH_SHORT
                                        )
                                        .show()
                                finish()
                            }
                        }
                )
        val info =
                BiometricPrompt.PromptInfo.Builder()
                        .setTitle("解锁 LyraDB")
                        .setAllowedAuthenticators(AUTHENTICATORS)
                        .build()
        prompt.authenticate(info)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(Menu.NONE, MENU_SWITCH_SERVER, Menu.NONE, "切换服务端")
        menu.add(
                Menu.NONE,
                MENU_TOGGLE_BIOMETRIC,
                Menu.NONE,
                if (PrefsManager.isBiometricEnabled(this)) "关闭生物识别解锁" else "启用生物识别解锁"
        )
        menu.add(Menu.NONE, MENU_CLEAR_CACHE, Menu.NONE, "退出登录并清缓存")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
            when (item.itemId) {
                MENU_SWITCH_SERVER -> {
                    openServerConfig()
                    true
                }
                MENU_TOGGLE_BIOMETRIC -> {
                    if (PrefsManager.isBiometricEnabled(this)) {
                        PrefsManager.setBiometricEnabled(this, false)
                        Toast.makeText(this, "已关闭设备认证解锁", Toast.LENGTH_SHORT).show()
                        invalidateOptionsMenu()
                    } else {
                        authenticateAndEnableBiometric()
                    }
                    true
                }
                MENU_CLEAR_CACHE -> {
                    CookieManager.getInstance().removeAllCookies {
                        CookieManager.getInstance().flush()
                        WebStorage.getInstance().deleteAllData()
                        webView.clearCache(true)
                        webView.clearFormData()
                        webView.loadUrl(serverUri.toString())
                    }
                    true
                }
                else -> super.onOptionsItemSelected(item)
            }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) webView.onResume()
    }

    override fun onPause() {
        if (::webView.isInitialized) webView.onPause()
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.removeJavascriptInterface("LyraDBAndroid")
            webView.destroy()
        }
        super.onDestroy()
    }

    companion object {
        private const val MENU_SWITCH_SERVER = 1001
        private const val MENU_CLEAR_CACHE = 1002
        private const val MENU_TOGGLE_BIOMETRIC = 1003
        private const val MAX_BASE64_CHARS = 48 * 1024 * 1024
        private const val AUTHENTICATORS =
                BiometricManager.Authenticators.BIOMETRIC_WEAK or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL
    }
}
