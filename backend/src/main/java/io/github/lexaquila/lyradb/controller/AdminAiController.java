package io.github.lexaquila.lyradb.controller;

import io.github.lexaquila.lyradb.model.entity.AiProviderConfig;
import io.github.lexaquila.lyradb.service.AiProviderService;
import io.github.lexaquila.lyradb.service.AuditService;
import io.github.lexaquila.lyradb.service.SecurityUtil;
import jakarta.servlet.http.HttpSession;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 当前工作空间 AI Provider 管理。
 */
@RestController
@RequestMapping("/admin/ai/providers")
public class AdminAiController {

    private final AiProviderService aiProviderService;
    private final SecurityUtil securityUtil;
    private final AuditService auditService;

    public AdminAiController(AiProviderService aiProviderService, SecurityUtil securityUtil,
                             AuditService auditService) {
        this.aiProviderService = aiProviderService;
        this.securityUtil = securityUtil;
        this.auditService = auditService;
    }

    @GetMapping
    public List<Map<String, Object>> list(
            @RequestParam(value = "workspaceId", required = false) String ignoredWorkspaceId,
            HttpSession session) {
        securityUtil.requireRole("DS_ADMIN");
        return aiProviderService.listMasked(securityUtil.requireCurrentWorkspace(session));
    }

    @PostMapping
    @Transactional
    public Map<String, Object> create(@RequestBody Map<String, Object> body, HttpSession session) {
        securityUtil.requireRole("DS_ADMIN");
        String workspaceId = securityUtil.requireCurrentWorkspace(session);
        AiProviderConfig config = aiProviderService.create(
                workspaceId,
                (String) body.get("providerKey"),
                (String) body.get("displayName"),
                (String) body.get("baseUrl"),
                (String) body.get("apiKey"),
                (String) body.get("model"),
                body.get("temperature") instanceof Number number ? number.doubleValue() : 0.2,
                body.get("maxTokens") instanceof Number number ? number.intValue() : 2048,
                Boolean.TRUE.equals(body.get("isDefault")),
                body.get("deploymentMode") instanceof String mode
                        ? mode : "PUBLIC");
        auditService.recordCurrent(workspaceId, "AI_PROVIDER_CREATE",
                null, config.getDisplayName(), true, null);
        return Map.of("id", config.getId(), "success", true);
    }

    @PostMapping("/{id}/default")
    @Transactional
    public Map<String, Object> setDefault(@PathVariable String id, HttpSession session) {
        securityUtil.requireRole("DS_ADMIN");
        String workspaceId = securityUtil.requireCurrentWorkspace(session);
        aiProviderService.setDefault(id, workspaceId);
        auditService.recordCurrent(workspaceId, "AI_PROVIDER_SET_DEFAULT",
                null, id, true, null);
        return Map.of("success", true);
    }

    @DeleteMapping("/{id}")
    @Transactional
    public Map<String, Object> delete(@PathVariable String id, HttpSession session) {
        securityUtil.requireRole("DS_ADMIN");
        String workspaceId = securityUtil.requireCurrentWorkspace(session);
        aiProviderService.delete(id, workspaceId);
        auditService.recordCurrent(workspaceId, "AI_PROVIDER_DELETE",
                null, id, true, null);
        return Map.of("success", true);
    }
}
