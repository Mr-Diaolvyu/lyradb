package io.github.lexaquila.lyradb.service;

import io.github.lexaquila.lyradb.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.*;

class OutboundUrlPolicyTest {

    @Test
    void emptyWebhookAllowlistRejectsAllTargets() {
        AppProperties properties = new AppProperties();
        OutboundUrlPolicy policy = new OutboundUrlPolicy(properties);

        assertThrows(IllegalArgumentException.class,
                () -> policy.validateWebhook("https://example.com/hook"));
    }

    @Test
    void allowlistedLoopbackStillRejected() {
        AppProperties properties = new AppProperties();
        properties.getOutbound().setWebhookAllowedHosts("127.0.0.1");
        OutboundUrlPolicy policy = new OutboundUrlPolicy(properties);

        assertThrows(IllegalArgumentException.class,
                () -> policy.validateWebhook("https://127.0.0.1/hook"));
    }

    @Test
    void privateModelIsDeniedUntilExplicitlyEnabled() {
        AppProperties properties = new AppProperties();
        properties.getAi().setPrivateModelAllowedHosts("localhost");
        OutboundUrlPolicy policy = new OutboundUrlPolicy(properties);

        assertThrows(IllegalArgumentException.class,
                () -> policy.validateAi(
                        "https://localhost:11434/v1", "PRIVATE"));
    }

    @Test
    void exactPrivateHostCanUseExplicitlyEnabledHttp() {
        AppProperties properties = new AppProperties();
        properties.getAi().setPrivateModelEnabled(true);
        properties.getAi().setPrivateModelAllowedHosts("localhost");
        properties.getAi().setPrivateModelRequireHttps(false);
        OutboundUrlPolicy policy = new OutboundUrlPolicy(properties);

        assertEquals("localhost", policy.validateAi(
                "http://localhost:11434/v1", "PRIVATE").getHost());
    }

    @Test
    void privateModelAllowlistRejectsWildcards() {
        AppProperties properties = new AppProperties();
        properties.getAi().setPrivateModelEnabled(true);
        properties.getAi().setPrivateModelAllowedHosts("*.internal.example");
        OutboundUrlPolicy policy = new OutboundUrlPolicy(properties);

        assertThrows(IllegalStateException.class,
                () -> policy.validateAi(
                        "https://model.internal.example/v1", "PRIVATE"));
    }

    @Test
    void exactAndWildcardHostMatchingHaveLabelBoundaries() {
        assertTrue(OutboundUrlPolicy.matchesAllowedHost(
                "api.example.com", "api.example.com"));
        assertFalse(OutboundUrlPolicy.matchesAllowedHost(
                "other.example.com", "api.example.com"));
        assertTrue(OutboundUrlPolicy.matchesAllowedHost(
                "api.example.com", "*.example.com"));
        assertFalse(OutboundUrlPolicy.matchesAllowedHost(
                "example.com", "*.example.com"));
        assertFalse(OutboundUrlPolicy.matchesAllowedHost(
                "evil-example.com", "*.example.com"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "127.0.0.1",
            "10.0.0.1",
            "172.16.0.1",
            "192.168.1.1",
            "169.254.169.254",
            "100.64.0.1",
            "192.0.2.1",
            "198.18.0.1",
            "198.51.100.1",
            "203.0.113.1",
            "fc00::1",
            "fd12:3456::1",
            "2001:db8::1"
    })
    void reservedAndPrivateAddressesAreRejected(String value) throws Exception {
        assertFalse(OutboundUrlPolicy.isPublicAddress(InetAddress.getByName(value)), value);
    }

    @Test
    void ordinaryPublicAddressIsAccepted() throws Exception {
        assertTrue(OutboundUrlPolicy.isPublicAddress(InetAddress.getByName("8.8.8.8")));
    }
}
