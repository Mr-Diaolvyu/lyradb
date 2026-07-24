package io.github.lexaquila.lyradb.controller;

import io.github.lexaquila.lyradb.service.AiProviderService;
import io.github.lexaquila.lyradb.service.SecurityUtil;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * AI Provider 管理控制器（DS_ADMIN/PLATFORM_ADMIN）
 */
@RestController
@RequestMapping("/admin/ai/providers")
public class AdminAiController {

    private final AiProviderService aiProviderService;
    private final SecurityUtil securityUtil;

    public AdminAiController(AiProviderService aiProviderService, SecurityUtil securityUtil) {
        this.aiProviderService = aiProviderService;
        this.securityUtil = securityUtil;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(value = "workspaceId", required = false) String workspaceId) {
        securityUtil.requireRole("DS_ADMIN");
        return aiProviderService.listMasked(workspaceId);
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        securityUtil.requireRole("DS_ADMIN");
        String id = aiProviderService.create(
                (String) body.get("workspaceId"),
                (String) body.get("providerKey"),
                (String) body.get("displayName"),
                (String) body.get("baseUrl"),
                (String) body.get("apiKey"),
                (String) body.get("model"),
                body.get("temperature") instanceof Number n ? n.doubleValue() : 0.2,
                body.get("maxTokens") instanceof Number m ? m.intValue() : 2048,
                Boolean.TRUE.equals(body.get("isDefault"))
        ).getId();
        return Map.of("id", id, "success", true);
    }

    @PostMapping("/{id}/default")
    public Map<String, Object> setDefault(@PathVariable String id) {
        securityUtil.requireRole("DS_ADMIN");
        aiProviderService.setDefault(id);
        return Map.of("success", true);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        securityUtil.requireRole("DS_ADMIN");
        aiProviderService.delete(id);
        return Map.of("success", true);
    }
}
