package io.github.lexaquila.lyradb.transfer.connection;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 已通过格式、版本、字段与安全限制校验的连接配置包。
 */
public record ConnectionPackageReadResult(
        int formatVersion,
        Instant createdAt,
        String sourceVersion,
        CredentialExportPolicy credentialPolicy,
        ConnectionPackageRisk risk,
        List<ConnectionPackageEntry> connections) {

    public ConnectionPackageReadResult {
        if (formatVersion != ConnectionPackageCodec.FORMAT_VERSION) {
            throw new IllegalArgumentException("不支持的连接配置包版本");
        }
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        sourceVersion = Objects.requireNonNull(sourceVersion, "sourceVersion");
        credentialPolicy = Objects.requireNonNull(credentialPolicy, "credentialPolicy");
        risk = Objects.requireNonNull(risk, "risk");
        connections = List.copyOf(Objects.requireNonNull(connections, "connections"));
    }
}
