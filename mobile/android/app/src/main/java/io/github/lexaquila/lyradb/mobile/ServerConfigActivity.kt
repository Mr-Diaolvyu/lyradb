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

/**
 * 服务端地址配置页（原生）。
 *
 * <p>首次启动或切换服务端时进入。用户填写 BS 服务端地址（个人自托管或企业），
 * 校验其 {@code /api/app/info} 可达后持久化，随后进入 WebView 主界面加载该地址。</p>
 */
class ServerConfigActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_server_config)

        val input = findViewById<EditText>(R.id.inputServerUrl)
        val status = findViewById<TextView>(R.id.textStatus)
        val btnTest = findViewById<Button>(R.id.btnTest)
        val btnEnter = findViewById<Button>(R.id.btnEnter)

        // 回填已保存地址
        PrefsManager.getServerUrl(this)?.let { input.setText(it) }

        btnTest.setOnClickListener {
            val url = normalize(input.text.toString())
            if (url.isEmpty()) {
                status.text = "请先填写服务端地址"
                return@setOnClickListener
            }
            status.text = "连接中…"
            btnTest.isEnabled = false
            lifecycleScope.launch {
                val result = checkServer(url)
                status.text = result
                btnTest.isEnabled = true
            }
        }

        btnEnter.setOnClickListener {
            val url = normalize(input.text.toString())
            if (url.isEmpty()) {
                status.text = "请先填写服务端地址"
                return@setOnClickListener
            }
            PrefsManager.setServerUrl(this, url)
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }

    /** 规范化地址：补全 http 前缀、去除结尾斜杠 */
    private fun normalize(raw: String): String {
        var s = raw.trim()
        if (s.isEmpty()) return ""
        if (!s.startsWith("http://") && !s.startsWith("https://")) {
            s = "http://$s"
        }
        return s.trimEnd('/')
    }

    /** 探测服务端 /api/app/info，返回可读结果 */
    private suspend fun checkServer(base: String): String = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = URL("$base/api/app/info").openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.requestMethod = "GET"
            val code = conn.responseCode
            if (code == 200) {
                val body = conn.inputStream.bufferedReader().readText()
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
            conn?.disconnect()
        }
    }
}
