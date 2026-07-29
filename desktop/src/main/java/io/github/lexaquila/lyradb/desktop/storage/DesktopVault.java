package io.github.lexaquila.lyradb.desktop.storage;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 原生桌面版本地凭据保险箱。
 *
 * <p>连接密码和 AI Key 使用 AES-256-GCM 加密。随机主密钥单独保存在当前用户
 * 的 LyraDB 数据目录，并在 POSIX/Windows ACL 能力可用时收紧为仅所有者可访问。</p>
 */
public final class DesktopVault implements AutoCloseable {

    private static final String PREFIX = "{lyradb-desktop-aes-gcm-v1}";
    private static final byte[] AAD =
            "LyraDB/NativeDesktop/Credential/v1".getBytes(StandardCharsets.UTF_8);
    private static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecureRandom random = new SecureRandom();
    private final byte[] key;

    public DesktopVault(Path dataDirectory) {
        try {
            Path safeDirectory = prepareDirectory(dataDirectory);
            this.key = loadOrCreateKey(safeDirectory.resolve("master.key"));
        } catch (IOException exception) {
            throw new IllegalStateException("无法初始化桌面凭据保险箱", exception);
        }
    }

    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            return "";
        }
        if (plaintext.startsWith(PREFIX)) {
            throw new IllegalArgumentException("拒绝重复加密已加密值");
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(AAD);
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] payload = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, payload, 0, iv.length);
            System.arraycopy(encrypted, 0, payload, iv.length, encrypted.length);
            return PREFIX + Base64.getEncoder().encodeToString(payload);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("桌面凭据加密失败", exception);
        }
    }

    public String decrypt(String ciphertext) {
        if (ciphertext == null || ciphertext.isEmpty()) {
            return "";
        }
        if (!ciphertext.startsWith(PREFIX)) {
            throw new IllegalArgumentException("敏感字段不是受支持的加密格式");
        }
        try {
            byte[] payload = Base64.getDecoder().decode(ciphertext.substring(PREFIX.length()));
            if (payload.length <= IV_BYTES + 16) {
                throw new IllegalArgumentException("加密载荷长度无效");
            }
            byte[] iv = Arrays.copyOfRange(payload, 0, IV_BYTES);
            byte[] encrypted = Arrays.copyOfRange(payload, IV_BYTES, payload.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(AAD);
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalStateException("桌面凭据解密失败，密钥或密文可能已损坏", exception);
        }
    }

    public static boolean isEncrypted(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    @Override
    public void close() {
        Arrays.fill(key, (byte) 0);
    }

    private byte[] loadOrCreateKey(Path keyFile) throws IOException {
        rejectUnsafeFile(keyFile);
        if (Files.exists(keyFile, LinkOption.NOFOLLOW_LINKS)) {
            return readKey(keyFile);
        }
        byte[] generated = new byte[KEY_BYTES];
        random.nextBytes(generated);
        String encoded = Base64.getEncoder().encodeToString(generated);
        try {
            Files.writeString(keyFile, encoded, StandardCharsets.US_ASCII,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            harden(keyFile);
            return generated;
        } catch (java.nio.file.FileAlreadyExistsException race) {
            Arrays.fill(generated, (byte) 0);
            return readKey(keyFile);
        }
    }

    private static byte[] readKey(Path keyFile) throws IOException {
        rejectUnsafeFile(keyFile);
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(
                    Files.readString(keyFile, StandardCharsets.US_ASCII).trim());
        } catch (IllegalArgumentException exception) {
            throw new IOException("桌面主密钥格式无效", exception);
        }
        if (decoded.length != KEY_BYTES) {
            Arrays.fill(decoded, (byte) 0);
            throw new IOException("桌面主密钥长度无效");
        }
        harden(keyFile);
        return decoded;
    }

    private static Path prepareDirectory(Path directory) throws IOException {
        Path normalized = directory.toAbsolutePath().normalize();
        if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)
                && Files.isSymbolicLink(normalized)) {
            throw new IOException("桌面数据目录不能是符号链接");
        }
        Files.createDirectories(normalized);
        if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("桌面数据路径不是目录");
        }
        harden(normalized);
        return normalized;
    }

    private static void rejectUnsafeFile(Path file) throws IOException {
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)
                && (Files.isSymbolicLink(file)
                || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))) {
            throw new IOException("桌面主密钥文件类型不安全");
        }
    }

    private static void harden(Path path) throws IOException {
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            Set<PosixFilePermission> permissions = Files.isDirectory(path)
                    ? EnumSet.of(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)
                    : EnumSet.of(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(path, permissions);
            return;
        }
        AclFileAttributeView view = Files.getFileAttributeView(
                path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (view == null) {
            return;
        }
        var owner = Files.getOwner(path, LinkOption.NOFOLLOW_LINKS);
        AclEntry ownerOnly = AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(owner)
                .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                .build();
        view.setAcl(List.of(ownerOnly));
    }
}
