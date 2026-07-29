
package io.github.lexaquila.lyradb.controller;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lexaquila.lyradb.model.entity.ApprovalPolicy;
import io.github.lexaquila.lyradb.repository.ApprovalPolicyRepository;
import io.github.lexaquila.lyradb.service.ApprovalSecurityContextService;
import io.github.lexaquila.lyradb.service.AuditService;
import io.github.lexaquila.lyradb.service.SecurityUtil;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminApprovalPolicyControllerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void responseContainsOnlyEffectivePolicyFields() throws Exception {
        ApprovalPolicyRepository repository = mock(ApprovalPolicyRepository.class);
        SecurityUtil securityUtil = mock(SecurityUtil.class);
        ApprovalSecurityContextService contextService = mock(ApprovalSecurityContextService.class);
        AuditService auditService = mock(AuditService.class);
        HttpSession session = mock(HttpSession.class);
        ApprovalPolicy stored = historicalPolicy();

        when(securityUtil.requireCurrentWorkspace(session)).thenReturn("workspace-1");
        when(repository.findByWorkspaceId("workspace-1")).thenReturn(Optional.of(stored));

        AdminApprovalPolicyController.ApprovalPolicyView view =
                new AdminApprovalPolicyController(repository, contextService, securityUtil, auditService)
                        .get("attacker-controlled-workspace", session);
        JsonNode json = MAPPER.valueToTree(view);
        Set<String> fields = new HashSet<>();
        json.fieldNames().forEachRemaining(fields::add);

        assertEquals(Set.of("approverRole", "requireTwoApprovers"), fields);
        assertEquals("STEWARD", json.get("approverRole").asText());
        assertFalse(json.get("requireTwoApprovers").asBoolean());
    }

    @Test
    void legacyPolicyFieldsAreExplicitlyRejected() {
        String json = """
                {
                  "approverRole": "STEWARD",
                  "requireTwoApprovers": false,
                  "dmlRowThreshold": 10
                }
                """;

        JsonMappingException exception = assertThrows(JsonMappingException.class,
                () -> MAPPER.readValue(json,
                        AdminApprovalPolicyController.ApprovalPolicyUpdateRequest.class));

        assertTrue(exception.getMessage().contains("dmlRowThreshold"));
    }

    @Test
    void saveChangesOnlyFieldsThatApprovalRuntimeReads() {
        ApprovalPolicyRepository repository = mock(ApprovalPolicyRepository.class);
        SecurityUtil securityUtil = mock(SecurityUtil.class);
        ApprovalSecurityContextService contextService = mock(ApprovalSecurityContextService.class);
        AuditService auditService = mock(AuditService.class);
        HttpSession session = mock(HttpSession.class);
        ApprovalPolicy stored = historicalPolicy();

        when(securityUtil.requireCurrentWorkspace(session)).thenReturn("workspace-1");
        when(repository.findByWorkspaceId("workspace-1")).thenReturn(Optional.of(stored));
        when(repository.saveAndFlush(any(ApprovalPolicy.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AdminApprovalPolicyController.ApprovalPolicyView saved =
                new AdminApprovalPolicyController(repository, contextService, securityUtil, auditService)
                        .save(new AdminApprovalPolicyController.ApprovalPolicyUpdateRequest(
                                "ds_admin", true), session);

        ArgumentCaptor<ApprovalPolicy> captor = ArgumentCaptor.forClass(ApprovalPolicy.class);
        verify(repository).saveAndFlush(captor.capture());
        ApprovalPolicy persisted = captor.getValue();
        assertEquals("DS_ADMIN", saved.approverRole());
        assertTrue(saved.requireTwoApprovers());
        assertEquals("DS_ADMIN", persisted.getApproverRole());
        assertTrue(persisted.isRequireTwoApprovers());
        assertFalse(persisted.isAlwaysApproveExport());
        assertEquals(4321, persisted.getDmlRowThreshold());
        assertFalse(persisted.isAlwaysApproveMigration());
        assertFalse(persisted.isAlwaysApproveAiDml());
        assertEquals("secret_table", persisted.getSensitiveTables());
    }

    private ApprovalPolicy historicalPolicy() {
        ApprovalPolicy policy = new ApprovalPolicy();
        policy.setWorkspaceId("workspace-1");
        policy.setApproverRole("STEWARD");
        policy.setRequireTwoApprovers(false);
        policy.setAlwaysApproveExport(false);
        policy.setDmlRowThreshold(4321);
        policy.setAlwaysApproveMigration(false);
        policy.setAlwaysApproveAiDml(false);
        policy.setSensitiveTables("secret_table");
        return policy;
    }
}
