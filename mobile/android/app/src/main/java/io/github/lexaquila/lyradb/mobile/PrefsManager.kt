package io.github.lexaquila.lyradb.mobile

import android.content.Context

/**
 * 本地偏好管理：持久化用户配置的服务端地址。
 *
 * <p>移动端为 BS 封装客户端（原生外壳 + WebView），所有数据库连接、驱动、查询 均由远端 BS 服务端完成，本地仅保存"连哪台服务端"。</p>
 */
object PrefsManager {
    private const val PREFS = "lyradb_mobile"
    private const val KEY_SERVER_URL = "server_url"

    fun getServerUrl(context: Context): String? =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_SERVER_URL, null)

    fun setServerUrl(context: Context, url: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_SERVER_URL, url)
                .apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_SERVER_URL)
                .apply()
    }
}
