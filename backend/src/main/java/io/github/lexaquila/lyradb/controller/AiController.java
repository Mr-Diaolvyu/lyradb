package io.github.lexaquila.lyradb.controller;

import io.github.lexaquila.lyradb.service.AiProviderService;
import io.github.lexaquila.lyradb.service.EnterpriseAiService;
import io.github.lexaquila.lyradb.service.SecurityUtil;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * AI SQL 助手控制器。
 */
@RestController
@RequestMapping("/ai")
public class AiController {

    private final AiProviderService aiProviderService;
    private final EnterpriseAiService enterpriseAiService;
    private final SecurityUtil securityUtil;
    private final ThreadPoolExecutor streamExecutor = new ThreadPoolExecutor(
            2, 4, 60L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16), runnable -> {
                Thread thread = new Thread(runnable, "ai-stream");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    public AiController(AiProviderService aiProviderService,
            EnterpriseAiService enterpriseAiService, SecurityUtil securityUtil) {
        this.aiProviderService = aiProviderService;
        this.enterpriseAiService = enterpriseAiService;
        this.securityUtil = securityUtil;
    }

    /** Provider 预置（百炼/GLM/豆包/DeepSeek/GPT/自定义）。 */
    @GetMapping("/presets")
    public Map<String, Map<String, String>> presets() {
        return aiProviderService.presets();
    }

    /** 列出当前工作空间的 Provider 配置（apiKey 掩码）。 */
    @GetMapping("/providers")
    public List<Map<String, Object>> providers(
            @RequestParam(value = "workspaceId", required = false) String ignoredWorkspaceId,
            HttpSession session) {
        return aiProviderService.listMasked(
                securityUtil.requireCurrentWorkspace(session));
    }

    /** 对话查数据。工作空间只取自已校验的服务端会话。 */
    @PostMapping("/chat")
    public Map<String, Object> chat(
            @RequestBody Map<String, Object> body, HttpSession session) {
        ChatRequest request = parseRequest(body);
        String workspaceId = securityUtil.requireCurrentWorkspace(session);
        return enterpriseAiService.chat(workspaceId, request.grantedSourceName(),
                request.message(), request.history(), request.attachMetadata(),
                request.metadataSnapshotId());
    }

    /**
     * 流式对话。提交任务前固定当前工作空间，并向受控线程传播独立的安全上下文快照；
     * 任务完成时无条件清理线程上下文，避免线程池身份串用。
     */
    @PostMapping(value = "/chat/stream")
    public SseEmitter chatStream(
            @RequestBody Map<String, Object> body, HttpSession session) {
        ChatRequest request = parseRequest(body);
        String workspaceId = securityUtil.requireCurrentWorkspace(session);

        SecurityContext capturedSecurityContext =
                SecurityContextHolder.createEmptyContext();
        capturedSecurityContext.setAuthentication(
                SecurityContextHolder.getContext().getAuthentication());
        RequestAttributes capturedRequestAttributes =
                RequestContextHolder.getRequestAttributes();

        SseEmitter emitter = new SseEmitter(120_000L);
        AtomicReference<Future<?>> futureRef = new AtomicReference<>();
        Runnable task = () -> {
            try {
                SecurityContextHolder.setContext(capturedSecurityContext);
                if (capturedRequestAttributes != null) {
                    RequestContextHolder.setRequestAttributes(
                            capturedRequestAttributes);
                }
                enterpriseAiService.chatStream(
                        workspaceId, request.grantedSourceName(),
                        request.message(), request.history(),
                        request.attachMetadata(), request.metadataSnapshotId(),
                        emitter);
            } finally {
                SecurityContextHolder.clearContext();
                RequestContextHolder.resetRequestAttributes();
            }
        };

        try {
            futureRef.set(streamExecutor.submit(task));
        } catch (RejectedExecutionException exception) {
            emitter.completeWithError(exception);
            throw new IllegalStateException("AI 流式请求过多，请稍后重试");
        }
        Runnable cancel = () -> {
            Future<?> future = futureRef.get();
            if (future != null) {
                future.cancel(true);
            }
        };
        emitter.onCompletion(cancel);
        emitter.onTimeout(cancel);
        emitter.onError(error -> cancel.run());
        return emitter;
    }

    @PreDestroy
    public void shutdown() {
        streamExecutor.shutdownNow();
    }

    private static ChatRequest parseRequest(Map<String, Object> body) {
        if (body == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        Object sourceValue = body.get("grantedSourceName");
        Object messageValue = body.get("message");
        if (!(sourceValue instanceof String grantedSourceName)
                || grantedSourceName.isBlank()
                || !(messageValue instanceof String message)
                || message.isBlank()) {
            throw new IllegalArgumentException(
                    "grantedSourceName 和 message 必填");
        }

        Object historyValue = body.get("history");
        List<Map<String, String>> history = List.of();
        if (historyValue != null) {
            if (!(historyValue instanceof List<?> values)) {
                throw new IllegalArgumentException("history 格式错误");
            }
            java.util.ArrayList<Map<String, String>> parsed =
                    new java.util.ArrayList<>();
            for (Object value : values) {
                if (!(value instanceof Map<?, ?> item)) {
                    throw new IllegalArgumentException("history 格式错误");
                }
                Object role = item.get("role");
                Object content = item.get("content");
                if (role instanceof String roleText
                        && content instanceof String contentText) {
                    parsed.add(Map.of(
                            "role", roleText, "content", contentText));
                }
            }
            history = List.copyOf(parsed);
        }
        Object attachMetadataValue = body.get("attachMetadata");
        boolean attachMetadata = false;
        if (attachMetadataValue != null) {
            if (!(attachMetadataValue instanceof Boolean value)) {
                throw new IllegalArgumentException(
                        "attachMetadata \u5fc5\u987b\u662f\u5e03\u5c14\u503c");
            }
            attachMetadata = value;
        }
        Object snapshotValue = body.get("metadataSnapshotId");
        String metadataSnapshotId = null;
        if (snapshotValue != null) {
            if (!(snapshotValue instanceof String value)) {
                throw new IllegalArgumentException(
                        "metadataSnapshotId \u5fc5\u987b\u662f\u5b57\u7b26\u4e32");
            }
            metadataSnapshotId = value.trim();
            if (metadataSnapshotId.isEmpty()) {
                metadataSnapshotId = null;
            }
        }
        return new ChatRequest(grantedSourceName.trim(), message.trim(),
                history, attachMetadata, metadataSnapshotId);
    }

    private record ChatRequest(
            String grantedSourceName, String message,
            List<Map<String, String>> history, boolean attachMetadata,
            String metadataSnapshotId) {
    }
}
