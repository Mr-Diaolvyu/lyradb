package io.github.lexaquila.lyradb.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class FrontendSecurityHeadersFilterTest {

    private final FrontendSecurityHeadersFilter filter =
            new FrontendSecurityHeadersFilter();

    @Test
    void 根页面使用严格Csp和禁止缓存() throws Exception {
        MockHttpServletRequest request = request("/", "127.0.0.1:38123");
        MockHttpServletResponse response = apply(request);

        assertThat(response.getHeader("Content-Security-Policy"))
                .contains("default-src 'self'")
                .contains("script-src 'self'")
                .contains("connect-src 'self' ws://127.0.0.1:38123"
                        + " wss://127.0.0.1:38123")
                .contains("style-src 'self' 'unsafe-inline'")
                .contains("img-src 'self' data: blob:")
                .contains("font-src 'self' data:")
                .contains("worker-src 'self' blob:")
                .contains("object-src 'none'")
                .contains("base-uri 'self'")
                .contains("frame-ancestors 'none'")
                .doesNotContain("script-src 'unsafe-inline'")
                .doesNotContain("unsafe-eval");
        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeader("X-Frame-Options")).isEqualTo("DENY");
        assertThat(response.getHeader("Referrer-Policy")).isEqualTo("no-referrer");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getHeader("Strict-Transport-Security")).isNull();
    }

    @Test
    void 仅Https响应发送Hsts() throws Exception {
        MockHttpServletRequest request = request("/", "db.example.test");
        request.setSecure(true);

        assertThat(apply(request).getHeader("Strict-Transport-Security"))
                .isEqualTo("max-age=31536000");
    }

    @Test
    void 仅哈希静态资源允许长期不可变缓存() throws Exception {
        assertThat(apply(request(
                "/assets/index-AbCdEf12.js", "example.test")).getHeader("Cache-Control"))
                .isEqualTo("public, max-age=31536000, immutable");
        assertThat(apply(request(
                "/assets/index.js", "example.test")).getHeader("Cache-Control"))
                .isEqualTo("no-cache");
        assertThat(apply(request(
                "/theme-init.js", "example.test")).getHeader("Cache-Control"))
                .isEqualTo("no-store");
    }

    @Test
    void 非法Host不会注入Csp() throws Exception {
        MockHttpServletRequest request = request("/", "evil.test; script-src *");
        request.setServerName("127.0.0.1");
        request.setServerPort(8088);

        String csp = apply(request).getHeader("Content-Security-Policy");

        assertThat(csp)
                .contains("ws://127.0.0.1:8088")
                .doesNotContain("evil.test")
                .doesNotContain("script-src *");
    }

    @Test
    void 静态资源抽取只接受Dist白名单() {
        assertThat(StaticResourceConfig.isAllowedStaticPath("index.html")).isTrue();
        assertThat(StaticResourceConfig.isAllowedStaticPath("theme-init.js")).isTrue();
        assertThat(StaticResourceConfig.isAllowedStaticPath("favicon.svg")).isTrue();
        assertThat(StaticResourceConfig.isAllowedStaticPath(
                "assets/index-AbCdEf12.js")).isTrue();
        assertThat(StaticResourceConfig.isAllowedStaticPath("../application.yml")).isFalse();
        assertThat(StaticResourceConfig.isAllowedStaticPath("application.yml")).isFalse();
        assertThat(StaticResourceConfig.isAllowedStaticPath(
                "assets/../../application.yml")).isFalse();
    }

    private MockHttpServletResponse apply(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    private static MockHttpServletRequest request(String uri, String host) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.addHeader("Host", host);
        return request;
    }
}
