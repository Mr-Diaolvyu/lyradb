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

/**
 * WebView 外壳主界面。
 *
 * <p>移动端为 BS 封装客户端：本 Activity 仅承载一个 WebView，加载用户配置的 远端 BS 服务端地址，复用其 Vue 前端完成登录、数据源管理、SQL
 * 查询、结果导出等全部业务。 本地不承载任何 JDBC 驱动与数据库连接。</p>
 *
 * <ul> <li>启用 JavaScript / DOM Storage，持久化会话 Cookie（企业版登录态）。</li> <li>页面内导航留在
 * WebView；外链交由系统浏览器。</li> <li>结果导出（Excel/CSV）经系统 DownloadManager 下载。</li> <li>返回键优先回退 WebView
 * 历史。</li> </ul>
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
        webView.loadUrl(serverUrl)

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

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(Menu.NONE, MENU_SWITCH_SERVER, Menu.NONE, "切换服务端")
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
    }
}
