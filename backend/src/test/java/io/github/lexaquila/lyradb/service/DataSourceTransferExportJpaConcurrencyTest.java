package io.github.lexaquila.lyradb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lexaquila.lyradb.driver.DriverRegistry;
import io.github.lexaquila.lyradb.model.entity.ApprovalRequest;
import io.github.lexaquila.lyradb.model.entity.DataSource;
import io.github.lexaquila.lyradb.model.entity.User;
import io.github.lexaquila.lyradb.repository.ApprovalRequestRepository;
import io.github.lexaquila.lyradb.repository.DataSourceRepository;
import org.junit.jupiter.api.AfterEach;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
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
        ApprovalService.class,
        DataSourceTransferExportJpaConcurrencyTest.JsonConfiguration.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DataSourceTransferExportJpaConcurrencyTest {

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
    private DataSourceImportPreviewStore previewStore;
    @MockitoBean
    private DriverRegistry driverRegistry;
    @MockitoBean
    private AuditService auditService;

    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void concurrentDownloadSucceedsOnceAndSuccessfulApprovalCannotReplay()
            throws Exception {
        Persisted persisted = persistApprovedExport();
        String payload = "{\"dataSourceRefs\":[{\"id\":\""
                + persisted.sourceId()
                + "\",\"displayName\":\"source\"}],"
                + "\"credentialMode\":\"OMIT\","
                + "\"plaintextRiskConfirmed\":false}";
        when(credentialService.decryptValue(
                "ENC-" + persisted.sourceId())).thenReturn(payload);
        when(securityContextService.fingerprintDataSources(
                "workspace-1", List.of(persisted.sourceId())))
                .thenReturn("fingerprint");
        when(credentialService.isSensitiveField(anyString()))
                .thenReturn(false);
        when(credentialService.isEncryptedValue(any()))
                .thenReturn(false);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<Boolean> first = executor.submit(
                () -> download(ready, start, persisted.approvalId()));
        Future<Boolean> second = executor.submit(
                () -> download(ready, start, persisted.approvalId()));
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();

        int successes = (first.get(30, TimeUnit.SECONDS) ? 1 : 0)
                + (second.get(30, TimeUnit.SECONDS) ? 1 : 0);
        assertEquals(1, successes);
        assertStatus(persisted.approvalId(), "DONE");
        assertThrows(RuntimeException.class,
                () -> transferService.exportApproved(
                        persisted.approvalId(), applicant(),
                        "workspace-1", new char[0], false));
        verify(auditService, times(1)).recordCurrentWithApproval(
                "workspace-1", DataSourceTransferApprovalService.OPERATION,
                "DATA_SOURCE_EXPORT_DOWNLOAD", null, "batch",
                true, null, persisted.approvalId());
    }

    private boolean download(
            CountDownLatch ready, CountDownLatch start,
            String approvalId) throws InterruptedException {
        ready.countDown();
        start.await();
        try {
            EnterpriseConnectionTransferService.ExportFile file =
                    transferService.exportApproved(
                            approvalId, applicant(), "workspace-1",
                            new char[0], false);
            return file.content().length > 0;
        } catch (RuntimeException exception) {
            return false;
        } catch (Exception exception) {
            return false;
        }
    }

    private Persisted persistApprovedExport() {
        TransactionTemplate transactions =
                new TransactionTemplate(transactionManager);
        return transactions.execute(status -> {
            DataSource source = new DataSource();
            source.setWorkspaceId("workspace-1");
            source.setDbType("H2");
            source.setDisplayName("source");
            source.setConnectionParamsJson(
                    "{\"url\":\"jdbc:h2:mem:test\"}");
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
            return new Persisted(approvalId, sourceId);
        });
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

    private record Persisted(String approvalId, String sourceId) {
    }

    @TestConfiguration
    static class JsonConfiguration {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}
