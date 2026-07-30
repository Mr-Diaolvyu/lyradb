package io.github.lexaquila.lyradb.desktop.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 个人桌面版保存的数据库连接。
 *
 * <p>该对象在内存中持有解密后的连接参数；落盘前由
 * {@code DesktopStateStore} 对敏感字段逐项加密。</p>
 */
public final class DesktopConnection {

    private String id = UUID.randomUUID().toString();
    private String name = "";
    private String dbType = "";
    private Map<String, Object> params = new LinkedHashMap<>();
    private Set<String> credentialKeys = Set.of();
    private String group = "";
    private boolean favorite;

    public DesktopConnection() {
    }

    public DesktopConnection copy() {
        DesktopConnection copy = new DesktopConnection();
        copy.id = id;
        copy.name = name;
        copy.dbType = dbType;
        copy.params = new LinkedHashMap<>(params);
        copy.credentialKeys = immutableCredentialKeys(credentialKeys);
        copy.group = group;
        copy.favorite = favorite;
        return copy;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = Objects.requireNonNullElse(name, "").trim();
    }

    public String getDbType() {
        return dbType;
    }

    public void setDbType(String dbType) {
        this.dbType = Objects.requireNonNullElse(dbType, "").trim().toUpperCase();
    }

    public Map<String, Object> getParams() {
        return params;
    }

    public void setParams(Map<String, Object> params) {
        this.params = params == null ? new LinkedHashMap<>() : new LinkedHashMap<>(params);
    }

    public Set<String> getCredentialKeys() {
        return immutableCredentialKeys(credentialKeys);
    }

    public void setCredentialKeys(Set<String> credentialKeys) {
        this.credentialKeys = immutableCredentialKeys(credentialKeys);
    }

    public String getGroup() {
        return group;
    }

    public void setGroup(String group) {
        this.group = Objects.requireNonNullElse(group, "").trim();
    }

    public boolean isFavorite() {
        return favorite;
    }

    public void setFavorite(boolean favorite) {
        this.favorite = favorite;
    }

    @Override
    public String toString() {
        return name.isBlank() ? dbType : name;
    }

    private static Set<String> immutableCredentialKeys(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        values.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .forEach(result::add);
        return result.isEmpty()
                ? Set.of()
                : Collections.unmodifiableSet(result);
    }
}
