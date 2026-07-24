package io.github.lexaquila.lyradb.mobile.network

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/** 简易内存 CookieJar（会话 JSESSIONID）。生产应持久化到 DataStore/EncryptedPrefs。 */
class InMemoryCookieJar : CookieJar {
    private val store = mutableListOf<Cookie>()
    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        store.removeAll { it.name == "JSESSIONID" }
        store.addAll(cookies)
    }
    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> =
        store.filter { it.matches(url) }
}
