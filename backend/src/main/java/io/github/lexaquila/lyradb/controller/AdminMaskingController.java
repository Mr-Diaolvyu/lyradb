package io.github.lexaquila.lyradb.controller;

import io.github.lexaquila.lyradb.model.entity.MaskingRule;
import io.github.lexaquila.lyradb.service.MaskingService;
import io.github.lexaquila.lyradb.service.SecurityUtil;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 数据脱敏规则管理（管理员，企业版 PM3）
 */
@RestController
@RequestMapping("/admin/masking")
public class AdminMaskingController {

    private final MaskingService maskingService;
    private final SecurityUtil securityUtil;

    public AdminMaskingController(MaskingService maskingService, SecurityUtil securityUtil) {
        this.maskingService = maskingService;
        this.securityUtil = securityUtil;
    }

    @GetMapping
    public List<MaskingRule> list() {
        securityUtil.requireRole("DS_ADMIN");
        return maskingService.listAll();
    }

    @PostMapping
    public Map<String, Object> save(@RequestBody MaskingRule rule) {
        securityUtil.requireRole("DS_ADMIN");
        MaskingRule saved = maskingService.save(rule);
        return Map.of("id", saved.getId(), "success", true);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        securityUtil.requireRole("DS_ADMIN");
        maskingService.delete(id);
        return Map.of("success", true);
    }
}
