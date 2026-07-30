package io.github.lexaquila.lyradb.transfer.connection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static io.github.lexaquila.lyradb.transfer.connection.ConnectionPackageException.Code;

/**
 * LyraDB 版本化连接配置包 v1 编解码器。
 *
 * <p>加密模式使用 PBKDF2-HMAC-SHA256 派生 AES-256 密钥，并以 AES-GCM
 * 加密完整连接列表。口令只作为方法参数存在，不会写入序列化模型、异常消息或
 * 日志。本类本身不记录任何日志。</p>
 */
public final class ConnectionPackageCodec {

    public static final String FORMAT = "lyradb.connection-package";
    public static final int FORMAT_VERSION = 1;
    public static final String ENCRYPTION_ALGORITHM = "AES-256-GCM";
    public static final String KEY_DERIVATION_ALGORITHM = "PBKDF2-HMAC-SHA256";
    public static final int PBKDF2_ITERATIONS = 310_000;
    public static final int AES_KEY_BITS = 256;
    public static final int GCM_TAG_BITS = 128;
    public static final int SALT_BYTES = 16;
    public static final int GCM_IV_BYTES = 12;
    public static final String INTEGRITY_ALGORITHM = "SHA-256";

    private static final int MIN_EXPORT_PASSWORD_LENGTH = 8;
    private static final int MAX_PASSWORD_LENGTH = 1_024;
    private static final int SHA_256_BYTES = 32;
    private static final String FALLBACK_SOURCE_VERSION = "3.1.0";
    private static final Set<String> ROOT_COMMON_FIELDS = Set.of(
            "format", "version", "createdAt", "sourceVersion",
            "credentialMode", "risk", "integrity");
    private static final Set<String> ENTRY_FIELDS = Set.of(
            "id", "name", "dbType", "displayName", "parameters", "credentials",
            "credentialKeys", "group", "color", "description", "tags",
            "favorite", "sortOrder", "autoConnect");
    private static final Set<String> RISK_FIELDS = Set.of(
            "code", "plaintextDatabaseCredentials",
            "credentialsEncrypted", "credentialsOmitted");
    private static final Set<String> ENCRYPTION_FIELDS = Set.of(
            "algorithm", "keyDerivation", "iterations", "keyBits", "tagBits",
            "saltBase64", "ivBase64");
    private static final Set<String> INTEGRITY_FIELDS = Set.of(
            "algorithm", "valueBase64");
    private static final Set<String> PAYLOAD_FIELDS = Set.of("connections");
    private static final Set<String> FORBIDDEN_APPLICATION_KEYS = Set.of(
            "aikey", "aiapikey", "openaiapikey", "encryptedapikey",
            "providerapikey", "desktopmasterkey", "enterprisemasterkey",
            "servermasterkey", "applicationmasterkey", "vaultmasterkey",
            "vaultkey", "lyradbmasterkey");

    private final ObjectMapper mapper;
    private final ConnectionPackageLimits limits;
    private final SecureRandom secureRandom;
    private final String sourceVersion;

    public ConnectionPackageCodec() {
        this(ConnectionPackageLimits.defaults());
    }

    public ConnectionPackageCodec(ConnectionPackageLimits limits) {
        this(limits, new SecureRandom());
    }

    ConnectionPackageCodec(ConnectionPackageLimits limits, SecureRandom secureRandom) {
        this.limits = Objects.requireNonNull(limits, "limits");
        this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom");
        this.sourceVersion = resolveSourceVersion();
        this.mapper = new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }

    /**
     * 导出脱敏连接包。所有 credentials 值都会被删除。
     */
    public byte[] exportWithoutCredentials(List<ConnectionPackageEntry> connections)
            throws ConnectionPackageException {
        List<ConnectionPackageEntry> prepared =
                prepareForExport(connections, CredentialExportPolicy.OMIT);
        return serializeUnencrypted(prepared, CredentialExportPolicy.OMIT);
    }

    /**
     * 显式导出含明文数据库凭据的高风险连接包。
     */
    public byte[] exportWithPlaintextCredentials(
            List<ConnectionPackageEntry> connections)
            throws ConnectionPackageException {
        List<ConnectionPackageEntry> prepared =
                prepareForExport(connections, CredentialExportPolicy.PLAINTEXT);
        return serializeUnencrypted(prepared, CredentialExportPolicy.PLAINTEXT);
    }

