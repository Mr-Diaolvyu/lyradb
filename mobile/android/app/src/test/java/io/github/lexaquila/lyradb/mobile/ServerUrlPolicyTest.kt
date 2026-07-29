package io.github.lexaquila.lyradb.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerUrlPolicyTest {
    @Test
    fun `origin 与 api 路径规范为同一应用入口`() {
        assertEquals(
                "https://db.example.com/",
                ServerUrlPolicy.canonicalAppUrl("db.example.com", false)
        )
        assertEquals(
                "https://db.example.com:8443/",
                ServerUrlPolicy.canonicalAppUrl("https://db.example.com:8443/api", false)
        )
    }

    @Test
    fun `探测地址不会重复 api`() {
        val appUrl = ServerUrlPolicy.canonicalAppUrl("https://db.example.com/", false)!!
        assertEquals(
                "https://db.example.com/api/app/info",
                ServerUrlPolicy.appInfoUrl(appUrl)
        )
    }

    @Test
    fun `发布策略拒绝 http 用户信息查询片段与其他路径`() {
        assertNull(ServerUrlPolicy.canonicalAppUrl("http://db.example.com", false))
        assertNull(ServerUrlPolicy.canonicalAppUrl("https://u:p@db.example.com", false))
        assertNull(ServerUrlPolicy.canonicalAppUrl("https://db.example.com?token=x", false))
        assertNull(ServerUrlPolicy.canonicalAppUrl("https://db.example.com/#x", false))
        assertNull(ServerUrlPolicy.canonicalAppUrl("https://db.example.com/admin", false))
    }

    @Test
    fun `debug 可显式接受 http`() {
        assertEquals(
                "http://10.0.2.2:8080/",
                ServerUrlPolicy.canonicalAppUrl("http://10.0.2.2:8080", true)
        )
    }
}
