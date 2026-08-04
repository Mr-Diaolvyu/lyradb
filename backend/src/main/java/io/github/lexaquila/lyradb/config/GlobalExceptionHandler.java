package io.github.lexaquila.lyradb.config;

import io.github.lexaquila.lyradb.service.AiGatewayRateLimitException;
import io.github.lexaquila.lyradb.service.ApprovalRequiredException;
import io.github.lexaquila.lyradb.service.QueryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 全局异常边界。客户端只获得稳定、可操作且不含驱动/内部实现细节的消息，
 * 完整异常仅写入服务端日志，并通过 requestId 关联排查。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException e) {
        return buildLogged(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", e.getMessage(), e, false);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(AccessDeniedException e) {
        return buildLogged(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "无权执行该操作", e, false);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Map<String, Object>> handleAuthentication(AuthenticationException e) {
        return buildLogged(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "请先登录", e, false);
    }

    @ExceptionHandler(QueryService.SqlReviewBlockedException.class)
    public ResponseEntity<Map<String, Object>> handleSqlReviewBlocked(
            QueryService.SqlReviewBlockedException e) {
        Map<String, Object> body = baseBody(HttpStatus.CONFLICT, "SQL_REVIEW_BLOCKED",
                "SQL 审核拦截");
        body.put("reviewBlocked", true);
        body.put("reviewFindings", e.getFindings());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(ApprovalRequiredException.class)
    public ResponseEntity<Map<String, Object>> handleApprovalRequired(
            ApprovalRequiredException exception) {
        Map<String, Object> body = baseBody(
                HttpStatus.CONFLICT, "APPROVAL_REQUIRED",
                "该操作需要审批，请到审批中心处理");
        body.put("approvalRequestId", exception.getApprovalRequestId());
        body.put("approvalStatus", exception.getApprovalStatus());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(AiGatewayRateLimitException.class)
    public ResponseEntity<Map<String, Object>> handleGatewayRateLimit(
            AiGatewayRateLimitException exception) {
        Map<String, Object> body = baseBody(
                HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                "Agent Gateway 请求过多，请稍后重试");
        body.put("retryAfterSeconds", exception.getRetryAfterSeconds());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(
                        exception.getRetryAfterSeconds())).body(body);
    }

    @ExceptionHandler(SQLTimeoutException.class)
    public ResponseEntity<Map<String, Object>> handleSqlTimeout(SQLTimeoutException e) {
        return buildLogged(HttpStatus.REQUEST_TIMEOUT, "QUERY_TIMEOUT",
                "SQL 执行超时，请优化查询或缩小结果范围", e, false);
    }

    @ExceptionHandler(SQLException.class)
    public ResponseEntity<Map<String, Object>> handleSqlException(SQLException e) {
        return buildLogged(HttpStatus.BAD_GATEWAY, "DATABASE_ERROR",
                "数据库操作失败，请根据 requestId 联系管理员", e, true);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalState(IllegalStateException e) {
        return buildLogged(HttpStatus.CONFLICT, "OPERATION_REJECTED",
                "操作当前无法完成，请稍后重试", e, true);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleException(Exception e) {
        return buildLogged(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "服务内部错误，请根据 requestId 联系管理员", e, true);
    }

    private ResponseEntity<Map<String, Object>> buildLogged(HttpStatus status, String error,
            String message, Exception exception, boolean stackTrace) {
        String requestId = UUID.randomUUID().toString();
        if (stackTrace) {
            log.error("请求失败 requestId={}, type={}",
                    requestId, exception.getClass().getSimpleName(), exception);
        } else {
            log.warn("请求被拒绝 requestId={}, type={}: {}",
                    requestId, exception.getClass().getSimpleName(), exception.getMessage());
        }
        Map<String, Object> body = baseBody(status, error, message);
        body.put("requestId", requestId);
        return ResponseEntity.status(status).body(body);
    }

    private Map<String, Object> baseBody(HttpStatus status, String error, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("error", error);
        body.put("message", message);
        body.put("status", status.value());
        return body;
    }
}