    /**
     * 使用用户提供的导出口令加密连接包。
     */
    public byte[] exportWithPassword(
            List<ConnectionPackageEntry> connections, char[] exportPassword)
            throws ConnectionPackageException {
        requireExportPassword(exportPassword);
        List<ConnectionPackageEntry> prepared =
                prepareForExport(connections, CredentialExportPolicy.PASSWORD_ENCRYPTED);
        byte[] payload = null;
        byte[] derivedKey = null;
        try {
            ObjectNode payloadNode = mapper.createObjectNode();
            payloadNode.set("connections", connectionsNode(prepared));
            payload = mapper.writeValueAsBytes(payloadNode);
            ensureWithinFileLimit(payload.length);

            byte[] salt = randomBytes(SALT_BYTES);
            byte[] iv = randomBytes(GCM_IV_BYTES);
            derivedKey = deriveKey(exportPassword, salt);
            byte[] encrypted = encrypt(payload, derivedKey, iv);

            ObjectNode root = baseRoot(CredentialExportPolicy.PASSWORD_ENCRYPTED);
            ObjectNode encryption = root.putObject("encryption");
            encryption.put("algorithm", ENCRYPTION_ALGORITHM);
            encryption.put("keyDerivation", KEY_DERIVATION_ALGORITHM);
            encryption.put("iterations", PBKDF2_ITERATIONS);
            encryption.put("keyBits", AES_KEY_BITS);
            encryption.put("tagBits", GCM_TAG_BITS);
            encryption.put("saltBase64", Base64.getEncoder().encodeToString(salt));
            encryption.put("ivBase64", Base64.getEncoder().encodeToString(iv));
            root.put("encryptedPayload",
                    Base64.getEncoder().encodeToString(encrypted));
            addIntegrity(root);
            return serializeRoot(root);
        } catch (ConnectionPackageException exception) {
            throw exception;
        } catch (GeneralSecurityException exception) {
            throw new ConnectionPackageException(Code.CRYPTO_UNAVAILABLE,
                    "当前运行环境无法创建安全的连接配置包", exception);
        } catch (IOException exception) {
            throw new ConnectionPackageException(Code.INVALID_INPUT,
                    "连接配置无法序列化");
        } finally {
            clear(payload);
            clear(derivedKey);
        }
    }

    public ConnectionPackageReadResult read(byte[] source, char[] exportPassword)
            throws ConnectionPackageException {
        if (source == null) {
            throw error(Code.INVALID_INPUT, "连接配置包内容不能为空");
        }
        ensureWithinFileLimit(source.length);
        return parse(Arrays.copyOf(source, source.length), exportPassword);
    }

    /**
     * 从流读取连接配置包。不会关闭调用方提供的流。
     */
    public ConnectionPackageReadResult read(
            InputStream source, char[] exportPassword)
            throws ConnectionPackageException {
        if (source == null) {
            throw error(Code.INVALID_INPUT, "连接配置包输入流不能为空");
        }
        return parse(readLimited(source), exportPassword);
    }

    public ConnectionPackageReadResult read(Path source, char[] exportPassword)
            throws ConnectionPackageException {
        if (source == null) {
            throw error(Code.INVALID_INPUT, "连接配置包路径不能为空");
        }
        try {
            if (Files.size(source) > limits.maxFileBytes()) {
                throw error(Code.FILE_TOO_LARGE, "连接配置包超过允许大小");
            }
            try (InputStream input = Files.newInputStream(source)) {
                return read(input, exportPassword);
            }
        } catch (ConnectionPackageException exception) {
            throw exception;
        } catch (IOException exception) {
            throw error(Code.IO_ERROR, "无法读取连接配置包");
        }
    }

    private ConnectionPackageReadResult parse(byte[] source, char[] exportPassword)
            throws ConnectionPackageException {
        JsonNode parsed;
        try {
            parsed = mapper.readTree(source);
        } catch (IOException exception) {
            throw error(Code.MALFORMED_PACKAGE, "连接配置包不是有效 JSON");
        }
        validateTree(parsed, 0);
        ObjectNode root = requireObject(parsed, "连接配置包根节点无效");
        validateHeader(root);
        verifyIntegrity(root);
        CredentialExportPolicy policy = parsePolicy(root);
        ConnectionPackageRisk risk = parseAndValidateRisk(root, policy);

        List<ConnectionPackageEntry> connections;
        if (policy == CredentialExportPolicy.PASSWORD_ENCRYPTED) {
            rejectUnknownFields(root, union(ROOT_COMMON_FIELDS,
                    Set.of("encryption", "encryptedPayload")));
            connections = decryptConnections(root, exportPassword);
        } else {
            rejectUnknownFields(root, union(ROOT_COMMON_FIELDS, Set.of("connections")));
            JsonNode array = required(root, "connections");
            connections = parseConnections(array, policy);
        }
        return new ConnectionPackageReadResult(FORMAT_VERSION,
                Instant.parse(requireText(root, "createdAt")),
                requireText(root, "sourceVersion"), policy, risk,
                connections);
    }

