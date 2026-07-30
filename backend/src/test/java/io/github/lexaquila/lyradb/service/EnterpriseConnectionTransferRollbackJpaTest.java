package io.github.lexaquila.lyradb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lexaquila.lyradb.driver.DriverRegistry;
import io.github.lexaquila.lyradb.model.entity.ApprovalRequest;
import io.github.lexaquila.lyradb.model.entity.DataSource;
import io.github.lexaquila.lyradb.model.entity.User;
import io.github.lexaquila.lyradb.repository.ApprovalRequestRepository;
import io.github.lexaquila.lyradb.repository.DataSourceRepository;
import io.github.lexaquila.lyradb.transfer.connection.ConnectionPackageException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.open-in-view=false"
})
@Import({
        DataSourceTransferApprovalService.class,
        EnterpriseConnectionTransferService.class,
        EnterpriseConnectionTransferRollbackJpaTest.JsonConfiguration.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class EnterpriseConnectionTransferRollbackJpaTest {

    @Autowired
    private ApprovalRequestRepository approvalRepository;

    @Autowired
    private DataSourceRepository dataSourceRepository;

    @Autowired
    private EnterpriseConnectionTransferService transferService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoBean
    private CredentialService credentialService;

    @MockitoBean
    private ApprovalSecurityContextService securityContextService;

    @MockitoBean
    private DataSourceService dataSourceService;

    @MockitoBean
    private ApprovalService approvalService;

    @MockitoBean
    private DataSourceImportPreviewStore previewStore;

    @MockitoBean
    private DriverRegistry driverRegistry;

    @MockitoBean
    private AuditService auditService;

    @Test
    void checkedCodecFailureRollsBackClaimAndAllowsRetry() {
        Persisted persisted = persist(
                "PLAINTEXT", true,
                "{\"aiApiKey\":\"stored\"}");
        stubClaim(persisted, "PLAINTEXT", true);
        when(credentialService.isSensitiveField("aiApiKey"))
                .thenReturn(true);
        when(credentialService.decryptSensitiveFields(anyMap()))
                .thenReturn(Map.of("aiApiKey", "application-secret"));

        User applicant = applicant();
        assertThrows(ConnectionPackageException.class,
                () -> transferService.exportApproved(
                        persisted.approvalId(), applicant,
                        "workspace-1", new char[0], true));
        assertStatus(persisted.approvalId(), "APPROVED");

        assertThrows(ConnectionPackageException.class,
                () -> transferService.exportApproved(
                        persisted.approvalId(), applicant,
                        "workspace-1", new char[0], true));
        assertStatus(persisted.approvalId(), "APPROVED");
        verify(approvalService, never())
                .markExecutionResult(anyString(),
                        org.mockito.ArgumentMatchers.anyBoolean(),
                        anyString());
    }

    @Test
    void plaintextConfirmationFailureRollsBackClaim() {
        Persisted persisted = persist(
                "PLAINTEXT", true,
                "{\"url\":\"jdbc:h2:mem:test\"}");
        stubClaim(persisted, "PLAINTEXT", true);

        assertThrows(IllegalArgumentException.class,
                () -> transferService.exportApproved(
                        persisted.approvalId(), applicant(),
                        "workspace-1", new char[0], false));

        assertStatus(persisted.approvalId(), "APPROVED");
    }

    @Test
    void encryptedPasswordValidationFailureRollsBackClaim() {
        Persisted persisted = persist(
                "PASSWORD_ENCRYPTED", false,
                "{\"url\":\"jdbc:h2:mem:test\"}");
        stubClaim(persisted, "PASSWORD_ENCRYPTED", false);

        assertThrows(IllegalArgumentException.class,
                () -> transferService.exportApproved(
                        persisted.approvalId(), applicant(),
                        "workspace-1", "12345678901".toCharArray(), false));

        assertStatus(persisted.approvalId(), "APPROVED");
    }

    private Persisted persist(
            String mode, boolean plaintextConfirmed,
            String connectionParams) {
        TransactionTemplate transactions =
                new TransactionTemplate(transactionManager);
        return transactions.execute(status -> {
            DataSource source = new DataSource();
            source.setWorkspaceId("workspace-1");
            source.setDbType("H2");
            source.setDisplayName("source");
            source.setConnectionParamsJson(connectionParams);
            String sourceId =
                    dataSourceRepository.saveAndFlush(source).getId();

            ApprovalRequest approval = new ApprovalRequest();
            approval.setWorkspaceId("workspace-1");
            approval.setApplicantId("user-1");
            approval.setApplicantName("alice");
            approval.setOperationType(
                    DataSourceTransferApprovalService.OPERATION);
            approval.setGrantedSourceName("batch");
            approval.setSecurityContextHash("fingerprint");
            approval.setPayloadJson("ENC-" + sourceId);
            approval.setPayloadHash("payload-hash-" + sourceId);
            approval.setStatus("APPROVED");
            approval.setExpiresAt(
                    LocalDateTime.now().plusMinutes(10));
            String approvalId =
                    approvalRepository.saveAndFlush(approval).getId();
            return new Persisted(
                    approvalId, sourceId, mode, plaintextConfirmed);
        });
    }

    private void stubClaim(
            Persisted persisted, String mode,
            boolean plaintextConfirmed) {
        String payload = "{\"dataSourceRefs\":[{\"id\":\""
                + persisted.sourceId()
                + "\",\"displayName\":\"source\"}],\"credentialMode\":\"" + mode
                + "\",\"plaintextRiskConfirmed\":"
                + plaintextConfirmed + "}";
        when(credentialService.decryptValue(
                "ENC-" + persisted.sourceId()))
                .thenReturn(payload);
        when(securityContextService.fingerprintDataSources(
                "workspace-1", List.of(persisted.sourceId())))
                .thenReturn("fingerprint");
    }

    private void assertStatus(String approvalId, String expected) {
        TransactionTemplate transactions =
                new TransactionTemplate(transactionManager);
        String actual = transactions.execute(status ->
                approvalRepository.findById(approvalId)
                        .orElseThrow().getStatus());
        assertEquals(expected, actual);
    }

    private static User applicant() {
        User user = new User();
        user.setId("user-1");
        user.setUsername("alice");
        return user;
    }

    private record Persisted(
            String approvalId,
            String sourceId,
            String mode,
            boolean plaintextConfirmed) {
    }

    @TestConfiguration
    static class JsonConfiguration {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}
