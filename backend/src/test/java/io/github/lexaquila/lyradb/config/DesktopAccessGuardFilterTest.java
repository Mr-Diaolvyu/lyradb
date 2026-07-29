package io.github.lexaquila.lyradb.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

class DesktopAccessGuardFilterTest {

    @Test
    void 正确令牌换取会话证明并只把证明放入Fragment() throws Exception {
        DesktopAccessTokenService tokens = new DesktopAccessTokenService();
        DesktopAccessGuardFilter filter = new DesktopAccessGuardFilter(tokens);
        String token = tokens.issueToken();
        MockHttpServletRequest request = bootstrapRequest(token);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getRedirectedUrl())
                .startsWith("/#desktop_token=")
                .doesNotContain(token);
        assertThat(response.getHeader("Referrer-Policy")).isEqualTo("no-referrer");
        assertThat(request.getSession(false)).isNotNull();
        assertThat(request.getSession(false).getAttribute(
                DesktopAccessGuardFilter.SESSION_AUTHORIZED)).isEqualTo(Boolean.TRUE);
        assertThat(request.getSession(false).getAttribute(
                DesktopAccessGuardFilter.SESSION_PROOF_DIGEST)).isInstanceOf(byte[].class);
        assertThat(tokens.consume(token)).isFalse();
    }

    @Test
    void 无令牌错误令牌和重放均被拒绝() throws Exception {
        DesktopAccessTokenService tokens = new DesktopAccessTokenService();
        DesktopAccessGuardFilter filter = new DesktopAccessGuardFilter(tokens);
        String token = tokens.issueToken();

        assertRejected(filter, bootstrapRequest(null), 401);
        assertRejected(filter, bootstrapRequest("wrong-token"), 401);

        MockHttpServletRequest accepted = bootstrapRequest(token);
        filter.doFilter(accepted, new MockHttpServletResponse(), new MockFilterChain());
        assertRejected(filter, bootstrapRequest(token), 401);
    }

    @Test
    void 仅Cookie会话不能访问敏感Get或Post而正确Proof可以() throws Exception {
        DesktopAccessTokenService tokens = new DesktopAccessTokenService();
        DesktopAccessGuardFilter filter = new DesktopAccessGuardFilter(tokens);
        Exchange exchange = exchange(filter, tokens);

        MockHttpServletRequest getWithoutProof = apiRequest(
                "GET", "/api/connections", exchange.session());
        assertRejected(filter, getWithoutProof, 401);
        MockHttpServletRequest postWithoutProof = apiRequest(
                "POST", "/api/query/execute", exchange.session());
        assertRejected(filter, postWithoutProof, 401);

        MockHttpServletRequest withProof = apiRequest(
                "GET", "/api/connections", exchange.session());
        withProof.addHeader(
                DesktopAccessGuardFilter.PROOF_HEADER, exchange.proof());
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(withProof, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void WebSocket要求QueryProof且Origin必须精确匹配当前端口() throws Exception {
        DesktopAccessTokenService tokens = new DesktopAccessTokenService();
        DesktopAccessGuardFilter filter = new DesktopAccessGuardFilter(tokens);
        Exchange exchange = exchange(filter, tokens);

        MockHttpServletRequest wrongPort = websocketRequest(exchange, 38123);
        wrongPort.addHeader("Origin", "http://127.0.0.1:38124");
        assertRejected(filter, wrongPort, 403);

        MockHttpServletRequest correct = websocketRequest(exchange, 38123);
        correct.addHeader("Origin", "http://127.0.0.1:38123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(correct, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void WebSocket仅Cookie或不同端口Origin均不能绕过() throws Exception {
        DesktopAccessTokenService tokens = new DesktopAccessTokenService();
        DesktopAccessGuardFilter filter = new DesktopAccessGuardFilter(tokens);
        Exchange exchange = exchange(filter, tokens);

        MockHttpServletRequest onlyCookie = apiRequest(
                "GET", "/api/ws/tasks", exchange.session());
        onlyCookie.setLocalPort(38123);
        onlyCookie.addHeader("Origin", "http://127.0.0.1:38123");
        assertRejected(filter, onlyCookie, 401);

        MockHttpServletRequest otherOrigin = websocketRequest(exchange, 38123);
        otherOrigin.addHeader("Origin", "http://localhost:38123");
        assertRejected(filter, otherOrigin, 403);
    }

    @Test
    void AppInfo保持公开但仍只接受回环访问() throws Exception {
        DesktopAccessTokenService tokens = new DesktopAccessTokenService();
        DesktopAccessGuardFilter filter = new DesktopAccessGuardFilter(tokens);
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/api/app/info");
        request.setContextPath("/api");
        request.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void 非回环来源即使持有正确令牌也被拒绝() throws Exception {
        DesktopAccessTokenService tokens = new DesktopAccessTokenService();
        DesktopAccessGuardFilter filter = new DesktopAccessGuardFilter(tokens);
        String token = tokens.issueToken();
        MockHttpServletRequest request = bootstrapRequest(token);
        request.setRemoteAddr("192.0.2.10");

        assertRejected(filter, request, 403);
        assertThat(tokens.consume(token)).isTrue();
    }

    private static Exchange exchange(DesktopAccessGuardFilter filter,
                                     DesktopAccessTokenService tokens) throws Exception {
        MockHttpServletRequest request = bootstrapRequest(tokens.issueToken());
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        String redirect = response.getRedirectedUrl();
        String proof = redirect.substring(
                redirect.indexOf("desktop_token=") + "desktop_token=".length());
        return new Exchange((MockHttpSession) request.getSession(false), proof);
    }

    private static MockHttpServletRequest websocketRequest(
            Exchange exchange, int localPort) {
        MockHttpServletRequest request = apiRequest(
                "GET", "/api/ws/tasks", exchange.session());
        request.setLocalPort(localPort);
        request.setParameter(
                DesktopAccessGuardFilter.WEBSOCKET_PROOF_QUERY,
                exchange.proof());
        return request;
    }

    private static MockHttpServletRequest apiRequest(
            String method, String uri, MockHttpSession session) {
        MockHttpServletRequest request =
                new MockHttpServletRequest(method, uri);
        request.setContextPath("/api");
        request.setRemoteAddr("127.0.0.1");
        request.setSession(session);
        return request;
    }

    private static MockHttpServletRequest bootstrapRequest(String token) {
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/api/desktop/bootstrap");
        request.setContextPath("/api");
        request.setRemoteAddr("127.0.0.1");
        if (token != null) {
            request.setParameter("token", token);
        }
        return request;
    }

    private static void assertRejected(DesktopAccessGuardFilter filter,
                                       MockHttpServletRequest request,
                                       int expectedStatus) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(expectedStatus);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    }

    private record Exchange(MockHttpSession session, String proof) {
    }
}
