package io.github.lexaquila.lyradb.desktop.transfer;

import io.github.lexaquila.lyradb.desktop.model.DesktopConnection;
import io.github.lexaquila.lyradb.transfer.connection.ConnectionPackageCodec;
import io.github.lexaquila.lyradb.transfer.connection.ConnectionPackageEntry;
import io.github.lexaquila.lyradb.transfer.connection.ConnectionPackageException;
import io.github.lexaquila.lyradb.transfer.connection.ConnectionPackageReadResult;
import io.github.lexaquila.lyradb.transfer.connection.ConnectionPackageRisk;
import io.github.lexaquila.lyradb.transfer.connection.CredentialExportPolicy;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Core 连接包协议与桌面连接模型之间的适配器。
 *
 * <p>输入范围严格限定为 {@link DesktopConnection}；不会读取 AI 配置、桌面
 * Vault 或主密钥。导出口令仅作为调用参数传给 Core codec。</p>
 */
public final class ConnectionTransferService {

    public static final String FILE_SUFFIX = ".lyradb-connections.json";

    public record ImportedConnection(DesktopConnection connection,
                                     Set<String> credentialKeys) {
        public ImportedConnection {
            credentialKeys = credentialKeys == null
                    ? Set.of() : Set.copyOf(credentialKeys);
            connection = java.util.Objects.requireNonNull(
                    connection, "导入连接不能为空").copy();
            connection.setCredentialKeys(credentialKeys);
        }

        @Override
        public DesktopConnection connection() {
            return connection.copy();
        }
    }

    public record ImportBundle(CredentialExportPolicy credentialPolicy,
                               ConnectionPackageRisk risk,
                               List<ImportedConnection> connections) {
        public ImportBundle {
            credentialPolicy =
                    java.util.Objects.requireNonNull(credentialPolicy);
            risk = java.util.Objects.requireNonNull(risk);
            connections = connections == null ? List.of() : List.copyOf(connections);
        }

        public List<DesktopConnection> desktopConnections() {
            return connections.stream()
                    .map(ImportedConnection::connection)
                    .toList();
        }
    }

    private final ConnectionPackageCodec codec;

    public ConnectionTransferService() {
        this(new ConnectionPackageCodec());
    }

    ConnectionTransferService(ConnectionPackageCodec codec) {
        this.codec = java.util.Objects.requireNonNull(codec);
    }

    public byte[] encode(List<DesktopConnection> connections,
            CredentialExportPolicy policy, char[] exportPassword)
            throws ConnectionPackageException {
        if (policy == null) {
            throw new IllegalArgumentException("凭据处理方式不能为空");
        }
        List<ConnectionPackageEntry> entries =
                connections == null ? List.of() : connections.stream()
                        .map(ConnectionTransferService::toEntry)
                        .toList();
        return switch (policy) {
            case OMIT -> codec.exportWithoutCredentials(entries);
            case PLAINTEXT -> codec.exportWithPlaintextCredentials(entries);
            case PASSWORD_ENCRYPTED ->
                    codec.exportWithPassword(entries, exportPassword);
        };
    }

    public void exportTo(Path target, List<DesktopConnection> connections,
            CredentialExportPolicy policy, char[] exportPassword)
            throws ConnectionPackageException, IOException {
        byte[] encoded = encode(connections, policy, exportPassword);
        try {
            writeAtomically(target, encoded);
        } finally {
            java.util.Arrays.fill(encoded, (byte) 0);
        }
    }

    public ImportBundle read(Path source, char[] exportPassword)
            throws ConnectionPackageException {
        Path safe = requireReadableFile(source);
        ConnectionPackageReadResult result = codec.read(safe, exportPassword);
        List<ImportedConnection> imported = new ArrayList<>();
        for (ConnectionPackageEntry entry : result.connections()) {
            DesktopConnection connection = new DesktopConnection();
            connection.setId(entry.id());
            connection.setName(entry.name());
            connection.setDbType(entry.dbType());
            connection.setGroup(entry.group());
            connection.setFavorite(entry.favorite());
            Map<String, Object> parameters =
                    new LinkedHashMap<>(entry.mergedParameters());
            if (result.credentialPolicy() == CredentialExportPolicy.OMIT) {
                entry.credentialKeys().forEach(key -> parameters.putIfAbsent(key, ""));
            }
            connection.setParams(parameters);
            connection.setCredentialKeys(entry.credentialKeys());
            imported.add(new ImportedConnection(
                    connection, entry.credentialKeys()));
        }
        return new ImportBundle(result.credentialPolicy(), result.risk(), imported);
    }

    static ConnectionPackageEntry toEntry(DesktopConnection connection) {
        if (connection == null) {
            throw new IllegalArgumentException("连接配置不能为空");
        }
        return ConnectionPackageEntry.fromMixedParameters(
                connection.getId(),
                connection.getName(),
                connection.getDbType(),
                connection.getParams(),
                connection.getCredentialKeys(),
                connection.getGroup(),
                connection.isFavorite());
    }

    private static Path requireReadableFile(Path source) {
        if (source == null) {
            throw new IllegalArgumentException("导入文件不能为空");
        }
        Path safe = source.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(safe)
                || !Files.isRegularFile(safe, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("导入路径不是安全的常规文件");
        }
        return safe;
    }

    private static void writeAtomically(Path target, byte[] content)
            throws IOException {
        if (target == null) {
            throw new IllegalArgumentException("导出文件不能为空");
        }
        Path safe = target.toAbsolutePath().normalize();
        if (Files.exists(safe, LinkOption.NOFOLLOW_LINKS)
                && (Files.isSymbolicLink(safe)
                || !Files.isRegularFile(safe, LinkOption.NOFOLLOW_LINKS))) {
            throw new IllegalArgumentException("导出路径不是安全的常规文件");
        }
        Path parent = safe.getParent();
        if (parent == null) {
            throw new IllegalArgumentException("导出文件缺少父目录");
        }
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, ".lyradb-connections-", ".tmp");
        try {
            Files.write(temporary, content);
            try {
                Files.move(temporary, safe,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, safe, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
