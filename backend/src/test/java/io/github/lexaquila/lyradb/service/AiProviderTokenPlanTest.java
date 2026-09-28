package io.github.lexaquila.lyradb.service;

import io.github.lexaquila.lyradb.model.entity.AiProviderConfig;
import io.github.lexaquila.lyradb.repository.AiProviderConfigRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AiProviderTokenPlanTest {

    @Test
    void tokenPlanPresetCallsOpenAiCompatibleChatEndpoint() {
        CredentialService credentials = mock(CredentialService.class);
        OutboundUrlPolicy outboundUrls = mock(OutboundUrlPolicy.class);
        when(credentials.decryptValue("encrypted-key")).thenReturn("test-only-key");
        when(outboundUrls.validateAi(anyString(), eq("PUBLIC")))
                .thenAnswer(invocation -> URI.create(invocation.getArgument(0)));
        AiProviderService service = new AiProviderService(
                mock(AiProviderConfigRepository.class), credentials,
                outboundUrls, mock(AiOperationalMetrics.class));
        Map<String, String> preset = service.presets().get("bailian_token");
        assertNotNull(preset);

        AiProviderConfig config = new AiProviderConfig();
        config.setBaseUrl(preset.get("baseUrl"));
        config.setModel(preset.get("model"));
        config.setDeploymentMode("PUBLIC");
        config.setApiKey("encrypted-key");
        config.setTemperature(0.2);
        config.setMaxTokens(2048);

        RestTemplate template = (RestTemplate) ReflectionTestUtils.getField(
                service, "restTemplate");
        assertNotNull(template);
        MockRestServiceServer server = MockRestServiceServer.bindTo(template).build();
        server.expect(requestTo(
                        "https://token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-only-key"))
                .andExpect(jsonPath("$.model").value("qwen3.7-plus"))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"content":"OK"}}],
                         "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
                        """, MediaType.APPLICATION_JSON));

        assertEquals("OK", service.chatWithUsage(config,
                List.of(Map.of("role", "user", "content", "ping"))).content());
        server.verify();
    }
}