    private List<ConnectionPackageEntry> decryptConnections(
            ObjectNode root, char[] exportPassword)
            throws ConnectionPackageException {
        requireReadPassword(exportPassword);
        ObjectNode encryption = requireObject(
                required(root, "encryption"), "加密参数无效");
        rejectUnknownFields(encryption, ENCRYPTION_FIELDS);
        requireExactText(encryption, "algorithm", ENCRYPTION_ALGORITHM);
        requireExactText(encryption, "keyDerivation", KEY_DERIVATION_ALGORITHM);
        requireExactInt(encryption, "iterations", PBKDF2_ITERATIONS);
        requireExactInt(encryption, "keyBits", AES_KEY_BITS);
        requireExactInt(encryption, "tagBits", GCM_TAG_BITS);
        byte[] salt = decodeBase64(encryption, "saltBase64", SALT_BYTES);
        byte[] iv = decodeBase64(encryption, "ivBase64", GCM_IV_BYTES);
        byte[] encrypted = decodeBase64Text(
                requireText(root, "encryptedPayload"), "加密载荷无效");
        if (encrypted.length < GCM_TAG_BITS / Byte.SIZE) {
            throw error(Code.MALFORMED_PACKAGE, "加密载荷无效");
        }

        byte[] derivedKey = null;
        byte[] plaintext = null;
        try {
            derivedKey = deriveKey(exportPassword, salt);
            plaintext = decrypt(encrypted, derivedKey, iv);
            ensureWithinFileLimit(plaintext.length);
            JsonNode payload;
            try {
                payload = mapper.readTree(plaintext);
            } catch (IOException exception) {
                throw error(Code.DECRYPTION_FAILED,
                        "连接配置包无法解密：口令错误或文件已被篡改");
            }
            validateTree(payload, 0);
            ObjectNode payloadObject =
                    requireObject(payload, "解密后的连接配置包无效");
            rejectUnknownFields(payloadObject, PAYLOAD_FIELDS);
            return parseConnections(required(payloadObject, "connections"),
                    CredentialExportPolicy.PASSWORD_ENCRYPTED);
        } catch (AEADBadTagException exception) {
            throw error(Code.DECRYPTION_FAILED,
                    "连接配置包无法解密：口令错误或文件已被篡改");
        } catch (GeneralSecurityException exception) {
            throw new ConnectionPackageException(Code.CRYPTO_UNAVAILABLE,
                    "当前运行环境无法解密连接配置包", exception);
        } finally {
            clear(derivedKey);
            clear(plaintext);
        }
    }

    private byte[] serializeUnencrypted(
            List<ConnectionPackageEntry> connections,
            CredentialExportPolicy policy) throws ConnectionPackageException {
        ObjectNode root = baseRoot(policy);
        root.set("connections", connectionsNode(connections));
        addIntegrity(root);
        return serializeRoot(root);
    }

    private ObjectNode baseRoot(CredentialExportPolicy policy) {
        ObjectNode root = mapper.createObjectNode();
        root.put("format", FORMAT);
        root.put("version", FORMAT_VERSION);
        root.put("createdAt", Instant.now().toString());
        root.put("sourceVersion", sourceVersion);
        root.put("credentialMode", policy.name());
        ConnectionPackageRisk risk = ConnectionPackageRisk.forPolicy(policy);
        ObjectNode riskNode = root.putObject("risk");
        riskNode.put("code", risk.name());
        riskNode.put("plaintextDatabaseCredentials",
                risk.hasPlaintextDatabaseCredentials());
        riskNode.put("credentialsEncrypted", risk.hasEncryptedCredentials());
        riskNode.put("credentialsOmitted", risk.hasOmittedCredentials());
        return root;
    }

    private ArrayNode connectionsNode(List<ConnectionPackageEntry> connections) {
        ArrayNode array = mapper.createArrayNode();
        for (ConnectionPackageEntry entry : connections) {
            ObjectNode node = array.addObject();
            node.put("id", entry.id());
            node.put("name", entry.name());
            node.put("dbType", entry.dbType());
            node.put("displayName", entry.displayName());
            node.set("parameters", mapper.valueToTree(entry.parameters()));
            node.set("credentials", mapper.valueToTree(entry.credentials()));
            ArrayNode credentialKeys = node.putArray("credentialKeys");
            entry.credentialKeys().forEach(credentialKeys::add);
            node.put("group", entry.group());
            node.put("color", entry.color());
            node.put("description", entry.description());
            ArrayNode tags = node.putArray("tags");
            entry.tags().forEach(tags::add);
            node.put("favorite", entry.favorite());
            node.put("sortOrder", entry.sortOrder());
            node.put("autoConnect", entry.autoConnect());
        }
        return array;
    }

