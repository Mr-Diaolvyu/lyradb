package io.github.lexaquila.lyradb.desktop.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.lexaquila.lyradb.desktop.model.AiProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiCompatibleClientTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void shouldValidateEndpointSecurity() {
        assertThat(OpenAiCompatibleClient.chatEndpoint(
                "https://api.example.com/v1").toString())
                .isEqualTo("https://api.example.com/v1/chat/completions");
        assertThat(OpenAiCompatibleClient.chatEndpoint(
                "http://127.0.0.1:11434/v1").toString())
                .isEqualTo("http://127.0.0.1:11434/v1/chat/completions");

        assertThatThrownBy(() -> OpenAiCompatibleClient.chatEndpoint(
                "http://api.example.com/v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTPS");
        assertThatThrownBy(() -> OpenAiCompatibleClient.chatEndpoint(
                "https://user:pass@example.com/v1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OpenAiCompatibleClient.chatEndpoint(
                "file:///etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldCallCompatibleApiWithoutExecutingSql() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
            byte[] response = """
                    {"choices":[{"message":{"content":"```sql\\nSELECT 1;\\n```"}}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        AiProfile profile = new AiProfile();
        profile.setProviderKey("custom");
        profile.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        profile.setModel("test-model");
        profile.setApiKey("secret-key");

        OpenAiCompatibleClient client = new OpenAiCompatibleClient();
        String result = client.complete(profile, AiTask.GENERATE,
                "生成常量查询", "POSTGRESQL", "无表", "");

        assertThat(result).contains("SELECT 1");
        assertThat(authorization.get()).isEqualTo("Bearer secret-key");
        assertThat(requestBody.get())
                .contains("test-model")
                .contains("不得声称已执行 SQL")
                .doesNotContain("Authorization");
        new ObjectMapper().readTree(requestBody.get());
    }

    @Test
    void shouldRejectOversizedProviderResponse() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] response = (
                    "{\"choices\":[{\"message\":{\"content\":\""
                            + "x".repeat(2_100_000)
                            + "\"}}]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try {
                exchange.getResponseBody().write(response);
            } finally {
                exchange.close();
            }
        });
        server.start();

        AiProfile profile = new AiProfile();
        profile.setProviderKey("custom");
        profile.setBaseUrl(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        profile.setModel("test-model");
        profile.setApiKey("secret-key");

        OpenAiCompatibleClient client = new OpenAiCompatibleClient();
        assertThatThrownBy(() -> client.complete(profile, AiTask.GENERATE,
                "生成查询", "POSTGRESQL", "", "SELECT 1"))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("2 MiB");
    }
}
