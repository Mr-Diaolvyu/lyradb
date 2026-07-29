package io.github.lexaquila.lyradb.desktop.storage;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.lexaquila.lyradb.desktop.model.AiProfile;
import io.github.lexaquila.lyradb.desktop.model.DesktopConnection;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 原生桌面版状态存储。
 *
 * <p>JSON 仅保存界面配置与加密后的连接凭据/AI Key；写入使用同目录临时文件和
 * 原子替换，避免断电产生半文件。</p>
 */
public final class DesktopStateStore {

    private static final int FORMAT_VERSION = 1;
    private static final Set<String> SENSITIVE_FIELDS = Set.of(
            "password", "accesskeysecret", "apikey", "secret",
            "accesskey", "privatekey", "token", "passphrase");

    private final Path stateFile;
    private final DesktopVault vault;
    private final ObjectMapper mapper;
    private final List<DesktopConnection> connections = new ArrayList<>();
    private AiProfile aiProfile = new AiProfile();

    public DesktopStateStore(Path dataDirectory, DesktopVault vault) {
        this.stateFile = dataDirectory.toAbsolutePath().normalize().resolve("desktop-state.json");
        this.vault = vault;
        this.mapper = new ObjectMapper()
                .findAndRegisterModules()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(SerializationFeature.INDENT_OUTPUT);
        load();
    }

