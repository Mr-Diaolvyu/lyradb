package io.github.lexaquila.lyradb.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.Profiles;

/**
 * 桌面版主密钥初始化器。
 *
 * <p>在 Bean 创建前加载或生成每安装随机密钥，并注入 Jasypt 配置。显式提供的
 * {@code JASYPT_PASSWORD} 始终优先，密钥值不会写入日志。</p>
 */
public class DesktopSecretEnvironmentPostProcessor
        implements EnvironmentPostProcessor, Ordered {

    static final String GENERATED_PROPERTY = "jasypt.encryptor.password";
    static final String KEY_PATH_PROPERTY = "lyradb.desktop.master-key-file";
    private static final String PROPERTY_SOURCE_NAME = "desktopGeneratedMasterKey";
    private static final int KEY_BYTES = 32;

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment,
                                       SpringApplication application) {
        if (!environment.acceptsProfiles(Profiles.of("desktop"))) {
            return;
        }
        String configured = environment.getProperty(GENERATED_PROPERTY);
        if (configured != null && !configured.isBlank()) {
            return;
        }

        Path keyPath = resolveKeyPath(environment);
        String masterKey = loadOrCreate(keyPath);
        environment.getPropertySources().addFirst(new MapPropertySource(
                PROPERTY_SOURCE_NAME, Map.of(GENERATED_PROPERTY, masterKey)));
    }

    @Override
    public int getOrder() {
        // 在 ConfigData(application*.yml) 之后运行，才能尊重显式配置。
        return Ordered.HIGHEST_PRECEDENCE + 20;
    }

    private static Path resolveKeyPath(ConfigurableEnvironment environment) {
        String configuredPath = environment.getProperty(KEY_PATH_PROPERTY);
        if (configuredPath == null || configuredPath.isBlank()) {
            configuredPath = environment.getProperty("LYRADB_DESKTOP_KEY_PATH");
        }
        if (configuredPath == null || configuredPath.isBlank()) {
            String userHome = System.getProperty("user.home");
            if (userHome == null || userHome.isBlank()) {
                throw new IllegalStateException("无法确定桌面密钥目录");
            }
            return Path.of(userHome, ".lyradb", "master.key").toAbsolutePath().normalize();
        }
        return Path.of(configuredPath).toAbsolutePath().normalize();
    }

    private static String loadOrCreate(Path keyPath) {
        try {
            Path parent = keyPath.getParent();
            if (parent == null) {
                throw new IllegalStateException("桌面密钥路径必须包含父目录");
            }
            if (Files.exists(parent, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(parent)) {
                throw new IllegalStateException("桌面密钥目录不能是符号链接");
            }
            Files.createDirectories(parent);
            restrictDirectory(parent);

            if (Files.exists(keyPath, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(keyPath) || !Files.isRegularFile(
                        keyPath, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IllegalStateException("桌面密钥文件类型不安全");
                }
                return validateKey(Files.readString(keyPath, StandardCharsets.US_ASCII));
            }

            String generated = generateKey();
            try {
                Files.writeString(keyPath, generated, StandardCharsets.US_ASCII,
                        java.nio.file.StandardOpenOption.CREATE_NEW,
                        java.nio.file.StandardOpenOption.WRITE);
                restrictFile(keyPath);
                return generated;
            } catch (FileAlreadyExistsException concurrentCreate) {
                return validateKey(Files.readString(keyPath, StandardCharsets.US_ASCII));
            }
        } catch (IOException e) {
            throw new IllegalStateException("无法安全初始化桌面凭据主密钥", e);
        }
    }

    private static String generateKey() {
        byte[] bytes = new byte[KEY_BYTES];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String validateKey(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() < 40 || normalized.length() > 256
                || !normalized.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalStateException("桌面凭据主密钥文件格式无效");
        }
        return normalized;
    }

    private static void restrictDirectory(Path directory) throws IOException {
        PosixFileAttributeView posix = Files.getFileAttributeView(
                directory, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (posix != null) {
            Files.setPosixFilePermissions(directory, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));
        }
    }

    private static void restrictFile(Path file) throws IOException {
        PosixFileAttributeView posix = Files.getFileAttributeView(
                file, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (posix != null) {
            Files.setPosixFilePermissions(file, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE));
            return;
        }

        AclFileAttributeView acl = Files.getFileAttributeView(
                file, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (acl != null) {
            var owner = Files.getOwner(file, LinkOption.NOFOLLOW_LINKS);
            AclEntry ownerOnly = AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(owner)
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                    .build();
            acl.setAcl(List.of(ownerOnly));
        }
    }
}