    private byte[] serializeRoot(ObjectNode root) throws ConnectionPackageException {
        try {
            byte[] result = mapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsBytes(root);
            ensureWithinFileLimit(result.length);
            return result;
        } catch (ConnectionPackageException exception) {
            throw exception;
        } catch (IOException exception) {
            throw error(Code.INVALID_INPUT, "连接配置无法序列化");
        }
    }

    private void addIntegrity(ObjectNode root) throws ConnectionPackageException {
        byte[] digest = digest(root);
        try {
            ObjectNode integrity = root.putObject("integrity");
            integrity.put("algorithm", INTEGRITY_ALGORITHM);
            integrity.put("valueBase64",
                    Base64.getEncoder().encodeToString(digest));
        } finally {
            clear(digest);
        }
    }

    private void verifyIntegrity(ObjectNode root) throws ConnectionPackageException {
        ObjectNode integrity = requireObject(
                required(root, "integrity"), "完整性信息无效");
        rejectUnknownFields(integrity, INTEGRITY_FIELDS);
        requireExactText(integrity, "algorithm", INTEGRITY_ALGORITHM);
        byte[] expected = decodeBase64(
                integrity, "valueBase64", SHA_256_BYTES);
        ObjectNode unsigned = root.deepCopy();
        unsigned.remove("integrity");
        byte[] actual = digest(unsigned);
        try {
            if (!MessageDigest.isEqual(expected, actual)) {
                throw error(Code.INTEGRITY_FAILED,
                        "连接配置包完整性校验失败，文件可能已损坏或被修改");
            }
        } finally {
            clear(expected);
            clear(actual);
        }
    }

    private byte[] digest(ObjectNode root) throws ConnectionPackageException {
        try {
            byte[] canonical = mapper.writeValueAsBytes(root);
            ensureWithinFileLimit(canonical.length);
            return MessageDigest.getInstance(INTEGRITY_ALGORITHM)
                    .digest(canonical);
        } catch (ConnectionPackageException exception) {
            throw exception;
        } catch (GeneralSecurityException exception) {
            throw new ConnectionPackageException(Code.CRYPTO_UNAVAILABLE,
                    "当前运行环境无法校验连接配置包完整性", exception);
        } catch (IOException exception) {
            throw error(Code.INVALID_INPUT, "连接配置无法序列化");
        }
    }

    private List<ConnectionPackageEntry> prepareForExport(
            List<ConnectionPackageEntry> source,
            CredentialExportPolicy policy) throws ConnectionPackageException {
        if (source == null) {
            throw error(Code.INVALID_INPUT, "连接列表不能为空");
        }
        if (source.size() > limits.maxConnections()) {
            throw error(Code.TOO_MANY_CONNECTIONS, "连接数量超过允许上限");
        }
        List<ConnectionPackageEntry> result = new ArrayList<>(source.size());
        Set<String> ids = new HashSet<>();
        for (ConnectionPackageEntry entry : source) {
            if (entry == null) {
                throw error(Code.INVALID_FIELD, "连接列表包含空对象");
            }
            rejectSensitiveParameterTree(entry);
            ConnectionPackageEntry prepared = entry;
            if (!entry.id().isBlank() && !ids.add(entry.id())) {
                throw error(Code.INVALID_FIELD, "连接标识不能重复");
            }
            if (policy == CredentialExportPolicy.OMIT) {
                prepared = new ConnectionPackageEntry(
                        entry.id(), entry.name(), entry.dbType(), entry.displayName(),
                        redactSensitiveNestedValues(entry.parameters()),
                        Map.of(), entry.credentialKeys(), entry.group(), entry.color(),
                        entry.description(), entry.tags(), entry.favorite(),
                        entry.sortOrder(), entry.autoConnect());
            }
            validateEntry(prepared, policy);
            result.add(prepared);
        }
        return List.copyOf(result);
    }

