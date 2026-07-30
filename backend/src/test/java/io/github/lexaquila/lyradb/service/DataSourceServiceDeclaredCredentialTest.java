package io.github.lexaquila.lyradb.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lexaquila.lyradb.driver.DriverFactory;
import io.github.lexaquila.lyradb.driver.DriverRegistry;
import io.github.lexaquila.lyradb.model.entity.DataSource;
import io.github.lexaquila.lyradb.repository.DataSourceRepository;
import org.jasypt.encryption.pbe.StandardPBEStringEncryptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DataSourceServiceDeclaredCredentialTest {

    private DataSourceRepository repository;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private CredentialService credentialService;
    private DataSourceService dataSourceService;

    @BeforeEach
    void setUp() {
        StandardPBEStringEncryptor legacy =
                new StandardPBEStringEncryptor();
        legacy.setAlgorithm("PBEWithMD5AndDES");
        legacy.setPassword("test-key");
        credentialService =
                new CredentialService(legacy, "test-master-key");

        repository = mock(DataSourceRepository.class);
        when(repository.save(any(DataSource.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        dataSourceService = new DataSourceService(
                repository, mock(DriverFactory.class),
                mock(DriverRegistry.class), credentialService,
                mock(ApprovalSecurityContextService.class),
                objectMapper);
    }

    @AfterEach
    void tearDown() {
        credentialService.clearMasterPassword();
    }

    @Test
    void importedDeclaredCredentialIsEncryptedAtRestAndDecryptable()
            throws Exception {
        assertFalse(credentialService.isSensitiveField(
                "clientCredential"));

        DataSource stored = dataSourceService.create(
                "workspace-1", "H2", "source",
                Map.of(
                        "url", "jdbc:h2:mem:test",
                        "clientCredential", "custom-secret"),
                null, "user-1", Set.of("clientCredential"));

        Map<String, Object> persisted = objectMapper.readValue(
                stored.getConnectionParamsJson(),
                new TypeReference<Map<String, Object>>() { });
        String ciphertext =
                String.valueOf(persisted.get("clientCredential"));
        assertTrue(ciphertext.startsWith("{aes-gcm-v2}"));
        assertTrue(credentialService.isEncryptedValue(ciphertext));
        assertEquals("custom-secret",
                credentialService.decryptSensitiveFields(persisted)
                        .get("clientCredential"));
        assertEquals(CredentialService.MASKED_VALUE,
                credentialService.maskSensitiveFields(persisted)
                        .get("clientCredential"));
    }
    @Test
    void importedOverwriteFullyReplacesParamsAndOmitClearsOldCredential()
            throws Exception {
        DataSource existing = existingSource(Map.of(
                "url", "jdbc:h2:mem:old",
                "password", "old-secret",
                "staleParam", "must-disappear"), Set.of());
        when(repository.findById("source-1"))
                .thenReturn(Optional.of(existing));

        DataSource stored = dataSourceService.replaceImportedConfiguration(
                "source-1", "replacement", null,
                Map.of("url", "jdbc:h2:mem:new"), Set.of());

        Map<String, Object> persisted = objectMapper.readValue(
                stored.getConnectionParamsJson(),
                new TypeReference<Map<String, Object>>() { });
        assertEquals(Set.of("url"), persisted.keySet());
        assertEquals("jdbc:h2:mem:new", persisted.get("url"));
        assertFalse(persisted.containsKey("password"));
        assertFalse(persisted.containsKey("staleParam"));
    }

    @Test
    void importedOverwriteWritesOnlyPackageFieldsAndEncryptsDeclaredCredential()
            throws Exception {
        DataSource existing = existingSource(Map.of(
                "url", "jdbc:h2:mem:old",
                "password", "old-secret",
                "staleParam", "must-disappear"), Set.of());
        when(repository.findById("source-1"))
                .thenReturn(Optional.of(existing));

        DataSource stored = dataSourceService.replaceImportedConfiguration(
                "source-1", "replacement", "description",
                Map.of(
                        "url", "jdbc:h2:mem:new",
                        "clientCredential", "new-secret"),
                Set.of("clientCredential"));

        Map<String, Object> persisted = objectMapper.readValue(
                stored.getConnectionParamsJson(),
                new TypeReference<Map<String, Object>>() { });
        assertEquals(Set.of("url", "clientCredential"),
                persisted.keySet());
        assertTrue(String.valueOf(persisted.get("clientCredential"))
                .startsWith("{aes-gcm-v2}"));
        assertEquals("new-secret",
                credentialService.decryptSensitiveFields(persisted)
                        .get("clientCredential"));
        assertFalse(persisted.containsKey("password"));
        assertFalse(persisted.containsKey("staleParam"));
    }

    private DataSource existingSource(
            Map<String, Object> params,
            Set<String> declaredSensitiveFields) throws Exception {
        DataSource source = new DataSource();
        source.setId("source-1");
        source.setWorkspaceId("workspace-1");
        source.setDbType("H2");
        source.setDisplayName("existing");
        source.setConnectionParamsJson(objectMapper.writeValueAsString(
                credentialService.encryptSensitiveFields(
                        params, declaredSensitiveFields)));
        return source;
    }
}
