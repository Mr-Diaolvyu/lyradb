package io.github.lexaquila.lyradb.mobile.network

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * 网络客户端：会话 Cookie 持久化（CookieJar 简化为内存，生产应持久化到 DataStore）。
 *
 * 后端地址：模拟器用 10.0.2.2 访问宿主机 8080；真机改为后端 IP。
 */
object ApiClient {

    // TODO: 真机改为后端实际地址，并配 HTTPS
    private const val BASE_URL = "http://10.0.2.2:8080/api/"

    private val cookieJar = InMemoryCookieJar()

    private val okHttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
            .build()
    }

    val api: ApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttp)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
}