    private List<ConnectionPackageEntry> parseConnections(
            JsonNode source, CredentialExportPolicy policy)
            throws ConnectionPackageException {
        if (!source.isArray()) {
            throw error(Code.INVALID_FIELD, "connections 必须是数组");
        }
        if (source.size() > limits.maxConnections()) {
            throw error(Code.TOO_MANY_CONNECTIONS, "连接数量超过允许上限");
        }
        List<ConnectionPackageEntry> result = new ArrayList<>(source.size());
        Set<String> ids = new HashSet<>();
        for (JsonNode element : source) {
            ObjectNode node = requireObject(element, "连接项必须是对象");
            rejectUnknownFields(node, ENTRY_FIELDS);
            try {
                Map<String, Object> parameters =
                        requireValueMap(node, "parameters");
                Map<String, Object> credentials =
                        requireValueMap(node, "credentials");
                Set<String> credentialKeys =
                        requireStringSet(node, "credentialKeys");
                List<String> tags = requireStringList(node, "tags");
                ConnectionPackageEntry entry = new ConnectionPackageEntry(
                        requireText(node, "id"),
                        requireText(node, "name"),
                        requireText(node, "dbType"),
                        requireText(node, "displayName"),
                        parameters,
                        credentials,
                        credentialKeys,
                        requireText(node, "group"),
                        requireText(node, "color"),
                        requireText(node, "description"),
                        tags,
                        requireBoolean(node, "favorite"),
                        requireInt(node, "sortOrder"),
                        requireBoolean(node, "autoConnect"));
                validateEntry(entry, policy);
                if (policy == CredentialExportPolicy.OMIT
                        && !entry.credentials().isEmpty()) {
                    throw error(Code.INVALID_FIELD,
                            "脱敏连接包不得包含凭据值");
                }
                if (!entry.id().isBlank() && !ids.add(entry.id())) {
                    throw error(Code.INVALID_FIELD, "连接标识不能重复");
                }
                result.add(entry);
            } catch (ConnectionPackageException exception) {
                throw exception;
            } catch (IllegalArgumentException exception) {
                throw error(Code.INVALID_FIELD, "连接项字段无效");
            }
        }
        return List.copyOf(result);
    }

    private void validateEntry(
            ConnectionPackageEntry entry, CredentialExportPolicy policy)
            throws ConnectionPackageException {
        validateString(entry.id());
        validateString(entry.name());
        validateString(entry.dbType());
        validateString(entry.displayName());
        validateString(entry.group());
        validateString(entry.color());
        validateString(entry.description());
        validateStrings(entry.tags());
        validateStrings(entry.credentialKeys());
        int parameterCount = entry.parameters().size() + entry.credentials().size();
        if (parameterCount > limits.maxParametersPerConnection()
                || entry.credentialKeys().size()
                > limits.maxParametersPerConnection()) {
            throw error(Code.INVALID_FIELD,
                    "单个连接的参数数量超过允许上限");
        }
        validateValue(entry.parameters(), 0);
        validateValue(entry.credentials(), 0);
        rejectForbiddenApplicationKeys(entry.parameters());
        rejectForbiddenApplicationKeys(entry.credentials());
        for (String key : entry.parameters().keySet()) {
            if (ConnectionPackageEntry.isDefaultCredentialName(key)
                    || containsIgnoreCase(entry.credentialKeys(), key)) {
                throw error(Code.INVALID_FIELD,
                        "敏感连接参数必须放入 credentials");
            }
        }
        for (String key : entry.credentials().keySet()) {
            if (!containsIgnoreCase(entry.credentialKeys(), key)) {
                throw error(Code.INVALID_FIELD,
                        "credentials 字段必须声明对应凭据名");
            }
        }
        if (containsSensitiveKey(
                entry.parameters(), entry.credentialKeys())) {
            throw error(Code.INVALID_FIELD,
                    "普通参数树中的敏感连接参数必须放入顶层 credentials");
        }
    }

    private void rejectSensitiveParameterTree(
            ConnectionPackageEntry entry) throws ConnectionPackageException {
        if (containsSensitiveKey(
                entry.parameters(), entry.credentialKeys())) {
            throw error(Code.INVALID_FIELD,
                    "普通参数树中的敏感连接参数必须放入顶层 credentials");
        }
    }

    private CredentialExportPolicy parsePolicy(ObjectNode root)
            throws ConnectionPackageException {
        String value = requireText(root, "credentialMode");
        try {
            return CredentialExportPolicy.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw error(Code.INVALID_FIELD, "凭据策略无效");
        }
    }

    private ConnectionPackageRisk parseAndValidateRisk(
            ObjectNode root, CredentialExportPolicy policy)
            throws ConnectionPackageException {
        ObjectNode node = requireObject(required(root, "risk"), "风险标识无效");
        rejectUnknownFields(node, RISK_FIELDS);
        ConnectionPackageRisk expected = ConnectionPackageRisk.forPolicy(policy);
        requireExactText(node, "code", expected.name());
        requireExactBoolean(node, "plaintextDatabaseCredentials",
                expected.hasPlaintextDatabaseCredentials());
        requireExactBoolean(node, "credentialsEncrypted",
                expected.hasEncryptedCredentials());
        requireExactBoolean(node, "credentialsOmitted",
                expected.hasOmittedCredentials());
        return expected;
    }

