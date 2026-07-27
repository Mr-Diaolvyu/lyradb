package io.github.lexaquila.lyradb.mobile

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.Menu
import android.view.MenuItem
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat

/**
 * WebView 外壳主界面。
 *
 * <p>移动端为 BS 封装客户端：本 Activity 仅承载一个 WebView，加载用户配置的 远端 BS 服务端地址，复用其 Vue 前端完成登录、数据源管理、SQL
 * 查询、结果导出等全部业务。 本地不承载任何 JDBC 驱动与数据库连接。</p>
 *
 * <ul> <li>启用 JavaScript / DOM Storage，持久化会话 Cookie（企业版登录态）。</li> <li>页面内导航留在
 * WebView；外链交由系统浏览器。</li> <li>结果导出（Excel/CSV）经系统 DownloadManager 下载。</li> <li>返回键优先回退 WebView
 * 历史。</li> <li>可选生物识别快速解锁（BiometricPrompt，菜单开关）。</li> </ul>
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 未配置服务端 → 进入配置页
        val serverUrl = PrefsManager.getServerUrl(this)
        if (serverUrl.isNullOrBlank()) {
            startActivity(Intent(this, ServerConfigActivity::class.java))
            finish()
            return
        }

        webView = WebView(this)
        setContentView(webView)

        setupWebView()

        // 生物识别解锁：开启且设备可用时，验证通过后再加载页面
        if (PrefsManager.isBiometricEnabled(this) && canUseBiometric()) {
            showBiometricUnlock { webView.loadUrl(serverUrl) }
        } else {
            webView.loadUrl(serverUrl)
        }

        // 返回键：优先回退 WebView 历史
        onBackPressedDispatcher.addCallback(
                this,
                object : OnBackPressedCallback(true) {
                    override fun handleOnBackPressed() {
                        if (webView.canGoBack()) webView.goBack() else finish()
                    }
                }
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        // 开发期允许 https 页面加载 http 子资源；生产部署 HTTPS 后不受影响
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

        // 会话 Cookie 持久化（企业版登录态）
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        // 页面内导航留在 WebView，外链交系统浏览器
        webView.webViewClient =
                object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest
                    ): Boolean {
                        val url = request.url.toString()
                        val host = Uri.parse(url).host
                        val serverHost =
                                Uri.parse(PrefsManager.getServerUrl(this@MainActivity)).host
                        return if (host != null && host == serverHost) {
                            false // 同源：留在 WebView
                        } else {
                            try {
                                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            } catch (_: Exception) {}
                            true
                        }
                    }
                }

        // 结果导出（Excel/CSV）→ 系统 DownloadManager
        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            try {
                val fileName = URLUtil.guessFileName(url, contentDisposition, mimetype)
                val request = DownloadManager.Request(Uri.parse(url))
                request.setMimeType(mimetype)
                request.addRequestHeader("User-Agent", userAgent)
                request.setTitle(fileName)
                request.setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
                dm.enqueue(request)
                Toast.makeText(this, "开始下载 $fileName", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "下载失败：${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 设备是否可用生物识别（或锁屏凭据兑底） */
    private fun canUseBiometric(): Boolean =
            BiometricManager.from(this).canAuthenticate(AUTHENTICATORS) ==
                    BiometricManager.BIOMETRIC_SUCCESS

    /** 启动时生物识别解锁；失败/取消则退出应用 */
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
                    startActivity(Intent(this, ServerConfigActivity::class.java))
                    finish()
                    true
                }
                MENU_TOGGLE_BIOMETRIC -> {
                    val enable = !PrefsManager.isBiometricEnabled(this)
                    if (enable && !canUseBiometric()) {
                        Toast.makeText(this, "设备未录入生物特征或不支持", Toast.LENGTH_SHORT).show()
                    } else {
                        PrefsManager.setBiometricEnabled(this, enable)
                        Toast.makeText(
                                        this,
                                        if (enable) "已启用生物识别解锁" else "已关闭生物识别解锁",
                                        Toast.LENGTH_SHORT
                                )
                                .show()
                        invalidateOptionsMenu()
                    }
                    true
                }
                MENU_CLEAR_CACHE -> {
                    CookieManager.getInstance().removeAllCookies(null)
                    webView.clearCache(true)
                    webView.clearFormData()
                    PrefsManager.getServerUrl(this)?.let { webView.loadUrl(it) }
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
        super.onPause()
    }

    override fun onDestroy() {
        if (::webView.isInitialized) webView.destroy()
        super.onDestroy()
    }

    companion object {
        private const val MENU_SWITCH_SERVER = 1001
        private const val MENU_CLEAR_CACHE = 1002
        private const val MENU_TOGGLE_BIOMETRIC = 1003
        private const val AUTHENTICATORS =
                BiometricManager.Authenticators.BIOMETRIC_WEAK or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL
    }
}
