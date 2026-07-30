


package io.github.lexaquila.lyradb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lexaquila.lyradb.model.entity.ApprovalPolicy;
import io.github.lexaquila.lyradb.model.entity.ApprovalRequest;
import io.github.lexaquila.lyradb.model.entity.Grant;
import io.github.lexaquila.lyradb.model.entity.User;
import io.github.lexaquila.lyradb.repository.ApprovalPolicyRepository;
import io.github.lexaquila.lyradb.repository.ApprovalRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * 审批的安全回归：载荷加密、完整资源绑定、过期处理和单次原子消费。
 */
class ApprovalServiceSecurityTest {

    private ApprovalRequestRepository repository;
    private ApprovalPolicyRepository policyRepository;
    private CredentialService credentialService;
    private ApprovalSecurityContextService securityContextService;
    private ApprovalService service;

    @BeforeEach
    void setUp() {
        repository = mock(ApprovalRequestRepository.class);
        policyRepository = mock(ApprovalPolicyRepository.class);
        credentialService = mock(CredentialService.class);
        securityContextService = mock(ApprovalSecurityContextService.class);
        service = new ApprovalService(
                repository, policyRepository, credentialService,
                securityContextService,
                mock(jakarta.persistence.EntityManager.class),
                new ObjectMapper());
        when(policyRepository.findByWorkspaceId(any()))
                .thenReturn(Optional.of(new ApprovalPolicy()));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(securityContextService.fingerprint(any())).thenReturn("CTX");
        when(credentialService.blindIndex(any(), any())).thenReturn("PAYLOAD_HASH");
    }

    @Test
    void createEncryptsCanonicalPayloadAndNeverPersistsPlaintext() {
        Grant grant = grant("user-1", "workspace-1", "source-1", "sales");
        User applicant = user("user-1", "alice");
        when(credentialService.encryptValue(
                "{\"sql\":\"select * from orders\",\"format\":\"csv\",\"defaultDatabase\":\"dw\"}"))
                .thenReturn("ENC(ciphertext)");

        service.create(grant, applicant, " export ",
                """
                {"defaultDatabase":" dw ","format":"CSV","sql":"select * from orders"}
                """, "  月度导出  ");

        ArgumentCaptor<ApprovalRequest> captor = ArgumentCaptor.forClass(ApprovalRequest.class);
        verify(repository).save(captor.capture());
        ApprovalRequest saved = captor.getValue();
        assertEquals("ENC(ciphertext)", saved.getPayloadJson());
        assertEquals("EXPORT", saved.getOperationType());
        assertEquals("月度导出", saved.getReason());
        assertEquals("PENDING", saved.getStatus());
        assertEquals("workspace-1", saved.getWorkspaceId());
        assertEquals("grant-1", saved.getGrantId());
        assertEquals("CTX", saved.getSecurityContextHash());
    }