    private void validateHeader(ObjectNode root) throws ConnectionPackageException {
        String format = requireText(root, "format");
        if (!FORMAT.equals(format)) {
            throw error(Code.UNSUPPORTED_FORMAT, "不是 LyraDB 连接配置包");
        }
        JsonNode version = required(root, "version");
        if (!version.isIntegralNumber()) {
            throw error(Code.INVALID_FIELD, "连接配置包版本字段无效");
        }
        if (version.intValue() != FORMAT_VERSION) {
            throw error(Code.UNSUPPORTED_VERSION, "不支持此连接配置包版本");
        }
        String createdAt = requireText(root, "createdAt");
        try {
            Instant.parse(createdAt);
        } catch (RuntimeException exception) {
            throw error(Code.INVALID_FIELD, "连接配置包创建时间无效");
        }
        String packageSourceVersion = requireText(root, "sourceVersion");
        if (!packageSourceVersion.matches(
                "\\d+\\.\\d+\\.\\d+(?:[-+][0-9A-Za-z.-]+)?")) {
            throw error(Code.INVALID_FIELD, "连接配置包来源版本无效");
        }
    }

    private static String resolveSourceVersion() {
        Package packageInfo = ConnectionPackageCodec.class.getPackage();
        String implementationVersion = packageInfo == null
                ? null : packageInfo.getImplementationVersion();
        return implementationVersion == null || implementationVersion.isBlank()
                ? FALLBACK_SOURCE_VERSION : implementationVersion.trim();
    }

