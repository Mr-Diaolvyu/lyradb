package io.github.lexaquila.lyradb.controller;

import io.github.lexaquila.lyradb.service.AiProviderService;
import io.github.lexaquila.lyradb.service.EnterpriseAiService;
import io.github.lexaquila.lyradb.service.SecurityUtil;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * AI SQL 助手控制器
 *
 * <p>POST /api/ai/chat · GET /api/ai/presets · /api/admin/ai/providers CRUD</p>
 */
@RestController
@RequestMapping("/ai")
public class AiController {

    private final AiProviderService aiProviderService;
    private final EnterpriseAiService enterpriseAiService;
    private final SecurityUtil securityUtil;
    private final ExecutorService streamExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ai-stream");
        t.setDaemon(true);
        return t;
    });

    public AiController(AiProviderService aiProviderService, EnterpriseAiService enterpriseAiService,
                       SecurityUtil securityUtil) {
        this.aiProviderService = aiProviderService;
        this.enterpriseAiService = enterpriseAiService;
        this.securityUtil = securityUtil;
    }

    /** Provider 预置（百炼/GLM/豆包/Deepseek/GPT/自定义） */
    @GetMapping("/presets")
    public Map<String, Map<String, String>> presets() {
        return aiProviderService.presets();
    }

    /** 列出 Provider 配置（apiKey 掩码） */
    @GetMapping("/providers")
    public List<Map<String, Object>> providers(@RequestParam(value = "workspaceId", required = false) String workspaceId) {
        return aiProviderService.listMasked(workspaceId);
    }

    /** 对话查数据 */
    @PostMapping("/chat")
    public Map<String, Object> chat(@RequestBody Map<String, Object> body) {
        String grantedSourceName = (String) body.get("grantedSourceName");
        String message = (String) body.get("message");
        @SuppressWarnings("unchecked")
        List<Map<String, String>> history = (List<Map<String, String>>) body.get("history");
        if (grantedSourceName == null || message == null) {
            throw new RuntimeException("grantedSourceName 和 message 必填");
        }
        return enterpriseAiService.chat(grantedSourceName, message, history);
    }

    /** 流式对话（SSE）：事件 explanation/sql/result/needsApproval/error */
    @PostMapping(value = "/chat/stream")
    public SseEmitter chatStream(@RequestBody Map<String, Object> body) {
        String grantedSourceName = (String) body.get("grantedSourceName");
        String message = (String) body.get("message");
        @SuppressWarnings("unchecked")
        List<Map<String, String>> history = (List<Map<String, String>>) body.get("history");
        if (grantedSourceName == null || message == null) {
            throw new RuntimeException("grantedSourceName 和 message 必填");
        }
        SseEmitter emitter = new SseEmitter(300_000L); // 5 分钟超时
        streamExecutor.submit(() -> enterpriseAiService.chatStream(grantedSourceName, message, history, emitter));
        return emitter;
    }
}