    public synchronized List<DesktopConnection> listConnections() {
        return connections.stream()
                .map(DesktopConnection::copy)
                .sorted(Comparator.comparing(DesktopConnection::isFavorite).reversed()
                        .thenComparing(DesktopConnection::getName,
                                String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    public synchronized Optional<DesktopConnection> findConnection(String id) {
        return connections.stream()
                .filter(connection -> connection.getId().equals(id))
                .findFirst()
                .map(DesktopConnection::copy);
    }

    public synchronized DesktopConnection saveConnection(DesktopConnection value) {
        DesktopConnection connection = value.copy();
        validate(connection);
        if (connection.getId() == null || connection.getId().isBlank()) {
            connection.setId(UUID.randomUUID().toString());
        }
        connections.removeIf(existing -> existing.getId().equals(connection.getId()));
        connections.add(connection);
        persist();
        return connection.copy();
    }

    public synchronized void deleteConnection(String id) {
        boolean removed = connections.removeIf(connection -> connection.getId().equals(id));
        if (removed) {
            persist();
        }
    }

    public synchronized AiProfile getAiProfile() {
        return aiProfile.copy();
    }

    public synchronized void saveAiProfile(AiProfile profile) {
        if (profile == null) {
            throw new IllegalArgumentException("AI 配置不能为空");
        }
        if (!profile.getBaseUrl().isBlank() && profile.getModel().isBlank()) {
            throw new IllegalArgumentException("AI 模型不能为空");
        }
        this.aiProfile = profile.copy();
        persist();
    }

    private void load() {
        if (!Files.exists(stateFile, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isSymbolicLink(stateFile)
                || !Files.isRegularFile(stateFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("桌面状态文件类型不安全");
        }
        try {
            PersistedState persisted = mapper.readValue(stateFile.toFile(), PersistedState.class);
            if (persisted.formatVersion != FORMAT_VERSION) {
                throw new IllegalStateException(
                        "不支持的桌面状态版本: " + persisted.formatVersion);
            }
            connections.clear();
            if (persisted.connections != null) {
                for (PersistedConnection stored : persisted.connections) {
                    DesktopConnection connection = new DesktopConnection();
                    connection.setId(stored.id);
                    connection.setName(stored.name);
                    connection.setDbType(stored.dbType);
                    connection.setGroup(stored.group);
                    connection.setFavorite(stored.favorite);
                    connection.setParams(decryptParams(stored.params));
                    validate(connection);
                    connections.add(connection);
                }
            }
            if (persisted.ai != null) {
                AiProfile loaded = new AiProfile();
                loaded.setProviderKey(persisted.ai.providerKey);
                loaded.setDisplayName(persisted.ai.displayName);
                loaded.setBaseUrl(persisted.ai.baseUrl);
                loaded.setModel(persisted.ai.model);
                loaded.setTemperature(persisted.ai.temperature);
                loaded.setMaxTokens(persisted.ai.maxTokens);
                loaded.setApiKey(decryptSensitive(persisted.ai.encryptedApiKey));
                aiProfile = loaded;
            }
        } catch (IOException exception) {
            throw new IllegalStateException("读取桌面状态失败，原文件未被覆盖", exception);
        }
    }

    private void persist() {
        PersistedState state = new PersistedState();
        state.formatVersion = FORMAT_VERSION;
        state.connections = connections.stream().map(connection -> {
            PersistedConnection stored = new PersistedConnection();
            stored.id = connection.getId();
            stored.name = connection.getName();
            stored.dbType = connection.getDbType();
            stored.group = connection.getGroup();
            stored.favorite = connection.isFavorite();
            stored.params = encryptParams(connection.getParams());
            return stored;
        }).toList();
        state.ai = new PersistedAiProfile();
        state.ai.providerKey = aiProfile.getProviderKey();
        state.ai.displayName = aiProfile.getDisplayName();
        state.ai.baseUrl = aiProfile.getBaseUrl();
        state.ai.model = aiProfile.getModel();
        state.ai.temperature = aiProfile.getTemperature();
        state.ai.maxTokens = aiProfile.getMaxTokens();
        state.ai.encryptedApiKey = encryptSensitive(aiProfile.getApiKey());

        try {
            Path parent = stateFile.getParent();
            Files.createDirectories(parent);
            Path temporary = Files.createTempFile(parent, ".desktop-state-", ".tmp");
            try {
                mapper.writeValue(temporary.toFile(), state);
                try {
                    Files.move(temporary, stateFile,
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException unsupported) {
                    Files.move(temporary, stateFile, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("保存桌面状态失败", exception);
        }
    }

    private Map<String, Object> encryptParams(Map<String, Object> params) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (params == null) {
            return result;
        }
        params.forEach((key, value) -> {
            if (isSensitive(key) && value != null && !value.toString().isBlank()) {
                result.put(key, vault.encrypt(value.toString()));
            } else {
                result.put(key, value);
            }
        });
        return result;
    }

    private Map<String, Object> decryptParams(Map<String, Object> params) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (params == null) {
            return result;
        }
        params.forEach((key, value) -> {
            if (isSensitive(key) && value != null && !value.toString().isBlank()) {
                result.put(key, decryptSensitive(value.toString()));
            } else {
                result.put(key, value);
            }
        });
        return result;
    }

    private String encryptSensitive(String value) {
        return value == null || value.isBlank() ? "" : vault.encrypt(value);
    }

    private String decryptSensitive(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        if (!DesktopVault.isEncrypted(value)) {
            throw new IllegalStateException("检测到未加密敏感字段，拒绝加载");
        }
        return vault.decrypt(value);
    }

    private static boolean isSensitive(String key) {
        return key != null && SENSITIVE_FIELDS.contains(
                key.replace("-", "").replace("_", "").toLowerCase(Locale.ROOT));
    }

    private static void validate(DesktopConnection connection) {
        if (connection.getName().isBlank()) {
            throw new IllegalArgumentException("连接名称不能为空");
        }
        if (connection.getDbType().isBlank()) {
            throw new IllegalArgumentException("数据库类型不能为空");
        }
    }

    public static final class PersistedState {
        public int formatVersion;
        public List<PersistedConnection> connections = List.of();
        public PersistedAiProfile ai;
    }

    public static final class PersistedConnection {
        public String id;
        public String name;
        public String dbType;
        public Map<String, Object> params = Map.of();
        public String group;
        public boolean favorite;
    }

    public static final class PersistedAiProfile {
        public String providerKey;
        public String displayName;
        public String baseUrl;
        public String model;
        public String encryptedApiKey;
        public double temperature = 0.2D;
        public int maxTokens = 4096;
    }
}