    private void validateTree(JsonNode node, int depth)
            throws ConnectionPackageException {
        if (node == null || node.isMissingNode()) {
            throw error(Code.MALFORMED_PACKAGE, "连接配置包结构不完整");
        }
        if (depth > limits.maxNestingDepth()) {
            throw error(Code.INVALID_FIELD, "连接配置包嵌套层级超过允许上限");
        }
        if (node.isTextual()) {
            validateString(node.textValue());
            return;
        }
        if (node.isArray()) {
            if (node.size() > limits.maxCollectionElements()) {
                throw error(Code.INVALID_FIELD, "连接配置包数组超过允许上限");
            }
            for (JsonNode child : node) {
                validateTree(child, depth + 1);
            }
            return;
        }
        if (node.isObject()) {
            if (node.size() > limits.maxCollectionElements()) {
                throw error(Code.INVALID_FIELD, "连接配置包对象超过允许上限");
            }
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                validateString(field.getKey());
                validateTree(field.getValue(), depth + 1);
            }
            return;
        }
        if (!node.isNull() && !node.isBoolean() && !node.isNumber()) {
            throw error(Code.INVALID_FIELD, "连接配置包含不支持的值类型");
        }
    }

    private void validateValue(Object value, int depth)
            throws ConnectionPackageException {
        if (depth > limits.maxNestingDepth()) {
            throw error(Code.INVALID_FIELD, "连接参数嵌套层级超过允许上限");
        }
        if (value == null || value instanceof Boolean || value instanceof Number) {
            return;
        }
        if (value instanceof String string) {
            validateString(string);
            return;
        }
        if (value instanceof Map<?, ?> map) {
            if (map.size() > limits.maxCollectionElements()) {
                throw error(Code.INVALID_FIELD, "连接参数对象超过允许上限");
            }
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                validateString((String) entry.getKey());
                validateValue(entry.getValue(), depth + 1);
            }
            return;
        }
        if (value instanceof List<?> list) {
            if (list.size() > limits.maxCollectionElements()) {
                throw error(Code.INVALID_FIELD, "连接参数数组超过允许上限");
            }
            for (Object element : list) {
                validateValue(element, depth + 1);
            }
            return;
        }
        throw error(Code.INVALID_FIELD, "连接参数包含不支持的值类型");
    }

    private Map<String, Object> requireValueMap(ObjectNode node, String field)
            throws ConnectionPackageException {
        JsonNode value = required(node, field);
        if (!value.isObject()) {
            throw error(Code.INVALID_FIELD, field + " 必须是对象");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = value.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            result.put(entry.getKey(), toPlainValue(entry.getValue()));
        }
        return result;
    }

    private Object toPlainValue(JsonNode node) throws ConnectionPackageException {
        if (node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.textValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isIntegralNumber()) {
            if (node.canConvertToInt()) {
                return node.intValue();
            }
            if (node.canConvertToLong()) {
                return node.longValue();
            }
            return node.bigIntegerValue();
        }
        if (node.isFloatingPointNumber()) {
            return node.decimalValue();
        }
        if (node.isArray()) {
            List<Object> values = new ArrayList<>(node.size());
            for (JsonNode child : node) {
                values.add(toPlainValue(child));
            }
            return values;
        }
        if (node.isObject()) {
            Map<String, Object> values = new LinkedHashMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                values.put(field.getKey(), toPlainValue(field.getValue()));
            }
            return values;
        }
        throw error(Code.INVALID_FIELD, "连接参数包含不支持的值类型");
    }

    private Set<String> requireStringSet(ObjectNode node, String field)
            throws ConnectionPackageException {
        return new LinkedHashSet<>(requireStringList(node, field));
    }

    private List<String> requireStringList(ObjectNode node, String field)
            throws ConnectionPackageException {
        JsonNode value = required(node, field);
        if (!value.isArray() || value.size() > limits.maxCollectionElements()) {
            throw error(Code.INVALID_FIELD, field + " 必须是受限字符串数组");
        }
        List<String> result = new ArrayList<>(value.size());
        for (JsonNode element : value) {
            if (!element.isTextual()) {
                throw error(Code.INVALID_FIELD, field + " 必须是字符串数组");
            }
            validateString(element.textValue());
            result.add(element.textValue());
        }
        return result;
    }

    private Map<String, Object> redactSensitiveNestedValues(
            Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            if (!ConnectionPackageEntry.isDefaultCredentialName(entry.getKey())) {
                result.put(entry.getKey(), redactValue(entry.getValue()));
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Object redactValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return redactSensitiveNestedValues((Map<String, Object>) map);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(this::redactValue).toList();
        }
        return value;
    }

    private boolean containsSensitiveKey(
            Object value, Set<String> declaredCredentialKeys) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = (String) entry.getKey();
                if (ConnectionPackageEntry.isDefaultCredentialName(key)
                        || containsIgnoreCase(declaredCredentialKeys, key)
                        || containsSensitiveKey(
                        entry.getValue(), declaredCredentialKeys)) {
                    return true;
                }
            }
            return false;
        }
        if (value instanceof List<?> list) {
            for (Object element : list) {
                if (containsSensitiveKey(element, declaredCredentialKeys)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void rejectForbiddenApplicationKeys(Map<String, Object> source)
            throws ConnectionPackageException {
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String normalized = ConnectionPackageEntry.normalizeKey(entry.getKey());
            if (FORBIDDEN_APPLICATION_KEYS.contains(normalized)
                    || normalized.contains("masterkey")
                    || normalized.contains("vaultkey")) {
                throw error(Code.INVALID_FIELD,
                        "连接配置包含禁止导出的应用级密钥字段");
            }
            if (entry.getValue() instanceof Map<?, ?> map) {
                rejectForbiddenApplicationKeys(castStringMap(map));
            } else if (entry.getValue() instanceof List<?> list) {
                for (Object element : list) {
                    if (element instanceof Map<?, ?> map) {
                        rejectForbiddenApplicationKeys(castStringMap(map));
                    }
                }
            }
        }
    }

    private static Map<String, Object> castStringMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            result.put((String) entry.getKey(), entry.getValue());
        }
        return result;
    }

    private void rejectUnknownFields(ObjectNode node, Set<String> allowed)
            throws ConnectionPackageException {
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            if (!allowed.contains(names.next())) {
                throw error(Code.INVALID_FIELD,
                        "连接配置包包含当前版本不支持的字段");
            }
        }
    }

    private static Set<String> union(Set<String> first, Set<String> second) {
        Set<String> result = new HashSet<>(first);
        result.addAll(second);
        return result;
    }

    private ObjectNode requireObject(JsonNode node, String message)
            throws ConnectionPackageException {
        if (node == null || !node.isObject()) {
            throw error(Code.INVALID_FIELD, message);
        }
        return (ObjectNode) node;
    }

    private JsonNode required(ObjectNode node, String field)
            throws ConnectionPackageException {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            throw error(Code.INVALID_FIELD, "连接配置包缺少必要字段");
        }
        return value;
    }

    private String requireText(ObjectNode node, String field)
            throws ConnectionPackageException {
        JsonNode value = required(node, field);
        if (!value.isTextual()) {
            throw error(Code.INVALID_FIELD, field + " 必须是字符串");
        }
        validateString(value.textValue());
        return value.textValue();
    }

    private boolean requireBoolean(ObjectNode node, String field)
            throws ConnectionPackageException {
        JsonNode value = required(node, field);
        if (!value.isBoolean()) {
            throw error(Code.INVALID_FIELD, field + " 必须是布尔值");
        }
        return value.booleanValue();
    }

    private int requireInt(ObjectNode node, String field)
            throws ConnectionPackageException {
        JsonNode value = required(node, field);
        if (!value.isInt()) {
            throw error(Code.INVALID_FIELD, field + " 必须是整数");
        }
        return value.intValue();
    }

    private void requireExactText(
            ObjectNode node, String field, String expected)
            throws ConnectionPackageException {
        if (!expected.equals(requireText(node, field))) {
            throw error(Code.INVALID_FIELD, "连接配置包算法或风险标识无效");
        }
    }

    private void requireExactInt(ObjectNode node, String field, int expected)
            throws ConnectionPackageException {
        if (requireInt(node, field) != expected) {
            throw error(Code.INVALID_FIELD, "连接配置包加密参数无效");
        }
    }

    private void requireExactBoolean(
            ObjectNode node, String field, boolean expected)
            throws ConnectionPackageException {
        if (requireBoolean(node, field) != expected) {
            throw error(Code.INVALID_FIELD, "连接配置包风险标识无效");
        }
    }

    private byte[] decodeBase64(ObjectNode node, String field, int expectedLength)
            throws ConnectionPackageException {
        byte[] result = decodeBase64Text(requireText(node, field), "加密参数无效");
        if (result.length != expectedLength) {
            throw error(Code.INVALID_FIELD, "连接配置包加密参数无效");
        }
        return result;
    }

    private byte[] decodeBase64Text(String value, String safeMessage)
            throws ConnectionPackageException {
        try {
            return Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException exception) {
            throw error(Code.INVALID_FIELD, safeMessage);
        }
    }

    private void validateStrings(Iterable<String> values)
            throws ConnectionPackageException {
        for (String value : values) {
            validateString(value);
        }
    }

    private void validateString(String value) throws ConnectionPackageException {
        if (value == null || value.length() > limits.maxStringLength()) {
            throw error(Code.INVALID_FIELD, "连接配置包字符串超过允许上限");
        }
    }

    private byte[] readLimited(InputStream source)
            throws ConnectionPackageException {
        try {
            ByteArrayOutputStream output =
                    new ByteArrayOutputStream(Math.min(limits.maxFileBytes(), 8192));
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = source.read(buffer)) != -1) {
                total += read;
                if (total > limits.maxFileBytes()) {
                    throw error(Code.FILE_TOO_LARGE, "连接配置包超过允许大小");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } catch (ConnectionPackageException exception) {
            throw exception;
        } catch (IOException exception) {
            throw error(Code.IO_ERROR, "无法读取连接配置包");
        }
    }

    private void ensureWithinFileLimit(int length)
            throws ConnectionPackageException {
        if (length > limits.maxFileBytes()) {
            throw error(Code.FILE_TOO_LARGE, "连接配置包超过允许大小");
        }
    }

    private void requireExportPassword(char[] password)
            throws ConnectionPackageException {
        if (password == null || password.length < MIN_EXPORT_PASSWORD_LENGTH
                || password.length > MAX_PASSWORD_LENGTH) {
            throw error(Code.PASSWORD_REQUIRED,
                    "加密导出口令长度必须在 8 到 1024 个字符之间");
        }
    }

    private void requireReadPassword(char[] password)
            throws ConnectionPackageException {
        if (password == null || password.length == 0
                || password.length > MAX_PASSWORD_LENGTH) {
            throw error(Code.PASSWORD_REQUIRED, "此连接配置包需要导出口令");
        }
    }

    private byte[] randomBytes(int length) {
        byte[] result = new byte[length];
        secureRandom.nextBytes(result);
        return result;
    }

    private byte[] deriveKey(char[] password, byte[] salt)
            throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(
                password, salt, PBKDF2_ITERATIONS, AES_KEY_BITS);
        try {
            SecretKeyFactory factory =
                    SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return factory.generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
    }

    private byte[] encrypt(byte[] plaintext, byte[] key, byte[] iv)
            throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        SecretKey secretKey = new SecretKeySpec(key, "AES");
        cipher.init(Cipher.ENCRYPT_MODE, secretKey,
                new GCMParameterSpec(GCM_TAG_BITS, iv));
        cipher.updateAAD(aad());
        return cipher.doFinal(plaintext);
    }

    private byte[] decrypt(byte[] encrypted, byte[] key, byte[] iv)
            throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        SecretKey secretKey = new SecretKeySpec(key, "AES");
        cipher.init(Cipher.DECRYPT_MODE, secretKey,
                new GCMParameterSpec(GCM_TAG_BITS, iv));
        cipher.updateAAD(aad());
        return cipher.doFinal(encrypted);
    }

    private byte[] aad() {
        String value = FORMAT + "|" + FORMAT_VERSION + "|"
                + CredentialExportPolicy.PASSWORD_ENCRYPTED.name() + "|"
                + ENCRYPTION_ALGORITHM + "|" + KEY_DERIVATION_ALGORITHM + "|"
                + PBKDF2_ITERATIONS + "|" + AES_KEY_BITS + "|" + GCM_TAG_BITS;
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private static boolean containsIgnoreCase(Set<String> values, String target) {
        String normalizedTarget = ConnectionPackageEntry.normalizeKey(target);
        return values.stream().map(ConnectionPackageEntry::normalizeKey)
                .anyMatch(normalizedTarget::equals);
    }

    private static void clear(byte[] value) {
        if (value != null) {
            Arrays.fill(value, (byte) 0);
        }
    }

    private static ConnectionPackageException error(Code code, String message) {
        return new ConnectionPackageException(code, message);
    }
}
