package io.github.lexaquila.lyradb.driver;

import io.github.lexaquila.lyradb.config.AppProperties;
import io.github.lexaquila.lyradb.model.entity.DriverInfo;
import io.github.lexaquila.lyradb.model.entity.MavenCoordinates;
import org.eclipse.aether.repository.RemoteRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.HexFormat;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MavenDriverManagerTest {

    @TempDir
    Path tempDirectory;

    @Test
    void shouldResolveAllTransitiveJarsAndCloseManagedClassLoader()
            throws Exception {
        Path remoteRepository = tempDirectory.resolve("remote");
        publish(remoteRepository, "transitive", List.of());
        publish(remoteRepository, "direct", List.of("transitive"));
        publish(remoteRepository, "root", List.of("direct"));

        AppProperties properties = new AppProperties();
        properties.setDriverCacheDir(
                tempDirectory.resolve("cache").toString());
        RemoteRepository repository = new RemoteRepository.Builder(
                "test-file", "default",
                remoteRepository.toUri().toASCIIString()).build();
        MavenDriverManager manager =
                new MavenDriverManager(properties, List.of(repository));
        DriverInfo driverInfo = driverInfo("root");

        MavenDriverManager.DriverClassLoader loader =
                (MavenDriverManager.DriverClassLoader)
                        manager.getOrCreateClassLoader(driverInfo);
        Set<String> jarNames = Arrays.stream(loader.getURLs())
                .map(url -> Path.of(URI.create(url.toString()))
                        .getFileName().toString())
                .collect(Collectors.toSet());

        assertThat(jarNames).contains(
                "root-1.0.jar", "direct-1.0.jar", "transitive-1.0.jar");
        assertThat(manager.getOrCreateClassLoader(driverInfo)).isSameAs(loader);
        try (InputStream marker = loader.getResourceAsStream("root.marker")) {
            assertThat(marker).isNotNull();
        }

        Path rootJar = Arrays.stream(loader.getURLs())
                .map(url -> {
                    try {
                        return Path.of(url.toURI());
                    } catch (Exception exception) {
                        throw new IllegalStateException(exception);
                    }
                })
                .filter(path -> path.getFileName().toString()
                        .equals("root-1.0.jar"))
                .findFirst()
                .orElseThrow();

        manager.close();

        assertThat(loader.isClosed()).isTrue();
        assertThatCode(() -> Files.delete(rootJar)).doesNotThrowAnyException();
        assertThatThrownBy(
                () -> manager.getOrCreateClassLoader(driverInfo))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("已关闭");
        assertThatCode(manager::close).doesNotThrowAnyException();
    }

    private static DriverInfo driverInfo(String artifactId) {
        MavenCoordinates coordinates = new MavenCoordinates();
        coordinates.setGroupId("io.test");
        coordinates.setArtifactId(artifactId);
        coordinates.setVersion("1.0");

        DriverInfo info = new DriverInfo();
        info.setDbType("TEST");
        info.setDisplayName("测试驱动");
        info.setMavenCoordinates(coordinates);
        return info;
    }

    private static void publish(
            Path repository, String artifactId, List<String> dependencies)
            throws Exception {
        Path directory = repository.resolve(
                "io/test/" + artifactId + "/1.0");
        Files.createDirectories(directory);
        Path jar = directory.resolve(artifactId + "-1.0.jar");
        try (JarOutputStream output =
                     new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry(artifactId + ".marker"));
            output.write(artifactId.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        writeSha1(jar);

        StringBuilder pom = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>io.test</groupId>
                  <artifactId>%s</artifactId>
                  <version>1.0</version>
                  <packaging>jar</packaging>
                  <dependencies>
                """.formatted(artifactId));
        for (String dependency : dependencies) {
            pom.append("""
                      <dependency>
                        <groupId>io.test</groupId>
                        <artifactId>%s</artifactId>
                        <version>1.0</version>
                      </dependency>
                    """.formatted(dependency));
        }
        pom.append("""
                  </dependencies>
                </project>
                """);
        Path pomFile = directory.resolve(artifactId + "-1.0.pom");
        Files.writeString(pomFile, pom, StandardCharsets.UTF_8);
        writeSha1(pomFile);
    }

    private static void writeSha1(Path file) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-1")
                .digest(Files.readAllBytes(file));
        Files.writeString(
                file.resolveSibling(file.getFileName() + ".sha1"),
                HexFormat.of().formatHex(digest),
                StandardCharsets.US_ASCII);
    }
}
