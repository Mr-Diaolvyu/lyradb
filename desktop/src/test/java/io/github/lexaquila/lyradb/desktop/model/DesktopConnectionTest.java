package io.github.lexaquila.lyradb.desktop.model;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DesktopConnectionTest {

    @Test
    void credentialKeysMustBeImmutableAndDefensivelyCopied() {
        Set<String> source = new LinkedHashSet<>();
        source.add("clientCredential");
        DesktopConnection connection = new DesktopConnection();
        connection.setCredentialKeys(source);

        source.clear();

        assertThat(connection.getCredentialKeys())
                .containsExactly("clientCredential");
        assertThat(connection.copy().getCredentialKeys())
                .containsExactly("clientCredential");
        assertThatThrownBy(() -> connection.getCredentialKeys().add("other"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
