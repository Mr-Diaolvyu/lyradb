package io.github.lexaquila.lyradb.mobile

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** 配置并校验 LyraDB BS 服务端地址。发布版仅接受 HTTPS。 */
class ServerConfigActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_server_config)

        val input = findViewById<EditText>(R.id.inputServerUrl)
        val status = findViewById<TextView>(R.id.textStatus)
        val btnTest = findViewById<Button>(R.id.btnTest)
        val btnEnter = findViewById<Button>(R.id.btnEnter)
        PrefsManager.getServerUrl(this)?.let { input.setText(it) }

        btnTest.setOnClickListener {
            val appUrl = canonicalUrl(input.text.toString())
            if (appUrl == null) {
                status.text = invalidAddressMessage()
                return@setOnClickListener
            }
            input.setText(appUrl)
            status.text = "连接中…"
            btnTest.isEnabled = false
            lifecycleScope.launch {
                val result = checkServer(appUrl)
                status.text = result
                btnTest.isEnabled = true
            }
        }

        btnEnter.setOnClickListener {
            val appUrl = canonicalUrl(input.text.toString())
            if (appUrl == null) {
                status.text = invalidAddressMessage()
                return@setOnClickListener
            }
            PrefsManager.setServerUrl(this, appUrl)
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }

    private fun canonicalUrl(raw: String): String? =
            ServerUrlPolicy.canonicalAppUrl(raw, BuildConfig.DEBUG)

    private fun invalidAddressMessage(): String =
            if (BuildConfig.DEBUG) {
                "请输入 origin 或 origin/api；HTTP 仅用于 debug 调试"
            } else {
                "请输入不含账号、查询或片段的 HTTPS origin（可带 /api）"
            }

    private suspend fun checkServer(appUrl: String): String = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val infoUrl = ServerUrlPolicy.appInfoUrl(appUrl)
                    ?: return@withContext "地址格式不正确"
            connection = URL(infoUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            connection.instanceFollowRedirects = false
            connection.requestMethod = "GET"
            val code = connection.responseCode
            if (code == 200) {
                val body = connection.inputStream.bufferedReader().readText()
                val json = JSONObject(body)
                val edition = json.optString("edition", "?")
                val auth = json.optBoolean("authRequired", false)
                val version = json.optString("version", "?")
                "连接成功：$edition 版（v$version），" + if (auth) "需登录" else "免登录"
            } else {
                "连接失败：HTTP $code（请确认地址与端口）"
            }
        } catch (e: Exception) {
            "无法连接：${e.message ?: e.javaClass.simpleName}"
        } finally {
            connection?.disconnect()
        }
    }
}
