package io.github.lexaquila.lyradb.transfer.connection;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * 可在个人版与企业版之间共享的数据库连接配置。
 *
 * <p>数据库敏感参数与普通参数显式分离。该模型只承载数据库连接信息，不得放入
 * AI Key、桌面主密钥、企业服务端主密钥或其他应用级密钥。</p>
 */
public record ConnectionPackageEntry(
        String id,
        String name,
        String dbType,
        String displayName,
        Map<String, Object> parameters,
        Map<String, Object> credentials,
        Set<String> credentialKeys,
        String group,
        String color,
        String description,
        List<String> tags,
        boolean favorite,
        int sortOrder,
        boolean autoConnect) {

    private static final Set<String> DEFAULT_CREDENTIAL_NAMES = Set.of(
            "password", "passphrase", "accesskeysecret", "secret",
            "clientsecret", "secretkey", "privatekey", "token",
            "authtoken", "refreshtoken", "apikey");

    public ConnectionPackageEntry {
        id = clean(id);
        name = required(name, "连接名称");
        dbType = required(dbType, "数据库类型").toUpperCase(Locale.ROOT);
        displayName = clean(displayName);
        parameters = immutableObjectMap(parameters);
        credentials = immutableObjectMap(credentials);
        credentialKeys = immutableCredentialKeys(credentialKeys, credentials.keySet());
        group = clean(group);
        color = clean(color);
        description = clean(description);
        tags = immutableStrings(tags);
    }

    /**
     * 适配桌面版现有的混合参数 Map。显式字段名与内置敏感字段识别结果都会进入
     * credentials，其余字段进入 parameters。
     */
    public static ConnectionPackageEntry fromMixedParameters(
            String id, String name, String dbType, Map<String, Object> mixedParameters,
            Set<String> credentialParameterNames, String group, boolean favorite) {
        return fromMixedParameters(id, name, dbType, "", mixedParameters,
                credentialParameterNames, group, "", "", List.of(),
                favorite, 0, false);
    }

    /**
     * 适配企业版连接 DTO 的完整字段集。
     */
    public static ConnectionPackageEntry fromMixedParameters(
            String id, String name, String dbType, String displayName,
            Map<String, Object> mixedParameters, Set<String> credentialParameterNames,
            String group, String color, String description, List<String> tags,
            boolean favorite, int sortOrder, boolean autoConnect) {
        Set<String> explicit = normalizedNames(credentialParameterNames);
        Map<String, Object> regular = new LinkedHashMap<>();
        Map<String, Object> sensitive = new LinkedHashMap<>();
        if (mixedParameters != null) {
            for (Map.Entry<String, Object> entry : mixedParameters.entrySet()) {
                String key = required(entry.getKey(), "连接参数名");
                if (explicit.contains(normalizeKey(key)) || isDefaultCredentialName(key)) {
                    sensitive.put(key, entry.getValue());
                } else {
                    regular.put(key, entry.getValue());
                }
            }
        }
        Set<String> keys = new LinkedHashSet<>();
        if (credentialParameterNames != null) {
            credentialParameterNames.stream()
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(value -> !value.isEmpty())
                    .forEach(keys::add);
        }
        keys.addAll(sensitive.keySet());
        return new ConnectionPackageEntry(id, name, dbType, displayName,
                regular, sensitive, keys, group, color, description, tags,
                favorite, sortOrder, autoConnect);
    }

    /**
     * 还原为现有 DesktopConnection/ConnectionDTO 使用的混合参数结构。
     */
    public Map<String, Object> mergedParameters() {
        Map<String, Object> merged = new LinkedHashMap<>(parameters);
        merged.putAll(credentials);
        return Collections.unmodifiableMap(merged);
    }

    static boolean isDefaultCredentialName(String value) {
        String normalized = normalizeKey(value);
        if (DEFAULT_CREDENTIAL_NAMES.contains(normalized)) {
            return true;
        }
        return normalized.endsWith("password")
                || normalized.endsWith("passphrase")
                || normalized.endsWith("secret")
                || normalized.endsWith("token")
                || normalized.endsWith("privatekey")
                || normalized.endsWith("apikey");
    }

    static String normalizeKey(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder normalized = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isLetterOrDigit(character)) {
                normalized.append(Character.toLowerCase(character));
            }
        }
        return normalized.toString();
    }

    private static Set<String> normalizedNames(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        Set<String> result = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                result.add(normalizeKey(value));
            }
        }
        return result;
    }

    private static Map<String, Object> immutableObjectMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copy = new TreeMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = required(entry.getKey(), "连接参数名");
            copy.put(key, immutableValue(entry.getValue()));
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(copy));
    }

    private static Object immutableValue(Object value) {
        if (value == null || value instanceof String || value instanceof Boolean
                || value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long
                || value instanceof Float || value instanceof Double
                || value instanceof BigInteger || value instanceof BigDecimal) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException("连接参数对象的键必须是字符串");
                }
                copy.put(key, immutableValue(entry.getValue()));
            }
            return immutableObjectMap(copy);
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object element : list) {
                copy.add(immutableValue(element));
            }
            return Collections.unmodifiableList(copy);
        }
        throw new IllegalArgumentException("连接参数只支持 JSON 基础类型");
    }

    private static Set<String> immutableCredentialKeys(
            Set<String> declared, Set<String> actual) {
        TreeMap<String, String> sorted = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (declared != null) {
            for (String key : declared) {
                if (key != null && !key.isBlank()) {
                    sorted.put(key.trim(), key.trim());
                }
            }
        }
        for (String key : actual) {
            sorted.put(key, key);
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(sorted.values()));
    }

    private static List<String> immutableStrings(List<String> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        List<String> result = source.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .distinct()
                .toList();
        return List.copyOf(result);
    }

    private static String required(String value, String label) {
        String cleaned = clean(value);
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        return cleaned;
    }

    private static String clean(String value) {
        return Objects.requireNonNullElse(value, "").trim();
    }
}
