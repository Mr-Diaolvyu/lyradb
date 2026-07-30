package io.github.lexaquila.lyradb.desktop.metadata;

import io.github.lexaquila.lyradb.metadata.snapshot.MetadataSnapshotRenderer;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Core 快照渲染结果的 UTF-8 原子保存。
 */
public final class MetadataExportService {

    public enum Format {
        MARKDOWN(".md"),
        JSON(".json");

        private final String suffix;

        Format(String suffix) {
            this.suffix = suffix;
        }

        public String suffix() {
            return suffix;
        }
    }

    private final MetadataSnapshotRenderer renderer;

    public MetadataExportService(MetadataSnapshotRenderer renderer) {
        this.renderer = java.util.Objects.requireNonNull(renderer);
    }

    public void save(Path target, MetadataCapture capture, Format format)
            throws IOException {
        if (capture == null || format == null) {
            throw new IllegalArgumentException("元数据快照和保存格式不能为空");
        }
        byte[] content = format == Format.MARKDOWN
                ? renderer.toMarkdownUtf8(capture.snapshot())
                : renderer.toJsonUtf8(capture.snapshot());
        try {
            writeAtomically(target, content);
        } finally {
            java.util.Arrays.fill(content, (byte) 0);
        }
    }

    private static void writeAtomically(Path target, byte[] content)
            throws IOException {
        if (target == null) {
            throw new IllegalArgumentException("保存文件不能为空");
        }
        Path safe = target.toAbsolutePath().normalize();
        if (Files.exists(safe, LinkOption.NOFOLLOW_LINKS)
                && (Files.isSymbolicLink(safe)
                || !Files.isRegularFile(safe, LinkOption.NOFOLLOW_LINKS))) {
            throw new IllegalArgumentException("保存路径不是安全的常规文件");
        }
        Path parent = safe.getParent();
        if (parent == null) {
            throw new IllegalArgumentException("保存文件缺少父目录");
        }
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, ".lyradb-metadata-", ".tmp");
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