    @Test
    void createRejectsApplicantWithoutTheGrant() {
        Grant grant = grant("user-1", "workspace-1", "source-1", "sales");

        assertThrows(RuntimeException.class, () -> service.create(
                grant, user("user-2", "mallory"), "EXPORT",
                "{\"sql\":\"select 1\",\"format\":\"csv\"}", null));

        verify(credentialService, never()).encryptValue(any());
        verify(repository, never()).save(any());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("mismatchedExecutionRequests")
    void claimRejectsEveryChangedSecurityBinding(
            String name, User applicant, Grant grant, String operation,
            String sql, String format, String defaultDatabase, String encryptedPayload) {
        ApprovalRequest approval = approvedRequest();
        when(repository.findByIdForUpdate("approval-1")).thenReturn(Optional.of(approval));
        when(credentialService.decryptValue("ENC")).thenReturn(encryptedPayload);

        assertThrows(RuntimeException.class, () -> service.claimForExecution(
                "approval-1", applicant, grant, operation, sql, format, defaultDatabase));

        assertEquals("APPROVED", approval.getStatus());
        verify(repository, never()).saveAndFlush(any());
    }

    private static Stream<Arguments> mismatchedExecutionRequests() {
        String canonical = "{\"sql\":\"select * from orders\",\"format\":\"csv\",\"defaultDatabase\":\"dw\"}";
        return Stream.of(
                Arguments.of("非申请人",
                        user("user-2", "mallory"),
                        grant("user-2", "workspace-1", "source-1", "sales"),
                        "EXPORT", "select * from orders", "csv", "dw", canonical),
                Arguments.of("不同工作空间",
                        user("user-1", "alice"),
                        grant("user-1", "workspace-2", "source-1", "sales"),
                        "EXPORT", "select * from orders", "csv", "dw", canonical),
                Arguments.of("不同数据源",
                        user("user-1", "alice"),
                        grant("user-1", "workspace-1", "source-2", "sales"),
                        "EXPORT", "select * from orders", "csv", "dw", canonical),
                Arguments.of("不同授权逻辑名",
                        user("user-1", "alice"),
                        grant("user-1", "workspace-1", "source-1", "finance"),
                        "EXPORT", "select * from orders", "csv", "dw", canonical),
                Arguments.of("不同操作",
                        user("user-1", "alice"),
                        grant("user-1", "workspace-1", "source-1", "sales"),
                        "DANGEROUS_SQL", "select * from orders", null, "dw", canonical),
                Arguments.of("不同 SQL",
                        user("user-1", "alice"),
                        grant("user-1", "workspace-1", "source-1", "sales"),
                        "EXPORT", "select * from users", "csv", "dw", canonical),
                Arguments.of("不同格式",
                        user("user-1", "alice"),
                        grant("user-1", "workspace-1", "source-1", "sales"),
                        "EXPORT", "select * from orders", "json", "dw", canonical),
                Arguments.of("不同默认库",
                        user("user-1", "alice"),
                        grant("user-1", "workspace-1", "source-1", "sales"),
                        "EXPORT", "select * from orders", "csv", "ods", canonical));
    }

    @Test
    void approvedRequestCanBeClaimedOnlyOnce() {
        ApprovalRequest approval = approvedRequest();
        when(repository.findByIdForUpdate("approval-1")).thenReturn(Optional.of(approval));
        when(credentialService.decryptValue("ENC")).thenReturn(
                "{\"sql\":\"select * from orders\",\"format\":\"csv\",\"defaultDatabase\":\"dw\"}");

        ApprovalRequest claimed = service.claimForExecution(
                "approval-1", user("user-1", "alice"),
                grant("user-1", "workspace-1", "source-1", "sales"),
                "EXPORT", "select * from orders", "CSV", " dw ");

        assertEquals("EXECUTING", claimed.getStatus());
        assertThrows(RuntimeException.class, () -> service.claimForExecution(
                "approval-1", user("user-1", "alice"),
                grant("user-1", "workspace-1", "source-1", "sales"),
                "EXPORT", "select * from orders", "csv", "dw"));
        verify(repository).saveAndFlush(approval);
    }

    @Test
    void expiredApprovalUsesIndependentConditionalUpdateAndCannotExecute() {
        when(repository.expireByIdAndStatusBefore(
                any(), any(), any())).thenReturn(1);

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> service.claimForExecution(
                        "approval-1", user("user-1", "alice"),
                        grant("user-1", "workspace-1", "source-1", "sales"),
                        "EXPORT", "select * from orders", "csv", "dw"));

        assertTrue(exception.getMessage().contains("过期"));
        verify(repository, never()).findByIdForUpdate(any());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void applicantCannotApproveOwnRequestAndOtherWorkspaceCannotApprove() {
        ApprovalRequest approval = approvedRequest();
        approval.setStatus("PENDING");
        when(repository.findByIdForUpdate("approval-1")).thenReturn(Optional.of(approval));

        assertThrows(RuntimeException.class,
                () -> service.approve("approval-1", "user-1", "workspace-1", null));
        assertThrows(RuntimeException.class,
                () -> service.approve("approval-1", "approver-1", "workspace-2", null));

        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void onlyApplicantCanCancelPendingRequest() {
        ApprovalRequest approval = approvedRequest();
        approval.setStatus("PENDING");
        when(repository.findByIdForUpdate("approval-1")).thenReturn(Optional.of(approval));

        assertThrows(RuntimeException.class,
                () -> service.cancel(
                        "approval-1", "user-2", "workspace-1"));
        assertEquals("PENDING", approval.getStatus());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void approveRechecksExpiryAfterPessimisticLock() {
        LocalDateTime beforeLock = LocalDateTime.of(
                2026, 7, 30, 13, 0, 0);
        ApprovalRequest approval = approvedRequest();
        approval.setStatus("PENDING");
        approval.setExpiresAt(beforeLock.plusSeconds(1));
        when(repository.findByIdForUpdate("approval-1"))
                .thenReturn(Optional.of(approval));
        ApprovalService timed = spy(service);
        doReturn(beforeLock, beforeLock.plusSeconds(2))
                .when(timed).now();

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> timed.approve(
                        "approval-1", "approver-1",
                        "workspace-1", null));

        assertTrue(exception.getMessage().contains("过期"));
        assertEquals("PENDING", approval.getStatus());
        verify(repository).expireByIdAndStatusBefore(
                "approval-1", "PENDING", beforeLock);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void rejectRechecksExpiryAfterPessimisticLock() {
        LocalDateTime beforeLock = LocalDateTime.of(
                2026, 7, 30, 13, 0, 0);
        ApprovalRequest approval = approvedRequest();
        approval.setStatus("PENDING");
        approval.setExpiresAt(beforeLock.plusSeconds(1));
        when(repository.findByIdForUpdate("approval-1"))
                .thenReturn(Optional.of(approval));
        ApprovalService timed = spy(service);
        doReturn(beforeLock, beforeLock.plusSeconds(2))
                .when(timed).now();

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> timed.reject(
                        "approval-1", "approver-1",
                        "workspace-1", null));

        assertTrue(exception.getMessage().contains("过期"));
        assertEquals("PENDING", approval.getStatus());
        verify(repository).expireByIdAndStatusBefore(
                "approval-1", "PENDING", beforeLock);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void claimRechecksExpiryAfterWorkspaceAndApprovalLocks() {
        LocalDateTime beforeLock = LocalDateTime.of(
                2026, 7, 30, 13, 0, 0);
        ApprovalRequest approval = approvedRequest();
        approval.setExpiresAt(beforeLock.plusSeconds(1));
        when(repository.findByIdForUpdate("approval-1"))
                .thenReturn(Optional.of(approval));
        ApprovalService timed = spy(service);
        doReturn(beforeLock, beforeLock.plusSeconds(2))
                .when(timed).now();

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> timed.claimForExecution(
                        "approval-1", user("user-1", "alice"),
                        grant("user-1", "workspace-1", "source-1", "sales"),
                        "EXPORT", "select * from orders", "csv", "dw"));

        assertTrue(exception.getMessage().contains("过期"));
        assertEquals("APPROVED", approval.getStatus());
        verify(repository).expireByIdAndStatusBefore(
                "approval-1", "APPROVED", beforeLock);
        verify(credentialService, never()).decryptValue(any());
        verify(repository, never()).saveAndFlush(any());
    }
    @Test
    void payloadRejectsUnknownFieldsAndUnsupportedFormat() {
        Grant grant = grant("user-1", "workspace-1", "source-1", "sales");
        User applicant = user("user-1", "alice");

        assertThrows(IllegalArgumentException.class, () -> service.create(
                grant, applicant, "EXPORT",
                "{\"sql\":\"select 1\",\"format\":\"csv\",\"workspaceId\":\"workspace-2\"}", null));
        assertThrows(IllegalArgumentException.class, () -> service.create(
                grant, applicant, "EXPORT",
                "{\"sql\":\"select 1\",\"format\":\"xlsx\"}", null));

        verify(repository, never()).save(any());
    }

    private static ApprovalRequest approvedRequest() {
        ApprovalRequest approval = new ApprovalRequest();
        approval.setId("approval-1");
        approval.setWorkspaceId("workspace-1");
        approval.setApplicantId("user-1");
        approval.setOperationType("EXPORT");
        approval.setDataSourceId("source-1");
        approval.setGrantId("grant-1");
        approval.setGrantedSourceName("sales");
        approval.setSecurityContextHash("CTX");
        approval.setPayloadJson("ENC");
        approval.setStatus("APPROVED");
        approval.setExpiresAt(LocalDateTime.now().plusHours(1));
        return approval;
    }

    private static Grant grant(String userId, String workspaceId,
                               String dataSourceId, String grantedSourceName) {
        Grant grant = new Grant();
        grant.setId("grant-1");
        grant.setUserId(userId);
        grant.setWorkspaceId(workspaceId);
        grant.setDataSourceId(dataSourceId);
        grant.setGrantedSourceName(grantedSourceName);
        return grant;
    }

    private static User user(String id, String username) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        return user;
    }
}
