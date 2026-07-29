package io.github.lexaquila.lyradb.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DesktopAccessTokenServiceTest {

    @Test
    void 令牌只能成功消费一次() {
        DesktopAccessTokenService service = new DesktopAccessTokenService();
        String token = service.issueToken();

        assertThat(token).hasSizeGreaterThanOrEqualTo(40);
        assertThat(service.consume(token)).isTrue();
        assertThat(service.consume(token)).isFalse();
    }

    @Test
    void 新令牌会使旧令牌立即失效() {
        DesktopAccessTokenService service = new DesktopAccessTokenService();
        String oldToken = service.issueToken();
        String newToken = service.issueToken();

        assertThat(service.consume(oldToken)).isFalse();
        assertThat(service.consume(newToken)).isTrue();
    }

    @Test
    void 错误令牌不会消费正确令牌() {
        DesktopAccessTokenService service = new DesktopAccessTokenService();
        String token = service.issueToken();

        assertThat(service.consume("wrong-token")).isFalse();
        assertThat(service.consume(token)).isTrue();
    }
}
