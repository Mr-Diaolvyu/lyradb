package io.github.lexaquila.lyradb.service;

import io.github.lexaquila.lyradb.config.AppProperties;
import org.springframework.stereotype.Component;

import java.net.IDN;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 服务端出站 URL 安全策略。
 *
 * <p>只允许白名单中的 HTTPS 主机，并拒绝解析到回环、私网、链路本地、
 * 共享地址或组播地址的目标。白名单项支持精确主机和显式的 {@code *.example.com}
 * 子域规则；空白名单默认拒绝。</p>
 */
@Component
public class OutboundUrlPolicy {

    private static final Set<String> BUILT_IN_AI_HOSTS = Set.of(
            "dashscope.aliyuncs.com",
            "open.bigmodel.cn",
            "ark.cn-beijing.volces.com",
            "api.deepseek.com",
            "api.openai.com");

    private final AppProperties appProperties;

    public OutboundUrlPolicy(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    public URI validateWebhook(String value) {
        Set<String> allowed = parseAllowed(appProperties.getOutbound().getWebhookAllowedHosts());
        return validate(value, allowed, "Webhook");
    }

    public URI validateAi(String value) {
        Set<String> allowed = new HashSet<>(BUILT_IN_AI_HOSTS);
        allowed.addAll(parseAllowed(appProperties.getOutbound().getAiAllowedHosts()));
        return validate(value, allowed, "AI Provider");
    }

    private URI validate(String value, Set<String> allowedHosts, String purpose) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(purpose + " URL 必填");
        }
        URI uri;
        try {
            uri = URI.create(value.trim());
        } catch (Exception e) {
            throw new IllegalArgumentException(purpose + " URL 格式无效");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException(purpose + " 仅允许无凭据、无片段的 HTTPS 地址");
        }

        String host = normalizeHost(uri.getHost());
        if (!isAllowed(host, allowedHosts)) {
            throw new IllegalArgumentException(purpose + " 主机不在出站白名单");
        }

        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            if (addresses.length == 0) {
                throw new IllegalArgumentException(purpose + " 主机无法解析");
            }
            for (InetAddress address : addresses) {
                if (!isPublicAddress(address)) {
                    throw new IllegalArgumentException(purpose + " 主机解析到非公网地址");
                }
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException(purpose + " 主机无法安全解析");
        }
        return uri;
    }

    private Set<String> parseAllowed(String configured) {
        Set<String> result = new HashSet<>();
        if (configured == null || configured.isBlank()) {
            return result;
        }
        for (String item : configured.split(",")) {
            String candidate = item.trim().toLowerCase(Locale.ROOT);
            boolean wildcard = candidate.startsWith("*.");
            String host = wildcard ? candidate.substring(2) : candidate;
            if (host.isBlank() || host.contains("/") || host.contains(":")
                    || host.contains("*") || host.startsWith(".") || host.endsWith(".")) {
                throw new IllegalStateException("出站白名单包含无效主机项");
            }
            String normalized = normalizeHost(host);
            result.add(wildcard ? "*." + normalized : normalized);
        }
        return result;
    }

    private boolean isAllowed(String host, Set<String> allowedHosts) {
        return allowedHosts.stream().anyMatch(allowed -> matchesAllowedHost(host, allowed));
    }

    static boolean matchesAllowedHost(String host, String allowed) {
        if (allowed.startsWith("*.")) {
            String suffix = allowed.substring(1);
            return host.endsWith(suffix) && host.length() > suffix.length();
        }
        return host.equals(allowed);
    }

    private String normalizeHost(String host) {
        try {
            return IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES)
                    .toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            throw new IllegalArgumentException("出站主机名无效");
        }
    }

    static boolean isPublicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return false;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            int a = Byte.toUnsignedInt(bytes[0]);
            int b = Byte.toUnsignedInt(bytes[1]);
            int c = Byte.toUnsignedInt(bytes[2]);

            if (a == 0 || a == 10 || a == 127 || a >= 224) {
                return false;
            }
            if (a == 100 && b >= 64 && b <= 127) {
                return false; // RFC 6598 共享地址
            }
            if (a == 169 && b == 254) {
                return false; // 链路本地/云元数据
            }
            if (a == 172 && b >= 16 && b <= 31) {
                return false;
            }
            if (a == 192 && (b == 168 || (b == 0 && (c == 0 || c == 2))
                    || (b == 88 && c == 99))) {
                return false; // 私网、IETF 特殊、TEST-NET-1、6to4 relay
            }
            if (a == 198 && ((b == 18 || b == 19) || (b == 51 && c == 100))) {
                return false; // 基准测试网段、TEST-NET-2
            }
            if (a == 203 && b == 0 && c == 113) {
                return false; // TEST-NET-3
            }
            return true;
        }
        if (address instanceof Inet6Address) {
            int first = Byte.toUnsignedInt(bytes[0]);
            if ((first & 0xfe) == 0xfc) {
                return false; // fc00::/7 ULA
            }
            return !(Byte.toUnsignedInt(bytes[0]) == 0x20
                    && Byte.toUnsignedInt(bytes[1]) == 0x01
                    && Byte.toUnsignedInt(bytes[2]) == 0x0d
                    && Byte.toUnsignedInt(bytes[3]) == 0xb8); // 2001:db8::/32
        }
        return false;
    }
}
