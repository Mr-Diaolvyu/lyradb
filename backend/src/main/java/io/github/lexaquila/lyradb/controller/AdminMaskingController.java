package io.github.lexaquila.lyradb.controller;

import io.github.lexaquila.lyradb.model.entity.MaskingRule;
import io.github.lexaquila.lyradb.service.AuditService;
import io.github.lexaquila.lyradb.service.MaskingService;
import io.github.lexaquila.lyradb.service.SecurityUtil;
import jakarta.servlet.http.HttpSession;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 当前工作空间脱敏规则管理。
 */
@RestController
@RequestMapping("/admin/masking")
public class AdminMaskingController {

    private final MaskingService maskingService;
    private final SecurityUtil securityUtil;
    private final AuditService auditService;

    public AdminMaskingController(MaskingService maskingService, SecurityUtil securityUtil,
                                  AuditService auditService) {
        this.maskingService = maskingService;
        this.securityUtil = securityUtil;
        this.auditService = auditService;
    }

    @GetMapping
    public List<MaskingRule> list(HttpSession session) {
        securityUtil.requireRole("DS_ADMIN");
        return maskingService.listAll(securityUtil.requireCurrentWorkspace(session));
    }

    @PostMapping
    @Transactional
    public Map<String, Object> save(@RequestBody MaskingRule rule, HttpSession session) {
        securityUtil.requireRole("DS_ADMIN");
        String workspaceId = securityUtil.requireCurrentWorkspace(session);
        MaskingRule saved = maskingService.save(rule, workspaceId);
        auditService.recordCurrent(workspaceId, "MASKING_RULE_SAVE",
                saved.getDataSourceId(), saved.getId(), true, null);
        return Map.of("id", saved.getId(), "success", true);
    }

    @DeleteMapping("/{id}")
    @Transactional
    public Map<String, Object> delete(@PathVariable String id, HttpSession session) {
        securityUtil.requireRole("DS_ADMIN");
        String workspaceId = securityUtil.requireCurrentWorkspace(session);
        maskingService.delete(id, workspaceId);
        auditService.recordCurrent(workspaceId, "MASKING_RULE_DELETE",
                null, id, true, null);
        return Map.of("success", true);
    }
}
