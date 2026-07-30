package io.github.lexaquila.lyradb.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lexaquila.lyradb.model.entity.ApprovalRequest;
import io.github.lexaquila.lyradb.model.entity.DataSource;
import io.github.lexaquila.lyradb.model.entity.User;
import io.github.lexaquila.lyradb.repository.ApprovalRequestRepository;
import io.github.lexaquila.lyradb.repository.DataSourceRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DataSourceTransferApprovalServiceTest {

    @Mock
    private ApprovalRequestRepository repository;
    @Mock
    private DataSourceRepository dataSourceRepository;
    @Mock
    private CredentialService credentialService;
    @Mock
    private ApprovalSecurityContextService securityContextService;
    @Mock
    private EntityManager entityManager;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private DataSourceTransferApprovalService service;
    private User applicant;

    @BeforeEach
    void setUp() {
        service = new DataSourceTransferApprovalService(
                repository, dataSourceRepository, credentialService, securityContextService,
                entityManager, objectMapper);
        applicant = new User();
        applicant.setId("user-1");
        applicant.setUsername("alice");
    }

    @Test
    void approvalPayloadIsCanonicalAndContainsNoCredential()
            throws Exception {
        when(dataSourceRepository.findAllById(List.of("source-a", "source-b")))
                .thenReturn(List.of(source("source-a", "Alpha"),
                        source("source-b", "Beta")));
        when(securityContextService.fingerprintDataSources(
                eq("workspace-1"), any())).thenReturn("fingerprint");
        when(credentialService.blindIndex(anyString(), anyString()))
                .thenReturn("payload-hash");
        when(credentialService.encryptValue(anyString()))
                .thenAnswer(invocation -> "encrypted:"
                        + invocation.getArgument(0, String.class));
        when(repository.save(any(ApprovalRequest.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ApprovalRequest result = service.create(
                "workspace-1", applicant,
                List.of("source-b", "source-a", "source-b"),
                "PLAINTEXT", true, "reason");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> ids =
                ArgumentCaptor.forClass(List.class);
        verify(securityContextService).fingerprintDataSources(
                eq("workspace-1"), ids.capture());
        assertEquals(List.of("source-a", "source-b"), ids.getValue());

        ArgumentCaptor<String> payload =
                ArgumentCaptor.forClass(String.class);
        verify(credentialService).encryptValue(payload.capture());
        Map<String, Object> parsed = objectMapper.readValue(
                payload.getValue(),
                new TypeReference<Map<String, Object>>() { });
        assertEquals(List.of(
                        Map.of("id", "source-a", "displayName", "Alpha"),
                        Map.of("id", "source-b", "displayName", "Beta")),
                parsed.get("dataSourceRefs"));
        assertEquals("PLAINTEXT", parsed.get("credentialMode"));
        assertEquals(true, parsed.get("plaintextRiskConfirmed"));
        assertFalse(payload.getValue().toLowerCase()
                .contains("password"));
        assertEquals(100, result.getRiskScore());
    }

    @Test
    void plaintextApprovalRequiresExplicitRiskConfirmation() {
        assertThrows(IllegalArgumentException.class, () -> service.create(
                "workspace-1", applicant, List.of("source-1"),
                "PLAINTEXT", false, null));
    }

    @Test
    void crossWorkspaceClaimFailsBeforePayloadIsDecrypted() {
        ApprovalRequest approval = approved("workspace-2");
        when(repository.findByIdForUpdate("approval-1"))
                .thenReturn(Optional.of(approval));

        assertThrows(RuntimeException.class, () -> service.claim(
                "approval-1", applicant, "workspace-1"));

        verify(credentialService, never()).decryptValue(anyString());
        verify(securityContextService, never())
                .fingerprintDataSources(anyString(), any());
    }

    @Test
    void changedDataSourceFingerprintInvalidatesClaim() {
        ApprovalRequest approval = approved("workspace-1");
        approval.setSecurityContextHash("old-fingerprint");
        when(repository.findByIdForUpdate("approval-1"))
                .thenReturn(Optional.of(approval));
        when(credentialService.decryptValue("encrypted-payload"))
                .thenReturn("""
                        {"dataSourceRefs":[{"id":"source-1","displayName":"Source"}],"credentialMode":"OMIT","plaintextRiskConfirmed":false}
                        """.trim());
        when(securityContextService.fingerprintDataSources(
                "workspace-1", List.of("source-1")))
                .thenReturn("new-fingerprint");

        assertThrows(RuntimeException.class, () -> service.claim(
                "approval-1", applicant, "workspace-1"));

        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void expiredApprovalCannotBeClaimed() {
        when(repository.expireByIdAndStatusBefore(
                eq("approval-1"), eq("APPROVED"), any()))
                .thenReturn(1);

        assertThrows(RuntimeException.class, () -> service.claim(
                "approval-1", applicant, "workspace-1"));

        verify(repository, never()).findByIdForUpdate(anyString());
    }

    @Test
    void canonicalClaimUsesServerVerifiedReferences() {
        ApprovalRequest approval = approved("workspace-1");
        approval.setSecurityContextHash("fingerprint");
        String payload = "{\"dataSourceRefs\":[{\"id\":\"source-1\","
                + "\"displayName\":\"Source\"}],\"credentialMode\":\"OMIT\","
                + "\"plaintextRiskConfirmed\":false}";
        when(repository.findByIdForUpdate("approval-1"))
                .thenReturn(Optional.of(approval));
        when(credentialService.decryptValue("encrypted-payload"))
                .thenReturn(payload);
        when(securityContextService.fingerprintDataSources(
                "workspace-1", List.of("source-1")))
                .thenReturn("fingerprint");
        when(dataSourceRepository.findAllById(List.of("source-1")))
                .thenReturn(List.of(source("source-1", "Source")));
        when(repository.saveAndFlush(approval)).thenReturn(approval);

        DataSourceTransferApprovalService.Claim claim = service.claim(
                "approval-1", applicant, "workspace-1");

        assertEquals(List.of("source-1"), claim.dataSourceIds());
        assertEquals("EXECUTING", approval.getStatus());
        verify(repository).saveAndFlush(approval);
    }

    @Test
    void tamperedDisplayNameIsRejectedEvenWhenFingerprintMatches() {
        ApprovalRequest approval = approved("workspace-1");
        approval.setSecurityContextHash("fingerprint");
        String payload = "{\"dataSourceRefs\":[{\"id\":\"source-1\","
                + "\"displayName\":\"Forged\"}],\"credentialMode\":\"OMIT\","
                + "\"plaintextRiskConfirmed\":false}";
        when(repository.findByIdForUpdate("approval-1"))
                .thenReturn(Optional.of(approval));
        when(credentialService.decryptValue("encrypted-payload"))
                .thenReturn(payload);
        when(securityContextService.fingerprintDataSources(
                "workspace-1", List.of("source-1")))
                .thenReturn("fingerprint");
        when(dataSourceRepository.findAllById(List.of("source-1")))
                .thenReturn(List.of(source("source-1", "Source")));

        assertThrows(RuntimeException.class, () -> service.claim(
                "approval-1", applicant, "workspace-1"));

        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void expirationIsRecheckedAfterWorkspaceAndRowLocks() {
        LocalDateTime beforeLock = LocalDateTime.of(
                2026, 7, 30, 12, 0, 0);
        ApprovalRequest approval = approved("workspace-1");
        approval.setExpiresAt(beforeLock.plusSeconds(1));
        when(repository.expireByIdAndStatusBefore(
                "approval-1", "APPROVED", beforeLock)).thenReturn(0);
        when(repository.findByIdForUpdate("approval-1"))
                .thenReturn(Optional.of(approval));
        DataSourceTransferApprovalService timed = spy(service);
        doReturn(beforeLock, beforeLock.plusSeconds(2))
                .when(timed).now();

        assertThrows(RuntimeException.class, () -> timed.claim(
                "approval-1", applicant, "workspace-1"));

        verify(credentialService, never()).decryptValue(anyString());
        verify(repository, never()).saveAndFlush(any());
    }
    private ApprovalRequest approved(String workspaceId) {
        ApprovalRequest approval = new ApprovalRequest();
        approval.setId("approval-1");
        approval.setApplicantId("user-1");
        approval.setWorkspaceId(workspaceId);
        approval.setOperationType(
                DataSourceTransferApprovalService.OPERATION);
        approval.setStatus("APPROVED");
        approval.setExpiresAt(LocalDateTime.now().plusMinutes(10));
        approval.setPayloadJson("encrypted-payload");
        return approval;
    }

    private DataSource source(String id, String displayName) {
        DataSource source = new DataSource();
        source.setId(id);
        source.setWorkspaceId("workspace-1");
        source.setDbType("POSTGRESQL");
        source.setDisplayName(displayName);
        source.setConnectionParamsJson("{}");
        return source;
    }
}
