package io.github.lexaquila.lyradb.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

class DesktopSecretEnvironmentPostProcessorTest {

    @TempDir
    Path tempDirectory;

    @Test
    void 桌面首次启动生成随机密钥并在后续启动复用() throws Exception {
        Path keyFile = tempDirectory.resolve("private").resolve("master.key");
        DesktopSecretEnvironmentPostProcessor processor =
                new DesktopSecretEnvironmentPostProcessor();

        MockEnvironment firstEnvironment = desktopEnvironment(keyFile);
        processor.postProcessEnvironment(firstEnvironment, new SpringApplication());
        String firstKey = firstEnvironment.getProperty(
                DesktopSecretEnvironmentPostProcessor.GENERATED_PROPERTY);

        MockEnvironment secondEnvironment = desktopEnvironment(keyFile);
        processor.postProcessEnvironment(secondEnvironment, new SpringApplication());
        String secondKey = secondEnvironment.getProperty(
                DesktopSecretEnvironmentPostProcessor.GENERATED_PROPERTY);

        assertThat(firstKey).hasSizeGreaterThanOrEqualTo(40);
        assertThat(secondKey).isEqualTo(firstKey);
        assertThat(Files.readString(keyFile).trim()).isEqualTo(firstKey);
    }

    @Test
    void 显式主密钥优先且不会创建本地密钥文件() {
        Path keyFile = tempDirectory.resolve("unused").resolve("master.key");
        MockEnvironment environment = desktopEnvironment(keyFile)
                .withProperty(DesktopSecretEnvironmentPostProcessor.GENERATED_PROPERTY,
                        "explicit-secret-value");

        new DesktopSecretEnvironmentPostProcessor().postProcessEnvironment(
                environment, new SpringApplication());

        assertThat(environment.getProperty(
                DesktopSecretEnvironmentPostProcessor.GENERATED_PROPERTY))
                .isEqualTo("explicit-secret-value");
        assertThat(keyFile).doesNotExist();
    }

    @Test
    void 非桌面Profile不生成主密钥() {
        Path keyFile = tempDirectory.resolve("unused").resolve("master.key");
        MockEnvironment environment = new MockEnvironment()
                .withProperty(DesktopSecretEnvironmentPostProcessor.KEY_PATH_PROPERTY,
                        keyFile.toString());
        environment.setActiveProfiles("dev");

        new DesktopSecretEnvironmentPostProcessor().postProcessEnvironment(
                environment, new SpringApplication());

        assertThat(environment.getProperty(
                DesktopSecretEnvironmentPostProcessor.GENERATED_PROPERTY)).isNull();
        assertThat(keyFile).doesNotExist();
    }

    private MockEnvironment desktopEnvironment(Path keyFile) {
        MockEnvironment environment = new MockEnvironment()
                .withProperty(DesktopSecretEnvironmentPostProcessor.KEY_PATH_PROPERTY,
                        keyFile.toString());
        environment.setActiveProfiles("desktop");
        return environment;
    }
}
